package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodeBounds
import com.jiaoay.easy_dev_toolbox.widget.PageHeader
import com.jiaoay.easy_dev_toolbox.widget.device.DeviceStatusBar
import kotlin.test.Test
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.DeviceState

/**
 * 页头装配宽度分配守护（标题压零事故的回归防线）：
 * [PageHeader] = `Row { Text(标题, weight(1f)); actions() }`——actions 先测、
 * weighted 标题后测。actions 内若出现任一 fill=true 的 weighted 子元素（如
 * [DeviceStatusBar] 状态文字被改 `weight(1f)` 吃满），会把整个动作槽宽吃光、
 * 标题配额归零（无头实证：标题 size=0×113）。守护断言：
 * 1. 标题在 1600px 场景下保有 ≥1200px 宽（标题保位）；
 * 2. 设备状态条保持内容宽（状态文字 <800px——inline-flex 胶囊语义）；
 * 3. 刷新钮贴胶囊右缘（≥1500px）。
 *
 * 🔴 Compose Row 尺寸语义（无头双盲实证）：weighted 子元素 fill=false → Row
 * 保持内容宽；fill=true → Row 拉满 max。改 DeviceStatusBar 状态文字的 weight
 * 形态前先读本守护。
 */
class DeviceObsHeaderLayoutGuardTest {

    private fun textNode(t: String): (SemanticsNode) -> Boolean = { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true
    }

    private fun descNode(d: String): (SemanticsNode) -> Boolean = { n ->
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == d } == true
    }

    @Test
    fun `page header title keeps width with device status bar in actions`() {
        SceneGuardHandle(width = 1600, height = 400).use { handle ->
            handle.setContent {
                PageHeader(title = "设备观测") {
                    DeviceStatusBar(
                        deviceState = DeviceState.None,
                        detectError = null,
                        detecting = false,
                        selectedSerial = null,
                        onRefresh = {},
                        onSelectDevice = {},
                        onPickDevice = {},
                        onClearSelection = {},
                    )
                }
            }
            handle.renderPixels()
            val title = handle.semanticsNodeBounds(matcher = textNode("设备观测"))
            val status = handle.semanticsNodeBounds(matcher = textNode("未连接 · 插入设备后将自动检测"))
            val refresh = handle.semanticsNodeBounds(matcher = descNode("刷新设备"))
            assertTrue(
                title.width >= 1200f,
                "页头标题必须保有宽度（${title.width}px < 1200）——actions 内出现 fill=true 的 " +
                    "weighted 子元素把动作槽宽吃光（标题压零事故回潮）",
            )
            assertTrue(
                status.width < 800f,
                "设备状态条必须保持内容宽胶囊（状态文字 ${status.width}px ≥ 800——被拉满即标题保位被破）",
            )
            assertTrue(
                refresh.right >= 1500f,
                "刷新钮应贴状态条右缘（right=${refresh.right} < 1500）",
            )
        }
    }
}
