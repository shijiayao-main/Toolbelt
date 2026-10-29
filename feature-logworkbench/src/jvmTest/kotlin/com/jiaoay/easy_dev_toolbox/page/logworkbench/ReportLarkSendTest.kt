package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 报告「送飞书」动作面测试：
 * - 置灰门控 = [larkSendDisabledReason] 纯函数真值表（桥接未运行必须置灰**并明示原因**——
 *   禁静默置灰的既有按钮纪律）；
 * - 通道契约 = :core [ReportDelivery] 中立槽（就绪态真源 + deliver 路由 + 未接线人话失败）；
 * - 接线守护 = 页面装配点与面板按钮的源码切片锚（功能面无 Compose 运行时，
 *   源码切片是渲染接线的一等守护形态——WorkbenchReportPanelRenderGuardTest 同族配方）。
 *
 * 真机飞书端到端收达不在本测试面（用户门禁项）——传输层语义由 :lark-bridge 的
 * BridgeSendFileTest 背书（出站排除表 + 两步式上传）。
 */
class ReportLarkSendTest {

    @BeforeTest
    fun setUp() {
        ReportDelivery.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        ReportDelivery.clearForTest()
    }

    // ── 置灰门控（真值表：判据序即文案序）──

    @Test
    fun `桥接未运行 ⇒ 置灰并明示原因（禁静默置灰）`() {
        val reason = larkSendDisabledReason(running = false, larkDeliveryReady = false)
        assertEquals(
            "飞书桥接未运行，请先在飞书桥接页启动桥接",
            reason,
            "桥接未运行时按钮必须置灰且带可操作指引文案",
        )
    }

    @Test
    fun `研判运行中先于桥接态判（半成品报告外发即废稿）`() {
        assertEquals(
            "研判运行中，结束后可发送",
            larkSendDisabledReason(running = true, larkDeliveryReady = false),
            "运行中判据先于桥接态——两条置灰态共存时文案不得漂移",
        )
        assertEquals(
            "研判运行中，结束后可发送",
            larkSendDisabledReason(running = true, larkDeliveryReady = true),
            "桥接在跑但研判未收尾 ⇒ 仍置灰（发送前恒重新导出，半成品必拒）",
        )
    }

    @Test
    fun `双绿 ⇒ null（可发送，无置灰原因）`() {
        assertEquals(
            null,
            larkSendDisabledReason(running = false, larkDeliveryReady = true),
            "研判收尾 + 桥接运行 ⇒ 按钮可点",
        )
    }

    // ── :core 中立槽契约（就绪态真源 + deliver 路由）──

    @Test
    fun `就绪态真源随 publishReady 发布，初始与复位恒 false`() {
        assertFalse(ReportDelivery.ready.value, "未接线时置灰判据必须恒 false")
        ReportDelivery.publishReady(true)
        assertTrue(ReportDelivery.ready.value, "接线方发布运行态后置灰判据放行")
        ReportDelivery.clearForTest()
        assertFalse(ReportDelivery.ready.value, "测试缝复位后回到初始 false")
    }

    @Test
    fun `deliver 路由到已接线通道，人话原因逐字透传`() {
        val file = File("report-run1.md")
        ReportDelivery.wire { f ->
            assertEquals(file, f, "消费面传入的产物路径必须逐字到达通道实现")
            "通道实现的人话失败"
        }
        assertEquals(
            "通道实现的人话失败",
            runBlocking { ReportDelivery.deliver(file) },
            "通道实现的失败人话必须原样透传给消费面",
        )
        ReportDelivery.wire { null }
        assertEquals(
            null,
            runBlocking { ReportDelivery.deliver(file) },
            "通道返回 null = 已送出——消费面据此发成功回执",
        )
    }

    @Test
    fun `未接线 ⇒ deliver 返回人话失败而非抛异常`() {
        val reason = runBlocking { ReportDelivery.deliver(File("report-run2.md")) }
        assertTrue(
            reason != null && reason.contains("不可用"),
            "未接线必须归一人话失败（恒可调用，不炸消费面），实际=$reason",
        )
    }

    // ── 接线守护（源码切片锚）──

    @Test
    fun `页面装配点接通就绪态与送出动作`() {
        val source = logWorkbenchPageSource()
        assertTrue(
            "onSendToLark = vm::sendCurrentReportToLark" in source,
            "LogWorkbenchPage 丢失「送飞书」动作接线（onSendToLark = vm::sendCurrentReportToLark）",
        )
        assertTrue(
            "ReportDelivery.ready.collectAsState()" in source,
            "LogWorkbenchPage 丢失报告外发就绪态消费（置灰判据真源 = ReportDelivery.ready）",
        )
    }

    @Test
    fun `报告面板按钮带置灰判据与文案消费`() {
        val source = reportPanelSource()
        assertTrue(
            "label = \"送飞书\"" in source,
            "报告面板丢失「送飞书」按钮（导出动线之上的直发出口）",
        )
        assertTrue(
            "disabledReason = larkSendDisabledReason(running = running, larkDeliveryReady = larkDeliveryReady)" in source,
            "「送飞书」按钮必须消费置灰判据纯函数（禁静默置灰）",
        )
        assertTrue(
            "enabled = !running && larkDeliveryReady" in source,
            "「送飞书」按钮就绪判据必须双闸（研判收尾 + 桥接运行）",
        )
    }

    // ── 源码定位（settings.gradle.kts 向上寻根——既有同族配方）──

    private fun logWorkbenchPageSource(): String =
        source("feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt")

    private fun reportPanelSource(): String =
        source("feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchReportPanel.kt")

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, path)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$path")
        return file.readText()
    }
}
