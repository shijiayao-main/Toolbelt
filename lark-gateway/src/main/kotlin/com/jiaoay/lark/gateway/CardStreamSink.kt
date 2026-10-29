// :lark-gateway 流式进度卡 sink 契约；本文件签名禁 SDK 类型（SDK 隔离三重强制之三：可测性）。
// 实现是 LarkCardStreamSink（gateway-contract.md §4，cardkit 四步 + 时间窗节流 + 24KB 预算分段 + 9min 轮换）。
package com.jiaoay.lark.gateway

/**
 * 流式进度卡会话（5 成员）。🔴 每次流式会话新建卡片实体，绝不复用 card_id（卡片实体仅可发送一次、14 天有效）。
 * 契约级约束：streaming == true 而 buttons.isNotEmpty() 时 LarkOutboundMapper 抛 IllegalArgumentException。
 *
 * ## 滚动窗口语义
 * 🔴 [tryReplace] 是**全量替换**语义——卡片内容恒等于调用方给出的最新窗口快照，不累积历史。
 *    底层 patchElement 本就是全量替换元素内容，实现侧零改动成本；追加语义会造成卡上内容重复。
 */
interface CardStreamSink {
    /** 流是否仍可写。false 的三种成因：已 close / 建卡失败 / 已发终态卡。 */
    val isOpen: Boolean

    /**
     * 把卡片元素内容**整体替换**为 [text]（最新窗口快照）。返回 false 表示「本次未接受」——
     * 调用方不需要重投（内部是 1 槽 conflate，槽内已是更新的快照）。
     * 🔴 非挂起、非阻塞、O(1)：只覆盖内部不可变快照并置脏标志，实际 patch 由内部节流协程发出；
     *    快照比上次 patch 后**更短**同样必须 patch（删除字符也是内容变化）。
     */
    fun tryReplace(text: String): Boolean

    /** 发终态卡：立即 flush（不受节流窗口限制）+ settings 结束流式 + 置 isOpen=false。 */
    suspend fun flushTerminal(card: CardSpec)

    /** 关闭流。若尚未发终态卡，则用当前快照补一次终态（幂等：重复 close 无副作用）。 */
    suspend fun close()

    /** 当前段序号，从 1 开始。每次因 24KB 预算 / 9min 期限 / 200750 / 300317 轮换后 +1。 */
    val segmentIndex: Int

    /**
     * 注册「流终结」回调（close / 建卡失败 / 主循环退出后触发一次）。
     *
     * 用途：消费方（桥接侧 `TrackingReplySink` 的 openStreams 登记）需要在流结束后
     * 把 sink 从登记表**摘除**——否则长跑期间「消息数 × sink」恒定增长
     * （每个 sink 持整段卡片快照 + Job）。对照网关侧 `SdkLarkGateway` 的
     * `sink.job.invokeOnCompletion { openSinks.remove(sink) }`（同语义的既有先例，
     * 只是桥接侧装饰器拿不到具体类型的 job，故提到接口面）。
     *
     * 幂等语义：回调至多触发一次；实现方对 null 回调安全（no-op）。
     */
    fun onFinished(callback: (() -> Unit)?)
}
