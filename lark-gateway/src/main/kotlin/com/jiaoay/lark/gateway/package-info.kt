// :lark-gateway 包说明文件。
// 本模块是飞书官方 SDK（com.lark.oapi.*）在全仓的唯一隔离边界：网关契约
// （MessageGateway / CardStreamSink / GatewayModel / LarkPolicy / LarkCredential）
// 与其 SDK 实现 SdkLarkGateway 均在此，SDK 类型不得泄漏到其他模块（由 LarkSdkIsolationTest 守护）。
// 唯一构造点是 BridgeGateways；真机实测结论见 gateway-contract.md §16 与 spike-findings.md。
package com.jiaoay.lark.gateway
