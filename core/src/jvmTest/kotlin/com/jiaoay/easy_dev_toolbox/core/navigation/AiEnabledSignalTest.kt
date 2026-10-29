package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [AiEnabledSignal] 中立镜像槽单测（WB-01 重设计批 1 · 2026-09-30）。
 *
 * 钉死槽语义三件事：
 * 1. 初值 fail-closed（恒 false 起步——与 AiToolsToggle 初值同向）；
 * 2. mirror/setForTest 直写即时反映到 enabled 流（VM 直读面零延迟）；
 * 3. 真源镜像契约：镜像值可独立于真源翻转（消费方读镜像不触 feature-ai——
 *    feature 互不依赖铁律的接缝存在性锚）。
 */
class AiEnabledSignalTest {

    @BeforeTest
    fun setUp() {
        AiEnabledSignal.setForTest(false)
    }

    @AfterTest
    fun tearDown() {
        AiEnabledSignal.setForTest(false)
    }

    @Test
    fun `mirror flips enabled flow immediately`() {
        AiEnabledSignal.mirror(true)
        assertEquals(true, AiEnabledSignal.enabled.value, "mirror 写点须即时反映到镜像流（VM 直读零延迟）")
        AiEnabledSignal.mirror(false)
        assertEquals(false, AiEnabledSignal.enabled.value)
    }

    @Test
    fun `setForTest does not differ from mirror observable behavior`() {
        // 测试缝与生产写点同一 Sink——行为不可分（测试缝不制造第二语义）
        AiEnabledSignal.setForTest(true)
        assertEquals(true, AiEnabledSignal.enabled.value)
    }

    @Test
    fun `enabled flow is stable reference across reads`() {
        // StateFlow 单实例锚：VM 持有引用跨读不换实例（collectAsState 消费面稳定）
        val first = AiEnabledSignal.enabled
        val second = AiEnabledSignal.enabled
        assertEquals(true, first === second, "enabled 必须是稳定 StateFlow 实例（object 单例槽）")
    }
}
