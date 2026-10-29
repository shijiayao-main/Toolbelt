package com.jiaoay.easy_dev_toolbox.core.runtime

import java.io.File

/**
 * 语音转写能力中立槽（生产侧 = feature-voice 转写内核，经宿主装配根适配注入；
 * 消费侧 = feature-larkbridge 桥接语音消息转写缝）。
 *
 * ## 为什么需要本槽
 * feature 互不依赖铁律：转写内核在 feature-voice，桥接装配在 feature-larkbridge，
 * 两侧唯一的共同可见层是 :core——转写契约必须落在双方可见的中立层
 * （ReportDelivery 同款配方：契约在 :core，宿主接线，feature 间不直连）。
 * 桥接域类型不进 :core——[Result] 以三态中立结果承载，桥接侧自行映射回其转写缝契约。
 *
 * ## 线程与生命周期契约
 * - [wire] 由宿主装配根（composeApp main）调用一次，恒先于任何桥接装配
 *   （装配根先于页面组合执行）；重复调用以后值为准，null = 摘除；
 * - [port] 从任意协程调用；未接线返回 null ⇒ 消费方按「能力缺席」降级，不炸主链路；
 * - 本槽不自建协程、不持有 scope；转写执行跑在消费方的任务协程内。
 */
object VoiceTranscriptionHub {

    /**
     * 转写端口契约（宿主把转写内核适配成本接口后注入）。
     * [transcribe] 挂起；CancellationException 必须原样上抛（吞取消会让消费方任务永不结束）；
     * 失败一律映射为 [Result] 三态返回，不靠异常传状态。
     */
    fun interface Port {
        suspend fun transcribe(audio: File): Result
    }

    /** 转写三态结果：成功文本 / 能力缺席（引导人话）/ 执行失败（人话原因）。 */
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Unavailable(val guidance: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    @Volatile
    private var portRef: Port? = null

    /** 宿主接线（null = 摘除）。 */
    fun wire(port: Port?) {
        portRef = port
    }

    /** 当前端口（未接线 = null——消费方据此走「能力缺席」降级路径）。 */
    fun port(): Port? = portRef
}
