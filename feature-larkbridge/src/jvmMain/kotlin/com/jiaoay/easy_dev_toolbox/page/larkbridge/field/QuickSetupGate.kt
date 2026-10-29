package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import com.jiaoay.lark.bridge.config.BridgeConfig

/**
 * 新用户门控判定真源（设计真源
 * 重设计稿「新用户门控」节 + 屏②）：
 * 新用户打开配置页先看快速上手卡，填全后原地变形为完整字段面。
 *
 * ## 判据是徽章组而非「BASIC 字段任一为空」
 * `connection.region` 是 Choice 且默认值 "feishu" 永不为空，字面判空对它会失效；徽章行与门控
 * 必须同源，否则徽章全绿门控仍开（或反之）即自相矛盾。故 [showQuickSetup] 只由 [badgeGroups]
 * 驱动（region 不参与门控），用例 regionNeverBlocksGate 钉死「只改 region 无法翻转门控」。
 *
 * ## 只看凭据与工作区
 * 准入恒放行后「填白名单」不再是从零到可用的必经步骤。
 */
object QuickSetupGate {

    /** 完成徽章组（「✓凭据 ✓工作区」的数据面）。 */
    data class BadgeGroup(val key: String, val label: String, val fieldId: String, val ready: Boolean)

    /**
     * 两组判定（顺序即徽章行渲染序与 [firstEmptyFieldId] 优先级）：
     * - 凭据 = connection.appId 与 connection.appSecret 均非空（fieldId 取 connection.appId）；
     * - 工作区 = workspace.root 非空。
     */
    fun badgeGroups(config: BridgeConfig): List<BadgeGroup> = listOf(
        BadgeGroup(
            key = "credentials",
            label = "凭据",
            fieldId = "connection.appId",
            ready = config.connection.appId.isNotBlank() && config.connection.appSecret.isNotBlank(),
        ),
        BadgeGroup(
            key = "workspace",
            label = "工作区",
            fieldId = "workspace.root",
            ready = config.workspace.root.isNotBlank(),
        ),
    )

    /**
     * 门控开 = 任一组未完成。判据是徽章组而非字面判空（region 豁免口径见类头）。
     */
    fun showQuickSetup(config: BridgeConfig): Boolean = badgeGroups(config).any { !it.ready }

    /** 首个未完成组的 fieldId（全部完成返回 null）——深链聚焦目标。 */
    fun firstEmptyFieldId(config: BridgeConfig): String? =
        badgeGroups(config).firstOrNull { !it.ready }?.fieldId
}
