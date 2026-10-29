package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.ui.input.key.Key
import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.home.DOCK_ENTRIES
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * P2-B2 热键系统单测：match 匹配表 / stepPage 环形切页 / handler 槽位语义。
 */
class HotkeyHubTest {

    // match/dispatch 共享 object 级 @Volatile 槽位：用例可能写入，退出前恢复现场
    private val savedOpen = HotkeyHub.openCommandPalette
    private val savedClose = HotkeyHub.closeOverlays
    private val savedCycle = HotkeyHub.cyclePage

    @AfterTest
    fun restoreSlots() {
        HotkeyHub.openCommandPalette = savedOpen
        HotkeyHub.closeOverlays = savedClose
        HotkeyHub.cyclePage = savedCycle
    }

    // ── match：键位匹配表（热键表唯一真源的直接背书） ──

    @Test
    fun match_ctrl_k_opens_palette() {
        assertEquals(HotkeyHub.Action.OpenCommandPalette, HotkeyHub.match(ctrl = true, shift = false, key = Key.K))
    }

    @Test
    fun match_ctrl_tab_cycles_page() {
        assertEquals(HotkeyHub.Action.CyclePage, HotkeyHub.match(ctrl = true, shift = false, key = Key.Tab))
    }

    @Test
    fun match_ctrl_w_closes_current_tab() {
        // 桌面惯例（H9）：可关 tab 的键盘通路——此前仅 tab 条 × 钮鼠标可达
        assertEquals(HotkeyHub.Action.CloseCurrentTab, HotkeyHub.match(ctrl = true, shift = false, key = Key.W))
    }

    @Test
    fun hotkey_table_covers_ctrl_w_close_tab() {
        // 键面提示表与 match 同源：Ctrl+W 行必须在表内（帮助卡/设置页经表渲染）
        val row = HotkeyHub.HOTKEY_TABLE.firstOrNull { it.first == "Ctrl+W" }
        assertTrue(row != null, "Ctrl+W 须在 HOTKEY_TABLE（键面提示渲染唯一真源）")
        assertTrue(
            row!!.second.contains("关闭"),
            "Ctrl+W 文案须描述关闭语义——实际：${row.second}",
        )
    }

    // ── Ctrl+M · logcat「标记复现点」热键（抓取中消费，未抓取落空不抢键）──

    @Test
    fun match_ctrl_m_marks_logcat_anchor() {
        assertEquals(HotkeyHub.Action.MarkLogcatAnchor, HotkeyHub.match(ctrl = true, shift = false, key = Key.M))
    }

    @Test
    fun match_ctrl_shift_m_still_marks_logcat_anchor() {
        // 修饰键共同匹配惯例（Ctrl+Shift+K 同款先例——Shift 只影响带方向语义的动作）
        assertEquals(HotkeyHub.Action.MarkLogcatAnchor, HotkeyHub.match(ctrl = true, shift = true, key = Key.M))
    }

    @Test
    fun hotkey_table_covers_ctrl_m_mark_anchor() {
        val row = HotkeyHub.HOTKEY_TABLE.firstOrNull { it.first == "Ctrl+M" }
        assertTrue(row != null, "Ctrl+M 须在 HOTKEY_TABLE（键面提示渲染唯一真源）")
        assertTrue(
            row!!.second.contains("标记"),
            "Ctrl+M 文案须描述标记语义——实际：${row.second}",
        )
    }

    @Test
    fun mark_logcat_anchor_wiring_gates_on_running_and_forwards_engine() {
        // 装配面源码锚（main.kt 注入点——KeyEvent 依赖平台事件构造不可直测 dispatch）：
        // Running 门控（未抓取返回 false 落空不抢键）+ 引擎 markAnchor 转发 + 回执同句
        val main = File(locateProjectRoot(), MAIN_KT_PATH).readText()
        val wiringBlock = main.substringAfter("HotkeyHub.markLogcatAnchor = {")
            .substringBefore("\n    }\n")
        assertTrue(
            "LogcatCaptureStatus.Running" in wiringBlock,
            "markLogcatAnchor 必须以引擎 Running 态为消费门控（未抓取落空不抢键）",
        )
        assertTrue(
            "engine.markAnchor()" in wiringBlock,
            "实现必须转发引擎 markAnchor（与面板钮同语义，不造第二套锚定通路）",
        )
        assertTrue(
            "已标记" in wiringBlock && "停止后点「查看」直达标记处" in wiringBlock,
            "成功回执与面板钮同句（「已标记 X，停止后点查看直达标记处」）",
        )
        assertTrue(
            "暂无可标记的带时间戳行" in wiringBlock,
            "无锚行拒绝回执不得静默（可见动作零反馈即死键体验）",
        )
    }

