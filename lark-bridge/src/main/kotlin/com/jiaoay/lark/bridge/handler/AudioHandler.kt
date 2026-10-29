// handler/：语音消息转写 handler（入站 AUDIO kind 的唯一承接点，经装配点未命中兜底 seam 装配）。
// 纪律与 BuiltinHandlers.kt 抬头同源：零 ProcessBuilder、零锁、零 @Volatile var、
//    零 com.jiaoay.easy_dev_toolbox（lib 是 jvmTarget 11 纯 JVM，转写内核经 [AudioTranscriberSeam]
//    由宿主注入）、不吞 CancellationException。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.files.ReferencedMessageResolver
import com.jiaoay.lark.bridge.pipeline.HandlerOutcome
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.gateway.DownloadResult
import com.jiaoay.lark.gateway.InboundKind
import java.io.File

/**
 * 宿主语音转写缝（App 形态由装配点注入；CLI/测试形态缺省 null ⇒ AUDIO 消息维持既有降级文案）。
 *
 * 契约：挂起（跑在任务协程内）；失败一律映射为
 * [AudioTranscriptionOutcome.Unavailable]/[AudioTranscriptionOutcome.Failed] 返回——不靠异常传状态；
 * CancellationException 必须原样上抛（吞取消会让任务协程永不结束）。
 * 转码（任意格式 → 16kHz mono wav）与 ASR 推理都归宿主闭包——lib 拿不到 :core 的解码器与
 * feature 侧的转写内核（模块 jvmTarget 隔离），本接口是唯一越界通道。
 */
fun interface AudioTranscriberSeam {

    /**
     * 转写一段已下载到本地的音频文件。
     * @param audio 网关下载落盘的原始语音资源（飞书语音为 opus 封装，格式判别归闭包）
     */
    suspend fun transcribe(audio: File): AudioTranscriptionOutcome
}

/**
 * 转写闭包的产出契约（三态，无 null 形态——「能力缺席」与「执行失败」必须可区分：
 * 前者给引导文案（模型未下载/解码器缺席），后者给失败回执并落审计 errorCode）。
 */
sealed class AudioTranscriptionOutcome {

    /** 转写成功（[text] 整段即用户说的话）。 */
    data class Success(val text: String) : AudioTranscriptionOutcome()

    /** 转写能力不可用——[guidance] 为引导用户的人话（去哪下载模型/装什么依赖），非失败不落 errorCode。 */
    data class Unavailable(val guidance: String) : AudioTranscriptionOutcome()

    /** 转写执行失败——[reason] 人话原因（解码失败/推理异常），落 AUDIO_TRANSCRIBE_FAILED 审计码。 */
    data class Failed(val reason: String) : AudioTranscriptionOutcome()
}

/**
 * 语音消息转写（HEAVY）：下载语音资源 → 宿主转写闭包 → **转写文本与用户手打文字走完全相同的
 * agent 管线路径**（整段转写文本即 prompt，经 [runAgentPipeline]；与 DispatchAgentHandler 同款，
 * 含引用文件四态分流——「引用文件 + 发语音」的组合复用引用流既有产物，零新链）。
 *
 * ## 触发口径（方案 A：零新触发语义）
 * 语音本身就是显式用户动作，转写文本恒按聊天消息路由——不发明口播触发词（ASR 识别触发词
 * 在口音/噪音下脆弱，识别不出则消息被静默丢弃是最差失败形态）；成本门控由
 * [MAX_DURATION_MILLIS] 时长硬上限承载。
 *
 * ## 装配形态
 * 与 [DispatchAgentHandler] 同经 Admission 的未命中兜底 seam 装配（route == null、恒 HEAVY 闸
 * ——下载+转码+推理重量不可控），**绝不进路由快照**（进快照会遮蔽「未命中」语义）。
 *
 * ## 群语音不处理
 * `match` 只判 AUDIO kind + fileKey，群聊豁免（PolicyGate 对 AUDIO 同 FILE 口径——语音物理上
 * 无法携带 @）后群语音也会到达本 handler；[handle] 首闸即静默跳过群聊：群语音无法 @ 定向，
 * 全员语音触发 agent 是成本/噪音双炸弹；静默与「群内未@闲聊不回」既有口径一致（人话回执
 * 会让机器人对每条群语音刷屏，且不为其新造频控机制）。
 *
 * ## 反馈纪律（长转写静默等待是最差体验）
 * - 通过全部闸门后先发 [TRANSCRIBING_NOTICE] 出站提示（失败只降级 log，不阻断转写）；
 * - 转写失败/空音频/能力缺席 ⇒ 人话回执不静默（Unavailable 走引导文案，Failed/下载失败落
 *   审计 errorCode）；
 * - 转写成功 ⇒ 后续回复形态完全由 agent 管线既有形态承载，本 handler 零新呈现面。
 */
