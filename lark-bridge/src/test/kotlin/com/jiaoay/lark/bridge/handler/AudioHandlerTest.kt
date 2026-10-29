// 语音消息转写链守护：AudioHandler 三态反馈契约 + PolicyGate AUDIO 群@豁免。
//
// 契约（见 AudioHandler KDoc）：
// 1. 转写成功 ⇒ 转写文本**整段**进 agent 管线（与手打文字同款 runAgentPipeline 路径，零触发词语义）；
// 2. 群语音不转写（静默留痕，闭包与下载零调用）；
// 3. 超时长下载前人话拒收（AUDIO_DURATION_EXCEEDED）；下载失败复用 FILE_DOWNLOAD_FAILED；
// 4. 能力缺席（Unavailable）⇒ 引导文案回执不静默、无 errorCode；执行失败（Failed）⇒ AUDIO_TRANSCRIBE_FAILED；
// 5. 空转写人话回执不进管线；agent 不可用先于下载报错（省下载开销）；
// 6. 转写中提示先出站（长转写静默等待是最差体验）；
// 7. 🔴 取消信号原样上抛（吞取消会让任务永不结束）+ 临时音频收尾删除；
// 8. PolicyGate 对 AUDIO 与 FILE 同口径群@豁免（语音物理上无法携带 @）。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.PolicyGate
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.ReplyDirective
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.DownloadResult
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.InboundResource
import com.jiaoay.lark.gateway.LarkTarget
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AudioHandlerTest {

    // ==================== 替身 ====================

    /** 转写闭包替身：记录入参文件与调用期存在性，回放固定结果。 */
    private class FakeTranscriber(private val result: AudioTranscriptionOutcome) : AudioTranscriberSeam {
        val calls = CopyOnWriteArrayList<File>()
        val existsAtCall = CopyOnWriteArrayList<Boolean>()
        override suspend fun transcribe(audio: File): AudioTranscriptionOutcome {
            existsAtCall.add(audio.isFile)
            calls.add(audio)
            return result
        }
    }

    /** 抛取消的转写闭包替身（取消放行用例）。 */
    private class CancellingTranscriber : AudioTranscriberSeam {
        val calls = CopyOnWriteArrayList<File>()
        override suspend fun transcribe(audio: File): AudioTranscriptionOutcome {
            calls.add(audio)
            throw CancellationException("test-cancel")
        }
    }

    /** 下载闭包替身：记录三元组，可编程成败。 */
    private class RecordingDownloader(private val ok: Boolean = true) {
        val calls = CopyOnWriteArrayList<Triple<String, String, File>>()
        val closure: suspend (String, String, File) -> DownloadResult = { messageId, fileKey, dest ->
            calls.add(Triple(messageId, fileKey, dest))
            if (ok) {
                dest.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) // "RIFF"——落盘即视为下载成功
                DownloadResult.OK
            } else {
                DownloadResult(ok = false, error = GatewayErrorCode.DownloadFailed, detail = "fake download failure")
            }
        }
    }

    /** AUDIO 合成消息（msg() 无 resources 形参——resources 经 copy 注入）。 */
    private fun audioMessage(
        messageId: String = "om_${System.nanoTime()}",
        chatType: ChatType = ChatType.P2P,
        durationMs: Long = 30_000L,
        fileKey: String? = "fk_voice",
    ): InboundMessage = msg(
        messageId = messageId,
        chatType = chatType,
        text = "[audio]",
        kind = InboundKind.AUDIO,
    ).copy(
        resources = listOf(
            InboundResource(type = "audio", fileKey = fileKey, fileName = null, durationMs = durationMs),
        ),
    )

    /** 直造 ctx（不跑 intake——本类守 handler 契约；生产接线由 BridgeAssemblerParityTest ⑩ 组守）。 */
    private fun ctxOf(
        message: InboundMessage,
        agent: AgentRunner = FakeAgentRunner(),
        reply: RecordingReplySink = RecordingReplySink(LarkTarget.ofInbound(message)),
    ): RequestContext {
        val registry = ProcessRegistry(1)
        try {
            return RequestContext(
                message = message,
                config = testConfig(),
                route = null,
                reply = reply,
                progress = ConflatedProgressSink(),
                log = BridgeLog.Silent,
                agent = agent,
                processes = registry,
            )
        } finally {
            // ctx 已捕获 registry 引用，此处只关登记表收尾面（测试窄面：无在途子进程）
            runCatching { registry.close() }
        }
    }

    private fun handlerOf(
        transcriber: AudioTranscriberSeam,
        downloader: suspend (String, String, File) -> DownloadResult,
    ): AudioHandler = AudioHandler(
        deps = HandlerDeps({ testConfig() }, BridgeLog.Silent),
        transcriber = transcriber,
        downloader = downloader,
    )

    // ==================== match ====================

    @Test
    fun `match 只认 AUDIO kind 且带非空 fileKey 资源`() {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("x"))
        val downloader = RecordingDownloader()
        val handler = handlerOf(transcriber, downloader.closure)
        val processes = ProcessRegistry(1)
        try {
            fun ctxOfRaw(message: InboundMessage): RequestContext = RequestContext(
                message = message,
                config = testConfig(),
                route = null,
                reply = RecordingReplySink(LarkTarget.ofInbound(message)),
                progress = ConflatedProgressSink(),
                log = BridgeLog.Silent,
                agent = FakeAgentRunner(),
                processes = processes,
            )
            assertTrue(handler.match(ctxOfRaw(audioMessage())), "AUDIO + fileKey ⇒ 命中")
            assertFalse(handler.match(ctxOfRaw(msg(text = "你好"))), "TEXT 不命中（转写链只认 AUDIO）")
            assertFalse(
                handler.match(ctxOfRaw(audioMessage(fileKey = null))),
                "AUDIO 但 fileKey 空 ⇒ 不命中（防御性兜底：缺资源的语音走既有降级文案）",
            )
            assertFalse(
                handler.match(ctxOfRaw(msg(kind = InboundKind.IMAGE).copy(resources = listOf(InboundResource("image", "fk_img", null, 0L))))),
                "IMAGE 不命中",
            )
            assertEquals(0, transcriber.calls.size, "match 是纯谓词零副作用")
            assertEquals(0, downloader.calls.size)
        } finally {
            runCatching { processes.close() }
        }
    }

    // ==================== 转写成功：整段文本进 agent 管线 ====================

    @Test
    fun `转写成功后转写文本整段进 agent 管线且转写中提示先出站`() = runBlocking {
        val agent = FakeAgentRunner()
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("帮我看看这个 crash 标志是什么"))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage(messageId = "om_voice_ok")))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(messageId = "om_voice_ok"), agent = agent, reply = reply))

        assertEquals(TaskStatus.SUCCESS, outcome.status, "fake runner 成功 ⇒ SUCCESS")
        assertEquals(1, agent.requests.size, "转写文本恰好进一次 agent 管线")
        assertEquals("帮我看看这个 crash 标志是什么", agent.requests[0].prompt, "整段转写文本即 prompt（与手打文字同款路径）")
        // 下载参数：messageId + fileKey → 临时文件
        assertEquals(listOf(Triple("om_voice_ok", "fk_voice", downloader.calls[0].third)), downloader.calls)
        assertEquals(listOf(true), transcriber.existsAtCall, "转写执行期临时音频在位（闭包可读）")
        assertTrue(reply.texts.contains(AudioHandler.TRANSCRIBING_NOTICE), "转写中提示必须先出站（长转写静默等待是最差体验）")
        // 出站正文面：直连 handle 只产 outcome——final 回复出站归 Admission.deliver（既有测试覆盖），
        // 本类断言收口在 outcome（decorate 后 summary 带「任务完成」抬头）。
        assertTrue(outcome.summary.contains("fake ok"), "outcome.summary 承载 agent 回复正文：${outcome.summary}")
    }

    @Test
    fun `转写收尾后临时音频删除（一次性消费不留残）`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("好的"))
        val downloader = RecordingDownloader()
        handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage()))
        assertFalse(downloader.calls[0].third.exists(), "handle 返回后临时音频必须删除")
    }

    // ==================== 群语音：静默跳过 ====================

    @Test
    fun `群语音不转写（静默留痕，闭包与下载零调用）`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("不该被转写"))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage(chatType = ChatType.GROUP)))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(chatType = ChatType.GROUP), reply = reply))

        assertEquals(TaskStatus.SKIPPED, outcome.status)
        assertEquals(AudioHandler.GROUP_VOICE_SUMMARY, outcome.summary)
        assertEquals(ReplyDirective.SILENT, outcome.directive, "静默与「群内未@闲聊不回」同口径（人话回执会对每条群语音刷屏）")
        assertEquals(0, downloader.calls.size, "下载零调用")
        assertEquals(0, transcriber.calls.size, "转写闭包零调用")
        assertEquals(emptyList(), reply.allBodies(), "群语音零出站")
    }

    // ==================== 时长硬上限：下载前拒收 ====================

    @Test
    fun `超时长语音下载前人话拒收（AUDIO_DURATION_EXCEEDED）`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("不该被转写"))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage(durationMs = AudioHandler.MAX_DURATION_MILLIS + 1)))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(durationMs = AudioHandler.MAX_DURATION_MILLIS + 1), reply = reply))

        assertEquals(TaskStatus.FAILURE, outcome.status)
        assertEquals(TaskErrorCodes.AUDIO_DURATION_EXCEEDED, outcome.errorCode)
        assertEquals(0, downloader.calls.size, "超时长在下载前拒收（省整条下载+转码开销）")
        assertEquals(0, transcriber.calls.size)
        assertTrue(outcome.summary.contains("太长"), "人话拒收回执必须产出（deliver 出站归 Admission）：${outcome.summary}")
        assertFalse(reply.allBodies().any { it.contains(AudioHandler.TRANSCRIBING_NOTICE) }, "拒收路径不发转写中提示")
    }

    @Test
    fun `时长未知（durationMs=0）放行进转写（大小与转码上界兜底仍在）`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("短语音"))
        val downloader = RecordingDownloader()
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(durationMs = 0L)))
        assertEquals(TaskStatus.SUCCESS, outcome.status)
        assertEquals(1, transcriber.calls.size, "时长未知不拒收")
    }

    // ==================== 三态反馈 ====================

    @Test
    fun `转写能力不可用时引导文案回执不静默且不落 errorCode`() = runBlocking {
        val guidance = "语音转写模型还没准备好——请先在桌面应用「语音转写」页下载模型"
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Unavailable(guidance))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage()))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(), reply = reply))

        assertEquals(TaskStatus.SKIPPED, outcome.status, "能力缺席不是任务失败（不计失败 metrics）")
        assertEquals(null, outcome.errorCode)
        assertEquals(ReplyDirective.DEFAULT, outcome.directive, "非静默——deliver 会把引导文案发出（人话回执不静默）")
        assertTrue(outcome.summary.contains(guidance), "引导文案必须随 outcome 产出：${outcome.summary}")
    }

    @Test
    fun `转写执行失败落 AUDIO_TRANSCRIBE_FAILED 人话回执`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Failed("音频解码失败"))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage()))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(), reply = reply))

        assertEquals(TaskStatus.FAILURE, outcome.status)
        assertEquals(TaskErrorCodes.AUDIO_TRANSCRIBE_FAILED, outcome.errorCode)
        assertTrue(outcome.summary.contains("音频解码失败"), "失败原因人话必须随 outcome 产出：${outcome.summary}")
    }

    @Test
    fun `空转写人话回执且不进 agent 管线`() = runBlocking {
        val agent = FakeAgentRunner()
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("   "))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage()))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(), agent = agent, reply = reply))

        assertEquals(TaskStatus.SKIPPED, outcome.status)
        assertEquals(ReplyDirective.DEFAULT, outcome.directive, "非静默——空音频人话回执不静默")
        assertEquals(0, agent.requests.size, "空转写不烧 agent")
        assertTrue(outcome.summary.contains("没有识别出"), "空音频人话回执随 outcome 产出：${outcome.summary}")
    }

    @Test
    fun `下载失败人话回执（复用 FILE_DOWNLOAD_FAILED）`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("不该被转写"))
        val downloader = RecordingDownloader(ok = false)
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage()))
        val outcome = handlerOf(transcriber, downloader.closure).handle(ctxOf(audioMessage(), reply = reply))

        assertEquals(TaskStatus.FAILURE, outcome.status)
        assertEquals(TaskErrorCodes.FILE_DOWNLOAD_FAILED, outcome.errorCode)
        assertEquals(0, transcriber.calls.size, "下载失败不触转写闭包")
        assertTrue(outcome.summary.contains("语音下载失败"), "下载失败人话随 outcome 产出：${outcome.summary}")
    }

    // ==================== 可用性闸与取消 ====================

    @Test
    fun `agent 不可用时先于下载报 AGENT_CONFIG_INVALID（省下载开销）`() = runBlocking {
        val transcriber = FakeTranscriber(AudioTranscriptionOutcome.Success("不该被转写"))
        val downloader = RecordingDownloader()
        val reply = RecordingReplySink(LarkTarget.ofInbound(audioMessage()))
        val outcome = handlerOf(transcriber, downloader.closure)
            .handle(ctxOf(audioMessage(), agent = FakeAgentRunner(isAvailable = false), reply = reply))

        assertEquals(TaskStatus.FAILURE, outcome.status)
        assertEquals(TaskErrorCodes.AGENT_CONFIG_INVALID, outcome.errorCode)
        assertEquals(0, downloader.calls.size, "可用性闸先于下载（闭包判定与执行之间配置可能热改，现读）")
        assertEquals(0, transcriber.calls.size)
        assertTrue(outcome.summary.contains("本地 AI 未就绪"), "明确报错卡随 outcome 产出：${outcome.summary}")
    }

    @Test
    fun `转写闭包的取消信号原样上抛且临时音频收尾`() {
        val transcriber = CancellingTranscriber()
        val downloader = RecordingDownloader()
        val handler = handlerOf(transcriber, downloader.closure)
        runBlocking {
            assertFailsWith<CancellationException> { handler.handle(ctxOf(audioMessage())) }
        }
        assertEquals(1, downloader.calls.size)
        assertEquals(1, transcriber.calls.size)
        assertFalse(downloader.calls[0].third.exists(), "取消路径临时音频同样收尾删除")
    }

    // ==================== PolicyGate AUDIO 群@豁免 ====================

    @Test
    fun `PolicyGate 对 AUDIO 群@豁免（与 FILE 同口径，不进群评估计数）`() {
        val gate = PolicyGate(
            { testConfig().access },
            agentGroupModeProvider = { "mention" },
        )
        assertTrue(
            gate.isAllowed(msg(chatType = ChatType.GROUP, kind = InboundKind.AUDIO, mentionedBot = false)),
            "语音物理上无法携带 @ ⇒ 与 FILE 同口径豁免（放行进管线，群聊拒绝闸收口在 AudioHandler 内）",
        )
        assertEquals(0L, gate.groupEvaluatedCount, "豁免分支不进群评估计数（与 FILE/CARD_ACTION 同口径）")
        // 对照锚：豁免只覆盖 AUDIO/FILE——群 TEXT 未@仍被拦（既有行为不变）
        assertFalse(gate.isAllowed(msg(chatType = ChatType.GROUP, mentionedBot = false)))
        assertEquals(1L, gate.groupEvaluatedCount)
    }
}
