// :lark-gateway 测试（M3-T3，§11.4 = BC NF-A3 的 LarkGatewayErrorMappingTest）。类名按裁定 5（B-16）用 LarkErrorMapperTest。
// 🔴 纯函数测试：零 SDK 加载（§6.3.1 fromThrowableName 收 String 不收 Throwable 的直接收益）。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §11.4：飞书业务码 / HTTP 状态 / SDK 异常码 → GatewayErrorCode 的映射 + isRetryable 穷尽二分。
 *
 * 🔴 §11.4 ⑨ 分工声明（否则 NF-A3 ① 会被误判为未覆盖）：「卡片更新失败必须可观测」三件套中，
 *    **计数与日志**部分不在本纯函数 mapper 内，落在 `LarkCardStreamSinkTest`（§4.9 的 `check()`：
 *    ① failureCounters[code] > 0；② 日志含 cardId + sequence + 业务码）；**本 mapper 只提供错误映射**（三件套的第 ③ 件）。
 * 🔴 §11.4 ⑧ 计数说明（§15.2 M-1）：规格文字写「26 值 / 7true / 19false」是文档误 count；
 *    GatewayErrorCode 实际 24 值（§2.5 枚举字面），isRetryable 穷尽 when 恰 7 true + 17 false = 24。断言按实测写。
 */
class LarkErrorMapperTest {

    // ① 6 个卡片业务码各有专属 GatewayErrorCode
    @Test
    fun `six card business codes map to dedicated error codes`() {
        assertEquals(GatewayErrorCode.CardSequenceConflict, LarkErrorMapper.fromLarkCode(300317, null))
        assertEquals(GatewayErrorCode.CardTooLarge, LarkErrorMapper.fromLarkCode(200860, null))
        assertEquals(GatewayErrorCode.CardStreamBusy, LarkErrorMapper.fromLarkCode(200810, null))
        assertEquals(GatewayErrorCode.CardEntityExpired, LarkErrorMapper.fromLarkCode(200750, null))
        assertEquals(GatewayErrorCode.CardJsonInvalid, LarkErrorMapper.fromLarkCode(300302, null))
        assertEquals(GatewayErrorCode.CardJsonInvalid, LarkErrorMapper.fromLarkCode(230099, null))
        // 六者互不相同（专属，非 N:1）
        val dedicated = setOf(
            LarkErrorMapper.fromLarkCode(300317, null),
            LarkErrorMapper.fromLarkCode(200860, null),
            LarkErrorMapper.fromLarkCode(200810, null),
            LarkErrorMapper.fromLarkCode(200750, null),
            LarkErrorMapper.fromLarkCode(300302, null),
        )
        assertEquals(5, dedicated.size) // 300302 与 230099 同归 CardJsonInvalid，故 5 个专属码
    }

    // ①补充 🔴 S6 真机四码（§16 / build/sdk-probe/probe-*.log）：均为「请求结构被飞书拒绝、重试必然再错」⇒ CardJsonInvalid（GiveUp、可观测）
    @Test
    fun `s6 real device structural codes map to CardJsonInvalid and are not retryable`() {
        // 断言常量：四个真机实测错误码（现象 → 飞书 msg 原文见 §16）
        val invalidMsgType = 230001        // probe-card.log A：msg="invalid msg_type: card"（msgType="card" 被拒）
        val tagCannotUpdate = 300312       // probe-patch.log P1/P4：msg="ErrMsg: tag cannot be updated"（partial 含 tag）
        val printStepNil = 11311           // streaming_config：msg="printStep is nil"（step:5 标量）
        val unmarshalPrintStep = 10002     // streaming_config：msg="failed to unmarshal PrintStepConfig, type float64"（print_step:5 标量）
        for (code in listOf(invalidMsgType, tagCannotUpdate, printStepNil, unmarshalPrintStep)) {
            // 映射到正确的 GatewayErrorCode（不新增枚举值、不触及 BridgeErrorCode 16 值）
            assertEquals(GatewayErrorCode.CardJsonInvalid, LarkErrorMapper.fromLarkCode(code, 200), "真机码 $code 应归 CardJsonInvalid")
            // 🔴 D18：不被静默吞掉——CardJsonInvalid 不可重试（GiveUp），但 sink.check() 会计数 + 日志 + 发 SendFailed 信号（可观测三件套）
            assertFalse(LarkErrorMapper.isRetryable(GatewayErrorCode.CardJsonInvalid), "CardJsonInvalid 不可重试（重试必然再错）⇒ GiveUp，可观测")
        }
        // HTTP 429 仍优先于业务码（限流语义不被结构码吞掉）
        assertEquals(GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(tagCannotUpdate, 429))
    }

