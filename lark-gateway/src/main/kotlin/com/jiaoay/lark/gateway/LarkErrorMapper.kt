// :lark-gateway 错误映射纯函数；本文件签名禁 SDK 类型（gateway-contract.md §6.0 纪律 1，gateway-contract.md §6.3.1 fromThrowableName 不收 Throwable）。
// 飞书业务码 / HTTP 状态 / SDK 异常码 → GatewayErrorCode（24 值，gateway-contract.md §15.2 M-1）。
package com.jiaoay.lark.gateway

/**
 * 错误映射（纯函数、无状态）。禁止在 mapper 内做重试/退避/频控判断（重试归 :lark-bridge 的 RateGovernor）。
 * 优先级（gateway-contract.md §6.3.2）：先 HTTP 状态 → 再业务码 → 最后兜底。理由：飞书限流时可能同时返回 HTTP 429 和某业务码，
 *    若先查业务码会漏掉 RateLimited 语义，导致 RateGovernor 不退避。
 */
object LarkErrorMapper {

    /** 飞书业务码（BaseResponse.getCode()）+ HTTP 状态 → GatewayErrorCode（映射表见 gateway-contract.md）。 */
    fun fromLarkCode(larkCode: Int?, httpStatus: Int?): GatewayErrorCode {
        // 1) 先看 HTTP 状态
        if (httpStatus != null) {
            when {
                httpStatus == 429 -> return GatewayErrorCode.RateLimited       // Too Many Requests，读 x-ogw-ratelimit-reset
                httpStatus == 404 -> return GatewayErrorCode.ConnectFatal      // 网关路径错误（非 JSON 响应），不重试 [BC:1271]
                httpStatus in 500..599 -> return GatewayErrorCode.ConnectRetryable // 服务端错误，可重试
            }
        }
        // 2) 再看业务码
        return when (larkCode) {
            300317 -> GatewayErrorCode.CardSequenceConflict   // sequence 不连续/乱序 → RebuildStream
            200860 -> GatewayErrorCode.CardTooLarge           // 单卡 30KB 超限 → Rotate
            200810 -> GatewayErrorCode.CardStreamBusy         // 流式期间无法响应卡片回传 → GiveUp（契约层已禁流式卡带组件）
            200750 -> GatewayErrorCode.CardEntityExpired      // 卡片实体 14 天过期/仅可发送一次 → RebuildStream
            // 真机四码：均为「请求结构被飞书拒绝、重试必然再错」⇒ 归 CardJsonInvalid（GiveUp、可观测，不新增枚举值）。
            //    230001 invalid msg_type: card（probe-card.log A）｜300312 tag cannot be updated（probe-patch.log P1/P4）
            //    11311 printStep is nil｜10002 failed to unmarshal PrintStepConfig（streaming_config 探测）
            300302, 230099, 230001, 300312, 11311, 10002 -> GatewayErrorCode.CardJsonInvalid // 卡片/请求 JSON 结构错误 → GiveUp
            11232 -> GatewayErrorCode.RateLimited             // 整点压力限流 → Backoff
            99991672 -> GatewayErrorCode.ConnectFatal         // 应用凭据无效/无权限，不重试
            // 平台下载大小限（真机：>100MB 文件 http=400 + 本码）。SDK MessageResource javadoc
            //    「仅支持下载 100 MB 以内的资源文件」——服务端硬限。Range 分片路径可下 >100MB 文件，
            //    本码仅在「服务器忽略 Range 走 200 降级」形态下对 >100MB 文件出现——保留登记与
            //    用户文案兜底。与「我方超限」同归 DownloadTooLarge，靠
            //    DownloadResult.larkCode（null=我方 / 非 null=平台）区分，不在他处复制 234037 魔数。
            //    isRetryable(DownloadTooLarge)=true 是契约锁定的分区（且零生产消费者）——
            //    但对固定文件重试必然再错，不得据 isRetryable 做自动重试。
            234037 -> GatewayErrorCode.DownloadTooLarge      // 下载超飞书平台 100MB 限 → 用户文案「压缩/拆分后重发」（分片路径下仅 200 降级形态可遇）
            // Range 分片协议业务码（飞书官方 message-resource 文档；枚举零新增——复用既有值）：
            //    234038 保密消息禁止下载 / 234040 消息不可见 ⇒ 权限类、不可重试；
            //    234045 Range 头格式无效 / 234046 Range 不满足（如超出文件大小）⇒ 分片协议类、可重试（isRetryable(DownloadFailed)=true 既有分区）。
            234038, 234040 -> GatewayErrorCode.PermissionDenied // 保密消息/不可见 = 应用无权下载该消息
            // 234043（不支持的消息类型）显式登记为 SendFailed：我方 FileRegistry 只登记真实文件消息，正常流程不应触达；
            //    可达形态 = 引用合并转发等容器消息的资源（fetchQuotedMessage 对 merge_forward 不做条目级解析）。
            234043 -> GatewayErrorCode.SendFailed            // 不支持的消息类型（引用链异常形态，重试无意义但可观测）
            234045, 234046 -> GatewayErrorCode.DownloadFailed // Range 分片协议错误（分片实现缺陷或服务器拒绝 Range）
            null, 0 -> GatewayErrorCode.Unknown               // 成功不进入本 mapper；兜底
            else -> GatewayErrorCode.SendFailed               // 未登记业务码（容忍未知值，记 WARN 含原始码）
        }
    }

