package com.jiaoay.lark.bridge.config

/**
 * lib 侧桥接错误码（[CDS §12.1]，14 个 + gateway-contract.md §15.1 裁定新增 2 个 = 16 个，码值 2000-2050）。
 * 🔴 jvmTarget 11，不依赖 :core；与 :core JErrorCode 桥接段（2000-2099）**同构但独立**，
 * feature 侧 BridgeErrorMapping 做 BridgeErrorCode → JErrorCode 映射（[CDS §12.2]）。
 *
 * 命名一致性：lib 侧统一**不带 Lark 前缀**（对齐 config-domain-spec.md §12.1 的 ConfigNotFound/ConnectFailed/…）。
 * 任务书在 BridgeErrorCode 条目里写的 `LarkCardStreamFailed`/`LarkRegisterAppFailed` 是 :core JErrorCode 侧的命名
 * （config-domain-spec.md §12.3 带 Lark 前缀）；本 lib 侧对应值取 `CardStreamFailed`/`RegisterAppFailed` 以保持同枚举内命名一致（详见 gateway-contract.md §15.1）。
 */
enum class BridgeErrorCode(val code: Int, val desc: String) {
    // 配置域 (2000-2009)
    ConfigNotFound(2000, "配置文件不存在"),
    ConfigParseFailed(2001, "配置文件解析失败"),
    ConfigInvalid(2002, "配置校验失败"),
    ConfigWriteFailed(2003, "配置写入失败"),

    // 连接域 (2010-2019)
    ConnectFailed(2010, "连接失败"),
    BlockedByPeerInstance(2011, "同 App ID 已有实例运行"),
    RateLimited(2012, "被飞书频控"),

    // Agent 域 (2020-2029)
    AgentUnavailable(2020, "Agent 后端不可用"),
    AgentTimeout(2021, "Agent 执行超时"),
    WorkspaceDenied(2022, "工作目录越界"),
    ConfirmUnavailable(2023, "确认链路不可用"),

    // 文件域 (2030-2039)
    DownloadFailed(2030, "文件下载失败"),
    ExtractUnsafeEntry(2031, "解包路径不安全"),

    // 出站域 (2040-2049)
    SendFailed(2040, "消息发送失败"),
    CardStreamFailed(2041, "卡片流式更新失败"),

    // 扫码建应用域 (2050-2059)
    RegisterAppFailed(2050, "扫码建应用失败"),
}
