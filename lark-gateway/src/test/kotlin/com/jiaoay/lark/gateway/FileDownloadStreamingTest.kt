// :lark-gateway 测试（M3-T3 + M4-T8，§11.12 FileDownloadStreamingTest = BC NF-A5，断言已按裁定 3/B-3 调整）。
// 🔴 SDK 2.8.5 硬约束（分片批 2026-09-13 演进）：GetMessageResourceResp extends BaseResponse<ByteArrayOutputStream>，
//    无 InputStream 流式路径 ⇒ 单片响应仍全量入堆，但 **Range 分片把内存上界从「并发 × 单文件大小」降到
//    「并发 × 单片大小」**；maxFileSizeBytes 语义 = 「我方磁盘保护阈值」——206 路径 = 首片 Content-Range
//    预检（下载前拒绝），200 降级路径 = 下载后拒绝（保留）。
// 🔴 可兑现性分工（记入报告）：
//   ①（downloadMaxBytes<=0 主动拒绝，不发请求）= **SDK-free 兑现**（未连接路径即返回 false，不触 SDK）。
//   ②（并发单飞）③（原子落盘 + 失败清理）⑤（路径策略归引擎）= grep 结构断言兑现。
//   ④（真实 SDK 调用路径：206 分片 / 200 降级 / 非 200 三分支）= 由 **SdkDownloadTransportTest** 真实覆盖
//      （注入假 IHttpTransport，无凭据即可驱动三条分支）。
//   ⑥（真机内存曲线 / >100MB 真机 206 行为）归真机验证清单（分片批交付后）。
package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** §11.12：文件下载的上限钳制 + 并发单飞 + 原子落盘 + 路径策略归引擎（降级方案的三条可兑现断言 + 结构前提）。 */
class FileDownloadStreamingTest {