class AudioHandler(
    private val deps: HandlerDepsLike,
    /** 宿主转写闭包（装配点注入；null 形态不存在——seam 为 null 时装配点根本不构造本 handler）。 */
    private val transcriber: AudioTranscriberSeam,
    /** 网关下载器闭包（与 ReferencedMessageResolver 同款形态：messageId + fileKey → dest 落盘）。 */
    private val downloader: suspend (messageId: String, fileKey: String, dest: File) -> DownloadResult,
    /** 引用文件解析器（null = 引用能力关闭，转写文本直接进管线）。 */
    private val referencedFiles: ReferencedMessageResolver? = null,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.AGENT
    override val weight: HandlerWeight = HandlerWeight.HEAVY
    override val timeoutSeconds: Long? = null

    override fun match(ctx: RequestContext): Boolean =
        ctx.inboundKind == InboundKind.AUDIO &&
            ctx.message.resources.any { !it.fileKey.isNullOrBlank() }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        if (ctx.isGroup) {
            return HandlerOutcome.skipped(TaskKind.AGENT, GROUP_VOICE_SUMMARY, silent = true)
        }
        // 可用性闸（与 DispatchAgentHandler 同款）：现读执行器——闭包判定与执行之间配置可能热改。
        if (!ctx.agent.isAvailable) {
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.AGENT,
                    "本地 AI 未就绪：语音转写后需要本地 AI 处理，请在应用的「AI 设置」页配置模型端点后重试",
                    errorCode = TaskErrorCodes.AGENT_CONFIG_INVALID,
                ),
            )
        }
        val resource = ctx.message.resources.firstOrNull { !it.fileKey.isNullOrBlank() }
            // match 已保证有 fileKey 资源；此分支仅防御直接构造调用的测试窄面。
            ?: return HandlerOutcome.skipped(TaskKind.AGENT, "语音消息缺少音频资源", silent = true)
        // 时长硬上限：下载前拒收（成本双约束——转写耗时上界 + 转写文本进 prompt 的上下文上界）。
        // durationMs <= 0（平台未报时长）放行：下载大小上限与转码超时兜底仍在。
        if (resource.durationMs > MAX_DURATION_MILLIS) {
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.AGENT,
                    "这条语音太长了（超过 ${MAX_DURATION_MILLIS / 60_000} 分钟），转写不了～ " +
                        "试试分段说，或直接打字发我？",
                    errorCode = TaskErrorCodes.AUDIO_DURATION_EXCEEDED,
                ),
            )
        }
        // 转写中提示（出站失败只降级——提示是体验增强，不阻断转写主链）。
        runCatching { ctx.reply.text(TRANSCRIBING_NOTICE) }
            .onFailure { deps.log.warn("转写中提示发送失败（已降级，继续转写）：${it.message}") }
        ctx.progress.touch()
        // 临时产物：一次性消费，收尾 finally 删除（取消路径也必达——删除无挂起点）。
        val audio = File.createTempFile(AUDIO_TEMP_PREFIX, AUDIO_TEMP_SUFFIX)
        return try {
            val download = downloader(ctx.message.messageId, resource.fileKey.orEmpty(), audio)
            if (!download.ok) {
                deps.log.warn(
                    "语音下载失败 messageId=${ctx.message.messageId} error=${download.error} " +
                        "larkCode=${download.larkCode} detail=${download.detail}",
                )
                return Responder.decorate(
                    HandlerOutcome.failure(
                        TaskKind.AGENT,
                        "语音下载失败，请稍后重试",
                        errorCode = TaskErrorCodes.FILE_DOWNLOAD_FAILED,
                    ),
                )
            }
            ctx.progress.touch()
            when (val transcription = transcriber.transcribe(audio)) {
                is AudioTranscriptionOutcome.Unavailable ->
                    Responder.decorate(HandlerOutcome.skipped(TaskKind.AGENT, transcription.guidance, silent = false))
                is AudioTranscriptionOutcome.Failed ->
                    Responder.decorate(
                        HandlerOutcome.failure(
                            TaskKind.AGENT,
                            "语音转写失败：${transcription.reason}",
                            errorCode = TaskErrorCodes.AUDIO_TRANSCRIBE_FAILED,
                        ),
                    )
                is AudioTranscriptionOutcome.Success -> {
                    val text = transcription.text.trim()
                    if (text.isEmpty()) {
                        Responder.decorate(
                            HandlerOutcome.skipped(TaskKind.AGENT, EMPTY_TRANSCRIPTION_REPLY, silent = false),
                        )
                    } else {
                        dispatchTranscribed(ctx, text)
                    }
                }
            }
        } finally {
            runCatching { audio.delete() }
        }
    }

    /**
     * 转写文本进 agent 管线（DispatchAgentHandler 同款路径：引用四态分流 + [runAgentPipeline]
     * 全套进度卡/表情/产物回传；在途引用目录 pin 住、收尾 finally unpin）。
     */
    private suspend fun dispatchTranscribed(ctx: RequestContext, text: String): HandlerOutcome =
        when (val resolved = resolveReferencedPrompt(referencedFiles, ctx, TaskKind.AGENT, text)) {
            is ReferencedPromptOutcome.Failed -> resolved.outcome
            is ReferencedPromptOutcome.Prompt -> try {
                Responder.decorate(
                    runAgentPipeline(
                        ctx,
                        resolved.text,
                        TaskKind.AGENT,
                        allowedFiles = resolved.allowedFiles,
                        homeDataRoot = deps.dataRootProvider(),
                        replyLedger = deps.replyLedgerProvider(),
                        nativeSurface = deps.nativeSurfaceFactory?.invoke(ctx),
                        confirmGate = deps.confirmGateFactory?.invoke(ctx),
                    ),
                )
            } finally {
                resolved.pinnedDirs.forEach { referencedFiles?.store?.unpin(it) }
            }
        }

    companion object {
        /**
         * 语音时长硬上限（分钟级）。双约束：转写耗时（非流式推理吃整段）与转写文本进 prompt
         * 的上下文上界；下载前拒收，省掉超长音频的整条下载+转码开销。
         */
        const val MAX_DURATION_MILLIS: Long = 10 * 60_000L

        /** 临时音频文件名前后缀（原始字节落盘，格式判别归宿主转码器——后缀不承诺真实容器）。 */
        const val AUDIO_TEMP_PREFIX: String = "lark-audio-"
        const val AUDIO_TEMP_SUFFIX: String = ".media"

        /** 转写中出站提示（长转写静默等待是最差体验；文案与既有回执语气一致）。 */
        const val TRANSCRIBING_NOTICE: String = "🎧 正在转写语音…"

        /** 群语音静默跳过的审计 summary（不进出站，只落 audit.jsonl；口径同 Admission.UNHANDLED_SILENT_SUMMARY）。 */
        const val GROUP_VOICE_SUMMARY: String = "group_voice_skipped"

        /** 空转写人话回执（识别不出内容不静默——用户需要知道消息到了但没听清）。 */
        const val EMPTY_TRANSCRIPTION_REPLY: String =
            "这段语音没有识别出说话内容（可能太短或环境太吵）～ 可以再试一次，或直接打字发我"
    }
}
