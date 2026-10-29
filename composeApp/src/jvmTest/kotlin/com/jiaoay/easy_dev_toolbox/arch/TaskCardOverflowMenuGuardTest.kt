package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TL-06 · 任务卡 ⋯ 溢出菜单钮源码锚守护（重设计批 11 · 2026-10-02）：
 *
 * 情境动作此前全在右键 AppContextMenu（不可发现——触控板/单键鼠标用户找不到
 * affordance，audit-tl.md F6）。本卡 TaskCard 行尾补 MORE_VERT IconButton，
 * onClick 直接弹 M3 DropdownMenu（勘误 8：AppContextMenu 仅右键触发无程序化
 * 展开口——「⋯ 钮挂 AppContextMenu」不可直译）；容器视觉参数抄 AppContextMenu
 * :77-86 同族（surfaceDeep + borderHairline 发丝描边）；菜单条目与右键菜单共享
 * buildTaskContextMenuEntries 单源函数（TaskListSection 抽出，TaskCard 同包
 * internal 调用——两处入口同序同词防漂移）。
 *
 * 验收面：
 * 1. ⋯ 钮在位：MORE_VERT + DropdownMenu onClick 直弹 + 命中区 MICS 32
 *    （PM 合成裁定 ×XA-20——不引入新 36dp 点）；
 * 2. 菜单同源：TaskCard 调用 buildTaskContextMenuEntries（与 TaskListSection
 *    右键 items 同一函数）；
 * 3. Running 态「取消任务」danger 条目在共享函数（单源即覆盖两入口）；
 * 4. Error/Cancelled 无 ⋯ 钮（menuEntries.isNotEmpty() 门控——空列表不渲染）；
 * 5. 视觉同族：containerColor surfaceDeep + border 描边（AppContextMenu 同款）；
 * 6. 菜单锚共处：⋯ 钮与 DropdownMenu 同包一个 Box 且钮在先（Compose Popup 锚定
 *    父布局节点——平级会锚到整卡 Row，菜单开在卡片左缘而非按钮下）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class TaskCardOverflowMenuGuardTest {

    @Test
    fun `task card has more-vert overflow button opening dropdown menu`() {
        val code = file(TASK_CARD).readText()
        assertTrue(
            "MaterialSymbols.MORE_VERT" in code,
            "TaskCard 须有 MORE_VERT ⋯ 溢出钮（TL-06：右键等效可视化 affordance）",
        )
        assertTrue(
            "DropdownMenu(" in code && "menuExpanded = true" in code,
            "⋯ 钮 onClick 须直弹 M3 DropdownMenu（勘误 8：AppContextMenu 无程序化展开口——" +
                "包一层 AppContextMenu 不可直译）",
        )
    }

    @Test
    fun `overflow button uses mics 32 touch target`() {
        val code = file(TASK_CARD).readText()
        val moreVertBlock = code.substringAfter("MaterialSymbols.MORE_VERT", "")
        assertTrue(
            moreVertBlock.isNotEmpty(),
            "MORE_VERT 图标缺失（TL-06 断言锚漂移）",
        )
        // 新钮命中区 = MICS 32（合成裁定不引入新 36dp 点——卡面「36dp 沿用」作废）
        // 蜂群回归 2026-10-06 更新：三行尾钮（打开文件夹/取消/⋯）统一收编 IcBtnContainer
        // （.ic-btn 形态）——MICS 收进组件本体
        // （KbdText.kt IcBtnContainer.size(minimumInteractiveSize)），本文件改锚组件收编
        assertTrue(
            "IcBtnContainer(" in code,
            "⋯ 钮须走 IcBtnContainer（.ic-btn 稿面形态 + MICS 32 收进组件本体——" +
                "2026-10-06 蜂群回归三钮收编后旧 minimumInteractiveSize 字面锚随迁）",
        )
        assertFalse(
            Regex("""\b36\s*\.\s*dp\b""").containsMatchIn(code),
            "TaskCard 不得引入 36.dp（TL-06 合成裁定：新钮直接 MICS 32；XA-20 同批收旧值）",
        )
    }

    @Test
    fun `menu entries share single source with context menu`() {
        val taskCard = file(TASK_CARD).readText()
        val section = file(TASK_LIST_SECTION).readText()
        assertTrue(
            "buildTaskContextMenuEntries" in taskCard,
            "TaskCard 菜单条目须调用共享单源 buildTaskContextMenuEntries（与右键菜单同源同序）",
        )
        assertTrue(
            "internal fun buildTaskContextMenuEntries" in section,
            "TaskListSection 须抽出 internal 共享条目函数（TaskCard 同包调用单源）",
        )
        // Running 态「取消任务」danger 在共享函数——单源即覆盖右键与 ⋯ 两入口
        assertTrue(
            "ContextMenuEntry(\"取消任务\", danger = true)" in section,
            "Running 态须有 danger「取消任务」条目（共享函数单源覆盖两入口）",
        )
    }

    @Test
    fun `error and cancelled states render no overflow button`() {
        val code = file(TASK_CARD).readText()
        // 有条目才渲染（Error/Cancelled 空列表无钮——与右键「空列表不弹」同判）
        assertTrue(
            "if (menuEntries.isNotEmpty())" in code,
            "⋯ 钮须以 menuEntries.isNotEmpty() 门控（Error/Cancelled 态无钮——两入口同判）",
        )
    }

    @Test
    fun `dropdown visual matches app context menu family`() {
        val code = file(TASK_CARD).readText()
        val contextMenu = file(APP_CONTEXT_MENU).readText()
        // 容器视觉参数抄 AppContextMenu :77-86（surfaceDeep + borderHairline 发丝描边）
        assertTrue(
            "containerColor = surfaceColors.surfaceDeep" in code,
            "⋯ 菜单容器须 surfaceDeep（AppContextMenu 同族——浅色下与页面底有档位差）",
        )
        assertTrue(
            "surfaceColors.borderHairline" in code,
            "⋯ 菜单容器须 borderHairline 发丝描边（DS-02 归一——AppContextMenu 同族）",
        )
        assertTrue(
            "containerColor = surfaceColors.surfaceDeep" in contextMenu &&
                "surfaceColors.borderHairline" in contextMenu,
            "AppContextMenu 基线锚漂移（视觉同族断言参照物变更须同步本守护）",
        )
    }

    @Test
    fun `context menu anchors dropdown to captured pointer position`() {
        val contextMenu = file(APP_CONTEXT_MENU).readText()
        // 右键指针位捕获 + 零尺寸锚 Box 承载 DropdownMenu（Popup 锚定父布局节点——
        // 锚 = 整域 Box 时菜单开在区域角落而非按下处；无指针记录回落零偏移整域锚）
        assertTrue(
            "var anchorOffset by remember { mutableStateOf<Offset?>(null) }" in contextMenu,
            "右键菜单须持指针坐标态（菜单锚单源——锚机制改动时随迁本断言）",
        )
        assertTrue(
            "anchorOffset = change?.position" in contextMenu,
            "右键按下须先捕获指针坐标再弹层（菜单开在按下处的锚单源）",
        )
        val anchorBox = contextMenu.indexOf("Modifier.offset {")
        val menu = contextMenu.indexOf("DropdownMenu(")
        assertTrue(
            anchorBox in 0 until menu,
            "DropdownMenu 须经指针位零尺寸锚 Box 承载（锚 = 整域 Box 回潮 = 菜单开在区域角落）",
        )
        assertTrue(
            "IntOffset.Zero" in contextMenu,
            "无指针记录（键盘触发等）须回落零偏移整域锚",
        )
    }

    @Test
    fun `success menu offers in-app viewer entry gated by isFile`() {
        val section = file(TASK_LIST_SECTION).readText()
        // 应用内查看器入口：Success 态「查看」条目在共享单源函数——右键与 ⋯ 两入口同获
        assertTrue(
            "ContextMenuEntry(\"查看\")" in section,
            "Success 态须有「查看」条目（应用内查看器入口，共享函数单源覆盖两入口）",
        )
        // isFile 门控：目录产物（如解压目录）自动缺席，不得出现死入口
        assertTrue(
            "File(success.outputPath).isFile" in section,
            "「查看」条目须以 File(success.outputPath).isFile 门控（目录产物条目自动缺席）",
        )
        // 承接动作走 viewFile 槽（TaskListSection 挂共享查看器 Dialog——脱离系统编辑器闭环）
        assertTrue(
            "TaskListSectionActions.viewFile?.invoke(success.outputPath)" in section,
            "「查看」条目执行体须经 TaskListSectionActions.viewFile 槽（Dialog 挂载态承接）",
        )
    }

    @Test
    fun `overflow button and dropdown menu share one anchor box`() {
        val code = file(TASK_CARD).readText()
        // 契约注释锚在位（TaskCard 内唯一，语义真源见 TaskCard.kt Block 内注释）
        assertTrue(
            "菜单锚 = 本 Box" in code,
            "菜单锚契约注释缺失（U6 语义：Popup 锚定父布局节点——平级锚到整卡 Row，" +
                "菜单开在卡片左缘；契约注释与 Box 须同迁）",
        )
        val gateIdx = code.indexOf("if (menuEntries.isNotEmpty())")
        assertTrue(
            gateIdx >= 0,
            "⋯ 钮门控锚漂移（菜单锚共处断言须随迁）",
        )
        val boxOpen = code.indexOf("Box {", gateIdx)
        assertTrue(
            boxOpen >= 0,
            "⋯ 钮与 DropdownMenu 须同包一个 Box（拆 Box = Popup 锚到整卡 Row，" +
                "菜单开在卡片左缘而非按钮下——U6 回潮）",
        )
        val boxBlock = braceMatchedBlock(code, code.indexOf('{', boxOpen))
        val buttonIdx = boxBlock.indexOf("contentDescription = \"更多操作\"")
        val menuIdx = boxBlock.indexOf("DropdownMenu(")
        assertTrue(
            buttonIdx in 0 until menuIdx,
            "⋯ 钮与 DropdownMenu 须同 Box 共处且钮在先（钮游离 Box 外 = 锚点漂到" +
                "整卡 Row，菜单开在卡片左缘——U6 回潮）",
        )
    }

    /** 花括号配平截取块体；openBraceIdx 须指向块首 "{"（块内无字符串插值花括号干扰）。 */
    private fun braceMatchedBlock(code: String, openBraceIdx: Int): String {
        var depth = 0
        var i = openBraceIdx
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(openBraceIdx, i + 1)
                }
            }
            i++
        }
        fail("花括号不配平——菜单锚切片失败（TaskCard.kt 语法异常或锚漂移）")
    }

    private fun file(relPath: String): File {
        val f = File(locateProjectRoot(), relPath)
        assertTrue(f.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return f
    }

    private companion object {
        const val TASK_CARD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/TaskCard.kt"
        const val TASK_LIST_SECTION =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/TaskListSection.kt"
        const val APP_CONTEXT_MENU =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/AppContextMenu.kt"
    }
}
