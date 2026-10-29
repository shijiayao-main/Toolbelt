// :lark-gateway 测试（2026-09-12 下载直连批次；2026-09-13 Range 分片批扩展）：
// **真实覆盖** SdkLarkGateway 的下载 SDK 调用路径。
//
// 🔴 为什么必须有这个文件：此前的 FileDownloadStreamingTest 只对源码做 grep 结构断言，
//    `downloadResource` 的真实调用路径**零覆盖**——这正是真机 `DownloadFailed`（Gson 反射
//    吞掉真实 HTTP 状态码）得以长期藏身的原因。本文件注入假 `IHttpTransport`，无凭据即可
//    驱动 **206 分片 / 200 降级 / 非 200** 三条真实分支。
package com.jiaoay.lark.gateway

import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.request.RawRequest
import com.lark.oapi.core.response.RawResponse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 假 HTTP 传输：恒返回预设的状态码与响应体，不发任何网络请求。 */
private class FakeHttpTransport(
    private val presetStatusCode: Int,
    private val presetBody: ByteArray,
) : IHttpTransport {
    val capturedUrls = mutableListOf<String>()
    val capturedSupportDownLoad = mutableListOf<Boolean>()
    override fun execute(request: RawRequest): RawResponse {
        capturedUrls += request.reqUrl
        // 🔴 `isSupportDownLoad` 是 RawRequest 自身的字段，由 ReqTranslator 依 RequestOptions 填——
        //    这是「SDK 笔误规避」唯一可在网关外部观测到的证据。
        capturedSupportDownLoad += request.isSupportDownLoad
        // 🔴 必须用 this@FakeHttpTransport 限定参数名：`RawResponse` 自带 `statusCode`/`body` 属性，
        //    而 `apply { }` 的接收者作用域优先级高于外部参数 ⇒ 不限定会写成「把接收者的当前值赋给它自己」
        //    （首版实测：setStatusCode(429) 之后读回仍是 0）。这个陷阱值得留注释。
        return RawResponse().apply {
            setStatusCode(this@FakeHttpTransport.presetStatusCode)
            setBody(this@FakeHttpTransport.presetBody)
        }
    }
}

/**
 * Range 感知假 HTTP 传输（2026-09-13 分片批）：模拟「支持 Range 的飞书服务器」。
 *
 * 行为：读请求 `Range: bytes=<start>-<end>` 头 → 从 [wholeBody] 切片 → 返回
 * `206 + Content-Range: bytes <start>-<clampedEnd>/<total>`（**键用小写**，复刻 OkHttp
 * `Headers.toMultimap()` 的实测形态——网关的 [headerValueOf] 必须大小写不敏感才能取到）。
 * 请求无 Range 头时返回 200 整文件（模拟「服务器忽略 Range」的降级形态）。
 *
 * 可观测面：
 * - [capturedRanges]：每片请求的 Range 原文（按序）
 * - [capturedHeaderMaps]：每片请求的 headers map **引用**（断言「每片新建」——ReqTranslator 会
 *   mutate 外部 map put Authorization，复用实例会被前片污染）
 *
 * 病态模式（[pathologicalBytes] 非 null）：每片只返回这么少字节且 Content-Range 只声明 1 字节——
 * 驱动「片数上限」防线。脚本化中断（[interruptFromChunk]/[interruptResponse]）：第 N 片起改返回注入的
 * 异常响应（驱动「分片中途失败清理」）。
 */