    /**
     * SDK LarkChannelErrorCode（9 值，getCode() 返回 String）→ GatewayErrorCode（gateway-contract.md §6.3.3）。
     * 字符串精确匹配 + else→Unknown（容忍 SDK 升级新增枚举值）。
     */
    fun fromLarkChannelCode(code: String?): GatewayErrorCode = when (code) {
        "FORMAT_ERROR" -> GatewayErrorCode.FormatError
        "TARGET_REVOKED" -> GatewayErrorCode.TargetRevoked
        "RATE_LIMITED" -> GatewayErrorCode.RateLimited
        "PERMISSION_DENIED" -> GatewayErrorCode.PermissionDenied
        "UPLOAD_FAILED" -> GatewayErrorCode.UploadFailed
        "SSRF_BLOCKED" -> GatewayErrorCode.SsrfBlocked
        "SEND_TIMEOUT" -> GatewayErrorCode.SendTimeout
        "NOT_CONNECTED" -> GatewayErrorCode.NotConnected
        "UNKNOWN" -> GatewayErrorCode.Unknown
        else -> GatewayErrorCode.Unknown
    }

    /**
     * 异常兜底。不 import SDK 异常类型：调用方（SdkLarkGateway）先取 t::class.java.name 与 t.message 传进来。
     * 按异常类名 endsWith 映射（gateway-contract.md §8 mapRegisterFailure 同纪律）。
     */
    fun fromThrowableName(fqcn: String?, message: String?): GatewayErrorCode {
        val name = fqcn ?: return GatewayErrorCode.Unknown
        return when {
            // 扫码建应用域（gateway-contract.md §8）
            name.endsWith("ExpiredException") -> GatewayErrorCode.RegisterQrExpired
            name.endsWith("RegisterAppException") ->
                if (message != null && message.contains("ERROR_INVALID_ARGUMENT")) GatewayErrorCode.RegisterInvalidArgument
                else GatewayErrorCode.RegisterFailed
            // 连接/网络域：IO 类一律可重试
            name.endsWith("SocketTimeoutException") || name.endsWith("TimeoutException") -> GatewayErrorCode.SendTimeout
            name.endsWith("LarkChannelException") -> GatewayErrorCode.Unknown // 应由 fromLarkChannelCode 处理；兜底
            name.endsWith("ConnectException") || name.endsWith("UnknownHostException") ||
                name.endsWith("SocketException") || name.endsWith("IOException") -> GatewayErrorCode.ConnectRetryable
            // 连接期「缺 URL/配置字段」由 SDK 抛 IllegalArgumentException（message 如 "url is blank"），
            //    ConnectRetryable 语义含「缺 URL 字段」、fromThrowableName 主要在 connect() 的 catch 内调用，
            //    此处 IllegalArgumentException 几乎必为配置缺失（可修后重连），故归 ConnectRetryable。
            name.endsWith("IllegalArgumentException") -> GatewayErrorCode.ConnectRetryable
            else -> GatewayErrorCode.Unknown
        }
    }

    /**
     * 是否值得由 RateGovernor 重试（只有卡片更新与资源下载可重试）。
     * 🔴 语义是「该错误码在幂等路径上是否值得重试」，不是「send() 是否可重试」。send() 永不重试（非幂等），
     *    SdkLarkGateway.send() 内禁止调用本函数（GatewayDispatcherTest 可加 grep 断言）。
     * 穷尽 when（无 else）：24 值 = 7 true + 17 false（gateway-contract.md §6.3.4），编译器兜底穷尽性。
     */
    fun isRetryable(e: GatewayErrorCode): Boolean = when (e) {
        // 可重试：幂等路径（卡片更新按 sequence 幂等 / 资源下载幂等）
        GatewayErrorCode.RateLimited,
        GatewayErrorCode.CardSequenceConflict,
        GatewayErrorCode.CardTooLarge,
        GatewayErrorCode.CardEntityExpired,
        GatewayErrorCode.ConnectRetryable,
        GatewayErrorCode.DownloadFailed,
        GatewayErrorCode.DownloadTooLarge -> true
        // 🔴 不可重试：非幂等（发消息会重复投递）或重试无意义
        GatewayErrorCode.SendFailed,
        GatewayErrorCode.SendTimeout,
        GatewayErrorCode.FormatError,
        GatewayErrorCode.TargetRevoked,
        GatewayErrorCode.PermissionDenied,
        GatewayErrorCode.UploadFailed,
        GatewayErrorCode.SsrfBlocked,
        GatewayErrorCode.CardStreamBusy,
        GatewayErrorCode.CardJsonInvalid,
        GatewayErrorCode.ConnectFatal,
        GatewayErrorCode.BlockedByPeerInstance,
        GatewayErrorCode.NotConnected,
        GatewayErrorCode.ReactionFailed,
        GatewayErrorCode.RegisterQrExpired,
        GatewayErrorCode.RegisterInvalidArgument,
        GatewayErrorCode.RegisterFailed,
        GatewayErrorCode.Unknown -> false
    }
}