    // ② 11232 与 HTTP 429 都 → RateLimited
    @Test
    fun `rate limit sources all map to RateLimited`() {
        assertEquals(GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(11232, 200))
        assertEquals(GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(null, 429))
        assertEquals(GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(11232, 429))
    }

    // ③ 优先级规则：HTTP 状态先于业务码（限流语义优先，否则 RateGovernor 不退避）
    @Test
    fun `http status takes precedence over business code`() {
        // 200860（CardTooLarge）叠加 HTTP 429 ⇒ RateLimited，不是 CardTooLarge
        assertEquals(GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(200860, 429))
        // 300317 叠加 HTTP 404 ⇒ ConnectFatal
        assertEquals(GatewayErrorCode.ConnectFatal, LarkErrorMapper.fromLarkCode(300317, 404))
        // 5xx ⇒ ConnectRetryable 优先于业务码
        assertEquals(GatewayErrorCode.ConnectRetryable, LarkErrorMapper.fromLarkCode(200810, 503))
        // 无 HTTP 状态（null）时才走业务码
        assertEquals(GatewayErrorCode.CardTooLarge, LarkErrorMapper.fromLarkCode(200860, null))
    }

    // ③补充 🔴 平台 100MB 下载大小限（2026-09-12 真机：108.1MiB zip ⇒ http=400 + 234037）。
    //    映射 DownloadTooLarge（与「我方超限」同码，DownloadResult.larkCode 区分）——回落 SendFailed 会让
    //    resolver 用户文案退回误导性的「请重试」。
    @Test
    fun `platform download size limit maps to DownloadTooLarge`() {
        assertEquals(
            GatewayErrorCode.DownloadTooLarge, LarkErrorMapper.fromLarkCode(234037, 400),
            "真机形态：http=400 + code=234037 ⇒ DownloadTooLarge",
        )
        assertEquals(
            GatewayErrorCode.DownloadTooLarge, LarkErrorMapper.fromLarkCode(234037, null),
            "无 HTTP 状态时业务码独立成立",
        )
        // 🔴 负向：HTTP 429 优先级不被新登记破坏（限流语义优先于业务码——RateGovernor 退避依赖）
        assertEquals(
            GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(234037, 429),
            "234037 叠加 429 ⇒ RateLimited（HTTP 优先）",
        )
    }

    // ④ 99991672 → ConnectFatal 且 retryable == false
    @Test
    fun `invalid credential code is connect fatal and not retryable`() {
        val e = LarkErrorMapper.fromLarkCode(99991672, null)
        assertEquals(GatewayErrorCode.ConnectFatal, e)
        assertFalse(LarkErrorMapper.isRetryable(e))
    }

    // 🔴 Range 分片批（2026-09-13）新登记业务码：飞书官方 message-resource 文档五码。
    //    234038（保密消息）/234040（消息不可见）⇒ 权限类；234043（不支持的消息类型）⇒ SendFailed；
    //    234045（Range 格式无效）/234046（Range 不满足）⇒ DownloadFailed（分片协议类，可重试）。
    @Test
    fun `range chunk business codes map to permission sendfailed and downloadfailed`() {
        assertEquals(
            GatewayErrorCode.PermissionDenied, LarkErrorMapper.fromLarkCode(234038, 400),
            "234038 保密消息禁止下载 ⇒ PermissionDenied（不可重试）",
        )
        assertEquals(
            GatewayErrorCode.PermissionDenied, LarkErrorMapper.fromLarkCode(234040, 400),
            "234040 消息不可见 ⇒ PermissionDenied",
        )
        assertEquals(
            GatewayErrorCode.SendFailed, LarkErrorMapper.fromLarkCode(234043, 400),
            "234043 不支持的消息类型 ⇒ SendFailed（引用链异常形态，重试无意义但可观测）",
        )
        assertEquals(
            GatewayErrorCode.DownloadFailed, LarkErrorMapper.fromLarkCode(234045, 400),
            "234045 Range 头格式无效 ⇒ DownloadFailed（分片协议类）",
        )
        assertEquals(
            GatewayErrorCode.DownloadFailed, LarkErrorMapper.fromLarkCode(234046, 400),
            "234046 Range 不满足 ⇒ DownloadFailed（分片协议类）",
        )
        // isRetryable 分区断言（24 值守恒 ⇒ isRetryable 零改动——PermissionDenied=false / DownloadFailed=true 既有分区）
        assertFalse(LarkErrorMapper.isRetryable(GatewayErrorCode.PermissionDenied), "权限类不可重试")
        assertTrue(LarkErrorMapper.isRetryable(GatewayErrorCode.DownloadFailed), "分片协议类可重试（幂等路径）")
        // 🔴 负向：HTTP 429 优先级不被新登记破坏
        assertEquals(
            GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkCode(234046, 429),
            "234046 叠加 429 ⇒ RateLimited（HTTP 优先——与 234037 用例同款防线）",
        )
    }

