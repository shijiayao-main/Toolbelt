// connect 失败排障文案（gateway-contract.md §3.6）：调用点在 SdkLarkGateway 的 connect 超时/失败两条路径，
// 写进 ConnectFatal/ConnectRetryable 的 msg，供 UI 与 CLI check 直接展示。
package com.jiaoay.lark.gateway

/**
 * 连接失败排障四项（gateway-contract.md §3.6）：写进 ConnectFatal/ConnectRetryable 的 msg，供 UI 与 CLI check 直接展示。
 * internal 而非 private：Kotlin 顶层 private 仅本文件可见，跨文件消费（SdkLarkGateway 的 connect 路径）须 internal。
 */
internal val CONNECT_TROUBLESHOOTING =
    "connect 超时/失败。排障四项：①应用是否已发版且可用范围含自己；②权限 scope 是否齐（含 cardkit:card:write）；" +
        "③后台事件/回调订阅方式是否已切「使用长连接」并保存成功——🔴 保存那一刻本地须已有一条 SDK 长连接处于已连接态（R21 顺序陷阱）；" +
        "④网络是否走代理（长连接不支持代理，检查 HTTP_PROXY/HTTPS_PROXY）。"
