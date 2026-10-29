// :lark-gateway 测试（M3-T3，§11.1）。类名按裁定 5（B-16）用 Lark*MapperTest；= BC NF-A6 的 LarkPolicyMapperTest。
// 🔴 纯函数测试：零 SDK 加载（§6.0 纪律 1 的收益），runTest 都不需要（无挂起）。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §11.1：LarkPolicy → GatewaySdkSettings 的逐字段映射 + 必须覆写的 SDK 默认值。
 *
 * 🔴 与规格的差异（施工实测，记入报告）：§11.1 用 `policy.groupRequireMention` 是**配置域**字段名；
 * 网关契约 [LarkPolicy] 的对应字段是 `requireMention`。§11.1 ② 原写「其余 4 字段恒为放行默认值、不随
 * LarkPolicy 变化」——实测 [LarkPolicyMapper.toSettings] **忠实透传**全部 5 字段；「恒放行」是**引擎**
 * （M4-T7 恒传 emptyList/"open"/emptyList/true）+ [LarkPolicy] 默认值的职责，不是 mapper 的职责。
 * 故本测试：① 断言 mapper 忠实透传 5 字段；② 断言 [LarkPolicy] 默认值即放行态；③ 断言 settings 侧
 * 硬编码覆写值（chatQueueEnabled/retry/throttle/chunk）对任意输入恒定。
 */
class LarkPolicyMapperTest {

    @Test
    fun `toSettings maps requireMention faithfully`() {
        // §11.1 ①：access.groupRequireMention → settings.requireMention（网关侧字段名 requireMention）
        assertTrue(LarkPolicyMapper.toSettings(LarkPolicy(requireMention = true)).requireMention)
        assertFalse(LarkPolicyMapper.toSettings(LarkPolicy(requireMention = false)).requireMention)
    }

    @Test
    fun `toSettings propagates all five policy fields`() {
        // §11.1 ②（修正版）：mapper 忠实透传 5 字段（全量覆盖语义的前提，决策 7）
        val policy = LarkPolicy(
            groupAllowlist = listOf("oc_a", "oc_b"),
            dmMode = "allowlist",
            dmAllowlist = listOf("ou_x"),
            requireMention = true,
            respondToMentionAll = false,
        )
        val s = LarkPolicyMapper.toSettings(policy)
        assertEquals(listOf("oc_a", "oc_b"), s.groupAllowlist)
        assertEquals("allowlist", s.dmMode)
        assertEquals(listOf("ou_x"), s.dmAllowlist)
        assertTrue(s.requireMention)
        assertFalse(s.respondToMentionAll)
    }

    @Test
    fun `default LarkPolicy is the all-pass shape the engine always sends`() {
        // §11.1 ②：SDK 侧全放行——由 LarkPolicy 默认值承载（引擎恒传默认值，决策 3）
        val d = LarkPolicy()
        assertEquals(emptyList(), d.groupAllowlist)
        assertEquals("open", d.dmMode)
        assertEquals(emptyList(), d.dmAllowlist)
        assertFalse(d.requireMention)
        assertTrue(d.respondToMentionAll)
        val s = LarkPolicyMapper.toSettings(d)
        assertEquals(emptyList(), s.groupAllowlist)
        assertEquals("open", s.dmMode)
        assertEquals(emptyList(), s.dmAllowlist)
        assertTrue(s.respondToMentionAll)
    }

    @Test
    fun `chat queue is disabled and cannot be turned on by any policy`() {
        // §11.1 ③ / 决策 4 / NF-A2 ②：真实 SDK 属性名 isChatQueueEnabled；恒 false，任何输入都不改变
        assertFalse(GatewaySdkSettings.DEFAULT.chatQueueEnabled)
        assertFalse(LarkPolicyMapper.toSettings(LarkPolicy()).chatQueueEnabled)
        assertFalse(LarkPolicyMapper.toSettings(LarkPolicy(requireMention = true, dmMode = "allowlist")).chatQueueEnabled)
    }

    @Test
    fun `retry max attempts is one to kill blind exponential backoff`() {
        // §11.1 ④ / 决策 6：关掉 SDK 盲指数退避（它用 Thread.sleep 占住 commonPool）
        assertEquals(1, GatewaySdkSettings.DEFAULT.retryMaxAttempts)
        assertEquals(1, LarkPolicyMapper.toSettings(LarkPolicy()).retryMaxAttempts)
    }

    @Test
    fun `throttle and chunk limits are the tuned values`() {
        // §11.1 ⑤ / 决策 19、18 / R7②：streamThrottleMs=250（SDK 默认 100 零余量）、chars=4000（或关系）、chunk=3500
        val s = LarkPolicyMapper.toSettings(LarkPolicy())
        assertEquals(250, s.streamThrottleMs)
        assertEquals(4000, s.streamThrottleChars)
        assertEquals(GatewayLimits.textChunkLimit, s.textChunkLimit)
        assertEquals(3500, s.textChunkLimit)
        // 与 Tuning 内部常量一致（cardByteBudget 是公开窄面，裁定 10）
        assertEquals(Tuning.streamThrottleMs, 250L)
        assertEquals(Tuning.streamThrottleChars, 4000)
    }

    @Test
    fun `allowed file dirs default empty and are engine injected not mapper derived`() {
        // §11.1 ⑥ / [SF:916] / §15.2 M-5：mapper 不产出 allowedFileDirs（LarkPolicy 无该字段）；引擎 M4-T8 经 copy() 注入
        assertEquals(emptyList(), LarkPolicyMapper.toSettings(LarkPolicy()).allowedFileDirs)
        val injected = LarkPolicyMapper.toSettings(LarkPolicy()).copy(allowedFileDirs = listOf("/workspace/root"))
        assertEquals(listOf("/workspace/root"), injected.allowedFileDirs)
    }

    @Test
    fun `full overwrite semantics changing requireMention keeps the other four fields`() {
        // §11.1 ⑦ / 🔴 决策 7 / §5.3：只改 requireMention 不得清空 groupAllowlist。
        // 引擎侧构造 current.copy(requireMention=true)（保留其余字段），mapper 忠实透传 5 字段。
        val current = LarkPolicy(groupAllowlist = listOf("oc_keep"), dmMode = "allowlist", dmAllowlist = listOf("ou_keep"), requireMention = false)
        val updated = current.copy(requireMention = true)
        val s = LarkPolicyMapper.toSettings(updated)
        // 🔴 groupAllowlist 未被清空——这是决策 7 的唯一守护点（该缺陷不抛异常、只静默清空白名单）
        assertEquals(listOf("oc_keep"), s.groupAllowlist)
        assertEquals("allowlist", s.dmMode)
        assertEquals(listOf("ou_keep"), s.dmAllowlist)
        assertTrue(s.requireMention)
        // 🔴 SDK PolicyConfig 的 5 字段全量构造在 SdkLarkGateway.policyConfig（显式 setter 恒设全 5 字段），
        //    属 SDK 翻译点，非本纯函数 mapper 职责；其正确性由 GatewayDispatcherTest 的 grep 断言 + T11 真机覆盖。
    }
}
