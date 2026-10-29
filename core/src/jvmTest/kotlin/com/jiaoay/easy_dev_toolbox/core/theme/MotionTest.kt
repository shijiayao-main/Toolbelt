package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Motion token 常量断言（visual-polish design D1）：取值被误改即测试失败，
 * 作为动效时长三档（120/180/240ms 封顶）、stagger 编排延迟与软弹簧规格的回归防线。
 */
class MotionTest {

    @Test
    fun `duration tiers match design values`() {
        assertEquals(120, MotionDurations.fast, "高频轻量反馈应为 120ms")
        assertEquals(180, MotionDurations.standard, "常规状态过渡应为 180ms")
        assertEquals(240, MotionDurations.slow, "大区域变化应为 240ms（封顶值）")
        assertEquals(30, MotionDurations.staggerDelay, "入场编排逐项延迟应为 30ms")
    }

    @Test
    fun `tween specs use duration tokens`() {
        val fast = assertIs<TweenSpec<Int>>(AppMotion.fast<Int>())
        val standard = assertIs<TweenSpec<Int>>(AppMotion.standard<Int>())
        val slow = assertIs<TweenSpec<Int>>(AppMotion.slow<Int>())
        assertEquals(MotionDurations.fast, fast.durationMillis, "fast() 应构造 120ms tween")
        assertEquals(MotionDurations.standard, standard.durationMillis, "standard() 应构造 180ms tween")
        assertEquals(MotionDurations.slow, slow.durationMillis, "slow() 应构造 240ms tween")
    }

    @Test
    fun `staggered spec pairs standard duration with index-scaled delay`() {
        val first = assertIs<TweenSpec<Float>>(AppMotion.staggered<Float>(0))
        assertEquals(MotionDurations.standard, first.durationMillis, "staggered() 时长应走 standard 档")
        assertEquals(0, first.delay, "首项（index=0）不应有入场延迟")

        val third = assertIs<TweenSpec<Float>>(AppMotion.staggered<Float>(2))
        assertEquals(MotionDurations.standard, third.durationMillis, "staggered() 时长应走 standard 档")
        assertEquals(2 * MotionDurations.staggerDelay, third.delay, "延迟应按序号乘 staggerDelay 递增")
    }

    @Test
    fun `soft spring uses medium-low stiffness`() {
        val spec = assertIs<SpringSpec<Float>>(AppMotion.softSpring<Float>())
        assertEquals(Spring.StiffnessMediumLow, spec.stiffness, "softSpring() 应使用 StiffnessMediumLow")
    }
}