private class RangedFakeHttpTransport(
    private val wholeBody: ByteArray,
    private val pathologicalBytes: Int? = null,
    private val interruptFromChunk: Int = Int.MAX_VALUE,
    private val interruptResponse: RawResponse? = null,
    private val malformedContentRange: Boolean = false,
) : IHttpTransport {
    val capturedRanges = mutableListOf<String>()
    val capturedHeaderMaps = mutableListOf<Map<String, List<String>>>()

    override fun execute(request: RawRequest): RawResponse {
        capturedHeaderMaps += request.headers
        val range = request.headers.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value?.firstOrNull()
        if (range == null) {
            // 服务器忽略 Range → 200 整文件（降级形态）
            return RawResponse().apply { setStatusCode(200); setBody(wholeBody) }
        }
        capturedRanges += range
        val calls = capturedRanges.size
        // 脚本化中断：第 interruptFromChunk 片起返回注入响应（如 400+234046 错误体）
        if (calls >= interruptFromChunk && interruptResponse != null) return interruptResponse
        val match = Regex("""bytes=(\d+)-(\d+)""").find(range) ?: error("Range 头不合法: $range")
        val start = match.groupValues[1].toLong()
        val pathEnd = if (pathologicalBytes != null) start + pathologicalBytes - 1
        else minOf(match.groupValues[2].toLong(), wholeBody.size.toLong() - 1)
        val slice = wholeBody.copyOfRange(start.toInt(), pathEnd.toInt() + 1)
        return RawResponse().apply {
            setStatusCode(206)
            setBody(slice)
            // 🔴 小写键复刻 OkHttp 实测形态；malformedContentRange=true 时给「格式匹配但语义违法」形态
            //    （end ≥ total——parseContentRange 的自洽性校验防线，纯格式垃圾测不到这条）
            setHeaders(
                if (malformedContentRange) mapOf("content-range" to listOf("bytes 0-99/50"))
                else mapOf("content-range" to listOf("bytes $start-$pathEnd/${wholeBody.size}")),
            )
        }
    }
}

/**
 * 下载路径的真实 SDK 调用测试。
 *
 * 🔴 **变异即红**：把 `doDownload` 改回 SDK 的 `client.im().v1().messageResource().get(req)`，
 *    `非 200 分支` 用例会抛 `com.google.gson.JsonIOException: Failed making field
 *    'java.io.ByteArrayOutputStream#buf' accessible`（JBR 25 强封装），整个用例红。
 */
class SdkDownloadTransportTest {

    // ==================== ① 非 200：真实状态码必须可观测（本次真机缺陷的回归防线）====================

    @Test
    fun `非 200 时取真实状态码映射而非 JsonIOException（真机 DownloadFailed 回归）`() {
        val denied = """{"code":99991672,"msg":"Access denied. One of the following scopes is required: [im:resource]"}"""
        withGateway(statusCode = 403, body = denied.toByteArray()) { gw, dest ->
            val event = runBlocking { downloadAndCapture(gw, dest) }

            assertFalse(event.result.ok, "非 200 必须返回 false（不得抛异常）")
            val failed = assertNotNull(event.signal, "非 200 必须发 SendFailed 信号（可观测）")
            assertEquals(
                GatewayErrorCode.ConnectFatal, failed.code,
                "403 附带业务码 99991672 ⇒ 应映射 ConnectFatal（应用凭据无效/无权限），实际=${failed.code}",
            )
            // 🔴 关键断言：真实 HTTP 状态码与飞书错误体必须出现在 msg 里——修复前这里只有一句
            //    JsonIOException 的 toString，完全看不出是权限问题。
            assertTrue(failed.msg.contains("403"), "msg 必须含真实 HTTP 状态码，实际=${failed.msg}")
            assertTrue(failed.msg.contains("99991672"), "msg 必须含飞书业务码（从错误体提取），实际=${failed.msg}")
            assertFalse(dest.toFile().isFile, "非 200 不得落盘")
        }
    }

    @Test
    fun `429 映射 RateLimited 以便退避`() {
        withGateway(statusCode = 429, body = """{"code":11232,"msg":"too many request"}""".toByteArray()) { gw, dest ->
            val event = runBlocking { downloadAndCapture(gw, dest) }
            val failed = assertNotNull(event.signal)
            assertEquals(GatewayErrorCode.RateLimited, failed.code, "429 须映射 RateLimited（RateGovernor 据此退避）")
            assertTrue(failed.msg.contains("429"), "msg 须含 429")
            assertEquals(GatewayErrorCode.RateLimited, event.result.error, "返回值 error 须与信号同码（双轨一致）")
            assertEquals(11232, event.result.larkCode, "飞书业务码须透传进返回值（resolver 判据依赖）")
            // 🔴 限流双信号（与 uploadFile 对齐）：SendFailed 之外须同发 RateLimited 驱动 RateGovernor 退避
            assertNotNull(event.rateLimited, "🔴 429 须同发 RateLimited 信号——只发 SendFailed 则 RateGovernor 永不退避")
        }
    }