    // ⑤ HTTP 404 + 非 JSON ⇒ ConnectFatal
    @Test
    fun `http 404 is connect fatal`() {
        assertEquals(GatewayErrorCode.ConnectFatal, LarkErrorMapper.fromLarkCode(null, 404))
        assertFalse(LarkErrorMapper.isRetryable(GatewayErrorCode.ConnectFatal))
    }

    // ⑥ 缺 URL 字段 → ConnectRetryable（§15.2 M-10）
    @Test
    fun `missing url field maps to connect retryable`() {
        val e = LarkErrorMapper.fromThrowableName("java.lang.IllegalArgumentException", "url is blank")
        assertEquals(GatewayErrorCode.ConnectRetryable, e)
        assertTrue(LarkErrorMapper.isRetryable(e))
    }

    // ⑥补充：fromThrowableName 的其它分支 + null 容忍（绝不抛）
    @Test
    fun `fromThrowableName covers register timeout io and null branches`() {
        assertEquals(GatewayErrorCode.RegisterQrExpired, LarkErrorMapper.fromThrowableName("com.x.ExpiredException", null))
        assertEquals(GatewayErrorCode.RegisterInvalidArgument,
            LarkErrorMapper.fromThrowableName("com.x.RegisterAppException", "ERROR_INVALID_ARGUMENT: bad"))
        assertEquals(GatewayErrorCode.RegisterFailed,
            LarkErrorMapper.fromThrowableName("com.x.RegisterAppException", "some other failure"))
        assertEquals(GatewayErrorCode.SendTimeout, LarkErrorMapper.fromThrowableName("java.net.SocketTimeoutException", null))
        assertEquals(GatewayErrorCode.SendTimeout, LarkErrorMapper.fromThrowableName("java.util.concurrent.TimeoutException", null))
        assertEquals(GatewayErrorCode.ConnectRetryable, LarkErrorMapper.fromThrowableName("java.net.ConnectException", null))
        assertEquals(GatewayErrorCode.ConnectRetryable, LarkErrorMapper.fromThrowableName("java.net.UnknownHostException", null))
        assertEquals(GatewayErrorCode.ConnectRetryable, LarkErrorMapper.fromThrowableName("java.io.IOException", null))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromThrowableName("com.x.LarkChannelException", null))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromThrowableName(null, null)) // null fqcn 不抛
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromThrowableName("com.x.WeirdException", null))
    }

    // ⑦ LarkChannelErrorCode 9 值全覆盖 + 未知字符串 → Unknown 不抛
    @Test
    fun `fromLarkChannelCode covers nine sdk values and tolerates unknown`() {
        assertEquals(GatewayErrorCode.FormatError, LarkErrorMapper.fromLarkChannelCode("FORMAT_ERROR"))
        assertEquals(GatewayErrorCode.TargetRevoked, LarkErrorMapper.fromLarkChannelCode("TARGET_REVOKED"))
        assertEquals(GatewayErrorCode.RateLimited, LarkErrorMapper.fromLarkChannelCode("RATE_LIMITED"))
        assertEquals(GatewayErrorCode.PermissionDenied, LarkErrorMapper.fromLarkChannelCode("PERMISSION_DENIED"))
        assertEquals(GatewayErrorCode.UploadFailed, LarkErrorMapper.fromLarkChannelCode("UPLOAD_FAILED"))
        assertEquals(GatewayErrorCode.SsrfBlocked, LarkErrorMapper.fromLarkChannelCode("SSRF_BLOCKED"))
        assertEquals(GatewayErrorCode.SendTimeout, LarkErrorMapper.fromLarkChannelCode("SEND_TIMEOUT"))
        assertEquals(GatewayErrorCode.NotConnected, LarkErrorMapper.fromLarkChannelCode("NOT_CONNECTED"))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromLarkChannelCode("UNKNOWN"))
        // 🔴 未知字符串（SDK 升级新增枚举值）→ Unknown，绝不抛
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromLarkChannelCode("SOME_NEW_CODE"))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromLarkChannelCode(null))
    }

    // ⑧ isRetryable 的完整二分：entries.forEach 逐个断言（新增枚举值时测试自动红）
    @Test
    fun `isRetryable partitions every error code exhaustively`() {
        val retryable = setOf(
            GatewayErrorCode.RateLimited,
            GatewayErrorCode.CardSequenceConflict,
            GatewayErrorCode.CardTooLarge,
            GatewayErrorCode.CardEntityExpired,
            GatewayErrorCode.ConnectRetryable,
            GatewayErrorCode.DownloadFailed,
            GatewayErrorCode.DownloadTooLarge,
        )
        // 🔴 遍历 entries：任何新增枚举值若未在此登记，下面断言立刻红（穷尽性守护）
        GatewayErrorCode.entries.forEach { code ->
            val expected = code in retryable
            assertEquals(expected, LarkErrorMapper.isRetryable(code), "isRetryable($code) 应为 $expected")
        }
        // 计数：24 值 = 7 true + 17 false（§15.2 M-1，规格文字的 26/19 是误 count）
        assertEquals(24, GatewayErrorCode.entries.size)
        assertEquals(7, GatewayErrorCode.entries.count { LarkErrorMapper.isRetryable(it) })
        assertEquals(17, GatewayErrorCode.entries.count { !LarkErrorMapper.isRetryable(it) })
    }

    // ⑨ 未登记业务码 → SendFailed（出站兜底，容忍未知值，绝不抛）；成功码 0/null → Unknown
    @Test
    fun `unregistered business codes fall back to SendFailed without throwing`() {
        assertEquals(GatewayErrorCode.SendFailed, LarkErrorMapper.fromLarkCode(123456, null))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromLarkCode(0, null))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromLarkCode(null, null))
        assertEquals(GatewayErrorCode.Unknown, LarkErrorMapper.fromLarkCode(null, 200))
    }

    // ⑩ mapper 无 throw：任意（含极端/负数/边界）输入都返回值而非抛异常（NF-A3 ④：出站失败返回值不抛）
    @Test
    fun `mappers never throw on any input`() {
        // fromLarkCode：负数、极大值、边界 HTTP 状态
        listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, 1, 999999999).forEach { code ->
            LarkErrorMapper.fromLarkCode(code, null) // 不抛即通过
            LarkErrorMapper.fromLarkCode(code, 500)
        }
        listOf(200, 201, 301, 400, 403, 404, 429, 500, 502, 503, 599, 600, 999).forEach { http ->
            LarkErrorMapper.fromLarkCode(300317, http) // 不抛即通过
        }
        // fromThrowableName：任意类名/消息组合不抛
        listOf("a", "", "java.lang.RuntimeException", "Error", "Throwable").forEach { n ->
            LarkErrorMapper.fromThrowableName(n, null)
            LarkErrorMapper.fromThrowableName(n, "some message")
        }
        // fromLarkChannelCode：任意字符串不抛
        listOf("", "a", "format_error", "FORMAT_ERROR ").forEach { c -> LarkErrorMapper.fromLarkChannelCode(c) }
        // SendResult.ok == false 的构造由 SdkLarkGateway.send() 承担（BC-459）；mapper 只产出错误码，绝不抛
        val failed = SendResult(ok = false, messageId = null, error = GatewayErrorCode.SendFailed, larkCode = 123456)
        assertFalse(failed.ok)
        assertEquals(GatewayErrorCode.SendFailed, failed.error)
    }
}
