package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.animation.core.*

/**
 * 动效设计 token：时长档位与动画规格的唯一定义点（`tween(` / `spring(` 全仓只许出现在
 * 本文件——UiArchitectureGuardTest 源码扫描强制，白名单挂 docs/core/theme.md「有据保留」清单）。
 *
 * 三档 120 / 180 / 240ms，240ms 为全应用封顶值：桌面工具键鼠高频操作，
 * 动效只服务于状态认知，不做等待表演；[MotionDurations.staggerDelay] 供入场编排逐项递进，
 * 编排延迟经 [AppMotion.staggered] 收口在本文件，页面代码不得裸写 `tween(delayMillis = ...)`。
 * 动画只做状态过渡；流式输出与日志行零动画（不施加 animateContentSize /
 * animateScrollToItem / 逐行动画——列表高频重组场景会引发重组风暴与掉帧）。
 * 取值表/弹簧规格/设计原则见 docs/core/theme.md「动效设计 token」节。
 */
object MotionDurations {

    /** 高频轻量反馈：hover、图标切换等（120ms） */
    const val fast = 120

    /** 常规状态过渡：面板展开、页面切换等（180ms） */
    const val standard = 180

    /** 大区域变化（240ms，全应用动效时长封顶值） */
    const val slow = 240

    /** 入场编排逐项延迟（30ms） */
    const val staggerDelay = 30

    /** 状态指示呼吸周期（900ms——活动 chip 脉冲；无限循环档，时长只影响单个半周期） */
    const val pulse = 900
}

/**
 * 动画规格工厂：按 [MotionDurations] 档位构造 [FiniteAnimationSpec]。
 *
 * 尺寸变化类（分组展开等）用 [softSpring]——spring 的速度曲线让尺寸插值自然，
 * 且不引入时长概念，避免与三档时长混用。
 */
object AppMotion {

    /** fast 档（120ms）：hover、图标切换等高频轻量反馈 */
    fun <T> fast(): FiniteAnimationSpec<T> = tween(MotionDurations.fast)

    /** standard 档（180ms）：面板展开、页面切换等常规状态过渡 */
    fun <T> standard(): FiniteAnimationSpec<T> = tween(MotionDurations.standard)

    /** slow 档（240ms，封顶值）：大区域变化 */
    fun <T> slow(): FiniteAnimationSpec<T> = tween(MotionDurations.slow)

    /**
     * pulse 呼吸档（900ms 半周期）：状态指示器无限循环呼吸（ActivityChip 绿点脉冲）。
     * 返回 [DurationBasedAnimationSpec] 供 infiniteRepeatable 消费——FiniteAnimationSpec
     * 不满足其类型约束，故独立工厂档（tween 收口纪律在 Motion.kt 内白名单）。
     */
    fun pulse(): DurationBasedAnimationSpec<Float> = tween(MotionDurations.pulse)

    /**
     * stagger 入场规格：standard 档时长 + 按序号递增的入场延迟（index * [MotionDurations.staggerDelay]）。
     *
     * 供一次性入场编排逐项递进（如欢迎页 hero 与提示卡 stagger）：延迟由动画系统在 tween 的
     * delayMillis 内消化，调用方只需单一 visible 状态，无需逐项分批置位；
     * index=0 无延迟。tween 在此收口（Wave 7 守护约束），页面代码不直接构造。
     */
    fun <T> staggered(index: Int): FiniteAnimationSpec<T> = tween(
        durationMillis = MotionDurations.standard,
        delayMillis = index * MotionDurations.staggerDelay,
    )

    /** 软弹簧（StiffnessMediumLow）：分组展开等尺寸变化 */
    fun <T> softSpring(): FiniteAnimationSpec<T> = spring(stiffness = Spring.StiffnessMediumLow)

    /**
     * 玻璃件按压弹簧：dampingRatio 0.75 轻度过冲不振荡，
     * StiffnessMedium 桌面档回弹 ~150ms 内收敛——「液态」手感但无等待感，
     * 与 240ms 封顶的时长纪律正交（spring 无时长概念，softSpring 同一合法通道）。
     * 消费点：dock 图标按压 scale 0.96 释放回弹（chrome 图标专属，不挂列表行/日志行）。
     */
    fun <T> glassSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMedium,
    )

    /** dock 图标按压缩放目标值（glassSpring 回弹专属——theme.md 玻璃动效节既有口径） */
    const val glassPressScale = 0.96f
}