    /**
     * 🔴 平台 100MB 下载限（2026-09-12 真机缺陷的回归防线）：
     * 真机日志 `download http=400 body={"code":234037,"msg":"Downloaded file size exceeds limit."}`。
     * 该码必须映射 **DownloadTooLarge** 且业务码透传进返回值——resolver 据 `larkCode != null`
     * 判定「平台限」并渲染「重试无用，请压缩或拆分」文案；若回落 SendFailed，用户会看到误导性的「请重试」。
     */
    @Test
    fun `234037 平台下载大小限映射 DownloadTooLarge 且 larkCode 透传`() {
        val platform = """{"code":234037,"msg":"Downloaded file size exceeds limit."}"""
        withGateway(statusCode = 400, body = platform.toByteArray()) { gw, dest ->
            val event = runBlocking { downloadAndCapture(gw, dest) }

            assertFalse(event.result.ok, "平台限必须拒绝")
            assertEquals(
                GatewayErrorCode.DownloadTooLarge, event.result.error,
                "🔴 234037 ⇒ DownloadTooLarge（与「我方超限」同码，靠 larkCode 区分）——回落 SendFailed 会让用户文案退回「请重试」",
            )
            assertEquals(234037, event.result.larkCode, "平台限判据：larkCode 非 null")
            val failed = assertNotNull(event.signal, "信号照发（双轨：日志 + 返回值）")
            assertEquals(GatewayErrorCode.DownloadTooLarge, failed.code, "信号与返回值须同码")
            assertTrue(failed.msg.contains("234037"), "信号 msg 须含业务码，实际=${failed.msg}")
            assertFalse(dest.toFile().isFile, "平台限（连字节都没给）自然不得落盘")
        }
    }

    @Test
    fun `5xx 映射 ConnectRetryable`() {
        withGateway(statusCode = 503, body = ByteArray(0)) { gw, dest ->
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertEquals(
                GatewayErrorCode.ConnectRetryable,
                assertNotNull(event.signal).code,
                "5xx 须映射 ConnectRetryable",
            )
        }
    }

    @Test
    fun `非 JSON 错误体不炸（业务码提取容错）`() {
        withGateway(statusCode = 502, body = "<html>bad gateway</html>".toByteArray()) { gw, dest ->
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertFalse(event.result.ok, "非 JSON 错误体也须优雅返回 false")
            val failed = assertNotNull(event.signal)
            assertEquals(
                GatewayErrorCode.ConnectRetryable, failed.code,
                "502 ⇒ ConnectRetryable（业务码提取失败不影响 HTTP 映射）",
            )
            assertTrue(failed.msg.contains("bad gateway"), "响应体片段仍须进 msg，实际=${failed.msg}")
        }
    }

    // ==================== ② 200：落盘正确性 ====================

