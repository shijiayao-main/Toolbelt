// T11 可观测性补强 · 施工项 1：contact.v3.user.get 失败诊断的纯函数单测。
// 🔴 零网络、零 SDK 响应对象构造：诊断文本由顶层 internal 纯函数（SdkLarkGateway.kt 尾部）格式化，
//    本测试直接钉死「真实 code/msg 原文出现在告警文本里」「四种原因排查清单齐备」「openId 被掩码」
//    「异常分支只取 class 简单名 + message」「每进程只 WARN 一次的 CAS 限流语义未被破坏」。
// 🔴 行为契约（resolveSenderName 失败仍返回 null、不抛异常、不影响主链路）由既有 ResolveSenderNameTest 3 tests 承担。
package com.jiaoay.lark.gateway

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactDiagnosticsTest {

    /** T11 第五轮真机出现过的 openId 形态（ou_ + 32 hex，共 35 字符）——掩码断言用同形态假值。 */
    private val openId = "ou_0123456789abcdef0123456789abcdef"

    // ==================== ① 失败响应分支：真实 code/msg 原文必须出现 ====================

    @Test
    fun `失败响应的 code 与 msg 原文出现在告警文本里（不做任何映射，映射会丢真值）`() {
        val text = contactFailureDiagnostics(openId, code = 99991672, msg = "permission denied: contact scope missing")
        assertTrue(text.contains("code=99991672"), "飞书返回的 code 原文必须在告警文本里：\n$text")
        assertTrue(
            text.contains("msg=\"permission denied: contact scope missing\""),
            "飞书返回的 msg 原文必须在告警文本里：\n$text",
        )
        // msg 为 null 时不得渲染成 "null" 之外的歧义形态（orEmpty ⇒ 空串引号）
        val nullMsg = contactFailureDiagnostics(openId, code = 10002, msg = null)
        assertTrue(nullMsg.contains("code=10002"), "code 原文必须在")
        assertTrue(nullMsg.contains("msg=\"\""), "msg 为 null 时渲染为空串（不得丢字段、不得 NPE）：\n$nullMsg")
    }

    // ==================== ② 四种可能原因的排查清单必须齐备 ====================

    @Test
    fun `四种可能原因的排查清单全部出现在失败诊断文本里`() {
        val text = contactFailureDiagnostics(openId, code = 99991672, msg = "denied")
        // ① scope 未添加
        assertTrue(text.contains("contact:contact.base:readonly 未添加"), "原因① scope 未添加（新名）必须在清单里：\n$text")
        assertFalse(text.contains("scope contact:user.base:readonly 未添加"), "旧 scope 名应已更名：\n$text")
        // ② 应用未发版 / 发版未生效（传播延迟）
        assertTrue(text.contains("应用未发版或发版未生效"), "原因② 未发版必须在清单里：\n$text")
        assertTrue(text.contains("传播延迟"), "原因② 的传播延迟提示必须在：\n$text")
        // ③ 🔴 通讯录授权范围未覆盖该用户（极常见坑：有 scope 仍返回权限错误）
        assertTrue(text.contains("通讯录授权范围"), "原因③ 通讯录授权范围必须在清单里：\n$text")
        assertTrue(text.contains("权限管理"), "原因③ 需给出后台入口路径（权限管理 → 通讯录授权范围）：\n$text")
        // ④ tenant_access_token 缓存早于发版（重启进程强制刷新）
        assertTrue(text.contains("tenant_access_token 缓存早于发版"), "原因④ token 缓存必须在清单里：\n$text")
        assertTrue(text.contains("重启进程可强制刷新"), "原因④ 的处置手段必须在：\n$text")
        // 限流语义的告知（与旧告警文案的收尾一致）
        assertTrue(text.contains("每进程只 WARN 一次"), "限流告知必须在：\n$text")
        assertTrue(text.contains("open_id 兜底"), "降级行为告知必须在：\n$text")
    }

    // ==================== ③ 隐私：openId 掩码（前 6 + **** + 后 4；短值全掩码）====================

    @Test
    fun `openId 被掩码——告警文本不含完整 openId`() {
        val masked = maskOpenId(openId)
        assertEquals("ou_012****cdef", masked, "掩码形态必须是前 6 + **** + 后 4")
        assertFalse(masked.contains(openId), "掩码值本身不得还原出完整 openId")

        val failure = contactFailureDiagnostics(openId, code = 1, msg = "x")
        assertFalse(failure.contains(openId), "🔴 失败诊断文本不得含完整 openId：\n$failure")
        assertTrue(failure.contains("ou_012****cdef"), "失败诊断文本必须含掩码后的 openId：\n$failure")

        val exception = contactExceptionDiagnostics(openId, IllegalStateException("boom"))
        assertFalse(exception.contains(openId), "🔴 异常诊断文本不得含完整 openId：\n$exception")
        assertTrue(exception.contains("ou_012****cdef"), "异常诊断文本必须含掩码后的 openId：\n$exception")
    }

    @Test
    fun `短 openId 全掩码——前 6 后 4 重叠会还原完整值，必须退化为星号`() {
        assertEquals("**********", maskOpenId("0123456789"), "长度 10：全掩码")
        assertEquals("*******", maskOpenId("ou_1234"), "长度 <= 10：全掩码（与桥接侧 maskSecret 短值策略同理）")
        assertEquals("", maskOpenId(""), "空串原样返回空")
        // 边界：长度 11 时前 6 + 后 4 恰好只重叠 1 位以内仍不得泄漏完整值
        val m11 = maskOpenId("0123456789a")
        assertFalse(m11.contains("0123456789a"), "长度 11 也不得还原完整值：$m11")
    }

    // ==================== ④ 异常分支：只取 class 简单名 + message ====================

    @Test
    fun `异常诊断只含 class 简单名与 message——不含堆栈、不含 toString`() {
        val t = FakeSdkException("token expired")
        val text = contactExceptionDiagnostics(openId, t)
        assertTrue(text.contains("FakeSdkException"), "class 简单名必须在：\n$text")
        assertTrue(text.contains("token expired"), "message 必须在：\n$text")
        assertFalse(text.contains("at com.jiaoay"), "🔴 不得含堆栈帧（会刷屏）：\n$text")
        assertFalse(text.contains("FAKE_TO_STRING_SECRET"), "🔴 不得走 toString（可能含 token 等敏感上下文）：\n$text")
        // message 为 null 时不得 NPE、不得渲染歧义
        val noMsg = contactExceptionDiagnostics(openId, FakeSdkException(null))
        assertTrue(noMsg.contains("FakeSdkException"), "message 为 null 仍需 class 简单名：\n$noMsg")
    }

    /** toString 里埋一个哨兵串：若诊断文本走了 toString（而非 simpleName+message），哨兵会泄漏 ⇒ 断言失败。 */
    private class FakeSdkException(message: String?) : RuntimeException(message) {
        override fun toString(): String = "FAKE_TO_STRING_SECRET"
    }

    // ==================== ⑤ 限流语义：每进程只 WARN 一次（CAS，未被诊断增强破坏）====================

    @Test
    fun `shouldWarnContactScope 保持 compareAndSet 限流语义——首次 true 后续恒 false`() {
        val warned = AtomicBoolean(false)
        assertTrue(shouldWarnContactScope(warned), "首次失败必须放行 WARN")
        assertFalse(shouldWarnContactScope(warned), "第二次起必须被限流（每进程只 WARN 一次）")
        assertFalse(shouldWarnContactScope(warned), "后续恒 false——T11 真机实证 9 条消息只告警 1 次的语义不得破坏")
        // 独立实例互不影响（每进程一个 contactScopeWarned 字段）
        assertTrue(shouldWarnContactScope(AtomicBoolean(false)), "新进程（新实例）重新放行一次")
    }

    // ==================== ⑥ 源码钉死：resolveSenderName 两个失败分支都走诊断版告警 ====================

    @Test
    fun `源码断言——失败响应与异常两个分支都把真实诊断喂给限流告警，且无参旧形态已消失`() {
        val source = java.io.File(
            testRepoRoot(),
            "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(
            source.contains("warnContactScopeOnce(contactFailureDiagnostics(openId, resp.code, resp.msg))"),
            "🔴 resp.success()==false 分支必须携带飞书真实 code/msg",
        )
        assertTrue(
            source.contains("warnContactScopeOnce(contactExceptionDiagnostics(openId, it))"),
            "🔴 抛异常分支必须携带 class 简单名 + message",
        )
        assertFalse(
            source.contains("warnContactScopeOnce()"),
            "无参旧形态（猜测性告警）必须已消失",
        )
        // 限流判据的**定义**在 ContactDiagnostics.kt（与诊断纯函数同文件）；调用点仍在上面的网关源里。
        val diagnosticsSource = java.io.File(
            testRepoRoot(),
            "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/ContactDiagnostics.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(
            diagnosticsSource.contains("internal fun shouldWarnContactScope(warned: AtomicBoolean): Boolean = warned.compareAndSet(false, true)"),
            "限流判据必须是逐字等价的 CAS",
        )
    }
}
