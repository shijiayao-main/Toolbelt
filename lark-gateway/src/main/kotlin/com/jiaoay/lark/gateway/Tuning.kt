// :lark-gateway 调优常量层；本文件禁 SDK 类型、禁 var、禁从环境/配置读。
// 本节全部常量不进用户配置文档（已否决「把 SDK 调优旋钮暴露进配置」）。
package com.jiaoay.lark.gateway

/**
 * 公开窄面：引擎合法需要的 4 个协议常量。RateGovernor（:lark-bridge 侧）读这里。
 * 其余 26 个常量保持 internal 的 [Tuning]，不公开（避免 :lark-bridge 复制数值，也避免全公开）。
 */
object GatewayLimits {
    /** 同群 5 QPS 硬限制，留 20% 余量 → 4 */
    const val perChatRps: Int = 4

    /** 接口级 50/s 硬限制，留 20% 余量 → 40 */
    const val globalRps: Int = 40

    /** 单条消息文本上限（出站分片阈值，属 OutboundConfig） */
    const val textChunkLimit: Int = 3500

    /** 单卡 30KB（30720B）硬限制，留 6144B（6KB）给 JSON 转义膨胀（按 UTF-8 字节算）→ 24576 */
    const val cardByteBudget: Int = 24576
}

/**
 * 网关内部调优常量（26 + 2 个，gateway-contract.md §15.2 M-9）。internal：不是契约面，:lark-bridge 不得引用。
 * 每个常量上方注释写「对应飞书硬限制 + 余量」。
 */
internal object Tuning {
    // ==================== gateway-contract.md §7.1 卡片流式域（6；cardByteBudget 已移入 GatewayLimits）====================

    /** 单卡更新 10 次/秒硬限制；250ms = 4 次/秒，留 60% 余量。🔴 SDK 默认 100（=10/s）零余量，必须覆写。 */
    const val streamThrottleMs: Long = 250L

    /** 一段 markdown 增量达 4000 字符即立即刷。🔴 SDK 默认 50；Throttle.note() 是「或」关系，只调时间无效——漏设则仍按 50 字符即刷、时间窗形同虚设。 */
    const val streamThrottleChars: Int = 4000

    /**
     * 主动 TimeDeadline 轮换已退役（常量仅供 11min 真机实验复现用）：每 9min 强发新卡会刷屏，
     * 服务端关流由被动重建兜底——check() 的 200750 EntityExpired 分支自动换卡续命。
     */
    const val streamSegmentDeadlineMs: Long = 540_000L

    /** sink 协程的 delay 粒度。25ms ⇒ 每秒最多 40 次唤醒，远低于 streamThrottleMs，保证节流窗口精度。 */
    const val sinkPollIntervalMs: Long = 25L

    /**
     * 🔴 重建预算：**一次成功 patch 之前**最多允许的连续重建次数，超限即关闭流式（GIVE_UP 语义）。
     * 没有它，「patch 持续报未登记码/404 而建卡持续成功」会让主循环每 250ms 重建一次——约 4 张
     * 幽灵卡/秒轰进会话。重建成功即说明建卡端点健康，故上限只需盖住「瞬时抖动」而非「持续性病因」。
     * 任一 patch 成功即清零。
     */
    const val maxConsecutiveRebuilds: Int = 3

    /** 重建后的退避（配合 [maxConsecutiveRebuilds]）：把最坏情况下的建卡速率从 ~4/s 压到 ~1/s。 */
    const val streamRebuildBackoffMs: Long = 1_000L

    /** flushTerminal + close 的总预算（十步关闭第 ④ 步「NonCancellable, 3s」）。 */
    const val sinkCloseTimeoutMs: Long = 3_000L

    /** 流式卡 markdown 元素 id。SDK OutboundSender 硬编码同值；不是契约面（下游不可依赖）。 */
    const val streamElementId: String = "stream_md"

    // ==================== gateway-contract.md §7.2 频控域（3；perChatRps/globalRps 已移入 GatewayLimits）====================

        /** 限流 reset 响应头名。真机已确认该头缺失（30 条文本零 429/零 11232），SDK 亦不读它——保留常量供防御性读取，实际退避走 RateGovernor 固定阶梯。 */
    const val rateLimitResetHeader: String = "x-ogw-ratelimit-reset"

    /** reset 头缺失时的兜底退避基数（指数：1s → 2s → 4s，上限 rateLimitBackoffMaxMs） */
    const val rateLimitBackoffMs: Long = 1_000L

    /** 退避上限，避免整点限流时无限退避 */
    const val rateLimitBackoffMaxMs: Long = 30_000L

    // ==================== gateway-contract.md §7.3 出站与 SDK 配置域（10；textChunkLimit 已移入 GatewayLimits）====================

    /** 🔴 关掉 SDK 盲指数退避（默认 3 / baseDelayMs=500 ⇒ 500ms→1.5s→4.5s，用 Thread.sleep 占住 commonPool）。 */
    const val retryMaxAttempts: Int = 1

