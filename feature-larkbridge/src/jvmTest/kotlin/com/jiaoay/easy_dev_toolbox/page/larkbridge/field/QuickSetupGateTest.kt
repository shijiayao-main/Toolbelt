package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QuickSetupGate] 新用户门控判定守护（IA 重设计批③ 2026-09-27，契约自足化——判据与
 * 优先级语义直接写在下方，不依赖已作废的外部设计稿）：
 *
 * - 判据 = 徽章组（凭据/工作区），**非**「BASIC 字段字面任一为空」——region 豁免；
 * - firstEmptyFieldId 优先级 = 徽章组顺序（凭据 → 白名单 → 工作区）。
 */
class QuickSetupGateTest {

    /** 凭据组已完成的配置（appId+appSecret 非空，其余保持默认）。 */
    private val credentialsFilled: BridgeConfig =
        DefaultBridgeConfig.default().copy(
            connection = DefaultBridgeConfig.default().connection.copy(
                appId = "cli_test_app",
                appSecret = "secret_value",
            ),
        )

    /** 两组全完成的配置（凭据 + 工作区）。 */
    private val allFilled: BridgeConfig =
        DefaultBridgeConfig.default().copy(
            connection = DefaultBridgeConfig.default().connection.copy(
                appId = "cli_test_app",
                appSecret = "secret_value",
            ),
            workspace = DefaultBridgeConfig.default().workspace.copy(
                root = "/tmp/workspace",
            ),
        )

    /** 🔒 默认配置：门控开、恰 2 组、顺序与全未完成、首空 = connection.appId。 */
    @Test
    fun defaultConfigShowsQuickSetupWithAllBadgesIncomplete() {
        val config = DefaultBridgeConfig.default()
        assertTrue(QuickSetupGate.showQuickSetup(config), "默认配置应显示快速上手卡（门控开）")
        val groups = QuickSetupGate.badgeGroups(config)
        assertEquals(2, groups.size, "徽章组恰 2 组（凭据/工作区——白名单已随准入恒放行退役）")
        assertEquals(
            listOf("credentials", "workspace"),
            groups.map { it.key },
            "徽章组顺序 = 凭据/工作区",
        )
        assertTrue(
            groups.none { it.ready },
            "默认配置两组全未完成（appId/appSecret/工作区全空）",
        )
        assertEquals("connection.appId", QuickSetupGate.firstEmptyFieldId(config), "首个空字段 = appId（优先级序首）")
    }

    /** 🔒 只填凭据：仅凭据徽章 ready，firstEmptyFieldId 跳到工作区。 */
    @Test
    fun credentialsFilledMarksOnlyCredentialsBadge() {
        val config = credentialsFilled
        val groups = QuickSetupGate.badgeGroups(config).associateBy { it.key }
        assertTrue(groups.getValue("credentials").ready, "appId+appSecret 均非空 ⇒ 凭据组 ready")
        assertFalse(groups.getValue("workspace").ready, "工作区仍空 ⇒ 工作区组未完成")
        assertEquals(
            "workspace.root",
            QuickSetupGate.firstEmptyFieldId(config),
            "凭据完成后首个空字段 = 工作区（优先级序第二）",
        )
    }

    /** 🔒 两组全填：门控关（原地变形为完整字段面），firstEmptyFieldId = null。 */
    @Test
    fun allGroupsReadyHidesQuickSetup() {
        val config = allFilled
        assertFalse(QuickSetupGate.showQuickSetup(config), "两组全完成 ⇒ 门控关，快速上手卡消失")
        assertEquals(null, QuickSetupGate.firstEmptyFieldId(config), "无空组 ⇒ 无聚焦目标")
    }

    /** 🔒 region 豁免：只改 region 无法翻转门控（判据是徽章组，region 是 Choice 有默认值永不为空）。 */
    @Test
    fun regionNeverBlocksGate() {
        val default = DefaultBridgeConfig.default()
        assertTrue(
            QuickSetupGate.badgeGroups(default).none { it.key == "region" },
            "徽章组不含 region（三组判定与屏②徽章行同源）",
        )
        val regionChanged = default.copy(
            connection = default.connection.copy(region = "lark"),
        )
        assertTrue(
            QuickSetupGate.showQuickSetup(regionChanged),
            "默认配置下只改 region 仍门控开——region 不参与门控判据（region 豁免裁定）",
        )
    }
}