    @Test
    fun match_ctrl_shift_tab_also_cycles_page() {
        // 修饰键共同匹配：Shift 只决定方向（dispatch 时传给 handler）
        assertEquals(HotkeyHub.Action.CyclePage, HotkeyHub.match(ctrl = true, shift = true, key = Key.Tab))
    }

    @Test
    fun match_esc_without_ctrl_closes_overlays() {
        assertEquals(HotkeyHub.Action.CloseOverlays, HotkeyHub.match(ctrl = false, shift = false, key = Key.Escape))
    }

    @Test
    fun match_esc_with_ctrl_is_noop() {
        // Ctrl+Esc 不在热键表（Windows 系统菜单键冲突面，不接管）
        assertNull(HotkeyHub.match(ctrl = true, shift = false, key = Key.Escape))
    }

    @Test
    fun match_bare_keys_are_noop() {
        assertNull(HotkeyHub.match(ctrl = false, shift = false, key = Key.K))
        assertNull(HotkeyHub.match(ctrl = false, shift = false, key = Key.Tab))
    }

    @Test
    fun match_unmapped_key_is_noop() {
        assertNull(HotkeyHub.match(ctrl = true, shift = false, key = Key.A))
    }

    // ── S2 · Ctrl+J 底面板热键随三舱退役（2026-09-22 维护者裁定：面板与热键一并删）──

    @Test
    fun match_ctrl_j_is_noop_after_bottom_panel_retirement() {
        // Ctrl+J / Ctrl+Alt+J 随 BottomPanel 退役：match 面必须不再命中任何动作
        assertNull(HotkeyHub.match(ctrl = true, shift = false, key = Key.J))
    }

    @Test
    fun hotkey_table_no_longer_covers_bottom_panel_entries() {
        // 键面提示表同步退役：底面板两行不得残留在渲染真源里
        assertTrue(HotkeyHub.HOTKEY_TABLE.none { it.first == "Ctrl+J" || it.first == "Ctrl+Alt+J" })
    }

    // ── P4 · F1 参考卡（无修饰键惯例）──

    @Test
    fun match_bare_f1_opens_hotkey_help() {
        assertEquals(HotkeyHub.Action.OpenHotkeyHelp, HotkeyHub.match(ctrl = false, shift = false, key = Key.F1))
    }

    @Test
    fun match_ctrl_f1_is_noop() {
        // F1 无修饰键惯例（HIG 帮助键——Ctrl+F1 不占）
        assertNull(HotkeyHub.match(ctrl = true, shift = false, key = Key.F1))
    }

    @Test
    fun hotkey_table_covers_f1_help() {
        assertTrue(HotkeyHub.HOTKEY_TABLE.any { it.first == "F1" })
    }

    // ── stepPage：环形切页纯函数 ──
    // S7：Default 枚举已删——用例数据换存量枚举（stepPage 是任意 List<ToolPage> 纯函数）
    // 批一 · 组 B（2026-09-23 导航分工）：order 生产侧 = openTabs 现值——纯函数
    // 签名不动，收窄语义经新增用例覆盖（见下方 openTabs 收窄组）

    @Test
    fun stepPage_forward_wraps_around() {
        val order = listOf(ToolPage.Setting, ToolPage.ShellTools, ToolPage.AppCenter)
        assertEquals(ToolPage.ShellTools, stepPage(order, ToolPage.Setting, backward = false))
        assertEquals(ToolPage.Setting, stepPage(order, ToolPage.AppCenter, backward = false))
    }

