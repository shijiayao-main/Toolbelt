// :lark-gateway 测试（M3-T3，§11.8）。🔴 RateGovernor 本体在 :lark-bridge（引擎侧），归 **M4-T7**，本文件不实现 RateGovernorTest。
// 本轮只测：① 裁定 10/B-13 的公开窄面 GatewayLimits 4 常量（值 + 出处注释）；② 网关侧不重复实现频控/退避（结构性约束）。
// 🔴 分工记入报告：RateGovernorTest(NF-05) 的令牌桶/退避断言由 M4-T7 落地，读 GatewayLimits 而非复制数值。
package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §11.8：网关暴露的公开协议常量窄面 + 网关侧不重复实现频控。 */
class GatewayLimitsTest {

    // 裁定 10 / B-13：GatewayLimits 只含引擎合法需要的 4 个常量，值精确（每个都带飞书硬限制出处）
    @Test
    fun `gateway limits expose exactly the four engine facing constants`() {
        assertEquals(4, GatewayLimits.perChatRps, "同群 5 QPS 留 20% 余量 → 4（R7⑥/[BC:1106]）")
        assertEquals(40, GatewayLimits.globalRps, "接口级 50/s 留 20% 余量 → 40（决策 20/[BC:1106]）")
        assertEquals(3500, GatewayLimits.textChunkLimit, "单条文本上限（出站分片，决策 18/[SF:903]）")
        assertEquals(24576, GatewayLimits.cardByteBudget, "单卡 30KB 留 6KB 转义膨胀 → 24576（R7①/[BC:1035]）")
    }

    // 4 个常量都带飞书硬限制 + 余量注释（注释宪法：契约/硬限制数值是合法注释内容；
    // 出处引用 [BC:]/[SF:]/R7 形态已随注释治理退役——出处知识归 docs/pages/lark-bridge.md）
    @Test
    fun `each public limit carries its hard limit provenance comment`() {
        val src = File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/Tuning.kt").readText()
        val limits = src.substringAfter("object GatewayLimits {").substringBefore("internal object Tuning")
        assertTrue("QPS" in limits || "5 QPS" in limits, "perChatRps 须注明同群 QPS 硬限制出处")
        assertTrue("50/s" in limits, "globalRps 须注明接口级 50/s 硬限制出处")
        assertTrue("30KB" in limits || "30720" in limits, "cardByteBudget 须注明单卡 30KB 硬限制出处")
    }

    // 裁定 10：其余常量保持 internal 的 Tuning，不全公开（避免 :lark-bridge 复制数值，也避免全公开）
    @Test
    fun `tuning stays internal and is not part of the public surface`() {
        val src = File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/Tuning.kt").readText()
        assertTrue("internal object Tuning" in src, "Tuning 须为 internal（非契约面）")
        assertTrue("object GatewayLimits" in src, "GatewayLimits 为公开窄面")
        assertFalse("public object Tuning" in src, "Tuning 不得公开")
    }

    // §11.8 网关侧约束：网关不重复实现频控/令牌桶（RateGovernor 归 :lark-bridge / M4-T7）
    @Test
    fun `gateway does not reimplement the rate governor`() {
        val mainDir = File(repoRoot(), "lark-gateway/src/main/kotlin")
        val all = mainDir.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("class RateGovernor" in all || "object RateGovernor" in all,
            "RateGovernor 本体归 :lark-bridge（M4-T7），网关不得重复实现")
        assertFalse("TokenBucket" in all, "令牌桶归 RateGovernor，网关不得自建")
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }
}
