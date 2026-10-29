package com.jiaoay.easy_dev_toolbox.page.tools.jwt

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JWT 解码引擎验收：三段 base64url 解码 + JSON 明文展开 + exp/iat 人类时间
 * 判读用公开基准向量（jwt.io 首页示例令牌）断言；坏 JWT 逐段定位失败面显式
 * 断言；时区与判定基准注入定点（引擎不读系统时钟）。
 */
class JwtDecoderEngineTest {

    private val zone = ZoneId.of("UTC")

    /** jwt.io 公开示例令牌（HS256 / {sub, name, iat}——公开教学向量非真凭证） */
    private val sampleJwt =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    private fun base64Url(text: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun buildToken(headerJson: String, payloadJson: String, signature: String = "sig"): String =
        "${base64Url(headerJson)}.${base64Url(payloadJson)}.$signature"

    @Test
    fun `decodes sample jwt into header and payload plaintext`() {
        val result = decodeJwt(sampleJwt, nowEpochSeconds = 1_700_000_000L, zone = zone)
        assertTrue(result is JwtDecodeResult.Ok, "公开示例令牌必须解码成功：$result")
        val ok = result as JwtDecodeResult.Ok
        assertTrue("HS256" in ok.headerJson, "header 明文含 alg（格式化 JSON）")
        assertTrue("1234567890" in ok.payloadJson, "payload 明文含 sub")
        assertEquals("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c", ok.signatureSegment, "签名段原样携带（未验证）")
    }

    @Test
    fun `expands iat into human time in injected zone`() {
        val result = decodeJwt(sampleJwt, nowEpochSeconds = 1_700_000_000L, zone = zone)
        assertTrue(result is JwtDecodeResult.Ok)
        val iat = (result as JwtDecodeResult.Ok).timeClaims.single()
        assertEquals("iat", iat.name)
        assertEquals(1_516_239_022L, iat.rawSeconds, "iat 原秒值")
        assertTrue("2018-01-18 01:30:22" in iat.humanTime, "iat 人类时间（UTC 定点）：${iat.humanTime}")
        assertNull(iat.verdict, "iat 无判定（非 exp/nbf）")
    }

    @Test
    fun `exp verdict distinguishes expired from valid with injected now`() {
        val token = buildToken("""{"alg":"none"}""", """{"exp":1000}""")
        val expired = decodeJwt(token, nowEpochSeconds = 2_000L, zone = zone)
        assertTrue(expired is JwtDecodeResult.Ok)
        val expClaim = (expired as JwtDecodeResult.Ok).timeClaims.single()
        assertTrue(expClaim.verdict?.contains("已过期") == true, "exp 1000 在 now=2000 时已过期：${expClaim.verdict}")

        val valid = decodeJwt(token, nowEpochSeconds = 500L, zone = zone)
        assertTrue(valid is JwtDecodeResult.Ok)
        val validClaim = (valid as JwtDecodeResult.Ok).timeClaims.single()
        // 剩余 500 秒按向下取整粒度展开为 8 分钟（人话时长档：秒/分/时/天）
        assertTrue(validClaim.verdict?.contains("未过期") == true && validClaim.verdict?.contains("8 分钟") == true, "剩余时长人话展开：${validClaim.verdict}")
    }

    @Test
    fun `nbf verdict distinguishes pending from active`() {
        val token = buildToken("""{"alg":"none"}""", """{"nbf":5000}""")
        val pending = decodeJwt(token, nowEpochSeconds = 1_000L, zone = zone)
        assertTrue(pending is JwtDecodeResult.Ok && (pending as JwtDecodeResult.Ok).timeClaims.single().verdict?.contains("尚未生效") == true)
        val active = decodeJwt(token, nowEpochSeconds = 6_000L, zone = zone)
        assertTrue(active is JwtDecodeResult.Ok && (active as JwtDecodeResult.Ok).timeClaims.single().verdict == "已生效")
    }

    @Test
    fun `non numeric time claim gets explicit receipt instead of silent skip`() {
        val token = buildToken("""{"alg":"none"}""", """{"exp":"soon"}""")
        val result = decodeJwt(token, nowEpochSeconds = 0L, zone = zone)
        assertTrue(result is JwtDecodeResult.Ok)
        val claim = (result as JwtDecodeResult.Ok).timeClaims.single()
        assertNull(claim.rawSeconds)
        assertTrue(claim.humanTime.contains("值非整数秒") && claim.humanTime.contains("soon"), "非数值 exp 显式回执：${claim.humanTime}")
    }

    @Test
    fun `out of range epoch seconds fold to receipt instead of throwing`() {
        // 18 位整数十进制合法、Long 值域内（toLongOrNull 收下）但越 Instant 秒界——
        // 面板 remember 组合期直调本引擎，抛 DateTimeException 即 App 崩溃，必须折叠回执
        val positive = buildToken("""{"alg":"none"}""", """{"exp":999999999999999999}""")
        val posResult = decodeJwt(positive, nowEpochSeconds = 0L, zone = zone)
        assertTrue(posResult is JwtDecodeResult.Ok)
        val posClaim = (posResult as JwtDecodeResult.Ok).timeClaims.single()
        assertEquals(999_999_999_999_999_999L, posClaim.rawSeconds, "越界原值保留不吞")
        assertTrue(posClaim.humanTime.contains("超出可表达范围"), "越界正值折叠为人话回执：${posClaim.humanTime}")
        assertNull(posClaim.verdict, "越界值无判定")

        val negative = buildToken("""{"alg":"none"}""", """{"exp":-999999999999999999}""")
        val negResult = decodeJwt(negative, nowEpochSeconds = 0L, zone = zone)
        assertTrue(negResult is JwtDecodeResult.Ok)
        val negClaim = (negResult as JwtDecodeResult.Ok).timeClaims.single()
        assertTrue(negClaim.humanTime.contains("超出可表达范围"), "越界负值折叠为人话回执：${negClaim.humanTime}")
        assertNull(negClaim.verdict, "越界负值无判定")
    }

    // ── 坏 JWT 分段定位 ──

    @Test
    fun `wrong segment count is rejected with explicit reason`() {
        val twoSegments = decodeJwt("aaa.bbb", zone = zone)
        assertTrue(twoSegments is JwtDecodeResult.Invalid && twoSegments.reason.contains("三段") && twoSegments.reason.contains("2 段"), "两段令牌回执说清段数：$twoSegments")

        val jwe = decodeJwt("a.b.c.d.e", zone = zone)
        assertTrue(jwe is JwtDecodeResult.Invalid && jwe.reason.contains("JWE"), "五段加密令牌显式拒绝并说明：$jwe")

        val blank = decodeJwt("   ", zone = zone)
        assertEquals(JwtDecodeResult.Empty, blank, "空白输入回引导态")
    }

    @Test
    fun `bad base64url segment is localized to its segment`() {
        // 三个感叹号不是任何 base64 字母表字符；第 1 段坏 → 回执定位 header
        val badHeader = decodeJwt("!!!.eyJhbGciOiJub25lIn0.sig", zone = zone)
        assertTrue(badHeader is JwtDecodeResult.Invalid && badHeader.reason.contains("第 1 段") && badHeader.reason.contains("header"), "坏 header 段定位回执：$badHeader")

        val badPayload = decodeJwt("eyJhbGciOiJub25lIn0.!!!.sig", zone = zone)
        assertTrue(badPayload is JwtDecodeResult.Invalid && badPayload.reason.contains("第 2 段") && badPayload.reason.contains("payload"), "坏 payload 段定位回执：$badPayload")
    }

    @Test
    fun `non json segment is rejected as invalid json`() {
        // 裸字面量 kotlinx 会按宽松字面量收下——用真解析不过的残缺 JSON 才走「不是合法 JSON」分支
        val brokenJsonHeader = base64Url("""{"alg": "none""")
        val result = decodeJwt("$brokenJsonHeader.eyJhbGciOiJub25lIn0.sig", zone = zone)
        assertTrue(result is JwtDecodeResult.Invalid && result.reason.contains("第 1 段") && result.reason.contains("不是合法 JSON"), "解 base64url 成功但内容非 JSON 显式失败：$result")
    }

    @Test
    fun `non object payload is rejected explicitly`() {
        val arrayPayload = base64Url("[1,2,3]")
        val result = decodeJwt("eyJhbGciOiJub25lIn0.$arrayPayload.sig", zone = zone)
        assertTrue(result is JwtDecodeResult.Invalid && result.reason.contains("payload") && result.reason.contains("对象"), "payload JSON 数组形态显式拒绝：$result")
    }

    @Test
    fun `oversized input is refused before decoding`() {
        val huge = "a".repeat(MAX_JWT_INPUT_CHARS + 1)
        val result = decodeJwt(huge, zone = zone)
        assertTrue(result is JwtDecodeResult.Invalid && result.reason.contains("超过上限"), "超长输入显式拒收：$result")
    }

    @Test
    fun `payload without time claims yields empty claim list`() {
        val token = buildToken("""{"alg":"none"}""", """{"sub":"x"}""")
        val result = decodeJwt(token, nowEpochSeconds = 0L, zone = zone)
        assertTrue(result is JwtDecodeResult.Ok && result.timeClaims.isEmpty(), "无时间声明回空列表（零声明非错误）")
    }
}