    @Test
    fun stepPage_backward_wraps_around() {
        val order = listOf(ToolPage.Setting, ToolPage.ShellTools, ToolPage.AppCenter)
        assertEquals(ToolPage.AppCenter, stepPage(order, ToolPage.Setting, backward = true))
        assertEquals(ToolPage.ShellTools, stepPage(order, ToolPage.AppCenter, backward = true))
    }

    @Test
    fun stepPage_current_not_in_order_returns_null() {
        val order = listOf(ToolPage.Setting, ToolPage.ShellTools)
        assertNull(stepPage(order, ToolPage.AppCenter, backward = false))
    }

    @Test
    fun stepPage_single_page_order_returns_same() {
        val order = listOf(ToolPage.Setting)
        assertEquals(ToolPage.Setting, stepPage(order, ToolPage.Setting, backward = false))
        assertEquals(ToolPage.Setting, stepPage(order, ToolPage.Setting, backward = true))
    }

    // ── handler 槽位语义：未注入时动作命中也不消费（安全降级） ──

    @Test
    fun empty_slot_match_is_noop() {
        HotkeyHub.openCommandPalette = null
        // match 命中 + 槽位空 → 应用侧恒返回 false（dispatch 的 ?: false 链路）
        val action = HotkeyHub.match(ctrl = true, shift = false, key = Key.K)
        val consumed = if (action == HotkeyHub.Action.OpenCommandPalette) {
            HotkeyHub.openCommandPalette?.invoke() ?: false
        } else {
            false
        }
        assertEquals(false, consumed)
    }

    @Test
    fun cycle_slot_consumed_even_when_page_missing() {
        // 恒 consumed 契约：当前页不在顺序表时也不放行裸 Tab（焦点副作用）
        var navigatedTo: ToolPage? = null
        HotkeyHub.cyclePage = { _ ->
            // 模拟 rememberHotkeyActions 内 handler：stepPage 返回 null 时只跳过导航
            val order = listOf(ToolPage.Setting)
            val next = stepPage(order, ToolPage.ShellTools, backward = false)
            if (next != null) navigatedTo = next
            true
        }
        val consumed = HotkeyHub.cyclePage?.invoke(false) ?: false
        assertEquals(true, consumed)
        assertNull(navigatedTo)
    }

    // ── 批一 · 组 B（2026-09-23 导航分工）：Ctrl+Tab 收窄 openTabs + 键面文案同源 ──

    @Test
    fun `ctrl tab hint wording follows open tabs semantics`() {
        // 键面提示表（渲染唯一真源）：Ctrl+Tab 文案 = 已打开 tab 语义
        val ctrlTab = HotkeyHub.HOTKEY_TABLE.first { it.first == "Ctrl+Tab" }
        val ctrlShiftTab = HotkeyHub.HOTKEY_TABLE.first { it.first == "Ctrl+Shift+Tab" }
        assertEquals("下一个已打开文档", ctrlTab.second, "Ctrl+Tab 文案随文档集语义更新（R1 空间/文档分离）")
        assertEquals("上一个已打开文档", ctrlShiftTab.second, "Ctrl+Shift+Tab 文案随文档集语义更新（R1 空间/文档分离）")
    }

    @Test
    fun `ctrl 1-6 hint wording covers six spaces`() {
        // 键面提示表：Ctrl+1..6 文案 = dock 六空间规范名（工作台/智能体中心/
        // Shell 工具/飞书桥接/工具/设备观测）——键面随 dock 空间集同步扩表，dock 集
        // 之外的键不虚标
        val jumpHint = HotkeyHub.HOTKEY_TABLE.first { it.first == "Ctrl+1..6" }
        assertTrue(
            jumpHint.second.contains("工作台/智能体中心/Shell 工具/飞书桥接/工具/设备观测"),
            "Ctrl+1..6 文案须反映 dock 六空间规范名——实际：${jumpHint.second}",
        )
        assertFalse(
            HotkeyHub.HOTKEY_TABLE.any { it.first == "Ctrl+1..9" },
            "Ctrl+1..9 死键宣传（dock 集之外键不虚标）不得回潮",
        )
    }

