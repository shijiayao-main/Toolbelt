package com.jiaoay.easy_dev_toolbox.core.llm.tools

/**
 * sys.* 会话级门控面（只读诊断域装配门控的桥接链侧契约）。
 *
 * ## 职责分界
 * 工具侧（[SysProbeTools] / [SysReadConfigTools]）只消费本接口——执行前必过
 * [requireSessionAuthorization]（缺席即 DENIED，fail-closed）；域准入判定与审计落盘
 * 全部由装配链的实现承担（桥接链实现在 :feature-larkbridge koog 域）。
 *
 * ## 实现契约（按用户信任的两档准入，无发卡面）
 * - 判定唯一维度 = 本消息发送者 openId 是否在桥接配置 `access.trustedUsers` 名单内
 *   （HOT 现读，名单变更下一条消息即生效）；
 * - 名单内 ⇒ 放行，并把全部路径族的族根 register 进 sys 专用 context 的允许集
 *   （严禁借道 [ToolExecutionContext] 的 allowedRoots——其语义 = 命中即免 gate
 *   直接放行，家目录进根 = 整盘免确认读 + 出站旁路）；
 * - 名单外 ⇒ 拒绝（进不了 sys.* 域）；
 * - 名单持久化于 bridge-config.json，重启后仍生效；无会话态、无进程内存授权档。
 *
 * ## [familyEscapeGate]（族根放行的运行时缝）
 * sys 专用 context 的 HITL 缝：族内相对路径未命中允许集时挂起询问，实现按
 * 「会话已授权 + canonical 落在任一族根内」判定——批准后 authorizeWithGate 把目标
 * 临时登记进允许集（ToolExecutionContext 既有机制承载，语义零新发明）。
 */
interface SysSessionGate {

    /**
     * 要求发送者通过 sys.* 域准入（名单内即时返回 true）。
     *
     * @param toolName 触发工具名（审计归因用）
     * @return true = 发送者在信任名单内；false = 不在名单（进不了域）
     */
    suspend fun requireSessionAuthorization(toolName: String): Boolean

    /** sys 专用 context 的 HITL 缝（族内相对路径的族根放行判定，见类 KDoc） */
    val familyEscapeGate: ToolExecutionContext.PathEscapeGate

    /** 触发者标识（发送者 openId——审计 trigger 单源；测试形态可 null） */
    val trigger: String?
}
