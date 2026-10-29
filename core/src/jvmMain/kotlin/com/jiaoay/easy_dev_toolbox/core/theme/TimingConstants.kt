package com.jiaoay.easy_dev_toolbox.core.theme

/**
 * 交互动效**计时器**常量单点（非动画规格）：一次性反馈的自动回收窗口、
 * 复制反馈驻留时长等「行为语义的毫秒值」。
 *
 * 不混入动画档——`AppMotion` 是 120/180/240ms 封顶的**动画档**（AppMotion 工厂），
 *    语义不同的计时器（toast 回收 5000ms、撤销窗口 6000ms）混进动画档体系会
 *    诱导「突破封顶」的错误类推；两者唯一交集是都不可在页面裸写字面量。
 *
 * 各常量的语义出处：
 * - [SUCCESS_BANNER_MS] ← 桥接 OperationBar banner 5s 自动收回
 * - [COPY_FEEDBACK_MS] ← CopyButton 族 1.5s「已复制」
 * - [REJECT_HINT_MS] ← FileDropZone 拒绝态 2.4s 消退
 * - [TOOLTIP_DELAY_MS] ← dock TooltipBox 200ms 显示延迟
 */
object TimingConstants {

    /** 成功类一次性反馈自动消退窗口（StatusHost 成功通道） */
    const val SUCCESS_BANNER_MS = 5_000L

    /** L1 撤销条驻留窗口（hover 暂停；全局 Ctrl+Z 同源） */
    const val UNDO_WINDOW_MS = 6_000L

    /** 复制反馈驻留时长（「已复制」→ 回弹原文案） */
    const val COPY_FEEDBACK_MS = 1_500L

    /** 拖放区全拒拒绝态自动消退时长 */
    const val REJECT_HINT_MS = 2_400L

    /** tooltip 显示延迟（防快速划过闪现） */
    const val TOOLTIP_DELAY_MS = 200L
}