    @Test
    fun `stepPage over open documents only cycles opened documents`() {
        // 收窄语义验收：order = openTabs 现值（文档集）时，Ctrl+Tab 只在文档间环形
        // ——未打开页不在循环表内切不进（R1：空间页不进文档集）
        val openTabs = listOf(ToolPage.Report, ToolPage.Setting)
        assertEquals(ToolPage.Setting, stepPage(openTabs, ToolPage.Report, backward = false))
        assertEquals(ToolPage.Report, stepPage(openTabs, ToolPage.Setting, backward = false))
        assertNull(stepPage(openTabs, ToolPage.ShellTools, backward = false), "未打开页不在文档循环表内")
    }

    @Test
    fun `stepPage over single open tab returns same page`() {
        // 循环表只剩单 tab（openTabs 极小集）也照常循环——环形取模单元素自返，无需特判
        val openTabs = listOf(ToolPage.LogWorkbench)
        assertEquals(ToolPage.LogWorkbench, stepPage(openTabs, ToolPage.LogWorkbench, backward = false))
        assertEquals(ToolPage.LogWorkbench, stepPage(openTabs, ToolPage.LogWorkbench, backward = true))
    }

    // ── GLB-07 · Ctrl+Tab 空集 toast 反馈（重设计批 2 · 2026-09-30）──

