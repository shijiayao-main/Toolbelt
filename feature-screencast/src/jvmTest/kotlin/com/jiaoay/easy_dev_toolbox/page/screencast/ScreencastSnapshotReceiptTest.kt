package com.jiaoay.easy_dev_toolbox.page.screencast

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 送飞书置灰判据单源回归：[screencastLarkSendDisabledReason] 可点/置灰文案两态
 * （真源 = :core ReportDelivery.ready 单源，消费面 = 独立窗快照回执行）。
 */
class ScreencastSnapshotReceiptTest {

    @Test
    fun `lark send gating consumes ready single source with disabled reason wording`() {
        assertNull(
            screencastLarkSendDisabledReason(larkDeliveryReady = true),
            "就绪态置灰判据须放行（ReportDelivery.ready 单源）",
        )
        val disabledReason = screencastLarkSendDisabledReason(larkDeliveryReady = false)
        assertTrue(
            disabledReason != null && "飞书桥接未运行" in disabledReason,
            "未就绪置灰判据须明示桥接未运行原因（置灰文案单源）：$disabledReason",
        )
    }
}
