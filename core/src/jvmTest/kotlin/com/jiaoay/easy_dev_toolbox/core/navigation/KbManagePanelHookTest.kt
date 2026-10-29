package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * 知识库管理面板深链载荷槽槽层语义：
 * 1. offer 幂等置位 + consume 读即清一次性（落签初值承载路径的前提）；
 * 2. 在签新载荷经槽流即时承载（订阅面消费前提——宿主导航同值不发射时不可只靠
 *    初值承载）+ 订阅面消费读即清（无迟发残留）；
 * 3. 已起播订阅者即时收到在签新载荷（流发射非「下次组合才见」），消费后槽清空。
 */
class KbManagePanelHookTest {

    @Test
    fun `offer is idempotent and consume is read-once`() {
        KbManagePanelHook.clearForTest()
        assertFalse(KbManagePanelHook.consume(), "空槽消费 = false（无展开载荷）")
        KbManagePanelHook.offer()
        KbManagePanelHook.offer()
        assertTrue(KbManagePanelHook.consume(), "重复 offer 幂等置位（「到达即展开」语义，不叠加）")
        assertFalse(KbManagePanelHook.consume(), "读即清（一次性载荷——面板已展开后再次组合不复活旧载荷）")
        KbManagePanelHook.clearForTest()
    }

    @Test
    fun `new payload offered while panel stays composed is carried by the flow and consumed read-once`() {
        // 在签场景的槽层语义（KbManagePanelContent 订阅面的契约前提）：用户停在
        // 蒸馏签时 palette 候选点击 offer——宿主导航同值不发射、组合期初值承载
        // 不重跑，新载荷必须经槽流即时承载（订阅面非 false 即消费）且读即清，
        // 滞留即切签返签迟发旧展开。
        KbManagePanelHook.clearForTest()
        assertFalse(KbManagePanelHook.pending.value, "槽初值 = false（无载荷）")

        KbManagePanelHook.offer()
        assertTrue(
            KbManagePanelHook.pending.value,
            "在签 offer 后载荷须经槽流即时可见（订阅面消费前提——不可只靠落签初值承载）",
        )

        assertTrue(KbManagePanelHook.consume(), "订阅面消费须取到在签期间到达的新载荷")
        assertFalse(KbManagePanelHook.pending.value, "订阅面消费后槽须清空（无迟发残留）")
        assertFalse(KbManagePanelHook.consume(), "读即清（一次性载荷——二次取空）")
        KbManagePanelHook.clearForTest()
    }

    @Test
    fun `active subscriber receives in-tab payload immediately and read-once clearing follows`() = runBlocking {
        // 已起播订阅者的实时语义：蒸馏签保持组合期间（订阅 effect 已在 collect），
        // 新 offer 经流发射即时唤醒订阅面——不等待任何重组；订阅面消费即清槽，
        // 同一载荷恰消费一次。
        val received = mutableListOf<Boolean>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) {
            KbManagePanelHook.pending
                .filter { it }
                .collect { value ->
                    received.add(value)
                    // 订阅面消费 = 读即清（collect 用收到的载荷，consume 只负责清槽）
                    KbManagePanelHook.consume()
                }
        }
        try {
            assertFalse(KbManagePanelHook.pending.value, "订阅起播后槽须为空（流初值 false 被过滤不双发）")
            KbManagePanelHook.offer()
            withTimeout(5_000) {
                while (received.isEmpty()) yield()
            }
            assertEquals(listOf(true), received, "在签新载荷须即时到达订阅面（恰一次）")
            assertFalse(KbManagePanelHook.pending.value, "订阅面消费后槽须清空（无迟发残留）")
            assertFalse(KbManagePanelHook.consume(), "读即清（一次性载荷——二次取空）")
        } finally {
            subscriber.cancel()
            KbManagePanelHook.clearForTest()
        }
    }
}