    @Test
    fun `cycle page empty order surfaces a toast instead of silent noop`() {
        // 按了没反应且无反馈 = 死键体验：next == null（openTabs 空集或当前页不在表内
        // ——空间页不进 openTabs 是常态可达路径）时必须走 StatusSignals.show 告知出路，
        // 不得静默无动作。cyclePage lambda 是 @Composable 内 DisposableEffect 装配，
        // 直测困难——源码断言（StatusSignals.show 单参形态，勿误用 showUndo）。
        val text = hotkeyHubFile().readText()
        val cycleBlock = text.substringAfter("HotkeyHub.cyclePage = { backward ->")
            .substringBefore("onDispose")
        assertTrue(
            "StatusSignals.show(" in cycleBlock,
            "cyclePage next==null 分支必须调 StatusSignals.show（GLB-07：死键补 toast 反馈）",
        )
        assertFalse(
            "showUndo(" in cycleBlock,
            "空集反馈是纯告知非可撤销操作——不得误用 showUndo（GLB-07）",
        )
        assertTrue(
            "没有已打开的文档 tab" in cycleBlock,
            "toast 文案必须说明空态与出路（GLB-07：Ctrl+K 打开报告或深入模式）",
        )
        // 恒 consumed 契约保留：空集分支也不放行裸 Tab（焦点副作用防线不动）——
        // 块内独立 `true` 行 = handler 返回值（toast 分支之后仍恒 consumed）
        assertTrue(
            Regex("""(?m)^\s*true\s*$""").containsMatchIn(cycleBlock),
            "cyclePage 恒 consumed 契约必须保留（GLB-07 明示不变项）",
        )
    }

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun hotkeyHubFile(): File {
        val file = File(locateProjectRoot(), HOTKEY_HUB_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$HOTKEY_HUB_PATH（重命名/移动后请同步本守护）")
        return file
    }

    // ── GLB-10 · Ctrl+Shift+P 全提示面曝光 + KDoc 漂移修（重设计批 2 · 2026-09-30）──

    @Test
    fun `hotkey kdoc stays in sync with the six-space jump table`() {
        // KDoc 与 HOTKEY_TABLE 的键面漂移守护——源码面清零即守恒（键名断言已钉
        // HOTKEY_TABLE 无 Ctrl+1..9 死键宣传，本断言钉 KDoc 侧与表同源无漂移）。
        val text = hotkeyHubFile().readText()
        assertFalse(
            "Ctrl+1..9" in text,
            "HotkeyHub KDoc 不得残留「Ctrl+1..9」死键宣传（HOTKEY_TABLE 已随 dock 六空间收敛）",
        )
        assertTrue(
            "Ctrl+1..6" in text,
            "KDoc 跳转段必须写「Ctrl+1..6」（与 HOTKEY_TABLE 同源无漂移）",
        )
    }

    @Test
    fun `ctrl shift p is exposed on palette hint and dock tooltip`() {
        // 第二热键（`>` 命令模式预填）此前仅 F1 参考卡可达——palette 底部提示条
        // 两态（推荐首页态/检索态）与 dock 命令钮 hint 均须曝光；状态栏常驻段
        // 卡面明示不动（宽度敏感，AppStatusBar「Ctrl+K 命令面板」保持单键）。
        val palette = File(locateProjectRoot(), COMMAND_PALETTE_PATH).readText()
        val hintBarBlock = palette.substringAfter("底部固定提示条")
            .substringBefore("style = MaterialTheme.typography.labelSmall")
        assertEquals(
            2,
            Regex("Ctrl\\+Shift\\+P 仅命令").findAll(hintBarBlock).count(),
            "palette 提示条两态（推荐首页态/检索态）均须含「Ctrl+Shift+P 仅命令」（GLB-10 改①）",
        )
        val dock = File(locateProjectRoot(), PAGE_HOME_MENU_PATH).readText()
        assertTrue(
            "hint = \"Ctrl+K 唤起命令面板 / Ctrl+Shift+P 仅命令\"" in dock,
            "dock 命令钮 hint 须为「Ctrl+K 唤起命令面板 / Ctrl+Shift+P 仅命令」（GLB-10 改②）",
        )
    }

    private companion object {
        const val HOTKEY_HUB_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/HotkeyHub.kt"
        const val COMMAND_PALETTE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPalette.kt"
        const val PAGE_HOME_MENU_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHomeMenu.kt"
        const val HOTKEY_HELP_CARD_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/HotkeyHelpCard.kt"
        const val MAIN_KT_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/main.kt"
    }

    @Test
    fun `jump dock order stays sourced from dock entries`() {
        // Ctrl+1..9 与 dock 视觉序同源锚：跳转序第一项 = 家（LogWorkbench）、
        // 第二项 = 智能体中心（AgentCenter——dock 第二项随组 B 升格）
        assertEquals(ToolPage.LogWorkbench, DOCK_ENTRIES.first().page, "Ctrl+1 = 家（dock 首项）")
        assertEquals(ToolPage.AgentCenter, DOCK_ENTRIES[1].page, "Ctrl+2 = 智能体中心（dock 第二项）")
        assertEquals(
            listOf(ToolPage.LogWorkbench, ToolPage.AgentCenter, ToolPage.ShellTools, ToolPage.LarkBridge, ToolPage.Tools, ToolPage.DeviceObservation),
            DOCK_ENTRIES.map { it.page },
            "dock 跳转序全量锚（家/智能体/设备/桥接/工具/设备观测——与 DOCK_ENTRIES 单点同源；" +
                "第六项起 = dock 末位追加的空间（Ctrl+1..5 序不变，肌肉记忆零迁移）",
        )
    }

    // ── D3 · F1 键帽 mono 档（终验蜂群修复批 7.5 · 2026-10-02）──

    @Test
    fun `hotkey help keycap text uses desktop mono style`() {
        // D3（roadmap 批 7.5 修复清单）：F1 参考卡键帽 Text 比例字体 labelMedium
        // → DesktopTypography.mono（.kbd 键帽口径——施工=
        // DesktopTypography mono 档」）——键名是键面符号串，等宽字形对齐键帽语义。
        // 比例字体回潮即红。
        val card = File(locateProjectRoot(), HOTKEY_HELP_CARD_PATH).readText()
        val keycapBlock = card.substringAfter("Surface(").substringBefore("Spacer(modifier = Modifier.width(Dimens.space8))")
        assertFalse(
            "labelMedium" in keycapBlock,
            "F1 键帽 Text 不得用比例字体 labelMedium（D3：键帽走 DesktopTypography.mono 档）——" +
                "键帽样式回潮即红。",
        )
        assertTrue(
            "DesktopTypography.mono" in keycapBlock,
            "F1 键帽 Text 必须 style = DesktopTypography.mono（D3 · shell.html 注区口径）。",
        )
    }
}
