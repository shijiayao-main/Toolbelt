package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.device.DeviceStatusBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceState

/**
 * 设备状态胶囊（:ui 共享件，宿主消费面）两态场景渲染像素守护：[SceneGuardSupport]
 * 单源配方。注入形态 = 参数直驱（胶囊是状态直入件——deviceState/selectedSerial/
 * detectError 全参数化，无 VM 无 fake，测试侧 mutableStateOf 换态）。宿主形态镜像
 * Shell 工具页的搜索行 Row（M3 Surface 恒 propagateMinConstraints——直挂场景根会被
 * 强制满幅，宿主 Row 先行吸收）。
 *
 * 归属裁决：落 composeApp 测试面而非 :ui——SceneGuardSupport 单源在本源集，:ui 侧
 * 无法 import（跨模块测试源不可见），落 :ui 必然新增 renderPixels 私有复刻、
 * [SceneGuardCopyRatchetTest] 棘轮只降不升即红；本包 [SegmentedGapGuardTest] 已有
 * :ui 微件消费面像素守护先例。源码形态锚（capsule/token/chip 直用）由 :ui 测试面
 * [DeviceStatusBarCapsuleGuardTest] 分工把守，本守护只钉像素呈现面。
 *
 * 验证面（多台 chips 态）：
 * - 胶囊单行成带（chips/文字断行或换行堆叠即红）；
 * - chips 在场差分：多台态比单台态多出内联 chip 内容（label/描边，[countContentPx]
 *   帧差——chips 未进渲染面即差分见底红）；
 * - chips 独立可选：逐台选中后各自呈现选中洗底带（primary×hotWash 合成色
 *   [probeWashColor] 探针 + [countColor] 精确计数——单台 chip 独立呈现/直选链断裂
 *   即该台计数见底红）；清除选择后洗底整体退场（未选定态残留即红）。
 *
 * 验证面（长报错行态）：
 * - 长报错下单行成带 + 错误色主行/提示进渲染面（maxLines=1 单行的像素面）；
 * - 行尾「去设置配置 adb 路径」入口不被长文案挤压：同场景摘除入口差分
 *   （weight(1f, fill=false) 让行尾动作区先测、文字吃剩余宽——入口被挤退场即
 *   差分见底红）。
 *
 * 配色/密度/昼战观感归维护者真机终裁，本守护只钉结构面。
 */
class DeviceStatusBarSceneGuardTest {

    /** 宿主形态镜像 Shell 工具页：胶囊挂搜索行 Row（CenterVertically 定纵位） */
    @Composable
    private fun hostContent(content: @Composable () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }

