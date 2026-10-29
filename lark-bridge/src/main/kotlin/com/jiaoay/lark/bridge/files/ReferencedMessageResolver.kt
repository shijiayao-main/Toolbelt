// 引用消息解析器。
// 语义：AgentHandler/CommandHandler 在 handle 内（触发词命中、prompt 剥离后）调用本类；
//    按 ctx.message.replyToMessageId 查 FileRegistry → 懒下载 + 预处理 → 产出拼接 prompt。
// **引用即上下文**：文件引用拼路径，文本引用拼原文（都带标识块）；lookup miss 时经
//    im.v1.message.get 现查兜底。
// 硬约束：无引用（replyTo 空 / fileMessage.enabled 关）⇒ NotReferenced，prompt 逐字等于现状（零变化路径）。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * 引用消息解析器（装配期单例）。
 *
 * 流程总览（`replyToMessageId` 非空才进入）：
 * 1. **查表**：registry.lookup → 命中（本进程登记过的文件消息）→ 懒下载 + 预处理 → [Result.Resolved]（File）；
 * 2. **补查**：查表 miss 且非跨会话 → [quotedFetcher] 现查被引用消息（`im.v1.message.get`）：
 *    - 文件消息（同 chatId）⇒ 补登记 → 走 1 的正常下载循环；
 *    - 文本消息（同 chatId）⇒ **不登记不下载**，原文直接进 [Result.Resolved]（Text）——
 *      「引用文字把对应文本补充到 prompt 末尾」的需求；
 *    - 补查未取回（scope 不足 / 已删除 / 不支持类型 / 他会消息）⇒ [Result.NotReferencedWithHint]；
 * 3. **跨会话**：registry 命中但 chatId 不匹配 ⇒ 不补查（护栏语义），回落 [Result.NotReferencedWithHint]。
 *
 * **任一文件 resource 下载失败 ⇒ 整单 [Result.Failed]**（半成功会让 agent 基于残缺文件集输出错误结论）。
 *
 * @param downloader 网关下载器闭包（装配期注入 `gateway::downloadResource`，非静态全局）。
 *    返回 [com.jiaoay.lark.gateway.DownloadResult]：失败原因码
 *    （error/larkCode）经 [downloadFailureReason] 转成用户可读文案——234037 平台限的「重试无用」
 *    必须传导到飞书端用户，不再一律「请重试」。
 * @param quotedFetcher 引用补查闭包（装配期注入 `gateway::fetchQuotedMessage`；默认 `{ null }` =
 *    补查能力关闭，miss 一律回落 hint——存量测试零改动）。lookup miss 时现查被引用消息，
 *    救回「引用旧气泡 / 进程重启清空登记表 / TTL 过期」场景；
 *    文本引用（查表必然 miss，登记表只收文件消息）全靠此闭包取回原文。
 */