    @Test
    fun `200 时按字节落盘、临时文件清理、目标就位`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }   // 非平凡内容，能抓截断/错位
        withGateway(statusCode = 200, body = payload) { gw, dest ->
            val result = runBlocking { gw.downloadResource("om_msg", "fk_1", dest) }
            assertTrue(result.ok, "200 应下载成功")
            assertTrue(dest.toFile().isFile, "目标文件必须就位")
            assertContentEquals(payload, Files.readAllBytes(dest), "落盘内容必须与响应体逐字节一致")

            // 临时 .part- 文件不得残留（已 ATOMIC_MOVE 到 dest）
            val leftovers = dest.parent.toFile().listFiles()?.filter { ".part-" in it.name }.orEmpty()
            assertTrue(leftovers.isEmpty(), "临时文件不得残留，实际=${leftovers.map { it.name }}")
        }
    }

    @Test
    fun `200 但超 downloadMaxBytes 时不落盘且映射 DownloadTooLarge`() {
        withGateway(statusCode = 200, body = ByteArray(2048), maxBytes = 1024) { gw, dest ->
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertFalse(event.result.ok, "超限必须拒绝")
            assertEquals(GatewayErrorCode.DownloadTooLarge, assertNotNull(event.signal).code)
            assertFalse(dest.toFile().isFile, "🔴 超限文件绝不写盘（下载后拒绝阈值语义）")
        }
    }

    // ==================== ③ 请求构造正确性（path/query 由 SDK 注解驱动）====================

    @Test
    fun `请求 URL 由 ReqTranslator 依 Path 注解正确拼接`() {
        val transport = FakeHttpTransport(200, ByteArray(8))
        withInjected(transport) { gw ->
            val dest = Files.createTempDirectory("gw-url").resolve("payload.bin")
            runBlocking { gw.downloadResource("om_msg_abc", "fk_xyz", dest) }
            val url = transport.capturedUrls.single()
            assertTrue(
                url.contains("/open-apis/im/v1/messages/om_msg_abc/resources/fk_xyz"),
                "@Path 参数须被替换，实际=$url",
            )
            assertTrue(url.contains("type=file"), "@Query 的 type 须出现在 URL 上，实际=$url")
            assertFalse(url.contains(":message_id"), "路径模板占位符不得残留，实际=$url")
        }
    }

    /**
     * 🔴 钉死 SDK 2.8.5 笔误的**规避**（这是最容易「顺手清理掉」的一行）：
     *    `ReqTranslator` 把 `RawRequest.supportDownLoad` 写成读 `requestOptions.isSupportUpload()`，
     *    而 SDK 的 `MessageResource` 只设了 download 没设 upload ⇒ 我们若不同时 `setSupportUpload(true)`，
     *    `RawRequest.supportDownLoad` 恒 false，`OkHttpTransport` 会走 `body().bytes()` 而非 `IOs.readAll`。
     *    两条路径结果等价（都不是流式），所以**删掉那行不会有任何可见症状**——正因如此才必须断言。
     */
    @Test
    fun `SDK 笔误规避：supportDownLoad 必须为 true（依赖 setSupportUpload）`() {
        val transport = FakeHttpTransport(200, ByteArray(4))
        withInjected(transport) { gw ->
            val dest = Files.createTempDirectory("gw-flag").resolve("payload.bin")
            runBlocking { gw.downloadResource("om_msg", "fk_1", dest) }
            assertTrue(
                transport.capturedSupportDownLoad.single(),
                "🔴 RawRequest.supportDownLoad 必须为 true——删掉 doDownload 里的 setSupportUpload(true) 即变红",
            )
        }
    }

    // ==================== ⑤ Range 分片（2026-09-13 分片批）====================

    @Test
    fun `多片 Range 分片按序拼接字节一致`() {
        val payload = ByteArray(2500) { (it % 251).toByte() }
        val transport = RangedFakeHttpTransport(payload)
        withInjected(transport, chunkBytes = 1024) { gw ->
            val dir = Files.createTempDirectory("gw-chunks")
            val dest = dir.resolve("payload.bin")
            val result = runBlocking { gw.downloadResource("om_msg", "fk_1", dest) }
            assertTrue(result.ok, "分片下载应成功，实际=$result")
            assertContentEquals(payload, Files.readAllBytes(dest), "分片拼接后必须与整文件逐字节一致")
            assertEquals(
                listOf("bytes=0-1023", "bytes=1024-2047", "bytes=2048-2499"),
                transport.capturedRanges,
                "Range 头序列必须按 offset 推进且末片 clamp 到 total-1（2500 字节 / 1024 片宽 = 3 片）",
            )
        }
    }

    @Test
    fun `单片文件一次请求即完成（首片即得 total 并 clamp）`() {
        val payload = ByteArray(100) { (it % 13).toByte() }
        val transport = RangedFakeHttpTransport(payload)
        withInjected(transport, chunkBytes = 1024) { gw ->
            val dest = Files.createTempDirectory("gw-single").resolve("payload.bin")
            val result = runBlocking { gw.downloadResource("om_msg", "fk_1", dest) }
            assertTrue(result.ok, "小文件单请求应成功")
            assertContentEquals(payload, Files.readAllBytes(dest))
            assertEquals(1, transport.capturedRanges.size, "服务器 clamp 后应单请求完成")
            assertEquals("bytes=0-1023", transport.capturedRanges.single(), "首片请求仍按 chunkBytes-1 开区间请求")
        }
    }

    // ==================== ⑥ 热更 downloadMaxBytes（2026-09-15 热更修复批）====================

    /**
     * 🔴 真机取证锚：`fileMessage.maxFileSizeBytes` 标 ApplyMode.HOT，但网关 settings 构造期固化、
     * applyConfig HOT 分支不下推——500MB 配置被 100MB 构造快照拦下 335MB 文件（日志
     * `total 335835212 > 104857600`）。修复后 [SdkLarkGateway.updateDownloadSettings] 原子替换、
     * download 消费点现读 ⇒ 保存后即刻生效、不再要求重启桥接。本用例钉「热更后新上限立即接管」。
     */
    @Test
    fun `热更 downloadMaxBytes 后新上限即刻生效（构造期旧值不再拦）`() {
        val payload = ByteArray(1500) { (it % 37).toByte() }
        val transport = RangedFakeHttpTransport(payload)
        withInjected(transport, maxBytes = 1024, chunkBytes = 1024) { gw ->
            // 构造期 1024：1500 字节文件必被拒（基线确认旧值在位）
            val dir = Files.createTempDirectory("gw-hotfix")
            val rejected = runBlocking { gw.downloadResource("om_msg", "fk_1", dir.resolve("a.bin")) }
            assertFalse(rejected.ok, "热更前 1024 上限必须拦下 1500 字节（基线）")
            assertEquals(GatewayErrorCode.DownloadTooLarge, rejected.error)

            // 热更到 2048：同一文件放行——AtomicReference 替换后消费点现读新值
            gw.updateDownloadSettings(2048)
            val accepted = runBlocking { gw.downloadResource("om_msg", "fk_1", dir.resolve("b.bin")) }
            assertTrue(accepted.ok, "🔴 热更后 2048 上限必须放行同一文件，实际=$accepted")
            assertContentEquals(payload, Files.readAllBytes(dir.resolve("b.bin")), "放行后内容逐字节一致")
        }
    }

    @Test
    fun `首片 Content-Range 预检超限拒绝且不落盘`() {
        // 🔴 参数设计（变异防线）：chunkBytes=512 ≪ maxBytes=1024 < total=2048——预检失效时首片仅写 512B
        //    （written=512 ≤ 1024 不触发累计兜底）⇒ 必然发出第 3 片请求（capturedRanges=3），
        //    「恰 1 次请求」断言即红；chunkBytes ≥ maxBytes 会让累计兜底遮蔽预检（变异实测假绿）。
        val payload = ByteArray(2048) { 7 }
        val transport = RangedFakeHttpTransport(payload)
        withInjected(transport, maxBytes = 1024, chunkBytes = 512) { gw ->
            val dir = Files.createTempDirectory("gw-precheck")
            val dest = dir.resolve("payload.bin")
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertFalse(event.result.ok, "预检超限必须拒绝")
            assertEquals(
                GatewayErrorCode.DownloadTooLarge, event.result.error,
                "预检拒绝映射 DownloadTooLarge（larkCode=null 我方限——resolver 据此渲染「超过本地大小上限」）",
            )
            assertEquals(null, event.result.larkCode, "我方限判据：larkCode 必须为 null")
            assertTrue(
                event.result.detail?.contains("total") == true,
                "detail 须是预检口径（total 2048 > 1024）而非累计兜底口径，实际=${event.result.detail}",
            )
            assertFalse(dest.toFile().isFile, "🔴 预检拒绝 = 零字节落盘（下载前预检语义）")
            assertEquals(1, transport.capturedRanges.size, "🔴 预检在首片完成——不得发后续请求（失效则 ≥3 片）")
            val leftovers = dir.toFile().listFiles()?.filter { ".part-" in it.name }.orEmpty()
            assertTrue(leftovers.isEmpty(), "预检拒绝不得留下 .part- 残渣，实际=${leftovers.map { it.name }}")
        }
    }

    @Test
    fun `Content-Range 畸形时失败清理不落盘`() {
        val payload = ByteArray(64) { 1 }
        val transport = RangedFakeHttpTransport(payload, malformedContentRange = true)
        withInjected(transport) { gw ->
            val dir = Files.createTempDirectory("gw-malformed")
            val dest = dir.resolve("payload.bin")
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertFalse(event.result.ok, "畸形 Content-Range 必须失败")
            assertEquals(GatewayErrorCode.DownloadFailed, event.result.error, "畸形响应按 DownloadFailed 处理（不猜）")
            assertFalse(dest.toFile().isFile, "失败不得落盘")
            val leftovers = dir.toFile().listFiles()?.filter { ".part-" in it.name }.orEmpty()
            assertTrue(leftovers.isEmpty(), "失败须清理 tmp（.part- 残渣为 0），实际=${leftovers.map { it.name }}")
        }
    }

    /**
     * 🔴 parseContentRange 纯函数直测（变异防线：doDownload 的一致性校验与解析校验互为冗余，
     *    只靠端到端用例会把解析变异遮蔽——必须打提取处本体）。
     */
    @Test
    fun `parseContentRange 解析与自洽性校验直测`() {
        // 正常形态
        assertEquals(ContentRange(0, 1023, 2500), parseContentRange("bytes 0-1023/2500"))
        assertEquals(ContentRange(1024, 2047, 2500), parseContentRange("  bytes 1024-2047/2500  "), "须容忍首尾空白")
        assertEquals(ContentRange(2048, 2499, 2500), parseContentRange("bytes 2048-2499/2500"))
        // 🔴 语义违法（自洽性校验防线——格式匹配但 start>end / end>=total 一律 null）
        assertEquals(null, parseContentRange("bytes 0-99/50"), "end ≥ total 必须 null（谎报区间）")
        assertEquals(null, parseContentRange("bytes 5-3/10"), "end < start 必须 null")
        assertEquals(null, parseContentRange("bytes -1-5/10"), "负 start 必须 null")
        // 格式垃圾
        assertEquals(null, parseContentRange("garbage-not-a-range"))
        assertEquals(null, parseContentRange("bytes 0-5"), "缺 total 必须 null")
        assertEquals(null, parseContentRange("BYTES 0-5/10"), "单位只接受小写 bytes（飞书实测形态）")
    }

    @Test
    fun `分片中途业务码失败透传 larkCode 且清理`() {
        val payload = ByteArray(4096) { 5 }
        // 第 2 片起返回 400 + 234046（Range 不满足）——234046 已登记为 DownloadFailed（分片协议类）
        val interrupt = RawResponse().apply {
            setStatusCode(400)
            setBody("""{"code":234046,"msg":"range not satisfiable"}""".toByteArray())
        }
        val transport = RangedFakeHttpTransport(payload, interruptFromChunk = 2, interruptResponse = interrupt)
        withInjected(transport, chunkBytes = 1024) { gw ->
            val dir = Files.createTempDirectory("gw-midfail")
            val dest = dir.resolve("payload.bin")
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertFalse(event.result.ok, "分片中途失败必须整单失败")
            assertEquals(GatewayErrorCode.DownloadFailed, event.result.error, "234046 ⇒ DownloadFailed（分片协议类）")
            assertEquals(234046, event.result.larkCode, "🔴 业务码必须透传进返回值（resolver 判据依赖）")
            val failed = assertNotNull(event.signal, "中途失败照发信号（可观测）")
            assertTrue(failed.msg.contains("234046"), "信号 msg 须含业务码，实际=${failed.msg}")
            assertFalse(dest.toFile().isFile, "失败不得落盘")
            val leftovers = dir.toFile().listFiles()?.filter { ".part-" in it.name }.orEmpty()
            assertTrue(leftovers.isEmpty(), "中途失败须清理已写的分片 tmp，实际=${leftovers.map { it.name }}")
        }
    }

    @Test
    fun `每片请求 headers map 是新建实例且含 Range 与 Authorization`() {
        val payload = ByteArray(2048) { 9 }
        val transport = RangedFakeHttpTransport(payload)
        withInjected(transport, chunkBytes = 1024) { gw ->
            val dest = Files.createTempDirectory("gw-hdrs").resolve("payload.bin")
            runBlocking { gw.downloadResource("om_msg", "fk_1", dest) }
            assertEquals(2, transport.capturedHeaderMaps.size, "2048/1024 = 2 片")
            val (m1, m2) = transport.capturedHeaderMaps
            assertNotEquals(m1, m2, "🔴 每片 headers map 必须是新建实例（ReqTranslator mutate map，复用即前片污染）")
            // 每片都带 Range（我们注入）+ Authorization（ReqTranslator put 进来——证明 map 可变且被 SDK 补齐）
            transport.capturedHeaderMaps.forEach { hdrs ->
                assertTrue(
                    hdrs.entries.any { it.key.equals("Range", ignoreCase = true) },
                    "每片请求必须带 Range 头",
                )
                assertTrue(
                    hdrs.entries.any { it.key.equals("Authorization", ignoreCase = true) },
                    "ReqTranslator 须往每片 map put Authorization（map 被 SDK mutate——不可变 map 会在此炸）",
                )
            }
        }
    }

    @Test
    fun `片数上限触发时失败清理（防病态服务器空转）`() {
        // 病态模式：每片只回 1 字节 ⇒ 不设上限会空转到 downloadMaxBytes；上限 4096 ⇒ 触发即拒
        val transport = RangedFakeHttpTransport(ByteArray(10_000), pathologicalBytes = 1)
        withInjected(transport, maxBytes = 1_000_000, chunkBytes = 1024) { gw ->
            val dir = Files.createTempDirectory("gw-chunkcap")
            val dest = dir.resolve("payload.bin")
            val event = runBlocking { downloadAndCapture(gw, dest) }
            assertFalse(event.result.ok, "片数超限必须失败")
            assertEquals(GatewayErrorCode.DownloadFailed, event.result.error, "片数超限映射 DownloadFailed")
            assertTrue(
                transport.capturedRanges.size <= 4_097,
                "片数上限触发后不得继续请求（实际请求数=${transport.capturedRanges.size}）",
            )
            val leftovers = dir.toFile().listFiles()?.filter { ".part-" in it.name }.orEmpty()
            assertTrue(leftovers.isEmpty(), "片数超限须清理 tmp")
        }
    }

    @Test
    fun `服务器忽略 Range 返回 200 整文件时降级落盘`() {
        // FakeHttpTransport 恒回 200（无视 Range 头）=「服务器忽略 Range」的降级形态；
        // 分片循环首片带 Range 请求、收到 200 ⇒ 走既有「下载后拒绝 + 一次落盘」路径。
        val payload = ByteArray(3000) { (it % 97).toByte() }
        withGateway(statusCode = 200, body = payload, maxBytes = 1_048_576) { gw, dest ->
            val result = runBlocking { gw.downloadResource("om_msg", "fk_1", dest) }
            assertTrue(result.ok, "200 降级应成功（现状等价路径）")
            assertContentEquals(payload, Files.readAllBytes(dest), "降级路径落盘必须逐字节一致")
        }
    }

    // ==================== ⑥ 未连接（无凭据）时优雅失败 ====================

    @Test
    fun `无凭据时返回 false（不抛）`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 不传 credentialOverride ⇒ credentialRef 恒空（没 connect 过）
            val gw = SdkLarkGateway(netDispatcher = Dispatchers.IO, scope = scope, wsInboundFactory = NoopWsInboundFactory)
            val dest = Files.createTempDirectory("gw-nocred").resolve("payload.bin")
            val result = runBlocking { gw.downloadResource("om_msg", "fk", dest) }
            assertFalse(result.ok, "无凭据须优雅返回 false")
            assertEquals(GatewayErrorCode.DownloadFailed, result.error, "无凭据须映射 DownloadFailed")
            gw.close()
        } finally {
            scope.cancel()
        }
    }

    // ==================== 辅助 ====================

    private class DownloadEvent(
        val result: DownloadResult,
        val signal: GatewaySignal.SendFailed?,
        val rateLimited: GatewaySignal.RateLimited?,
    )

    /**
     * 跑一次下载并捕获首个 `SendFailed` 与 `RateLimited` 信号。
     * 🔴 `doDownload` **在返回前**就 `offerSignal`（同步路径），故只需在调用前挂一个收集器，
     * 调用返回后再给收集器一点时间取到值即可。
     */
    private suspend fun downloadAndCapture(gw: SdkLarkGateway, dest: Path): DownloadEvent {
        val signal = Channel<GatewaySignal.SendFailed>(Channel.UNLIMITED)
        val rateLimited = Channel<GatewaySignal.RateLimited>(Channel.UNLIMITED)
        val collectorScope = CoroutineScope(Dispatchers.Default)
        try {
            collectorScope.launch {
                gw.signals.collect {
                    when (it) {
                        is GatewaySignal.SendFailed -> signal.trySend(it)
                        is GatewaySignal.RateLimited -> rateLimited.trySend(it)
                        else -> Unit
                    }
                }
            }
            val result = gw.downloadResource("om_msg", "fk_1", dest)
            // 信号投递与下载在同一协程内先后发生，但收集是异步的 ⇒ 给一个很短的让出窗口。
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(50L)
            return DownloadEvent(result, signal.tryReceive().getOrNull(), rateLimited.tryReceive().getOrNull())
        } finally {
            collectorScope.cancel()
        }
    }

    private fun withInjected(
        transport: IHttpTransport,
        maxBytes: Long = 1_073_741_824L,
        chunkBytes: Int = GatewaySdkSettings.DEFAULT.downloadChunkBytes,
        block: (SdkLarkGateway) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gw = SdkLarkGateway(
            netDispatcher = Dispatchers.IO,
            scope = scope,
            wsInboundFactory = NoopWsInboundFactory,
            settings = GatewaySdkSettings.DEFAULT.copy(downloadMaxBytes = maxBytes, downloadChunkBytes = chunkBytes),
            // 🔴 测试缝：假凭据 + 假 transport ⇒ 无需真连即可驱动下载路径
            credentialOverride = LarkCredential(appId = "cli_test", appSecret = "secret_test"),
            httpTransportOverride = transport,
        )
        try {
            block(gw)
        } finally {
            runCatching { gw.close() }
            scope.cancel()
        }
    }

    /** 起一座注入假 transport 的网关跑 [block]，收尾统一关闭。 */
    private fun withGateway(
        statusCode: Int,
        body: ByteArray,
        maxBytes: Long = 1_073_741_824L,
        block: (SdkLarkGateway, Path) -> Unit,
    ) = withInjected(FakeHttpTransport(statusCode, body), maxBytes) { gw ->
        // 🔴 必须用**不存在的**路径：`File.createTempFile` 会先建出空文件，那样「失败不得落盘」
        //    的断言会永远为假（文件本来就在）。用「临时目录 + 未创建的文件名」才是干净起点。
        val dir = Files.createTempDirectory("gw-dl")
        block(gw, dir.resolve("payload.bin"))
    }
}
