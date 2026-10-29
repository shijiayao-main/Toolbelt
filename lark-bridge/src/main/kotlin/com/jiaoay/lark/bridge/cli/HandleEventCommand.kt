// handle-event 子命令：完全离线端到端验证入口（不依赖飞书即可跑完整五阶段管线）。
// 读一段事件 JSON（--event <path> 或 stdin）→ 构造 InboundMessage → 喂 SyntheticGateway → 装配唯一门面跑管线 →
// 打印出站结果与计数器。
// 🔴 恒用 SyntheticGateway（结构性离线保证，绝不触网）；缺省事件为 p2p "ping" ⇒ SystemHandler ⇒ "pong 🏓"。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.jiaoay.lark.bridge.Bridge
import com.jiaoay.lark.bridge.BridgeAssembler
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.InboundResource
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** handle-event 的事件 JSON DTO（[InboundMessage] 非 @Serializable，故自定义离线回放 DTO）。 */
@Serializable
private data class CliEvent(
    val messageId: String = "",
    val chatId: String = "oc_cli_replay",
    val chatType: String = "p2p",
    val senderId: String = "ou_cli_sender",
    val senderName: String = "CliSender",
    val text: String = "ping",
    val kind: String = "text",
    val mentionAll: Boolean = false,
    val mentionedBot: Boolean = false,
    val rootId: String? = null,
    val threadId: String? = null,
    /** 引用回复的消息 ID（引用流回放：文件事件 + 引用事件两连的关键字段）。 */
    val replyToMessageId: String? = null,
    /** 文件资源（kind=file 时登记进 FileRegistry；缺省空 = 无资源）。 */
    val resources: List<CliResource> = emptyList(),
    val createTimeMillis: Long = 0L,
)

/** [CliEvent] 的文件资源 DTO（对齐 [InboundResource] 的 4 字段面）。 */
@Serializable
private data class CliResource(
    val type: String? = "file",
    val fileKey: String? = null,
    val fileName: String? = null,
    val durationMs: Long = 0L,
)

/** 事件 JSON 解析专用配方（宽容未知键；与配置文档 [com.jiaoay.lark.bridge.config.BridgeJson] 无关）。 */
private val EventJson = Json { ignoreUnknownKeys = true }

private const val EXAMPLE_EVENT_JSON = """{
  "messageId": "om_demo_1",
  "chatId": "oc_demo",
  "chatType": "p2p",
  "senderId": "ou_demo",
  "senderName": "Demo",
  "text": "ping",
  "kind": "text"
}"""

/** 引用流两连示例（文件事件 → 引用事件）；下载由 SyntheticGateway 模拟（不触网）。 */
private const val EXAMPLE_FILE_EVENT_JSON = """{
  "messageId": "om_file_1",
  "chatId": "oc_demo",
  "senderId": "ou_demo",
  "kind": "file",
  "text": "<file key=\"fk_demo\" name=\"crash.zip\"/>",
  "resources": [{"type": "file", "fileKey": "fk_demo", "fileName": "crash.zip"}]
}"""

/**
 * `handle-event`：离线回放一条事件 JSON，验证五阶段管线（喂 SyntheticGateway，恒不触网）。
 * @param gatewayCustomizer 合成网关定制钩子（测试注入 `fetchQuotedMessage` 可编程面用；
 *    默认 null = 零定制，生产行为不变）。
 */
