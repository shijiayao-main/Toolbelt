package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.entity.JErrorCode
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `BridgeErrorMapping.toJErrorCode` 的 16 值**双射**运行时背书（item 13）。
 *
 * 🔴 第一轮只留下编译器穷尽性背书（`when` 无 `else`，lib 侧新增值即红构建），本测试补运行时断言：
 * - 16 个 [BridgeErrorCode] 逐一映射到预期的 `JErrorCode.Lark*`（显式列出，防命名巧合）；
 * - **单射**：16 个像互不相同（无 N:1 收窄）；
 * - **命名不变量**：`toJErrorCode(e).name == "Lark" + e.name`（CDS §12.2「仅名称差 Lark 前缀」）；
 * - 像集恰好落在 [JErrorCode] 的 Lark* 段（往返闭合）。
 */
class BridgeErrorMappingTest {

    /** 16 值逐一显式断言（防「命名规律巧合」掩盖错映）。 */
    @Test
    fun mapsAllSixteenCodesExplicitly() {
        assertEquals(JErrorCode.LarkConfigNotFound, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ConfigNotFound))
        assertEquals(JErrorCode.LarkConfigParseFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ConfigParseFailed))
        assertEquals(JErrorCode.LarkConfigInvalid, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ConfigInvalid))
        assertEquals(JErrorCode.LarkConfigWriteFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ConfigWriteFailed))
        assertEquals(JErrorCode.LarkConnectFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ConnectFailed))
        assertEquals(JErrorCode.LarkBlockedByPeerInstance, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.BlockedByPeerInstance))
        assertEquals(JErrorCode.LarkRateLimited, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.RateLimited))
        assertEquals(JErrorCode.LarkAgentUnavailable, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.AgentUnavailable))
        assertEquals(JErrorCode.LarkAgentTimeout, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.AgentTimeout))
        assertEquals(JErrorCode.LarkWorkspaceDenied, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.WorkspaceDenied))
        assertEquals(JErrorCode.LarkConfirmUnavailable, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ConfirmUnavailable))
        assertEquals(JErrorCode.LarkDownloadFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.DownloadFailed))
        assertEquals(JErrorCode.LarkExtractUnsafeEntry, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.ExtractUnsafeEntry))
        assertEquals(JErrorCode.LarkSendFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.SendFailed))
        assertEquals(JErrorCode.LarkCardStreamFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.CardStreamFailed))
        assertEquals(JErrorCode.LarkRegisterAppFailed, BridgeErrorMapping.toJErrorCode(BridgeErrorCode.RegisterAppFailed))
    }

    /** 枚举基数 == 16（lib 侧增删值时本断言与编译器穷尽性双重报警）。 */
    @Test
    fun bridgeErrorCodeHasExactlySixteenValues() {
        assertEquals(16, BridgeErrorCode.entries.size, "BridgeErrorCode 必须恰好 16 值")
    }

    /** 单射：16 个像互不相同（无 N:1 收窄，CDS §12.2「16 值严格 1:1」）。 */
    @Test
    fun mappingIsInjective() {
        val images = BridgeErrorCode.entries.map { BridgeErrorMapping.toJErrorCode(it) }
        assertEquals(16, images.toSet().size, "16 个映射像必须互不相同（单射）")
    }

    /** 命名不变量：toJErrorCode(e).name == "Lark" + e.name。 */
    @Test
    fun mappingFollowsLarkPrefixNamingInvariant() {
        BridgeErrorCode.entries.forEach { code ->
            val mapped = BridgeErrorMapping.toJErrorCode(code)
            assertEquals("Lark${code.name}", mapped.name, "命名必须仅差 Lark 前缀：$code")
        }
    }

    /** 往返闭合：像集恰好 == JErrorCode 的全部 Lark* 段（无遗漏、无越界）。 */
    @Test
    fun imageSetEqualsAllLarkPrefixedJErrorCodes() {
        val images = BridgeErrorCode.entries.map { BridgeErrorMapping.toJErrorCode(it) }.toSet()
        val larkCodes = JErrorCode.entries.filter { it.name.startsWith("Lark") }.toSet()
        assertEquals(larkCodes, images, "映射像集必须恰好覆盖 JErrorCode 的 Lark* 段")
        assertTrue(images.all { it.name.startsWith("Lark") }, "所有像都应是 Lark* 段")
    }
}
