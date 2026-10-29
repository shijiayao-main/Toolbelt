package com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit

import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.UserNameResolver

/**
 * 驾驶舱「快速开始」向导的状态机（纯函数）。
 *
 * ## 五步定义（全部派生，零新增落盘字段）
 * 0. **AI 端点就绪**：本地 AI 端点已配置（P5-5c 评审修正 15 新增——`agent.dispatchEnabled`
 *    退役后调度 agent 恒为生效执行器，端点未配 ⇒ 用户每条消息收固定错误卡；此步把失败
 *    路径变引导路径。数据源 = `LarkBridgeViewModel.aiEndpointReady`（端点就绪订阅流，页面注入），桥接未装配 = false）
 * 1. **凭据就绪**：appId 与 appSecret 均非空
 * 2. **已启动**：运行快照 running == true
 * 3. **后台配置**：用户勾选完成 7 项飞书后台清单（会话态勾选，见 [CockpitPanel]）
 * 4. **首条消息**：连接已 Connected 且 intakeReceived > 0（真实收到过一条消息）
 *
 * ## 顺序陷阱提示
 * 「先启动并等已连接、再去飞书后台把订阅方式切长连接并保存」是头号非代码失败原因
 * （后台保存那一刻本地必须已在线，否则保存失败）——step2 未完成时 step3 常驻红字警告。
 *
 * ## 向导卡不渲染条件
 * 纯新用户（凭据全空且 bridge-config.json 不存在）⇒ 展开态直出；**凭据就绪但后台清单
 * 未勾且首条消息未收 ⇒ 不再整卡消失，改为 SectionCard(collapsible=true) 折叠存续**
 * （折叠态标题「快速开始 · 后台配置未完成」）——扫码回填凭据后恰是用户要去飞书后台
 * 配置的时刻，7 项清单与顺序陷阱警告不得随之消失（头号非代码失败原因的提示面）；
 * 后台清单勾完 **或** 首条消息已收 ⇒ 向导整体消失。
 *
 * 零 Compose、零 IO、零 .dp——纯派生，由 `CockpitLogicTest` 断言。
 */
object CockpitLogic {

    /** 快速开始五步的完成态（[allDone] = 整个向导完成，向导卡收起）。 */
    data class QuickStartSteps(
        val aiEndpointReady: Boolean,
        val credentialsReady: Boolean,
        val started: Boolean,
        val backendConfigured: Boolean,
        val firstMessageReceived: Boolean,
    ) {
        val allDone: Boolean
            get() = aiEndpointReady && credentialsReady && started && backendConfigured && firstMessageReceived
    }

    /**
     * 是否显示向导卡（存续语义收窄）：
     * - 纯新用户（凭据全空且配置文件不存在）⇒ true（展开态直出）；
     * - 凭据就绪（扫码回填/手填）但后台清单未勾 **且** 首条消息未收 ⇒ true（折叠存续态——
     *   渲染侧以 [QuickStartSteps.credentialsReady] 区分展开/折叠两态）；
     * - 后台清单勾完成首条消息已收 ⇒ false（向导完成，整卡消失）。
     * 「凭据已开始填写（半填）」维持旧语义不显示——存续只对「凭据就绪」成立。
     */
    fun showQuickStart(
        appId: String,
        appSecret: String,
        configFileExists: Boolean,
        backendChecklistDone: Boolean,
        firstMessageReceived: Boolean,
    ): Boolean {
        val freshUser = appId.isBlank() && appSecret.isBlank() && !configFileExists
        if (freshUser) return true
        val credentialsReady = appId.isNotBlank() && appSecret.isNotBlank()
        // 凭据半填（旧收起语义保留）或凭据就绪但清单已勾完/首条已收 ⇒ 不显示
        if (!credentialsReady) return false
        return !backendChecklistDone && !firstMessageReceived
    }

    /**
     * 五步完成态（运行快照可空 = 未启动/未装配；[aiEndpointReady] 由页面注入——
     * 桥接未装配时恒 false，见 `LarkBridgeViewModel.aiEndpointReady`）。
     */
    fun steps(
        appId: String,
        appSecret: String,
        snapshot: BridgeStatusSnapshot?,
        backendChecklistDone: Boolean,
        aiEndpointReady: Boolean,
    ): QuickStartSteps = QuickStartSteps(
        aiEndpointReady = aiEndpointReady,
        credentialsReady = appId.isNotBlank() && appSecret.isNotBlank(),
        started = snapshot?.running == true,
        backendConfigured = backendChecklistDone,
        firstMessageReceived = snapshot?.connection is BridgeConnectionStatus.Connected &&
            snapshot.counters.intakeReceived > 0,
    )

    /** 顺序陷阱警告的显隐：step2（已启动）未完成而用户开始勾后台清单 ⇒ 常驻警告（五步化后口径不变）。 */
    fun orderTrapWarningVisible(steps: QuickStartSteps): Boolean =
        !steps.started

    /**
     * 飞书后台核对清单（逐字对应 docs/pages/lark-bridge.md「7 项后台核对清单」表第 2 项的**最小集**部分；
     * 两处必须同步改，docs 侧另有 L2 可选 scope 的展开说明）。
     *
     * 🔴 第 2 项含 `im:message.reactions:write_only`
     * （表情回应的写 scope——漏登记会让 react_emoji 全部失败）。
     */
    val BACKEND_CHECKLIST: List<String> = listOf(
        "机器人能力已启用（应用能力 → 机器人）",
        "权限 scope：im:message.p2p_msg:readonly / im:message.group_at_msg:readonly / im:message:send_as_bot / im:message.reactions:write_only / im:resource / ${UserNameResolver.CONTACT_BASE_SCOPE} / cardkit:card:write（跨会话能力 scope 另见 docs/pages/lark-bridge.md 第 2 项可选清单）",
        "事件订阅 im.message.receive_v1 = 使用长连接接收事件",
        "回调订阅 card.action.trigger = 使用长连接接收回调（新版）",
        "应用已发版，可用范围包含你自己",
        "飞书客户端 ≥ 7.20",
        "本机可直连公网（长连接不支持代理）",
    )
}
