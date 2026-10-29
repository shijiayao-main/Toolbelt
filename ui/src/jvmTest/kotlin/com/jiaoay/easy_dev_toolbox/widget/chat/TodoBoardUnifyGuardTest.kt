package com.jiaoay.easy_dev_toolbox.widget.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TodoBoard 双件统一守护：
 *
 * 会话内 todo 投影与工作台同件同形态——ChatEntryRow 不再自持私有 TodoBoardRow
 * 件（surfaceRaised 底 + 6dp 状态点），统一消费 TodoBoardPanel（TonalCard +
 * 三态图标）。渲染件无 Compose 运行时直测缝（:ui 无 skiko 运行时——
 * TodoBoardPanelTest 同款口径），源码切片是一等守护形态：
 *
 * - **TodoBoardRow 零残留**：私有件 + TodoStatusDot 状态点件 + 「任务计划」
 *   独立头行全数退役（防回潮）；
 * - **TodoBoardPanel 消费点**：ChatEntryRow 必须经 TodoBoardPanel 渲染
 *   TodoBoardSnapshot（类型映射 TodoChip→TodoItem 消费点 1:1 换）；
 * - **互锚**：TodoBoardPanel 侧配方锚已由 TodoBoardPanelTest 钉住（本类不重复
 *   ——单源分工），本类钉消费侧。
 */
class TodoBoardUnifyGuardTest {

    @Test
    fun `wb26 todo board row private composable is fully retired`() {
        val source = chatEntryRowSource()
        assertFalse(
            "TodoBoardRow" in source,
            "TodoBoardRow 私有件必须零残留（双件统一后 " +
                "surfaceRaised+6dp 状态点形态退役，防回潮）",
        )
        assertFalse(
            "TodoStatusDot" in source,
            "TodoStatusDot 状态点件随 TodoBoardRow 一并退役（私有件家族零残留）",
        )
        assertFalse(
            "任务计划" in source,
            "「任务计划」独立头行随 TodoBoardRow 退役（统一后头行归 TodoBoardPanel " +
                "「计划」+ 计数 pill——两会话域同件同文案）",
        )
        assertFalse(
            "surfaceRaised" in source,
            "surfaceRaised 底形态随 TodoBoardRow 退役（统一后壳归 TonalCard）",
        )
    }

    @Test
    fun `wb26 chat entry row consumes todo board panel with chip to item mapping`() {
        val source = chatEntryRowSource()
        listOf(
            "TodoBoardPanel(" to "会话内计划板快照必须经 TodoBoardPanel 渲染（" +
                "与工作台同件同形态）",
            "TodoChipStatus.PENDING -> com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus.PENDING" to
                "类型映射 PENDING（TodoChip/TodoChipStatus 消息流面 → TodoItem/TodoStatus 计划板 state 面）",
            "TodoChipStatus.IN_PROGRESS -> com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus.IN_PROGRESS" to
                "类型映射 IN_PROGRESS（三枚状态语义一一对应）",
            "TodoChipStatus.DONE -> com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus.DONE" to
                "类型映射 DONE（三枚状态语义一一对应）",
            "is ChatEntry.TodoBoardSnapshot -> " to "when 分支消费锚（改名/删分支即红）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "ChatEntryRow 丢失 WB-26 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb26 todo board panel remains the single todo board renderer in ui`() {
        // 互锚另一面：:ui 域 todo 投影渲染单源——TodoBoardPanel 之外不得出现第二个
        // todo 渲染件（surfaceRaised 私有件形态不得在别处还魂）
        val chatDir = chatSourceDir()
        val offenders = chatDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.name != "ChatEntryRow.kt" }
            .filter { "TodoBoardRow" in it.readText() || "TodoStatusDot" in it.readText() }
            .map { it.name }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            ":ui widget/chat 域发现 TodoBoardRow/TodoStatusDot 残留：$offenders——WB-26 双件统一后私有件零残留",
        )
    }

    private fun chatEntryRowSource(): String = sourceAt(
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/chat/ChatEntryRow.kt",
    )

    private fun chatSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        return File(dir, "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/chat")
    }

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
