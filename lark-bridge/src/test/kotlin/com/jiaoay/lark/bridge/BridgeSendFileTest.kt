// Bridge.sendFile 门面契约测试：宿主直发文件消息（上传得 file_key → 发 msg_type=file 两步式）
// + 出站排除表语义（第三条出站通道与 send_file 工具同一张表——拒绝不消耗上传配额）。
// 🔴 全部经 BridgeAssembler.assemble 装配（App 与 CLI 唯一共用装配点），网关用 SyntheticGateway
//    替身，不连真飞书；断言面 = FileSendOutcome 分流 + 网关上传统计（recordedUploads/recordedSends）。
// runBlocking 纪律：每个挂起调用各包一层顺序（非嵌套）runBlocking（BridgeLifecycleTest 同款）。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.OutboundKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("bridge-sendfile-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

/** 带凭据配置（appId 非空 ⇒ start 预检通过） */
private fun fileSendConfig(appId: String = "file_send_app"): BridgeConfig =
    testConfig(routes = listOf(SystemRoute()))
        .copy(connection = ConnectionConfig(appId = appId, appSecret = "secret", region = "feishu"))

/** 经唯一装配点组装；sensitiveRoot = 排除表判据 1 的机密根（null ⇒ 回落 dataRoot 语义） */
private fun assembleBridge(
    dataRoot: Path,
    sensitiveRoot: Path?,
    gateways: CopyOnWriteArrayList<SyntheticGateway>,
): Bridge = BridgeAssembler.assemble(
    dataRoot = dataRoot,
    sensitiveRoot = sensitiveRoot,
    gatewayFactory = { SyntheticGateway().also { g -> gateways.add(g) } },
    defaultConfig = fileSendConfig(),
    consoleLog = false,
)

class BridgeSendFileTest {

    @Test
    fun `未运行 ⇒ 返回 null（无网关可用，与 sendTest 同契约）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleBridge(tempRoot("notrunning"), null, gateways).use { bridge ->
            val file = Files.createTempFile("report", ".md").let {
                it.toFile().writeText("# 报告")
                it
            }
            assertNull(
                runBlocking { bridge.sendFile("oc_chat", file) },
                "未 start 时必须返回 null——调用方据此提示「先启动桥接」",
            )
            assertEquals(0, gateways.size, "未运行绝不建网关")
        }
    }

    @Test
    fun `正常报告产物 ⇒ Sent，两步式上传后发 FILE 消息`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleBridge(tempRoot("sendok"), null, gateways).use { bridge ->
            assertEquals(
                BridgeStartResult.Started,
                runBlocking { bridge.start() },
                "前置：带凭据配置必须能启动（否则用例失效）",
            )
            val file = Files.createTempFile("report-run42", ".md").let {
                it.toFile().writeText("# 日志分析报告\n正文")
                it
            }
            val outcome = runBlocking { bridge.sendFile("oc_chat", file) }
            assertIs<FileSendOutcome.Sent>(outcome, "普通报告产物必须放行送出，实际=$outcome")
            val gw = gateways[0]
            assertEquals(1, gw.recordedUploads.size, "上传恰好一次")
            assertEquals(
                file.toAbsolutePath(),
                gw.recordedUploads[0].source.toAbsolutePath(),
                "上传源必须是目标文件本体",
            )
            assertEquals(
                file.fileName.toString(),
                gw.recordedUploads[0].displayName,
                "文件消息展示名 = 文件名",
            )
            assertEquals(1, gw.recordedSends.size, "发送恰好一次")
            assertEquals(OutboundKind.FILE, gw.recordedSends[0].out.kind, "必须发文件消息而非文本")
        }
    }

    @Test
    fun `机密根内文件 ⇒ Denied，拒绝不消耗上传配额`() {
        val dataRoot = tempRoot("denied-root")
        val sensitiveRoot = tempRoot("denied-secret")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleBridge(dataRoot, sensitiveRoot, gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            // 机密根内的桥接配置（判据 1：宿主机密根整棵子树）
            val secretFile = sensitiveRoot.resolve("bridge-config.json").let {
                Files.write(it, "{}".toByteArray())
                it
            }
            val outcome = runBlocking { bridge.sendFile("oc_chat", secretFile) }
            assertIs<FileSendOutcome.Denied>(outcome, "机密根内文件必须命中排除表，实际=$outcome")
            assertEquals(0, gateways[0].recordedUploads.size, "拒绝必须发生在上传之前")
            assertEquals(0, gateways[0].recordedSends.size, "拒绝不得产生任何出站")
        }
    }

    @Test
    fun `凭据族文件名（机密根外）⇒ Denied（判据 2 不依赖路径位置）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleBridge(tempRoot("denied-name"), null, gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            // 裸名私钥形态：不带扩展名（stem 判据的靶形——createTempFile 会追加随机
            // 后缀破坏裸名，必须手工命名）
            val idRsa = tempRoot("denied-name").resolve("id_rsa").let {
                Files.write(it, "PRIVATE".toByteArray())
                it
            }
            val outcome = runBlocking { bridge.sendFile("oc_chat", idRsa) }
            assertIs<FileSendOutcome.Denied>(outcome, "凭据族文件名必须命中排除表，实际=$outcome")
            assertEquals(0, gateways[0].recordedUploads.size, "拒绝必须发生在上传之前")
        }
    }

    @Test
    fun `chat_id 空或文件不存在 ⇒ InvalidInput（不触网关上传与发送）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleBridge(tempRoot("invalid"), null, gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val file = Files.createTempFile("report-run7", ".md").let {
                it.toFile().writeText("# 报告")
                it
            }
            assertIs<FileSendOutcome.InvalidInput>(
                runBlocking { bridge.sendFile("", file) },
                "chat_id 空必须归一 InvalidInput",
            )
            val missing = tempRoot("invalid").resolve("no-such-report.md")
            assertIs<FileSendOutcome.InvalidInput>(
                runBlocking { bridge.sendFile("oc_chat", missing) },
                "文件不存在必须归一 InvalidInput",
            )
            assertEquals(0, gateways[0].recordedUploads.size, "输入校验失败不触上传")
            assertEquals(0, gateways[0].recordedSends.size, "输入校验失败不触发送")
        }
    }

    @Test
    fun `上传失败（file_key 为 null）⇒ Failed(UploadFailed)，不发文件消息`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleBridge(tempRoot("uploadfail"), null, gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            gateways[0].uploadFileResult = false // 可编程失败开关（SyntheticGateway 同款）
            val file = Files.createTempFile("report-run9", ".md").let {
                it.toFile().writeText("# 报告")
                it
            }
            val outcome = runBlocking { bridge.sendFile("oc_chat", file) }
            assertIs<FileSendOutcome.Failed>(outcome, "上传失败必须归一 Failed，实际=$outcome")
            assertEquals(
                com.jiaoay.lark.gateway.GatewayErrorCode.UploadFailed,
                outcome.error,
                "错误码必须归一 UploadFailed",
            )
            assertEquals(0, gateways[0].recordedSends.size, "无 file_key 不得发文件消息")
        }
    }
}