    /** SDK 默认同值；maxAttempts=1 下不生效，写死只为「SDK 升级改默认值时我方不变」。 */
    const val retryBaseDelayMs: Int = 500

    /** 🔴 不关。网关会替用户下载/上传图片，SSRF 防护是唯一屏障。SDK 默认同值 */
    const val ssrfGuardEnabled: Boolean = true

    /** SDK SafetyConfig 入站去重是第二道网（第一道是我方 MessageDeduper LRU 4096 + TTL 12h）。SDK 默认同值 */
    const val dedupMaxEntries: Int = 5000

    /** 入站去重 TTL 12h。SDK 默认同值 */
    const val dedupTtlMs: Long = 43_200_000L

    /** 入站去重清扫周期 5min。SDK 默认同值 */
    const val dedupSweepMs: Long = 300_000L

    /** SDK 侧陈旧消息过滤窗口 30min；我方 StaleFilter 是第二道。SDK 默认同值 */
    const val staleMessageWindowMs: Long = 1_800_000L

    /** SDK 处理锁 TTL 5min。SDK 默认同值 */
    const val processingLockTtlMs: Long = 300_000L

    /** 🔴 必须显式覆写（SDK 默认 true）——漏覆写则 600ms 窗口内多条消息被合并、只留最后一条 messageId（消息丢失）。真实 Kotlin 属性名 isChatQueueEnabled。 */
    const val chatQueueEnabled: Boolean = false

    /** chatQueueEnabled=false 下不生效；写死是为了「若有人误开队列，行为可预测」。SDK 默认同值 */
    const val batchTextDelayMs: Int = 600

    // ==================== gateway-contract.md §7.4 连接与生命周期域（7；downloadMaxBytes/downloadSingleFlight 在 GatewaySdkSettings）====================

    /** 🔴 SDK 内部硬编码 LarkChannel.awaitWebSocketReady(15000)，我方无法覆写；connect() 的 withTimeoutOrNull 必须 > 15s。 */
    const val wsHandshakeTimeoutMs: Long = 15_000L

    /** connect() 整体预算（> wsHandshakeTimeoutMs + 5s 余量） */
    const val connectTimeoutMs: Long = 20_000L

    /** disconnect() 预算；十步关闭第 ⑤ 步「gateway.disconnect()(5s)」 */
    const val disconnectTimeoutMs: Long = 5_000L

    /** close() 预算（反射路径删除后只剩 wsInboundTransport.close + channel 断连）；十步关闭第 ⑥ 步。 */
    const val closeTimeoutMs: Long = 5_000L

    /** SDK handler → intake 的 Channel 容量。溢出 ⇒ GatewaySignal.Dropped("inbound", n)。 */
    const val inboundChannelCapacity: Int = 512

    /** 信号 Channel 容量。溢出 ⇒ Dropped("signal", n)；🔴 Dropped 自身溢出只累加计数器不递归发信号——递归即信号自我放大 */
    const val signalChannelCapacity: Int = 256

    /**
     * 引用文件下载的 API 路径。
     * 🔴 与 SDK `MessageResource.get()` 内部逐字一致（改一字即打错端点、下载恒 404），`:message_id`/`:file_key` 由 `ReqTranslator`
     * 依 `GetMessageResourceReq` 的 `@Path` 注解替换（`type` 是 `@Query`）。
     */
    const val messageResourcePath: String = "/open-apis/im/v1/messages/:message_id/resources/:file_key"

    /** 下载成功状态码（200 = 整文件；分片循环中遇 200 = 服务器忽略 Range 的降级形态）。 */
    const val httpOk: Int = 200

    /** 分片成功状态码（Partial Content）。 */
    const val httpPartialContent: Int = 206

    /**
     * 🔴 **测试专用**假 token：`SdkDownloadTransportTest` 注入假 `IHttpTransport` 时用它绕开 token 往返
     * （否则 `ReqTranslator.getToken` 会先打一次 tenant_access_token 接口、把假 transport 抢掉）。
     * 生产路径**绝不使用**此常量（`httpTransportOverride == null` 时不设手动 token）——生产误用即所有请求带假 token、全部被拒。
     */
    const val testAccessToken: String = "t-test-only-token"

    /**
     * 非 200 响应体进日志/信号的截断上限：飞书错误体是短 JSON（`{"code":…,"msg":…}`），
     * 截断只为防异常放大时日志膨胀。
     */
    const val bodySnippetMax: Int = 512

    // ==================== gateway-contract.md §7.5 表情回应域 ====================

    /** 表情回应单页大小（飞书 list reactions 上限 50，取满）。 */
    const val reactionsPageSize: Int = 50

    /** 表情回应翻页上界（防病态服务器空转；正常一页 50 条足够，40 页 = 2000 条回应兜底）。 */
    const val reactionsMaxPages: Int = 40
}
