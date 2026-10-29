// 产物回传单测（2026-09-12 agent-artifact-return）：标记行解析 + 沙箱校验 + 降级纪律。
// 🔴 协议失败模式安全钉死：无标记行 ⇒ 零上传零副作用；任何失败 ⇒ 文字回复不受阻断、失败条目还原。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.exec.SensitiveFilePolicy
import com.jiaoay.lark.bridge.exec.WorkspacePolicy
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.SendResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ArtifactsTest {

    // ── ArtifactMarkers.extract：纯解析 ──

    @Test
    fun `末尾连续标记块被完整提取`() {
        val text = "分析完成。\n结论：TTS 引擎不支持。\n\n[产物] /tmp/report.md\n[产物] /tmp/data.csv"
        val extracted = ArtifactMarkers.extract(text)
        assertEquals(listOf("/tmp/report.md", "/tmp/data.csv"), extracted.paths)
        assertEquals("分析完成。\n结论：TTS 引擎不支持。", extracted.body)
    }

    @Test
    fun `无标记行时原样返回零路径`() {
        val text = "普通回复，没有任何标记。"
        assertEquals(text, ArtifactMarkers.extract(text).body)
        assertTrue(ArtifactMarkers.extract(text).paths.isEmpty())
    }

    @Test
    fun `正文中间的标记字样不剥离不解析（防误吞）`() {
        val text = "说明格式：\n[产物] 这只是文档示例\n\n但正文继续到末尾。"
        val extracted = ArtifactMarkers.extract(text)
        assertTrue(extracted.paths.isEmpty(), "非末尾标记不解析：${extracted.paths}")
        assertEquals(text, extracted.body)
    }

    @Test
    fun `空路径条目跳过`() {
        val text = "ok\n[产物]   \n[产物] /tmp/a.md"
        val extracted = ArtifactMarkers.extract(text)
        assertEquals(listOf("/tmp/a.md"), extracted.paths)
        assertEquals("ok", extracted.body)
    }

    @Test
    fun `尾部空行后仍是标记行则纳入块`() {
        val text = "结论先行。\n[产物] /tmp/x.md\n\n"
        val extracted = ArtifactMarkers.extract(text)
        assertEquals(listOf("/tmp/x.md"), extracted.paths)
    }

    // ── deliverArtifacts：校验链 + 降级 ──

    /** 可编程上传假件：records 记录调用，result 可注入失败。 */
    private class FakeFileSink(val result: SendResult = SendResult(ok = true, messageId = "om_file_1")) : ReplyFileSink {
        val records = mutableListOf<Pair<File, String?>>()
        override suspend fun file(file: File, displayName: String?): SendResult {
            records.add(file to displayName)
            return result
        }
    }

    private val config = object : ArtifactsConfigView {
        override val maxCount = 3
        override val maxSizeBytes = 26_214_400L
        override val allowedExtensions = ""
    }

    private fun tempWorkspace(): File = Files.createTempDirectory("artifacts-ws").toFile().also { it.deleteOnExit() }

    @Test
    fun `沙箱内文件上传成功且正文剥离标记`() = runBlocking {
        val ws = tempWorkspace()
        val report = File(ws, "analysis_result_x.md").apply { writeText("# 报告") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()

        val result = deliverArtifacts(
            text = "分析完成。\n[产物] ${report.absolutePath}",
            config = config, policy = policy, reply = sink,
        )

        assertEquals(1, sink.records.size, "恰一次上传")
        assertEquals("分析完成。", result.body)
        assertEquals(listOf("analysis_result_x.md"), result.artifacts.map { it.fileName })
    }

    @Test
    fun `沙箱外路径拒绝并还原标记行`() = runBlocking {
        val ws = tempWorkspace()
        val outside = File(Files.createTempDirectory("outside").toFile(), "secret.md").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()

        val result = deliverArtifacts(
            text = "完成\n[产物] ${outside.absolutePath}",
            config = config, policy = policy, reply = sink,
        )

        assertTrue(sink.records.isEmpty(), "沙箱外不得上传")
        assertTrue(result.artifacts.isEmpty())
        assertTrue(result.body.contains("[产物] ${outside.absolutePath}"), "失败条目须还原路径供用户自查")
        assertTrue(result.body.contains(SensitiveFilePolicy.OPAQUE_DENIED_HINT), "围栏外统一 OPAQUE 拒绝文案：${result.body}")
    }

    /** 🔴 围栏外路径的存在性不可区分：存在与不存在的绝对路径须得到同一 OPAQUE 拒绝文案。 */
    @Test
    fun `沙箱外路径的存在性不可区分（统一 OPAQUE 文案）`() = runBlocking {
        val ws = tempWorkspace()
        val outsideDir = Files.createTempDirectory("outside-probe").toFile()
        val existing = File(outsideDir, "exists.md").apply { writeText("x") }
        val ghost = File(outsideDir, "ghost.md")
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()

        val result = deliverArtifacts(
            text = "完成\n[产物] ${existing.absolutePath}\n[产物] ${ghost.absolutePath}",
            config = config, policy = policy, reply = sink,
        )

        assertTrue(sink.records.isEmpty())
        assertEquals(2, result.rejected.size, "两条围栏外条目都被拒")
        assertTrue(result.rejected[0].contains(SensitiveFilePolicy.OPAQUE_DENIED_HINT))
        assertTrue(result.rejected[1].contains(SensitiveFilePolicy.OPAQUE_DENIED_HINT))
        assertTrue(!result.rejected[0].contains("不存在"), "围栏外不得回显存在性：${result.rejected[0]}")
        assertTrue(!result.rejected[1].contains("不存在"), "围栏外不得回显存在性：${result.rejected[1]}")
    }

    @Test
    fun `空名单 fail-safe 拒绝所有`() = runBlocking {
        val ws = tempWorkspace()
        val f = File(ws, "a.md").apply { writeText("x") }
        val sink = FakeFileSink()
        val result = deliverArtifacts(
            text = "完成\n[产物] ${f.absolutePath}",
            config = config, policy = WorkspacePolicy(emptyList()), reply = sink,
        )
        assertTrue(sink.records.isEmpty(), "空白名单必须拒绝所有（fail-safe）")
        assertTrue(result.body.contains("未能回传"))
    }

    @Test
    fun `不存在的文件拒绝并还原`() = runBlocking {
        val ws = tempWorkspace()
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val result = deliverArtifacts(
            text = "完成\n[产物] ${File(ws, "ghost.md").absolutePath}",
            config = config, policy = policy, reply = sink,
        )
        assertTrue(sink.records.isEmpty())
        // 沙箱内路径已过围栏——存在性回显不构成探测面（与 `send_file` 工具侧同序）。
        assertTrue(result.body.contains("文件不存在"))
    }

    @Test
    fun `超出数量上限的条目降级还原`() = runBlocking {
        val ws = tempWorkspace()
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val files = (1..3).joinToString("\n") { "[产物] ${File(ws, "f$it.md").apply { writeText("x") }.absolutePath}" }
        val limited = object : ArtifactsConfigView {
            override val maxCount = 2
            override val maxSizeBytes = 26_214_400L
            override val allowedExtensions = ""
        }
        val result = deliverArtifacts(
            text = "完成\n$files",
            config = limited, policy = policy, reply = sink,
        )
        assertEquals(2, sink.records.size, "只上传 maxCount 个")
        assertEquals(2, result.artifacts.size)
        assertTrue(result.body.contains("数量上限"), "第 3 条须还原并注明原因")
        // 被拒清单（2026-09-12 取证盲区补齐）：rejected 带路径与原因，供审计落盘
        assertEquals(1, result.rejected.size, "被拒条目数")
        assertTrue(
            result.rejected[0].contains("f3.md") && result.rejected[0].contains("数量上限"),
            "被拒条目含路径与原因：${result.rejected[0]}",
        )
    }

    @Test
    fun `扩展名白名单生效`() = runBlocking {
        val ws = tempWorkspace()
        val exe = File(ws, "tool.bin").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val restricted = object : ArtifactsConfigView {
            override val maxCount = 3
            override val maxSizeBytes = 26_214_400L
            override val allowedExtensions = ".md,.txt"
        }
        val result = deliverArtifacts(
            text = "完成\n[产物] ${exe.absolutePath}",
            config = restricted, policy = policy, reply = sink,
        )
        assertTrue(sink.records.isEmpty(), ".bin 不在白名单")
        assertTrue(result.body.contains("白名单"))
    }

    /** 🔴 P2 评审补测（2026-09-12）：无点写法自动补点——"md" 不得以裸后缀误匹配 "cmd"（扩展名语义而非后缀语义）。 */
    @Test
    fun `扩展名白名单无点写法不误匹配后缀`() = runBlocking {
        val ws = tempWorkspace()
        val cmd = File(ws, "report.cmd").apply { writeText("x") }
        val md = File(ws, "report.md").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val dotless = object : ArtifactsConfigView {
            override val maxCount = 3
            override val maxSizeBytes = 26_214_400L
            override val allowedExtensions = "md" // 无点写法
        }
        val result = deliverArtifacts(
            text = "完成\n[产物] ${cmd.absolutePath}\n[产物] ${md.absolutePath}",
            config = dotless, policy = policy, reply = sink,
        )
        assertEquals(1, sink.records.size, ".cmd 不得被无点条目 \"md\" 以裸后缀误匹配，.md 应放行")
        assertEquals(md.absolutePath, sink.records[0].first.absolutePath)
        assertTrue(result.body.contains(cmd.absolutePath), ".cmd 条目还原告知")
    }

    @Test
    fun `上传失败降级还原不抛`() = runBlocking {
        val ws = tempWorkspace()
        val f = File(ws, "a.md").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink(result = SendResult(ok = false, messageId = null, error = GatewayErrorCode.UploadFailed))

        val result = deliverArtifacts(
            text = "完成\n[产物] ${f.absolutePath}",
            config = config, policy = policy, reply = sink,
        )
        assertTrue(result.artifacts.isEmpty())
        assertTrue(result.body.contains("上传失败"), "上传失败须还原条目")
    }

    @Test
    fun `超出大小上限拒绝`() = runBlocking {
        val ws = tempWorkspace()
        val f = File(ws, "big.md").apply { writeText("x".repeat(100)) }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val tinyLimit = object : ArtifactsConfigView {
            override val maxCount = 3
            override val maxSizeBytes = 10L
            override val allowedExtensions = ""
        }
        val result = deliverArtifacts(
            text = "完成\n[产物] ${f.absolutePath}",
            config = tinyLimit, policy = policy, reply = sink,
        )
        assertTrue(sink.records.isEmpty(), "超大小不得上传")
        assertTrue(result.body.contains("大小上限"))
    }

    @Test
    fun `无标记行零上传零副作用`() = runBlocking {
        val ws = tempWorkspace()
        val sink = FakeFileSink()
        val result = deliverArtifacts(
            text = "纯文本回复",
            config = config, policy = WorkspacePolicy(listOf(ws)), reply = sink,
        )
        assertTrue(sink.records.isEmpty())
        assertEquals("纯文本回复", result.body)
        assertTrue(result.artifacts.isEmpty())
    }

    // ── 敏感文件排除表（2026-09-18 对抗验证补漏：本通道与 send_file 共用沙箱，必须同装一把锁）──

    @Test
    fun `沙箱内的凭据族文件被拒（与 send_file 同表）`() = runBlocking {
        // 布景：工作区配成家目录（常见）时的 ~/.ssh/id_rsa——沙箱放行，但敏感表必须拦下。
        val ws = tempWorkspace()
        val idRsa = File(ws, "id_rsa").apply { writeText("-----BEGIN OPENSSH PRIVATE KEY-----") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()

        val result = deliverArtifacts(
            text = "完成\n[产物] ${idRsa.absolutePath}",
            config = config, policy = policy, reply = sink,
        )

        assertTrue(sink.records.isEmpty(), "凭据族文件不得经标记链外发（同宽旁路）")
        assertTrue(result.artifacts.isEmpty())
        assertTrue(result.body.contains("敏感"), "须带敏感拒绝原因：${result.body}")
    }

    @Test
    fun `数据根内的文件被拒（明文 appSecret 防泄漏）`() = runBlocking {
        val ws = tempWorkspace()
        val dataRoot = File(ws, "data-root").apply { mkdirs() }
        // 🔴 名字必须**不在凭据族名单**里：用 bridge-config.json 会让判据 2 独立兜住它，
        //    断言恒真、homeDataRoot 判据删掉也照样绿。audit.jsonl 是数据根内的典型真实目标
        //    （含用户 prompt 全文的运维记录）且名字无害 ⇒ 只有数据根判据能拦。
        val auditLog = File(dataRoot, "audit.jsonl").apply { writeText("""{"prompt":"内部记录"}""") }
        // 沙箱 = ws ⇒ 数据根在其内（模拟工作区配成 ~/.toolbelt）
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()

        val result = deliverArtifacts(
            text = "完成\n[产物] ${auditLog.absolutePath}",
            config = config, policy = policy, reply = sink,
            homeDataRoot = dataRoot,
        )

        assertTrue(sink.records.isEmpty(), "桥接数据根内文件不得外发")
        assertTrue(result.body.contains("敏感"))
    }

    @Test
    fun `homeDataRoot 为 null 时数据根判据跳过但凭据族判据仍生效`() = runBlocking {
        val ws = tempWorkspace()
        val report = File(ws, "fine.md").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        // 不传 homeDataRoot（默认 null，存量调用方形态）：普通产物照常通过
        val result = deliverArtifacts(
            text = "完成\n[产物] ${report.absolutePath}",
            config = config, policy = policy, reply = sink,
        )
        assertEquals(1, sink.records.size, "根未知时普通产物不受影响（默认参数不误杀）")
        assertTrue(result.artifacts.isNotEmpty())
    }

    // ── 归档/超阈值产物强制 HIGH 确认卡（P1c：单跳批量外发链的最短形态收窄）──

    /** 自动裁决 notifier：发卡即按 [approve] 裁决（记录请求供卡面断言）。 */
    private class AutoDecideNotifier(
        private val service: com.jiaoay.lark.bridge.confirm.ConfirmService,
        private val approve: Boolean,
    ) : com.jiaoay.lark.bridge.confirm.ConfirmNotifier {
        val requests = mutableListOf<com.jiaoay.lark.bridge.confirm.ConfirmService.ConfirmRequest>()
        override suspend fun notify(request: com.jiaoay.lark.bridge.confirm.ConfirmService.ConfirmRequest) {
            requests.add(request)
            service.decide(
                com.jiaoay.lark.bridge.confirm.ConfirmService.shortId(request.requestId),
                request.chatId,
                request.senderOpenId,
                approve,
            )
        }
    }

    private fun gate(approve: Boolean): Pair<com.jiaoay.lark.bridge.confirm.ToolConfirmGate, AutoDecideNotifier> {
        val service = com.jiaoay.lark.bridge.confirm.ConfirmService()
        val notifier = AutoDecideNotifier(service, approve)
        return com.jiaoay.lark.bridge.confirm.ToolConfirmGate(
            confirmService = service,
            notifier = notifier,
            chatId = "oc_test",
            senderOpenId = "ou_test",
            timeoutMillis = 5_000,
        ) to notifier
    }

    @Test
    fun `归档扩展名产物无确认门时 fail-closed 拒发`() = runBlocking {
        val ws = tempWorkspace()
        val archive = File(ws, "bundle.tar.gz").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val result = deliverArtifacts(
            text = "完成\n[产物] ${archive.absolutePath}",
            config = config, policy = policy, reply = sink,
            confirmGate = null,
        )
        assertTrue(sink.records.isEmpty(), "归档产物无门必须 fail-closed 拒发")
        assertTrue(result.body.contains("确认链不可用"), "须说明 fail-closed 拒发原因：${result.body}")
    }

    @Test
    fun `归档扩展名产物过 HIGH 确认卡且卡面含文件名与大小`() = runBlocking {
        val ws = tempWorkspace()
        val archive = File(ws, "bundle.tar.gz").apply { writeText("0123456789") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val (gate, notifier) = gate(approve = true)
        val result = deliverArtifacts(
            text = "完成\n[产物] ${archive.absolutePath}",
            config = config, policy = policy, reply = sink,
            confirmGate = gate,
        )
        assertEquals(1, sink.records.size, "批准后归档产物须上传")
        val request = notifier.requests.single()
        assertTrue("bundle.tar.gz" in request.inputSummary && "10B" in request.inputSummary,
            "卡面须含完整文件名+大小：${request.inputSummary}")
        assertTrue(request.inputSummary.contains(archive.absolutePath), "卡面须含完整路径（防截断藏尾）：${request.inputSummary}")
        assertEquals(
            com.jiaoay.lark.bridge.confirm.RiskClassifier.RiskLevel.HIGH.name,
            request.riskLevel,
            "归档产物确认卡必须 HIGH",
        )
    }

    @Test
    fun `归档产物被拒绝或超时则拒发`() = runBlocking {
        val ws = tempWorkspace()
        val archive = File(ws, "bundle.zip").apply { writeText("x") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val (gate, _) = gate(approve = false)
        val result = deliverArtifacts(
            text = "完成\n[产物] ${archive.absolutePath}",
            config = config, policy = policy, reply = sink,
            confirmGate = gate,
        )
        assertTrue(sink.records.isEmpty(), "未获确认的归档产物不得上传")
        assertTrue(result.body.contains("未获用户确认"), "须说明未获确认拒发：${result.body}")
    }

    @Test
    fun `超阈值体积的散文件产物同样过确认卡`() = runBlocking {
        val ws = tempWorkspace()
        val big = File(ws, "bigdata.txt").apply {
            writeBytes(ByteArray(LARGE_ARTIFACT_CONFIRM_BYTES.toInt() + 1))
        }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val (gate, _) = gate(approve = true)
        val result = deliverArtifacts(
            text = "完成\n[产物] ${big.absolutePath}",
            config = config, policy = policy, reply = sink,
            confirmGate = gate,
        )
        assertEquals(1, sink.records.size, "批准后超阈值散文件须上传")
    }

    @Test
    fun `散文件产物维持总开关语义不挂卡（回归锚）`() = runBlocking {
        val ws = tempWorkspace()
        val report = File(ws, "report.md").apply { writeText("# 报告") }
        val policy = WorkspacePolicy(listOf(ws))
        val sink = FakeFileSink()
        val (gate, notifier) = gate(approve = true)
        val result = deliverArtifacts(
            text = "完成\n[产物] ${report.absolutePath}",
            config = config, policy = policy, reply = sink,
            confirmGate = gate,
        )
        assertEquals(1, sink.records.size, "散文件不挂卡照常上传")
        assertTrue(notifier.requests.isEmpty(), "小体积散文件不得发确认卡（维持总开关语义）")
        assertTrue(result.artifacts.isNotEmpty())
    }
}
