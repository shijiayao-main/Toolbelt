package com.jiaoay.easy_dev_toolbox.entity

/**
 */
enum class JErrorCode(val code: Int, val desc: String) {
    Success(code = 0, desc = "成功"),

    // 1000 - 1999 shell tools 错误码
    ShellConfigNotFound(code = 1000, desc = "没有找到配置文件"),
    ShellConfigParseFailed(code = 1001, desc = "配置文件解析失败"),
    ShellConfigEmpty(code = 1002, desc = "配置文件为空"),

    // 2000 - 2099 lark bridge 错误码（与 lib 侧 BridgeErrorCode 同构但独立；feature 侧做映射）
    // 配置域 2000-2009
    LarkConfigNotFound(code = 2000, desc = "配置文件不存在"),
    LarkConfigParseFailed(code = 2001, desc = "配置文件解析失败"),
    LarkConfigInvalid(code = 2002, desc = "配置校验失败"),
    LarkConfigWriteFailed(code = 2003, desc = "配置写入失败"),
    // 连接域 2010-2019
    LarkConnectFailed(code = 2010, desc = "连接失败"),
    LarkBlockedByPeerInstance(code = 2011, desc = "同 App ID 已有实例运行"),
    LarkRateLimited(code = 2012, desc = "被飞书频控"),
    // Agent 域 2020-2029
    LarkAgentUnavailable(code = 2020, desc = "Agent 后端不可用"),
    LarkAgentTimeout(code = 2021, desc = "Agent 执行超时"),
    LarkWorkspaceDenied(code = 2022, desc = "工作目录越界"),
    LarkConfirmUnavailable(code = 2023, desc = "确认链路不可用"),
    // 文件域 2030-2039
    LarkDownloadFailed(code = 2030, desc = "文件下载失败"),
    LarkExtractUnsafeEntry(code = 2031, desc = "解包路径不安全"),
    // 出站域 2040-2049
    LarkSendFailed(code = 2040, desc = "消息发送失败"),
    LarkCardStreamFailed(code = 2041, desc = "卡片流式更新失败"),
    // 扫码建应用域 2050-2059
    LarkRegisterAppFailed(code = 2050, desc = "扫码建应用失败"),
}