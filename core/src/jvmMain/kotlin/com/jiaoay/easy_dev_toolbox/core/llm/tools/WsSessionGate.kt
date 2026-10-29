package com.jiaoay.easy_dev_toolbox.core.llm.tools

/**
 * 工作区域（ws.* / git.* 工具族）会话级门控面（装配门控的桥接链侧契约）。
 *
 * ## 职责分界（与 [SysSessionGate] 同一信任名单判据）
 * 与 [SysSessionGate] 同一信任名单判据（`access.trustedUsers`，按消息发送者 openId 判定）：
 * 名单内三域同时放行、名单外三域同时拒绝，不存在按域的独立授权状态；接口独立仅为工具侧
 * 依赖收口。工具侧（[WsWriteTools] / [WsRunTaskTools] / [GitReadTools]）只消费本接口——
 * 执行前必过 [requireSessionAuthorization]（缺席即 DENIED，fail-closed）。
 *
 * ## 实现契约（按用户信任的两档准入，无发卡面）
 * 名单内 ⇒ 放行并把工作区根 register 进 ws 专用 context 允许集（严禁借道 allowedRoots——
 * 命中即免 gate 直接放行 + 出站旁路）；名单外 ⇒ 拒绝（进不了 ws./git. 域）；名单持久化于
 * bridge-config.json，无会话态。
 *
 * ## [wsEscapeGate]（工作区根放行的运行时缝）
 * ws 专用 context 的 HITL 缝：目标路径未命中允许集时挂起询问，实现按
 * 「会话已授权 + canonical 落在工作区根内」判定——批准后 authorizeWithGate 把目标
 * 临时登记进允许集（ToolExecutionContext 既有机制承载，语义零新发明）。
 *
 * @param wsRootProvider 工作区根（装配链从桥接配置解析注入；工具不收路径参数——
 *   工作区根是装配事实不是 LLM 输入）
 */
interface WsSessionGate {

    /**
     * 要求发送者通过 ws./git. 域准入（名单内即时返回 true）。
     *
     * @param toolName 触发授权的工具名（审计用）
     * @return true = 发送者在信任名单内；false = 不在名单（进不了域）
     */
    suspend fun requireSessionAuthorization(toolName: String): Boolean

    /** ws 专用 context 的 HITL 缝（工作区根内路径的放行判定，见类 KDoc） */
    val wsEscapeGate: ToolExecutionContext.PathEscapeGate

    /** 工作区根（canonical 绝对路径；装配链注入，非 LLM 输入） */
    val wsRoot: java.io.File

    /** 触发者标识（发送者 openId——审计 trigger 单源；测试形态可 null） */
    val trigger: String?
}
