package com.jiaoay.lark.bridge.config

import com.jiaoay.lark.gateway.GatewayErrorCode

/**
 * 网关错误码 → 桥接错误码映射（gateway-contract.md §6.3.5 建议对接表 + gateway-contract.md §15.1 裁定）。
 *
 * 🔴 允许 N:1，但 `when` **必须穷尽全部 24 个 [GatewayErrorCode]**、**不得有兜底 `else`**——
 * 用 enum 穷尽性让编译器兜底：网关侧新增错误码时本文件立即红构建，逼施工者显式决定落点，
 * 而非静默落进 else 桶丢失语义。
 *
 * 缺口消解（gateway-contract.md §15.1）：Card* 5 值 → CardStreamFailed(2041)、Register* 3 值 → RegisterAppFailed(2050)，
 * 使 NF-A3① 的「每个网关码有专属桥接落点」成立（原 gateway-contract.md §6.3.5 标注的两处「无对应值」缺口已补齐）。
 */
object GatewayErrorMapping {

    fun toBridge(code: GatewayErrorCode): BridgeErrorCode = when (code) {
        // —— 连接域 → ConnectFailed(2010)（CDS 只有一个连接失败码）——
        GatewayErrorCode.ConnectFatal -> BridgeErrorCode.ConnectFailed
        GatewayErrorCode.ConnectRetryable -> BridgeErrorCode.ConnectFailed
        GatewayErrorCode.NotConnected -> BridgeErrorCode.ConnectFailed
        // —— 单实例互斥 / 频控 1:1 ——
        GatewayErrorCode.BlockedByPeerInstance -> BridgeErrorCode.BlockedByPeerInstance
        GatewayErrorCode.RateLimited -> BridgeErrorCode.RateLimited
        // —— 资源下载 → DownloadFailed(2030) ——
        GatewayErrorCode.DownloadFailed -> BridgeErrorCode.DownloadFailed
        GatewayErrorCode.DownloadTooLarge -> BridgeErrorCode.DownloadFailed
        // —— 卡片流式 5 值 → CardStreamFailed(2041) ——
        GatewayErrorCode.CardSequenceConflict -> BridgeErrorCode.CardStreamFailed
        GatewayErrorCode.CardTooLarge -> BridgeErrorCode.CardStreamFailed
        GatewayErrorCode.CardStreamBusy -> BridgeErrorCode.CardStreamFailed
        GatewayErrorCode.CardEntityExpired -> BridgeErrorCode.CardStreamFailed
        GatewayErrorCode.CardJsonInvalid -> BridgeErrorCode.CardStreamFailed
        // —— 扫码建应用 3 值 → RegisterAppFailed(2050) ——
        GatewayErrorCode.RegisterQrExpired -> BridgeErrorCode.RegisterAppFailed
        GatewayErrorCode.RegisterInvalidArgument -> BridgeErrorCode.RegisterAppFailed
        GatewayErrorCode.RegisterFailed -> BridgeErrorCode.RegisterAppFailed
        // —— 出站 9 值 → SendFailed(2040)（N:1 语义收窄，无 else，逐值显式）——
        GatewayErrorCode.SendFailed -> BridgeErrorCode.SendFailed
        GatewayErrorCode.SendTimeout -> BridgeErrorCode.SendFailed
        GatewayErrorCode.FormatError -> BridgeErrorCode.SendFailed
        GatewayErrorCode.TargetRevoked -> BridgeErrorCode.SendFailed
        GatewayErrorCode.PermissionDenied -> BridgeErrorCode.SendFailed
        GatewayErrorCode.UploadFailed -> BridgeErrorCode.SendFailed
        GatewayErrorCode.SsrfBlocked -> BridgeErrorCode.SendFailed
        GatewayErrorCode.ReactionFailed -> BridgeErrorCode.SendFailed
        GatewayErrorCode.Unknown -> BridgeErrorCode.SendFailed
    }
}
