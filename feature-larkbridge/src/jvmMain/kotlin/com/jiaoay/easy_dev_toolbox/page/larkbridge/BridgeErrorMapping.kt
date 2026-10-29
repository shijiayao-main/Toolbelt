package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.entity.JErrorCode
import com.jiaoay.lark.bridge.config.BridgeErrorCode

/**
 * 桥接错误码 → 宿主错误码映射（[CDS §12.2]）。
 *
 * 🔴 lib 侧 `:lark-bridge` 是 jvmTarget 11，不能依赖 `:core`（jvmTarget 25）的 [JErrorCode]
 * （违反即编译失败），
 * 故两侧定义同构但独立的枚举，映射只能落在 feature 侧本文件。
 *
 * 🔴 `when` **必须穷尽全部 16 个 [BridgeErrorCode]**、**不得有兜底 `else`**——
 * 用 enum 穷尽性让编译器兜底：lib 侧新增错误码时本文件立即红构建，逼施工者显式决定落点。
 * （书写规范照抄 `:lark-bridge` 的 `GatewayErrorMapping`，同一套穷尽性纪律。）
 *
 * 16 值严格 1:1：code 数值、desc 文本、声明顺序与 [JErrorCode] 的 Lark* 段逐项一致，
 * 仅名称差 `Lark` 前缀，故无需 N:1 收窄、无需新增 JErrorCode 值。
 */
object BridgeErrorMapping {

    fun toJErrorCode(code: BridgeErrorCode): JErrorCode = when (code) {
        // —— 配置域 2000-2009 ——
        BridgeErrorCode.ConfigNotFound -> JErrorCode.LarkConfigNotFound
        BridgeErrorCode.ConfigParseFailed -> JErrorCode.LarkConfigParseFailed
        BridgeErrorCode.ConfigInvalid -> JErrorCode.LarkConfigInvalid
        BridgeErrorCode.ConfigWriteFailed -> JErrorCode.LarkConfigWriteFailed
        // —— 连接域 2010-2019 ——
        BridgeErrorCode.ConnectFailed -> JErrorCode.LarkConnectFailed
        BridgeErrorCode.BlockedByPeerInstance -> JErrorCode.LarkBlockedByPeerInstance
        BridgeErrorCode.RateLimited -> JErrorCode.LarkRateLimited
        // —— Agent 域 2020-2029 ——
        BridgeErrorCode.AgentUnavailable -> JErrorCode.LarkAgentUnavailable
        BridgeErrorCode.AgentTimeout -> JErrorCode.LarkAgentTimeout
        BridgeErrorCode.WorkspaceDenied -> JErrorCode.LarkWorkspaceDenied
        BridgeErrorCode.ConfirmUnavailable -> JErrorCode.LarkConfirmUnavailable
        // —— 文件域 2030-2039 ——
        BridgeErrorCode.DownloadFailed -> JErrorCode.LarkDownloadFailed
        BridgeErrorCode.ExtractUnsafeEntry -> JErrorCode.LarkExtractUnsafeEntry
        // —— 出站域 2040-2049 ——
        BridgeErrorCode.SendFailed -> JErrorCode.LarkSendFailed
        BridgeErrorCode.CardStreamFailed -> JErrorCode.LarkCardStreamFailed
        // —— 扫码建应用域 2050-2059 ——
        BridgeErrorCode.RegisterAppFailed -> JErrorCode.LarkRegisterAppFailed
    }
}
