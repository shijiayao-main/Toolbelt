package com.jiaoay.easy_dev_toolbox.core.runtime

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 调查报告外发通道中立槽（生产侧 = 日志工作台报告面板「送飞书」动作；
 * 消费侧 = 桥接宿主门面在生命周期启动时 [wire]）。
 *
 * ## 为什么需要本槽
 * feature 互不依赖铁律：报告动作在 feature-logworkbench，桥接门面在
 * feature-larkbridge，两侧唯一的共同可见层是 :core——通道接口必须落在
 * 双方可见的中立层（TaskPipelineHandoff 同款配方：契约在 :core，宿主接线）。
 * 桥接域类型（SendResult/出站结果）不进 :core——[Channel.deliver] 以
 * 人话原因字符串承载失败（null = 已送出），桥接域细节止于接线实现方。
 *
 * ## 线程与生命周期契约
 * - [wire] 由宿主参与者在 App 生命周期启动单点调用一次；[deliver] 从任意协程调用；
 * - [ready] 是置灰判据真源：接线方经 [publishReady] 发布桥接运行态
 *   （未接线 = 恒 false——消费面据此置灰并明示原因，不静默）；
 * - [publishReady] 与 [wire] 同属宿主接线面；本槽不自建协程、不持有 scope。
 */
object ReportDelivery {

    /**
     * 出站通道契约（桥接域实现方提供）。
     * [deliver] 返回 null = 已送出；非 null = 失败人话（含未运行/未配置会话/出站拒绝/
     * 传输失败——由实现方归一，消费面直接展示）。
     */
    fun interface Channel {
        suspend fun deliver(file: File): String?
    }

    @Volatile
    private var channelRef: Channel? = null

    private val _ready = MutableStateFlow(false)

    /** 通道就绪态（桥接运行态镜像；未接线/未运行 = false——置灰判据唯一真源） */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 宿主接线（null = 摘除；重复调用以后值为准） */
    fun wire(channel: Channel?) {
        channelRef = channel
        if (channel == null) publishReady(false)
    }

    /** 就绪态发布（接线方桥接运行态收集回路的单写点；未接线发布无意义恒 false） */
    fun publishReady(ready: Boolean) {
        _ready.value = ready
    }

    /**
     * 送出报告产物。未接线时返回人话失败（消费面恒可调用，无需自判接线态）。
     *
     * 为什么不能写成 `channelRef?.deliver(file) ?: 人话失败`：[Channel.deliver] 的
     * null 是「已送出」成功语义，elvis 会把成功吞成失败——先判接线再转发。
     */
    suspend fun deliver(file: File): String? {
        val channel = channelRef ?: return "报告外发通道不可用（桥接未接入）"
        return channel.deliver(file)
    }

    /** 测试缝：清空接线并复位就绪态（单测隔离用） */
    fun clearForTest() {
        channelRef = null
        _ready.value = false
    }
}
