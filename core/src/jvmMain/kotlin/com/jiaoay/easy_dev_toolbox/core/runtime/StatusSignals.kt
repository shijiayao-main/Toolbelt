package com.jiaoay.easy_dev_toolbox.core.runtime

import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.TimingConstants
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 全局状态信号面（调查工作台 S5b——自宿主 `host/StatusHost` object 状态面下沉 :core）。
 *
 * ## 为何下沉
 * feature 模块（feature-quest 等）不得 import 宿主包——完成通知「running 下降沿 +
 * 当前页 ≠ 会话页」的判定在 feature VM 内，toast 通道必须落在双方可见的 :core。
 * 宿主渲染壳（StatusHostOverlay）改引本 object；宿主侧全部门面调用同步迁移。
 *
 * ## 形态（单槽 → 三槽堆叠）
 * - toast 列表 StateFlow（[Dimens.toastMaxStack] 封顶，空表 = 无渲染）；
 * - show：成功/确认类（[TimingConstants.SUCCESS_BANNER_MS] 后自动消退）；
 * - showUndo：L1 可撤销（撤销窗口 [TimingConstants.UNDO_WINDOW_MS]）；
 * - show/showUndo = **append 到列表尾**（新到在下），超槽位挤掉最旧——连续操作
 *   （复制 A → 保存 B）先到反馈不再被顶掉（单槽后到顶掉先到即本卡要修的丢失）；
 * - dismiss(id)：按 id 精确移除目标条（渲染壳逐条关闭钮/撤销/到点消退传 id）。
 *
 * ## 计时器归属
 * 自动消退计时器**留在渲染壳的 LaunchedEffect**（组合作用域 delay 循环 + hover 冻结）
 * ——不下沉进 object：零 scope 争议（AppHost.scope 只做状态面以外的 App 级回路）、
 * 零 AppHost 依赖、hover 暂停语义原样保留。本 object 零协程。
 */
object StatusSignals {

    /**
     * Toast 载荷（撤销槽 payload）。
     * public：下沉 :core 后渲染壳在宿主模块读 [toast]——internal 会编译红
     *    （原 host 包 internal 的可见性墙随下沉失效）。
     *
     * [id]：堆叠槽内定位锚——dismiss(id) 精确移除、渲染壳 key/remember 逐条
     * 独立。自增 AtomicLong 生成（System.nanoTime 理论可撞，荐计数器）。
     */
    data class ToastEntry(
        val id: Long,
        val text: String,
        val undoLabel: String?,
        val onUndo: (() -> Unit)?,
        val dismissAt: Long,
    )

    private val nextId = AtomicLong(0L)

    private val _toast = MutableStateFlow<List<ToastEntry>>(emptyList())

    /** 当前 Toast 堆叠（头 = 最旧，尾 = 最新；空表 = 无渲染） */
    val toast: StateFlow<List<ToastEntry>> = _toast.asStateFlow()

    /** 显示成功/确认类 Toast（[TimingConstants.SUCCESS_BANNER_MS] 后自动消退） */
    fun show(text: String) {
        append(
            ToastEntry(
                id = nextId.getAndIncrement(),
                text = text,
                undoLabel = null,
                onUndo = null,
                dismissAt = System.currentTimeMillis() + TimingConstants.SUCCESS_BANNER_MS,
            )
        )
    }

    /**
     * 显示可撤销 Toast（L1 配方）：[TimingConstants.UNDO_WINDOW_MS] 撤销窗口；
     * [onUndo] 由调用方传入恢复逻辑。
     */
    fun showUndo(text: String, onUndo: () -> Unit) {
        append(
            ToastEntry(
                id = nextId.getAndIncrement(),
                text = text,
                undoLabel = "撤销",
                onUndo = onUndo,
                dismissAt = System.currentTimeMillis() + TimingConstants.UNDO_WINDOW_MS,
            )
        )
    }

    /** 手动关闭单条（× 承接/撤销回调/到点消退——按 id 精确移除目标条） */
    fun dismiss(id: Long) {
        _toast.update { current -> current.filterNot { it.id == id } }
    }

    /** 测试缝：清空堆叠（单测隔离用——toast 断言的前置清场） */
    fun clearForTest() {
        _toast.value = emptyList()
    }

    /** append + 超槽位挤最旧（[Dimens.toastMaxStack] 封顶；CAS update 保并发 append 不丢条） */
    private fun append(entry: ToastEntry) {
        _toast.update { current ->
            (current + entry).takeLast(Dimens.toastMaxStack)
        }
    }
}