class HandleEventCommand(
    out: PrintStream = System.out,
    exit: (Int) -> Unit = { exitProcess(it) },
    private val gatewayCustomizer: (SyntheticGateway) -> Unit = {},
) : BridgeConfigCommand("handle-event", out, exit) {

    private val eventPath: Path? by option(
        "--event",
        help = "事件 JSON 文件路径（缺省则从 stdin 读）",
    ).path(mustExist = true, canBeDir = false)

    override fun help(context: Context) =
        "离线回放事件 JSON 跑完整五阶段管线（恒用 SyntheticGateway，不连飞书）。\n" +
            "事件 JSON 从 --event <path> 或 stdin 读；单对象或 JSON 数组均可（数组 = 逐条回放），缺字段用默认值。\n" +
            "单事件示例（ping ⇒ pong 🏓）：\n" + EXAMPLE_EVENT_JSON + "\n\n" +
            "引用流两连示例（文件事件登记 → 引用事件触发分析；一条数组喂两次 emit，登记表同进程存活）：\n" +
            "[\n" + EXAMPLE_FILE_EVENT_JSON + ",\n" +
            " {\"messageId\": \"om_quote_1\", \"chatId\": \"oc_demo\", \"senderId\": \"ou_demo\",\n" +
            "  \"text\": \"ai 分析这个压缩包\", \"kind\": \"text\", \"replyToMessageId\": \"om_file_1\"}\n" +
            "]"

    override fun run() {
        val raw = eventPath?.toFile()?.readText(Charsets.UTF_8)
            ?: System.`in`.readBytes().toString(Charsets.UTF_8)
        if (raw.isBlank()) {
            emit("未提供事件 JSON（用 --event <path> 或从 stdin 传入）。示例：")
            emit(EXAMPLE_EVENT_JSON)
            exit(EXIT_GENERIC)
            return
        }
        // 单对象或 JSON 数组均可（数组 = 引用流两连：文件事件 → 引用事件，同进程登记表存活）
        val events = runCatching { parseEvents(raw) }.getOrNull()
        if (events == null) {
            emit("事件 JSON 解析失败 [${BridgeErrorCode.ConfigParseFailed}]：无法解析为事件对象或事件数组")
            exit(exitCodeFor(BridgeErrorCode.ConfigParseFailed))
            return
        }

        val resolution = resolveConfig()
        if (resolution is ConfigResolution.Err) {
            emit("配置错误 [${resolution.code}]：${resolution.message}")
            exit(exitCodeFor(resolution.code))
            return
        }
        var config = (resolution as ConfigResolution.Ok).config

        val inbounds = events.map { toInboundMessage(it) }

        // 准入恒放行（access 白名单已退役）——离线回放不再需要自适配白名单。
        // start() 需要非空 appId 才建 bundle；离线回放用合成 appId（绝不触网）。
        if (config.connection.appId.isBlank()) {
            config = config.copy(connection = config.connection.copy(appId = SyntheticGateway.DEFAULT_APP_ID))
        }

        val synthetic = SyntheticGateway().also(gatewayCustomizer)
        val dataRoot = resolveDataRoot()
        val bridge = BridgeAssembler.assemble(
            dataRoot = dataRoot,
            gatewayFactory = { synthetic },
            defaultConfig = config,
            parentJob = null,
            consoleLog = verbose,
            // --verbose 时既开控制台又把下限降到 DEBUG（放出既有拒绝诊断）；不加持 consoleLog=false 完全无控制台输出。
            consoleMinLevel = consoleLevelFor(verbose),
        )

        // 🔴 run() 边界**恰好一层** runBlocking（CLI 阻塞入口，runBlocking 唯一正当位置；applyConfig/start/stop/awaitSettle 均挂起）。
        //    块内**绝不**调用 bridge.close()——close() 自带唯一一层 runBlocking，块内调用即嵌套（严禁）；
        //    close()/exit() 一律挪到 runBlocking 返回后的阻塞世界执行。
        var refusedCode: BridgeErrorCode? = null
        val refused = runBlocking {
            // 🔴 --set ephemeral：persist=false，覆写只在内存生效，任何情况都不写 bridge-config.json。
            bridge.applyConfig(config, persist = false)

            val started = bridge.start()
            if (started is BridgeStartResult.Refused) {
                refusedCode = started.code
                emit("管线启动被拒 [${started.code}]：${started.reason}")
                return@runBlocking true
            }

            // 逐条回放（多事件 = 引用流两连：前一条登记完再发引用，awaitSettle 等到回复落定）
            inbounds.forEachIndexed { i, inbound ->
                emit("== 事件 ${i + 1}/${inbounds.size} ==")
                emit("messageId: ${inbound.messageId}")
                emit("chatType:  ${inbound.chatType}")
                emit("chatId:    ${inbound.chatId}")
                emit("senderId:  ${inbound.senderId}")
                emit("kind:      ${inbound.kind}")
                emit("replyTo:   ${inbound.replyToMessageId ?: "-"}")
                emit("text:      ${inbound.text}")

                synthetic.emitInbound(inbound)
                awaitSettle(bridge, synthetic)
                bridge.publishStatus()
            }

            emit()
            emit("== 出站结果（SyntheticGateway 记录）==")
            if (synthetic.recordedSends.isEmpty()) {
                emit("  （无出站消息）")
            } else {
                synthetic.recordedSends.forEach { send ->
                    val body = send.text ?: send.card?.let { "[card] ${it.title}" } ?: "[${send.out.kind}]"
                    emit("  → ${send.target.receiveIdType}=${send.target.receiveId} ${send.out.kind}: $body")
                }
            }

            emit()
            emit("== 计数器（五阶段管线）==")
            val c = bridge.status.value.counters
            emit("  intakeReceived=${c.intakeReceived} submitted=${c.submitted} completed=${c.completed}")
            emit("  accessRejected=${c.accessRejected} policyRejected=${c.policyRejected} unmatched=${c.unmatched}")
            emit("  staleDropped=${c.staleDropped} duplicatesDropped=${c.duplicatesDropped} skipped=${c.skipped}")
            emit("  failed=${c.failed} handlerErrors=${c.handlerErrors} replyFailures=${c.replyFailures}")
            emit("  filesRegistered=${c.filesRegistered} filesReferenced=${c.filesReferenced} downloadsStored=${c.downloadsStored} quotaEvictions=${c.quotaEvictions}")

            bridge.stop(Bridge.GRACE_MILLIS)
            false
        }

        // 🔴 close() 在 runBlocking 之外（阻塞世界）调用，避免其内部 runBlocking 与外层嵌套。
        bridge.close()
        if (refused) exit(exitCodeFor(refusedCode))
    }

    /** 单 JSON 对象 → 单事件；JSON 数组 → 逐条（引用流两连回放）。 */
    private fun parseEvents(raw: String): List<CliEvent> {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("[")) {
            EventJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CliEvent.serializer()), trimmed)
        } else {
            listOf(EventJson.decodeFromString(CliEvent.serializer(), trimmed))
        }
    }

    /**
     * 轮询直至管线落定（出现出站或终态计数），或超时；随后短暂 settle 让回复 sink flush。
     * 🔴 已是 suspend（由 run() 边界的唯一一层 runBlocking 驱动），内部**不再** runBlocking（否则嵌套）。
     */
    private suspend fun awaitSettle(bridge: Bridge, synthetic: SyntheticGateway) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            bridge.publishStatus()
            val c = bridge.status.value.counters
            val terminal = c.completed + c.failed + c.skipped + c.unmatched +
                c.accessRejected + c.policyRejected + c.staleDropped + c.duplicatesDropped
            if (terminal > 0 || synthetic.recordedSends.isNotEmpty()) break
            delay(POLL_INTERVAL_MILLIS)
        }
        delay(SETTLE_FLUSH_MILLIS)
    }

    private fun toInboundMessage(event: CliEvent): InboundMessage = InboundMessage(
        messageId = event.messageId.ifBlank { "om_cli_${System.nanoTime()}" },
        chatId = event.chatId,
        chatType = when (event.chatType.lowercase()) {
            "p2p" -> ChatType.P2P
            "group" -> ChatType.GROUP
            else -> ChatType.UNKNOWN
        },
        senderId = event.senderId,
        senderName = event.senderName,
        text = event.text,
        kind = runCatching { InboundKind.valueOf(event.kind.uppercase()) }.getOrDefault(InboundKind.TEXT),
        rawContentType = event.kind,
        resources = event.resources.map {
            InboundResource(type = it.type, fileKey = it.fileKey, fileName = it.fileName, durationMs = it.durationMs)
        },
        mentions = emptyList(),
        mentionAll = event.mentionAll,
        mentionedBot = event.mentionedBot,
        rootId = event.rootId,
        threadId = event.threadId,
        replyToMessageId = event.replyToMessageId,
        createTimeMillis = if (event.createTimeMillis > 0L) event.createTimeMillis else System.currentTimeMillis(),
    )

    private companion object {
        const val SETTLE_TIMEOUT_MILLIS = 8_000L
        const val POLL_INTERVAL_MILLIS = 50L
        const val SETTLE_FLUSH_MILLIS = 300L
    }
}