    // ① 上限钳制：downloadMaxBytes <= 0 时主动拒绝，不发请求（未触 SDK 即返回 false，SDK-free 可测）
    @Test
    fun `non positive download budget is rejected before any sdk call`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gw = SdkLarkGateway(
                netDispatcher = Dispatchers.IO,
                scope = scope,
                wsInboundFactory = NoopWsInboundFactory,
                settings = GatewaySdkSettings.DEFAULT.copy(downloadMaxBytes = 0L),
            )
            val dest = File.createTempFile("gw-dl-reject", ".bin").toPath()
            // 🔴 downloadMaxBytes<=0 ⇒ downloadResource 在触 netDispatcher/SDK/channel 之前即拒（§3.7 防护 ①）
            val result = runBlocking { gw.downloadResource("om_msg_1", "file_key_1", dest) }
            assertFalse(result.ok, "downloadMaxBytes<=0 应直接拒绝返回 false")
            assertEquals(
                GatewayErrorCode.DownloadTooLarge, result.error,
                "预检拒绝须映射 DownloadTooLarge（调用方据此给「超限」而非「网络问题」文案）",
            )
            gw.close()
        } finally {
            scope.cancel()
        }
    }

    // 裁定 3 / B-3 硬性要求：并发下载上限 Semaphore(2)（分片后堆峰值 = 并发下载数 × 单片大小），默认值登记
    @Test
    fun `download concurrency is capped at two by default`() {
        assertEquals(2, GatewaySdkSettings.DEFAULT.downloadSingleFlight,
            "裁定 3：并发下载上限须为 2（Semaphore），分片后堆峰值 = 并发数 × 单片大小")
        assertEquals(1_073_741_824L, GatewaySdkSettings.DEFAULT.downloadMaxBytes,
            "downloadMaxBytes 默认 1GB = fileMessage.maxFileSizeBytes 新默认（分片批 2026-09-13；磁盘保护阈值，首片预检口径）")
        assertEquals(33_554_431, GatewaySdkSettings.DEFAULT.downloadChunkBytes,
            "分片单片 = 32MB-1（飞书官方限制单片 ≤ 32MB，33554432 会超）")
        assertEquals(4_096, GatewaySdkSettings.DEFAULT.downloadMaxChunks,
            "片数上限 4096（防「1 字节片」病态服务器；诚实分片数 = 1GB/32MB = 32，百倍余量）")
        val src = stripComments(readGatewaySource())
        assertTrue("Semaphore(settings.downloadSingleFlight)" in src, "并发上限须由 Semaphore(downloadSingleFlight) 承担")
        assertTrue("downloadSemaphore.withPermit" in src, "下载须在 withPermit 内执行（限并发）")
    }

    // ②③④ 结构前提（真机断言归 T11/M4-T8）：并发单飞 + 原子落盘 + 失败清理 + 直连 Transport.send
    @Test
    fun `download has single flight atomic move and cleanup structure`() {
        val src = stripComments(readGatewaySource())
        // downloadResource + doDownload 都在 downloadResource(..) 与 setReaction(..) 之间
        val dl = src.substringAfter("override suspend fun downloadResource(").substringBefore("override suspend fun setReaction(")
        assertTrue(dl.isNotEmpty(), "定位不到 downloadResource/doDownload 函数体")
        // ② 并发单飞：同 (messageId,fileKey) 只允许一个在途
        assertTrue("inflightDownloads.add(key)" in dl, "并发单飞须用 inflightDownloads 去重（§3.7 防护 ②）")
        // ③ 原子落盘 + 失败清理：临时 .part- 文件 + ATOMIC_MOVE + catch 里 deleteIfExists
        assertTrue(".part-" in dl, "落盘须先写临时 .part- 文件（§3.7 防护 ③）")
        assertTrue("ATOMIC_MOVE" in dl, "须原子移动到 dest")
        assertTrue("deleteIfExists(tmp)" in dl, "失败路径须清理临时文件（BC-298）")
        // ④ 🔴 2026-09-12 直连批次：不再用 SDK 的 resp.writeFile(String)，改为 Transport.send 拿 RawResponse
        //    后直接 Files.write(Path, ByteArray)——少一次 BAOS.toByteArray() 全量拷贝，内存峰值反而下降。
        assertTrue("Files.write(tmp," in dl, "落盘须为 Files.write(tmp, …)（绕开 SDK writeFile）")
        assertFalse("writeFile(" in dl, "不得再走 SDK 的 resp.writeFile（其非 200 分支会抛 JsonIOException）")
        // 🔴 直连证据：必须经 Transport.send，且不得再用 messageResource().get()
        assertTrue("Transport.send(" in dl, "下载须直连 com.lark.oapi.core.Transport.send（绕开 unmarshalResp）")
        assertFalse(
            "messageResource()" in dl,
            "不得用 SDK 的 messageResource().get()——非 200 分支的 Gson 反射缺陷会吞掉真实 HTTP 状态码（2026-09-12 真机）",
        )
        // 🔴 错误可观测性：真实 HTTP 状态码须进信号（修复前恒 DownloadFailed + 异常 toString）
        assertTrue("raw.statusCode" in dl, "非 200 判定须读真实 HTTP 状态码")
        assertTrue("LarkErrorMapper.fromLarkCode(" in dl, "真实状态码/业务码须经 mapper 映射")
        // 防护 ①'：下载后按已知字节数拒绝超限文件（200 降级路径保留；206 路径升级为首片预检）
        assertTrue("DownloadTooLarge" in dl, "超限文件须映射 DownloadTooLarge 并拒绝落盘")
        // 🔴 Range 分片批（2026-09-13）：循环结构 + 206 解析 + 取消观察点 + 分片续写
        assertTrue("Tuning.httpPartialContent" in dl, "分片循环须判 206（Partial Content）分支")
        assertTrue("parseContentRange(" in dl, "206 须解析 Content-Range（提取处——首片预检的数据源）")
        assertTrue("bytes=" in dl, "Range 头构造字面量须在（bytes=<start>-<end>）")
        assertTrue("ensureActive()" in dl, "每片发送前须有取消观察点（配对 CE rethrow）")
        assertTrue("catch (c: CancellationException)" in dl, "CE 须单独 catch 并 rethrow（不吞 CE 纪律）")
        assertTrue("APPEND" in dl, "分片续写须用 APPEND（首片 CREATE+TRUNCATE）")
        assertTrue("mutableMapOf(\"Range\"" in dl, "🔴 每片 headers map 必须新建（ReqTranslator 会 mutate 外部 map put Authorization）")
    }

    // ⑤ 下载路径策略归引擎：网关不校验 dest 是否在白名单内（DownloadPathPolicy 在 :lark-bridge，BC-414..418）
    @Test
    fun `gateway does not enforce a destination whitelist`() {
        val src = stripComments(readGatewaySource())
        val dl = src.substringAfter("override suspend fun downloadResource(").substringBefore("override suspend fun setReaction(")
        assertFalse("DownloadPathPolicy" in dl, "路径白名单策略归引擎（:lark-bridge），网关不得引用")
        assertFalse("allowedFileDirs" in dl, "网关不校验 dest 是否在 allowedFileDirs 内（对任意 dest 照写，§11.12 ⑤）")
        assertFalse("isWithin" in dl || "startsWith(allowed" in dl, "网关不做 dest 前缀白名单校验")
    }

    // ⑥ 凭据留存的生命周期（2026-09-12 直连批次新增）：留存凭据是为了自建 Config，但留存的是**明文
    //    appSecret**（Client.config 私有且无 getter）⇒ 关闭时必须清空，否则多一座网关就多一份长驻 secret。
    //    🔴 用源码断言而非行为断言：行为侧要靠测试专用 getter 去读 private 字段，或走 connect() 真连（不可能）。
    @Test
    fun `credential is retained for download and cleared on close`() {
        val src = stripComments(readGatewaySource())
        assertTrue(
            "credentialRef.set(cred)" in src,
            "connect() 成功路径须留存凭据（下载直连的自建 Config 只能靠它）",
        )
        assertTrue("credentialRef.set(null)" in src, "🔴 disconnect()/close() 必须清空凭据（防 appSecret 长驻）")
        val clearAt = src.indexOf("credentialRef.set(null)")
        val retainAt = src.indexOf("credentialRef.set(cred)")
        assertTrue(retainAt in 1..<clearAt, "留存点须在清理点之前（否则顺序被改坏）")
        // 留存必须发生在**连接成功之后**：过早留存（例如 connect() 开头就 set）会让「连接失败、网关对外
        // 报 Disconnected」时，downloadResource 仍真的发出一次带凭据的 HTTP 请求。判据取 `channel = ch`
        // ——它才是「已连上」的真标记（步 4）；`_connectionState` 赋值只是对外广播，与它同一微步、无挂起点。
        val connectedAt = src.indexOf("channel = ch")
        assertTrue(
            connectedAt in 1..<retainAt,
            "🔴 凭据须在 `channel = ch`（连接成功）**之后**才留存，实际 connectOk@$connectedAt retain@$retainAt",
        )
        assertTrue(
            retainAt < src.indexOf("attachDisconnectOnCancel(ch)"),
            "留存须仍在 connect() 的步 4（挂断连之前），不要漂到别处——下线批后步 5 不再有 registerHandlers（SDK 入站已删）",
        )
    }

    private fun readGatewaySource(): String =
        File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt").readText()

    /**
     * 剥掉行注释（`//` 之后）与块注释（含跨行 KDoc）的**内容**，保留换行以维持行号对齐。
     *
     * 🔴 为什么必须剥（2026-09-12 假红事件）：本类的断言有一半是 `assertFalse("xxx" in dl)` 的**反向**结构断言，
     *    而 `doDownload` 的 KDoc 恰恰要解释「为什么**不用** SDK 的 `resp.writeFile(tmp)` / `messageResource().get()`」
     *    ——注释里提一句字面量就足以让正向断言假绿、反向断言假红。扫生产源码的结构断言一律先过这里。
     *
     * 🔴 只剥注释、**不剥字符串字面量**：正向断言里的 `.part-` 正是 `".part-"` 这个字面量本身，
     *    连字符串一起剥会把正向断言一起剥没。（与 `LarkSdkIsolationTest.normalizeKotlinSource` 的取舍不同，
     *    那个用法的黑名单前缀只可能出现在类型引用里，故它可以连字符串一起剥。）
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                // 字符串字面量：整体照抄（含 `\"` 转义），避免把字面量里的 `//` 误当注释
                c == '"' -> {
                    out.append(c); i++
                    while (i < src.length) {
                        val d = src[i]
                        if (d == '\\' && i + 1 < src.length) {
                            out.append(d).append(src[i + 1]); i += 2; continue
                        }
                        out.append(d); i++
                        if (d == '"') break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i < src.length && !(src[i] == '*' && i + 1 < src.length && src[i + 1] == '/')) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }
}
