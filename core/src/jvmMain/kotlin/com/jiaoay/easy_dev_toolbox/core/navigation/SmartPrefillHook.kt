package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 剪贴板智能识别预填槽（palette 推荐行 → 目标工具页输入区的载荷通道）。
 *
 * 预填不自动执行（违反即产生「复制即执行」误动作）：载荷只把内容送进目标页
 * 输入区，任何「会动任务/写盘」的落地必须由用户显式动作触发后才发生。
 *
 * - **生产侧**（palette 推荐行，宿主域）：[offer] 写入（后到覆写——单载荷面，
 *   新推荐取代旧推荐）+ 宿主自行跳页；跳页动作不经本槽（与 [GoXLogDecryptHook]
 *   的 wire 回调分流不同——palette 本就在宿主域，可直接导航）；
 * - **消费侧两形态**（按目标页输入区形态分流，槽配方族纪律见
 *   docs/pages/navigation.md「一次性消费槽与页面驻留」节）：
 *   - [Payload.EpochValue] / [Payload.JsonText] / [Payload.Base64Text] → 目标页
 *     **输入框/粘贴区**形态（时间戳页 / JSON 工具页 / Base64 提取页）：标准双
 *     effect 读即清消费（组合期初值承载 + 在页订阅），预填只写输入框、求值/
 *     嗅探由页面既有纯显示链承担（无自动执行面）；
 *   - [Payload.XLogFilePath] → XLog 解密页**任务卡**形态：入卡即按拖放语义立即
 *     提交任务（= 执行），故载荷**驻槽不读走**，页面按 [pending] 渲染「待确认」
 *     行，用户点「入卡解析」/「忽略」时才 [consumeIfType] 消费——确认点击即
 *     用户显式动作，满足上方红线。
 */
object SmartPrefillHook {

    /** 预填载荷（typed——消费页按类型认领，异型载荷不互吞） */
    sealed interface Payload {

        /** 时间戳页输入框载荷：epoch 秒/毫秒整串原文 */
        data class EpochValue(val value: String) : Payload

        /** XLog 解密页待确认载荷：`.xlog` / `.alog` 文件路径原文 */
        data class XLogFilePath(val path: String) : Payload

        /** JSON 工具页输入框载荷：JSON 对象原文（覆盖语义 = 覆写——输入框本是一次性粘贴区） */
        data class JsonText(val value: String) : Payload

        /** Base64 提取页粘贴区载荷：base64 token 原文（文本/图片 token 同面承载，产物分流在页内既有嗅探链） */
        data class Base64Text(val value: String) : Payload
    }

    private val _pending = MutableStateFlow<Payload?>(null)

    /** 待消费载荷（只读面；XLog 待确认行按本流渲染，消费仍走 [consumeIfType]） */
    val pending: StateFlow<Payload?> = _pending.asStateFlow()

    /** 写入（后到覆写前值；palette 推荐行点击点——无预填面的识别不写槽） */
    fun offer(payload: Payload) {
        _pending.value = payload
    }

    /**
     * 类型匹配才取走（读即清）：异型载荷返回 null 且槽不动——时间戳页与 XLog 页
     * 共用单槽不互吞对方载荷。同一载荷只被首个匹配消费方取走。
     */
    inline fun <reified T : Payload> consumeIfType(): T? = consumeIfTypeImpl(T::class) as? T

    @PublishedApi
    internal fun consumeIfTypeImpl(type: KClass<out Payload>): Payload? {
        val current = _pending.value ?: return null
        if (current::class != type) return null
        _pending.value = null
        return current
    }

    /** 测试缝：清空残留载荷（单测隔离用） */
    fun clearForTest() {
        _pending.value = null
    }
}