class ReferencedMessageResolver(
    private val registry: FileRegistry,
    /** 下载区账本（internal：在途保护——resolveReferencedPrompt 调用方 finally unpin 需要 store 面）。 */
    internal val store: DownloadStore,
    private val preprocess: PreprocessPipeline,
    private val downloader: suspend (messageId: String, fileKey: String, dest: File) -> com.jiaoay.lark.gateway.DownloadResult,
    private val configProvider: () -> BridgeConfig,
    private val counters: PipelineCounters,
    private val log: BridgeLog,
    /** 机器人回复账本：null = 账本兜底关闭（测试窄面），生产装配注入 Bridge 稳定件。 */
    private val cardReplyLedger: CardReplyLedger? = null,
    private val quotedFetcher: suspend (messageId: String) -> com.jiaoay.lark.gateway.QuotedMessage? = { null },
) {

    /** resolve 结果（sealed：调用方按四态分流）。 */
    sealed interface Result {
        /** 无引用（replyTo 为空 / fileMessage 关闭）——调用方走原 prompt（零变化路径）。 */
        data object NotReferenced : Result

        /**
         * 引用了但取不到（查表 miss 且补查未取回：scope 不足 / 已删除 / 不支持类型 / 跨会话）——
         * prompt 附说明后继续跑 agent（静默回落纯文本会让 agent
         * 「拿不到路径还自己瞎猜」，用户无从得知失败原因；附提示后 agent 能解释并引导用户重发）。
         */
        data class NotReferencedWithHint(val hint: String) : Result

        /** 引用命中且上下文就绪（files = 文件 payload 列表；texts = 文本 payload 列表）。 */
        data class Resolved(
            val files: List<FilePayload>,
            val texts: List<TextPayload>,
        ) : Result

        /** 引用命中（文件）但下载/校验失败——调用方直接回失败 outcome。 */
        data class Failed(val summary: String, val errorCode: String) : Result
    }

    /** 引用上下文的文件 payload（[Result.Resolved.files] 单元素）。 */
    data class FilePayload(
        val name: String,
        val path: String,
        val dir: String,
        val artifacts: List<String>,
    )

    /** 引用上下文的文本 payload（[Result.Resolved.texts] 单元素）。 */
    data class TextPayload(
        val text: String,
    )

    /**
     * 解析引用上下文。
     *
     * @param onDownloadStarted 下载卡开卡信号：**已确定有文件要处理、即将产出
     *   `FilePayload`** 时回调一次（幂等语义由调用方保证——多文件只应在首个资源前触发一次）。
     *   判据刻意**不含「真的会走网络」**：复用命中（同内容寻址 / 同消息幂等）同样触发，
     *   否则复用路径的用户会完全无反馈。
     *   放在**文件循环之前**（`record != null` 之后）而非 `resolveSingleResource` 内部——
     *   一次开卡覆盖多条资源，不会为多文件开多张卡。
     *   纯文本引用（只走 `im.v1.message.get` 取原文、无下载）**不触发**：那是高频低延迟路径，
     *   为它建卡是纯噪音。
     *   **suspend**：调用方要在此回调里真正建卡（`reply.stream` 是一次真实 API 调用），
     *   而建卡必须发生在文件循环**之前**——同步回调会逼调用方另找延期点，那正是「下载已跑、卡还没开」
     *   的老问题。默认 null ⇒ 存量调用点（含全部测试）零改动。
     * @param onStage 阶段文案旁路：每条阶段文案除喂 `ctx.progress`（服务空闲看门狗
     *   与审计，**逐字保留**）外，再原样旁路一份给下载卡。**同一字面量双路分发**（由内部
     *   `report` 助手保证，见下）——绝不写两套文案，那必然随时间漂移。
     *   注意 `ctx.progress` 的 [ProgressAccumulator] 是**滚动窗口**（丢最旧），下载卡是**线性累积**，
     *   故卡片能看到「准备引用文件」这类早期阶段而窗口早已挤掉——这正是下载卡独立存在的理由之一。
     *   默认 null ⇒ 存量调用点零改动。
     */
    suspend fun resolve(
        ctx: RequestContext,
        onDownloadStarted: (suspend () -> Unit)? = null,
        onStage: ((String) -> Unit)? = null,
    ): Result {
        /**
         * 阶段上报唯一入口：同一字面量喂两路（`ctx.progress` 看门狗/审计 + 下载卡旁路）。
         * 🔴 全部阶段文案必须经此，禁止再直接调 `ctx.progress.append`——否则下载卡会漏掉那一条。
         */
        fun report(text: String) {
            ctx.progress.append(text)
            onStage?.invoke(text)
        }
        // 空白与 null 同义（无引用）——不加 hint 的零变化路径（SDK 未引用时为 null，空白是防御）。
        val replyTo = ctx.message.replyToMessageId?.takeIf { it.isNotBlank() } ?: return Result.NotReferenced
        val cfg = configProvider().fileMessage
        if (!cfg.enabled) return Result.NotReferenced // 总开关：文件与文本引用一并关闭

        // 查表（本进程登记过的文件消息）→ miss 时【单次】补查（一次 im.v1.message.get 按 sealed 子类
        // 分流到文件补登记 / 文本上下文两消费路径——绝不各查各的，防同一条消息双倍 API 调用）。
        var record = registry.lookup(replyTo, ctx.chatId, cfg.registryTtlHours * 3_600_000L)
        var recoveredText: String? = null
        // 「补查确实跑过」标记：missOutcome 据此在 WARN/hint 里注明
        // 「已尝试补查未取回」，与「跨会话/未补查」的未命中区分开——真机日志可对账网关侧分类日志。
        var fetchAttempted = false
        // 机器人回复账本兜底：机器人回复是 CardKit 流式卡，message.get 只能拿到
        //    {"type":"card"} 实体引用（飞书无读卡片实体 API）——终态正文在我们自己发卡时已落账，先查本地再考虑 fetch。
        if (record == null && recoveredText == null && !registry.contains(replyTo)) {
            // 🔴 lookup 带 ctx.chatId 做本会话校验——账目 chatId 不匹配即
            //    miss（A 会话引用不到 B 会话的机器人回复正文），与下方补查路径的跨会话护栏同口径。
            cardReplyLedger?.lookup(replyTo, ctx.chatId)?.let { ledgerText ->
                log.info("引用机器人回复命中本地账本 messageId=$replyTo length=${ledgerText.length}")
                report("已取回机器人回复内容，作为上下文补充…")
                recoveredText = ledgerText
            }
        }
        if (record == null && recoveredText == null && !registry.contains(replyTo)) { // contains=true = 跨会话：不补查（护栏语义）
            fetchAttempted = true
            val quoted = fetchQuotedOnce(replyTo)
            if (quoted?.chatId == ctx.chatId) { // 补查路径的跨会话护栏
                when (quoted) {
                    is com.jiaoay.lark.gateway.QuotedMessage.File -> {
                        log.info("引用补查成功，补登记 fileName=${quoted.fileName} messageId=$replyTo")
                        registry.register(
                            messageId = quoted.messageId,
                            chatId = quoted.chatId,
                            senderId = quoted.senderId,
                            resources = listOf(FileRegistry.ResourceRef(fileKey = quoted.fileKey, fileName = quoted.fileName)),
                        )
                        counters.recordFileRegistered()
                        report("已找回被引用文件 ${quoted.fileName}，开始下载…")
                        record = registry.lookup(replyTo, ctx.chatId, cfg.registryTtlHours * 3_600_000L)
                    }
                    is com.jiaoay.lark.gateway.QuotedMessage.Text -> {
                        log.info("引用文本取回 messageId=$replyTo length=${quoted.text.length}")
                        report("已取回被引用消息文本，作为上下文补充…")
                        recoveredText = quoted.text
                    }
                }
            }
        }
        if (record == null && recoveredText == null) return missOutcome(replyTo, ctx, fetchAttempted)

        counters.recordFileReferenced()

        val filePayloads = mutableListOf<FilePayload>()
        if (record != null) {
            // 下载卡开卡信号：确定有文件要处理、且**在**文件循环之前——
            //    用户在整个「准备→下载→预处理」期间都能看到进度，而非等 agent 阶段才出现。
            onDownloadStarted?.invoke()
            val failures = mutableListOf<ResourceOutcome.Failed>()
            for (resource in record.resources) {
                if (resource.fileKey.isBlank()) continue
                // 复核修复（二次）：dirFor 返回 null 有两种成因——工作区未配置，或
                //    senderId/messageId 段清洗后无有效内容（防多 sender 混用同一兜底目录）。
                //    两者都是「无法确定落盘位置」，统一按下载失败回报（原先一律报 workspace_missing 会误导）。
                val dir = store.dirFor(record.senderId, record.messageId, resource.fileName)
                    ?: return Result.Failed("无法确定文件落盘目录（工作区未配置或消息标识非法）", TaskErrorCodes.FILE_DOWNLOAD_FAILED)
                val dest = File(dir, DownloadPolicy.sanitizeFileName(resource.fileName))
                report("准备引用文件 ${resource.fileName}…")

                // ---- 文件复用：复用优先 + 下载后内容寻址 ----
                val payload = resolveSingleResource(record, resource, dir, dest, cfg, ctx, ::report)
                when (payload) {
                    is ResourceOutcome.Failed -> failures += payload
                    is ResourceOutcome.Payload -> filePayloads += payload.value
                    null -> Unit
                }
            }

            if (failures.isNotEmpty()) {
                // 大小限口径：全部为大小限（飞书平台 234037
                //    降级形态 / 我方 maxFileSizeBytes 磁盘保护阈值）时尾部建议改为「压缩或拆分后重发」——
                //    恒「请重试」会误导用户对永久性失败反复重试。
                val allSizeLimited = failures.all { it.code == com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge }
                val advice = if (allSizeLimited) {
                    "该文件超过大小上限，重试无法解决，请压缩或拆分后重新发送"
                } else {
                    "请重试或重新发送文件"
                }
                return Result.Failed(
                    "文件下载失败：${failures.joinToString("、") { it.reason }}（$advice）",
                    TaskErrorCodes.FILE_DOWNLOAD_FAILED,
                )
            }
        }

        if (filePayloads.isEmpty() && recoveredText == null) return Result.NotReferenced
        return Result.Resolved(
            files = filePayloads,
            texts = listOfNotNull(recoveredText?.let { TextPayload(it) }),
        )
    }

    /** 单资源解析结果（内部分流）。 */
    private sealed interface ResourceOutcome {
        data class Payload(val value: FilePayload) : ResourceOutcome

        /**
         * @param code 网关错误码：聚合句据 `all { code == DownloadTooLarge }`
         *    选尾部建议；null = 原因不明（follower / 合成替身），走通用建议。
         */
        data class Failed(
            val reason: String,
            val code: com.jiaoay.lark.gateway.GatewayErrorCode? = null,
        ) : ResourceOutcome
    }

    /**
     * 一个资源的完整解析（同消息复用 → 跨消息 sha256 → 下载+hash+索引+预处理 → 并发单飞）。
     *
     * 解析序：
     * 1. **同消息复用**（recordedDirFor）：命中且 isFile ⇒ 复用目录与产物（stamp 匹配时）；跨天/重启后仍可命中
     *    （rescan 读 .meta.json 重建映射——修「目录按当天日期分桶，跨天再引用必重下」缺陷）；
     * 2. **跨消息内容寻址**（dirBySha256）：单飞段内下载完成后 hash 命中其他消息的目录 ⇒ 删副本、
     *    FilePayload.path 指向原目录（省磁盘省预处理；不省下载 API——入站无内容指纹，能力上限如此）；
     * 3. **正常下载**：downloader → enforceSizeLimit（🔴 复用路径同样复查——maxFileSizeBytes 是 HOT 配置，漏复查则 HOT 收紧上限后复用路径仍放行旧大文件）→
     *    流式 SHA-256 → hash 命中处理 → recordStored（写 meta）→ launch{evictIfNeeded}。
     *
     * 🔴 并发单飞：per-dest CompletableDeferred 共享「下载+预处理+记账」整段——并发引用同一条
     *    文件消息时后到者 await 全段结果后直接走复用路径。零 synchronized：CHM.putIfAbsent + await。
 *    拆段共享会让并发双写同一 dest（下载竞态损坏文件）。
     * 🔴 在途保护：pin/unpin 包住 agent 持有路径的生命周期段（resolver 侧）——本方法返回的
     *    FilePayload.dir 在 agent 执行期间由调用方 pin（AgentExecutor 收尾 finally unpin）。
     */
    private suspend fun resolveSingleResource(
        record: FileRegistry.FileRecord,
        resource: FileRegistry.ResourceRef,
        dir: File,
        dest: File,
        cfg: com.jiaoay.lark.bridge.config.FileMessageConfig,
        ctx: RequestContext,
        report: (String) -> Unit,
    ): ResourceOutcome? {
        val stamp = preprocessStamp(cfg)

        // 1) 同消息复用：.meta.json 重建的映射命中 ⇒ 免下载免 hash（meta 里已有）
        store.recordedDirFor(record.messageId, resource.fileName)?.let { recorded ->
            if (!DownloadPolicy.enforceSizeLimit(File(recorded.dir, DownloadPolicy.sanitizeFileName(resource.fileName)), cfg.maxFileSizeBytes, log)) {
                return ResourceOutcome.Failed("${resource.fileName}（超大小上限）", com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge)
            }
            val sourceFile = File(recorded.dir, DownloadPolicy.sanitizeFileName(resource.fileName))
            val artifacts = if (recorded.meta.preprocessStamp == stamp) {
                listArtifacts(recorded.dir)
            } else {
                // stamp 不匹配（配置已变）⇒ 重跑预处理；输入 = 既有主文件，产物写其所在目录（同目录覆盖）
                preprocess.process(sourceFile, recorded.dir)
            }
            if (!store.isRecorded(recorded.dir)) {
                store.recordStored(recorded.dir, record.senderId, recorded.meta)
            }
            counters.recordDownloadStored()
            return ResourceOutcome.Payload(
                FilePayload(
                    name = resource.fileName,
                    path = sourceFile.absolutePath,
                    dir = recorded.dir.absolutePath,
                    artifacts = artifacts.map { it.absolutePath },
                ),
            )
        }

        // 2) 上一进程遗留的同名文件（无 meta 映射——崩溃残留 / legacy 目录）：直接走下载后处理
        if (dest.isFile) {
            return afterDownload(record, resource, dir, dest, cfg, ctx, stamp, report)
        }

        // 3) 下载 + per-dest 单飞：leader 跑「下载+预处理+记账」整段，follower await 后走复用路径。
        val myLock = CompletableDeferred<Boolean>()
        val existingLock = inflightLocks.putIfAbsent(dest.absolutePath, myLock)
        if (existingLock != null) {
            // follower：并发引用同一条文件消息——共享 leader 全段结果（修「第二个直接整单 Failed」）
            val ok = existingLock.await()
            if (!ok) return ResourceOutcome.Failed(resource.fileName)  // follower 降级：无失败原因（CompletableDeferred<Boolean>），走通用文案
            val recorded = store.recordedDirFor(record.messageId, resource.fileName)
                ?: return ResourceOutcome.Failed(resource.fileName)  // follower 降级：同上
            val sourceFile = File(recorded.dir, DownloadPolicy.sanitizeFileName(resource.fileName))
            val artifacts = if (recorded.meta.preprocessStamp == stamp) {
                listArtifacts(recorded.dir)
            } else {
                preprocess.process(sourceFile, recorded.dir)
            }
            return ResourceOutcome.Payload(
                FilePayload(
                    name = resource.fileName,
                    path = sourceFile.absolutePath,
                    dir = recorded.dir.absolutePath,
                    artifacts = artifacts.map { it.absolutePath },
                ),
            )
        }
        // leader
        try {
            val result = downloader(record.messageId, resource.fileKey, dest)
            if (!result.ok) {
                // 平台 100MB 限传导：error/larkCode 必须进日志与用户文案——
                //    修复前用户只看到「请重试」，对 234037 平台限（重试必然再错）完全是误导。
                log.warn(
                    "引用文件下载失败 fileName=${resource.fileName} messageId=${record.messageId} " +
                        "code=${result.error} larkCode=${result.larkCode} detail=${result.detail}",
                )
                report("下载 ${resource.fileName} 失败：${downloadFailureReason(resource.fileName, result).substringAfter("（").substringBeforeLast("）")}")
                return ResourceOutcome.Failed(
                    downloadFailureReason(resource.fileName, result),
                    result.error,
                )
            }
            val outcome = afterDownload(record, resource, dir, dest, cfg, ctx, stamp, report)
            myLock.complete(outcome is ResourceOutcome.Payload)
            return outcome
        } catch (t: Throwable) {
            // 🔴 不吞 CancellationException（rethrow）；leader 失败/取消也要唤醒 follower（complete(false)）——漏唤醒即 follower 的 await 永久挂死。
            myLock.complete(false)
            throw t
        } finally {
            inflightLocks.remove(dest.absolutePath)
        }
    }

    /**
     * 下载失败的用户可读文案（纯函数）。
     *
     * 分层：错误码**分类**归 :lark-gateway（LarkErrorMapper——234037 的唯一归属地）；
     *    **用户文案**归本处（:lark-bridge——与 TaskErrorHint 的宿主/lib 分工同理）。
     *    本表刻意收窄，只覆盖「用户据此能改行为」的码；其余走通用文案（聚合句尾部建议兜底）。
     *
     * 平台限 vs 我方限的判据：`larkCode != null`——我方 maxFileSizeBytes 超限发生在
     *    首片 Content-Range 预检（分片批后）/ 200 降级的下载后拒绝（业务码必为 null）；
     *    飞书平台限在下载中被拒、必带业务码（234037 降级形态）。**不在本文件复制 234037 魔数**，
     *    234037 的知识只登记在 LarkErrorMapper 一处。
     */
    private fun downloadFailureReason(
        fileName: String,
        result: com.jiaoay.lark.gateway.DownloadResult,
    ): String = when {
        result.error == com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge && result.larkCode != null ->
            "$fileName（超过飞书平台 100MB 下载上限，该文件无法下载）"
        result.error == com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge ->
            "$fileName（超过本地大小上限，无法下载；可在飞书桥接设置的「文件大小上限」中调整）"
        result.error == com.jiaoay.lark.gateway.GatewayErrorCode.PermissionDenied ->
            "$fileName（消息不允许下载：可能为保密消息或对应用不可见）"
        result.error == com.jiaoay.lark.gateway.GatewayErrorCode.RateLimited ->
            "$fileName（下载被飞书限流，请稍后重试）"
        else -> fileName
    }

    /** 下载成功后的统一处理：大小校验 → SHA-256 → 内容寻址命中分流 → 预处理 → 记账。 */
    private suspend fun afterDownload(
        record: FileRegistry.FileRecord,
        resource: FileRegistry.ResourceRef,
        dir: File,
        dest: File,
        cfg: com.jiaoay.lark.bridge.config.FileMessageConfig,
        ctx: RequestContext,
        stamp: String,
        report: (String) -> Unit,
    ): ResourceOutcome {
        if (!DownloadPolicy.enforceSizeLimit(dest, cfg.maxFileSizeBytes, log)) {
            return ResourceOutcome.Failed("${resource.fileName}（超大小上限）", com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge)
        }

        // 流式 SHA-256（任务协程内，绝不进 intake；100MB≈0.1s）
        val sha256 = sha256Of(dest)

        // 跨消息内容寻址：hash 命中其他消息的目录且 stamp 匹配 ⇒ 删刚下的副本、指向原目录
        // （🔴 stamp 不匹配 = 配置已变，产物须按新配置重跑 ⇒ 走正常下载路径（保留副本、产物写本目录）——
        //    不在共享源目录上跑新配置的预处理，防账本字节与磁盘漂移）
        if (sha256 != null) {
            val existing = store.dirBySha256(sha256, resource.fileName)
            if (existing != null &&
                existing.dir.absolutePath != dir.absolutePath &&
                existing.meta.preprocessStamp == stamp
            ) {
                report("内容与已下载文件相同，复用既有文件…")
                runCatching { dest.delete() }
                runCatching { if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete() }
                if (!store.isRecorded(existing.dir)) {
                    store.recordStored(existing.dir, record.senderId, existing.meta)
                }
                counters.recordDownloadStored()
                return ResourceOutcome.Payload(
                    FilePayload(
                        name = resource.fileName,
                        path = File(existing.dir, DownloadPolicy.sanitizeFileName(resource.fileName)).absolutePath,
                        dir = existing.dir.absolutePath,
                        artifacts = listArtifacts(existing.dir).map { it.absolutePath },
                    ),
                )
            }
        }

        report("已下载 ${resource.fileName}，预处理中…")
        val artifacts = preprocess.process(dest, dir)
        store.recordStored(
            dir,
            record.senderId,
            DownloadStore.DirMeta(
                messageId = record.messageId,
                chatId = record.chatId,
                senderId = record.senderId,
                fileKey = resource.fileKey,
                fileName = resource.fileName,
                sha256 = sha256,
                bytes = dest.length(),
                preprocessStamp = stamp,
            ),
        )
        counters.recordDownloadStored()
        // 配额逐出：recordStored 后在本任务协程同步跑（有界；KDoc 约定绝不进 intake 单协程）
        runCatching { store.evictIfNeeded() }
            .onFailure { log.warn("配额逐出失败（已忽略）:${it.message}") }
        return ResourceOutcome.Payload(
            FilePayload(
                name = resource.fileName,
                path = dest.absolutePath,
                dir = dir.absolutePath,
                artifacts = artifacts.map { it.absolutePath },
            ),
        )
    }

    /** per-dest 单飞锁：并发引用同一文件消息时共享下载+预处理+记账整段。 */
    private val inflightLocks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** 预处理配置指纹：autoExtract/preprocessCommand/xlogPrivateKey 任一变更 ⇒ 重跑。 */
    private fun preprocessStamp(cfg: com.jiaoay.lark.bridge.config.FileMessageConfig): String {
        val raw = listOf(cfg.autoExtract, cfg.preprocessCommand, cfg.xlogPrivateKey)
            .joinToString("|")
        return sha256Text(raw)
    }

    /** 流式 SHA-256（文件）；失败返回 null（复用能力降级，不阻断主链路）。 */
    private fun sha256Of(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 文本 SHA-256（stamp 用；短字符串）。 */
    private fun sha256Text(text: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** 目录内既有产物清单（stamp 匹配复用时的 artifacts；extracted/decoded/preprocessed 三子目录）。 */
    private fun listArtifacts(dir: File): List<File> {
        val out = mutableListOf<File>()
        for (sub in listOf(PreprocessPipeline.EXTRACTED_DIR, PreprocessPipeline.DECODED_DIR, PreprocessPipeline.PREPROCESSED_DIR)) {
            val subDir = File(dir, sub)
            if (subDir.isDirectory) {
                subDir.walkTopDown().filter { it.isFile }.forEach { out += it }
            }
        }
        return out
    }

    /**
     * 单次补查（两条路径各查一次 ⇒ 同一条消息双倍 im.v1.message.get，故收口为单次）。
     * 🔴 [quotedFetcher] 是真实网关挂起点（装配注入 gateway::fetchQuotedMessage），任务协程取消时
     *    在挂起点抛出 kotlinx.coroutines.CancellationException——**必须放行**（resolver 纪律：
     *    不吞 CancellationException；停止指令/超时取消才能及时生效，不会被误报成「未取回」）。
     * 非 CE 异常（API 失败等）吞成 null 回落 hint 路径。
     */
    private suspend fun fetchQuotedOnce(replyTo: String): com.jiaoay.lark.gateway.QuotedMessage? =
        try {
            quotedFetcher(replyTo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("引用补查异常 messageId=$replyTo：${e.javaClass.simpleName} ${e.message}")
            null
        }

    /**
     * 未命中兜底（不再静默回落纯文本让 agent「拿不到路径还自己瞎猜」——
     * prompt 附说明，agent 能向用户解释原因并引导重发；补查失败（scope 不足 / 不支持类型 / 已删除）
     * 也走这里）。
     * 诊断补全：[fetchQuoted] 非空 = 确实跑过 im.v1.message.get 补查但没取回——
     * WARN 尾部追加「原因见网关日志」（网关侧 5 条静默路径已各自带分类日志），真机排查不再瞎猜。
     */
    private fun missOutcome(replyTo: String, ctx: RequestContext, fetchQuoted: Boolean): Result {
        val reason = if (registry.contains(replyTo)) {
            "跨会话引用（消息在别的会话里）"
        } else {
            "本地未取回该消息" +
                (if (fetchQuoted) "（已尝试 im.v1.message.get 补查未取回——具体原因见网关日志「引用补查」条目）" else "") +
                "（消息可能已删除、类型不支持引用上下文，或应用缺少 im:message:readonly 权限）"
        }
        log.warn("引用未命中 replyTo=$replyTo chatId=${ctx.chatId}：$reason")
        return Result.NotReferencedWithHint("（引用提示：用户引用了消息 $replyTo，但$reason。请向用户说明这一情况，请用户重新发送内容后再引用；如无需引用内容也可直接基于文本继续。）")
    }

    /**
     * 引用上下文 prompt 组装（纯函数；JSON 围栏会污染用户原信息，改为直接拼接）。
     *
     * 形态：用户文本**原样在前**，引用上下文作结构化附加块在后：
     * ```
     * <用户原文>
     *
     * [引用文件 1]
     * 名称：crash.zip
     * 路径：/ws/downloads/.../crash.zip
     * 目录：/ws/downloads/...（工作目录，预处理产物在其下）
     * 预处理产物：
     *   - /ws/.../decoded/main.log
     *
     * [引用消息]
     * 内容：
     * 今天早上9点服务重启了一次……
     * ```
     * 标识块头（`[引用文件 N]` / `[引用消息]`）即「让传递给的 AI 知道这是什么」的标识——
     * agent 对自然语言指令的解析不被 JSON 语法干扰；文件元数据与引用原文都是明确、可复制引用的行。
     * 🔴 prompt 经 argv 独立 token 传子进程（无 shell 注入面）。
     */
    companion object {
        fun buildPromptText(text: String, files: List<FilePayload>, texts: List<TextPayload>): String = buildString {
            append(text)
            files.forEachIndexed { i, f ->
                append("\n\n[引用文件 ${i + 1}]\n")
                append("名称：").append(f.name).append('\n')
                append("路径：").append(f.path).append('\n')
                append("目录：").append(f.dir)
                if (f.artifacts.isNotEmpty()) {
                    append("\n预处理产物：")
                    f.artifacts.forEach { a -> append("\n  - ").append(a) }
                }
            }
            texts.forEachIndexed { i, t ->
                append("\n\n[引用消息")
                if (texts.size > 1) append(" ${i + 1}")
                append("]\n内容：\n").append(t.text)
            }
        }
    }
}