    @Test
    fun `multi device chips render independently selectable with selected wash`() {
        val devices = listOf(
            DeviceInfo("aaa11111", "ModelA"),
            DeviceInfo("bbb22222", "ModelB"),
            DeviceInfo("ccc33333", "ModelC"),
        )
        // 选中 chip 洗底 = primary×hotWash 叠 bar surfaceDeep（胶囊底实色）
        val selectedWash = probeWashColor(
            base = { LocalToolboxSurfaceColors.current.surfaceDeep },
            wash = { MaterialTheme.colorScheme.primary },
        )
        var state by mutableStateOf<DeviceState>(DeviceState.Multi(devices))
        var selected by mutableStateOf<String?>(null)

        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent {
                hostContent {
                    DeviceStatusBar(
                        deviceState = state,
                        detectError = null,
                        detecting = false,
                        selectedSerial = selected,
                        onRefresh = {},
                        onSelectDevice = {},
                        onPickDevice = { selected = it },
                        onClearSelection = { selected = null },
                    )
                }
            }
            val multiPx = handle.renderPixels()
            val bands = contentRows(multiPx, SCENE_W)
            assertEquals(
                1,
                bands.size,
                "多台态胶囊应单行成带（chips/文字断行即红），实际：$bands",
            )
            assertEquals(
                0,
                countColor(multiPx, selectedWash),
                "未选定时不应有选中洗底色（初始态残留即红）",
            )

            // chips 在场差分：单台基线（同场景换态，帧差只含 chips 面）
            state = DeviceState.Single("aaa11111", "ModelA")
            val singlePx = handle.renderPixels()
            assertTrue(
                countContentPx(multiPx) >= countContentPx(singlePx) + CHIPS_GAIN_MIN,
                "多台态应比单台态多出三枚内联 chip 内容（label/描边差分 < $CHIPS_GAIN_MIN 即 " +
                    "chips 未进渲染面）——多台 ${countContentPx(multiPx)} px / 单台 ${countContentPx(singlePx)} px",
            )

            // chips 独立可选：逐台选中，各自呈现洗底带
            state = DeviceState.Multi(devices)
            for (device in devices) {
                selected = device.serial
                val pickedPx = handle.renderPixels()
                assertTrue(
                    countColor(pickedPx, selectedWash) >= CHIP_WASH_MIN,
                    "选中 ${device.serial} 后该 chip 选中洗底带未进渲染面（计数 " +
                        "${countColor(pickedPx, selectedWash)} < $CHIP_WASH_MIN——独立呈现/直选链断裂）",
                )
            }
            selected = null
            val clearedPx = handle.renderPixels()
            assertEquals(
                0,
                countColor(clearedPx, selectedWash),
                "清除选择后选中洗底应整体退场（未选定态残留即红）",
            )
        }
    }

    @Test
    fun `long detect error keeps single line and settings entry`() {
        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }
        // 长报错载荷：远超胶囊可容纳宽（ellipsis 兜底路径的像素面前提）
        val longError = buildString {
            repeat(20) { append("adb daemon refused the connection attempt and gave up retrying; ") }
        }
        var settingsWired by mutableStateOf(true)

        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent {
                hostContent {
                    DeviceStatusBar(
                        // 失败态下设备态已坍缩 None（detectError 是唯一失败信号源）
                        deviceState = DeviceState.None,
                        detectError = longError,
                        detecting = false,
                        selectedSerial = null,
                        onRefresh = {},
                        onSelectDevice = {},
                        onPickDevice = {},
                        onClearSelection = {},
                        onGoSettings = if (settingsWired) ({ }) else null,
                    )
                }
            }
            val withEntryPx = handle.renderPixels()
            val bands = contentRows(withEntryPx, SCENE_W)
            assertEquals(
                1,
                bands.size,
                "长报错态应单行成带（换行/主行副行堆叠回归即红），实际：$bands",
            )
            assertTrue(
                countColor(withEntryPx, errorColor) >= ERROR_TEXT_MIN,
                "长报错主行/提示未以错误色进渲染面（计数 ${countColor(withEntryPx, errorColor)} < " +
                    "$ERROR_TEXT_MIN——失败态错误色呈现面缺席）",
            )

            // 行尾入口不被长文案挤压：摘除入口差分（换态帧差只含入口面）
            settingsWired = false
            val withoutEntryPx = handle.renderPixels()
            assertTrue(
                countContentPx(withEntryPx) >= countContentPx(withoutEntryPx) + SETTINGS_ENTRY_GAIN_MIN,
                "长报错下「去设置配置 adb 路径」入口应在场（摘除差分 < $SETTINGS_ENTRY_GAIN_MIN 即 " +
                    "入口被长文案挤压退场）——带入口 ${countContentPx(withEntryPx)} px / " +
                    "摘除 ${countContentPx(withoutEntryPx)} px",
            )
        }
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 120

        /** 三枚 chip（label+描边）对单台基线的内容像素差分下限 */
        private const val CHIPS_GAIN_MIN = 250

        /** 单枚选中 chip 洗底带（容器面减 label 笔画）精确色计数下限 */
        private const val CHIP_WASH_MIN = 100

        /** 失败态错误色主行+提示的精确色计数下限 */
        private const val ERROR_TEXT_MIN = 60

        /** 「去设置配置 adb 路径」入口（labelSmall 文案笔画）的内容像素差分下限 */
        private const val SETTINGS_ENTRY_GAIN_MIN = 200
    }
}
