package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LB-14 / LB-17 守护（重设计批 9 · 2026-10-02）：OperationBar 三管理钮迁页头 actions +
 * 预检结果常驻至下次预检。
 *
 * 锚面 = LarkBridgePage.kt + OperationBar.kt 归一化（剥注释压空白，锚只对代码字面）：
 * 1. **LB-14 页头三图标钮在位**：PageScaffold actions 槽含 FILE_UPLOAD（导入）/FILE_DOWNLOAD（导出）/
 *    FOLDER_OPEN（打开配置目录）三 [MaterialSymbols] 常量（🔴 卡面 IMPORT/EXPORT 不存在——
 *    勘误后图标）+ 中文 contentDescription + pickJsonFile 链（回调在 Page 层直调）；
 * 2. **LB-14 tooltip 必须在**：HeaderActionIconButton 经 HoverTooltipBox 承载 hover 气泡
 *    （:ui 共享配方——验收「带 tooltip」硬性项，ShellToolsPage 页头裸 IconButton 形态不达标）；
 * 3. **LB-14 OperationBar 收窄**：三钮行与三参数（onImport/onExport/onOpenDir）清零防回潮；
 *    🔴 pendingImport 的 AppConfirmDialog（LB-02 遗产）保留在 OperationBar（常驻可弹锚）；
 * 4. **LB-17 预检结果常驻**：probe 条移出折叠区（折叠收起也可见，P3 修复先例同位）+
 *    5s 清除链只剩 banner（probe 移出 key）+ 行尾关闭钮（CLOSE——🔴 卡面 DISMISS 不存在）
 *    + Page 侧 onClearProbe = vm::clearProbe 接线；VM 侧语义拆分（clearBanner 只清 banner /
 *    clearProbe 只清 probe）由 LarkBridgeViewModelTest 用例直测。
 *
 * 🔴 验收纪律：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LbGuardOperationBarTest {

    @Test
    fun `lb14 three manage buttons live in page header actions`() {
        val page = normalizedCode(PAGE)
        // PageScaffold actions 槽消费
        assertTrue(
            "actions = {" in page,
            "LarkBridgePage 的 PageScaffold 必须消费 actions 槽（LB-14：管理钮上页头）",
        )
        // 三图标勘误后常量（IMPORT/EXPORT 不存在——FILE_UPLOAD/FILE_DOWNLOAD/FOLDER_OPEN）
        assertTrue("MaterialSymbols.FILE_UPLOAD" in page, "导入钮图标必须 FILE_UPLOAD（LB-14）")
        assertTrue("MaterialSymbols.FILE_DOWNLOAD" in page, "导出钮图标必须 FILE_DOWNLOAD（LB-14）")
        assertTrue("MaterialSymbols.FOLDER_OPEN" in page, "打开目录钮图标必须 FOLDER_OPEN（LB-14）")
        // 中文 contentDescription + pickJsonFile 链在 Page 层
        assertTrue("\"导入配置 JSON\"" in page, "导入钮须带中文描述（LB-14 验收）")
        assertTrue("\"导出配置 JSON\"" in page, "导出钮须带中文描述（LB-14 验收）")
        assertTrue("\"打开配置目录\"" in page, "目录钮须带中文描述（LB-14 验收）")
        assertTrue("pickJsonFile" in page, "pickJsonFile 链必须在 Page 层直调（回调换位非上移）")
        // tooltip 硬性项：hover 气泡包裹（配方单源 = :ui HoverTooltipBox，Page 侧锚消费形态）
        assertTrue("HoverTooltipBox(" in page, "页头图标钮必须 hover 气泡包裹（LB-14 验收「带 tooltip」）")
        assertTrue("private fun HeaderActionIconButton(" in page, "HeaderActionIconButton 私有件必须在位")
        // busy 置灰：导入/导出 enabled=!busy + disabledReason
        assertTrue("disabledReason = if (busy)" in page, "busy 时导入/导出钮须置灰且带原因（禁止静默置灰）")
    }

    @Test
    fun `lb14 operation bar folded zone no longer has manage row`() {
        val bar = normalizedCode(BAR)
        // 三钮行清零（防回潮）
        assertFalse("\"导入 JSON\")" in bar, "OperationBar 不得再有「导入 JSON」钮（LB-14：迁页头）")
        assertFalse("\"导出 JSON\")" in bar, "OperationBar 不得再有「导出 JSON」钮（LB-14：迁页头）")
        assertFalse("\"打开配置目录\")" in bar, "OperationBar 不得再有「打开配置目录」钮（LB-14：迁页头）")
        // 三参数删除
        assertFalse("onImport: () -> Unit" in bar, "onImport 参数须删除（LB-14）")
        assertFalse("onExport: () -> Unit" in bar, "onExport 参数须删除（LB-14）")
        assertFalse("onOpenDir: () -> Unit" in bar, "onOpenDir 参数须删除（LB-14）")
        // LB-02 遗产在位锚：pendingImport 确认框保留
        assertTrue("AppConfirmDialog(" in bar, "pendingImport 的 AppConfirmDialog 必须保留在 OperationBar（LB-02 遗产，对话框常驻可弹）")
        assertTrue("onConfirmImport" in bar && "onCancelImport" in bar, "确认/取消回调参数保留（LB-02 遗产链零破坏）")
    }

    // ── LB-17：预检结果常驻至下次预检 ──

    @Test
    fun `lb17 probe banner is persistent with close button and decoupled 5s chain`() {
        val bar = normalizedCode(BAR)
        // 5s 清除链只剩 banner（probe 移出 key）——链块内零 probe 引用
        assertTrue("LaunchedEffect(banner)" in bar, "5s 清除链 key 必须只剩 banner（LB-17：probe 常驻不自动清）")
        val chainStart = bar.indexOf("LaunchedEffect(banner)")
        val chainSlice = bar.substring(chainStart, minOf(chainStart + 300, bar.length))
        assertFalse(
            "probe" in chainSlice,
            "5s 清除链块内不得引用 probe（LB-17：probe 移出自动清除）——切片=${chainSlice.take(200)}",
        )
        // probe 常驻条移出折叠区 + 行尾关闭钮（CLOSE——🔴 卡面 DISMISS 图标不存在）
        assertTrue("onClearProbe" in bar, "预检条关闭必须走 onClearProbe（LB-17：手动处置入口）")
        assertTrue("MaterialSymbols.CLOSE" in bar, "关闭钮图标必须 CLOSE（LB-17 勘误：DISMISS 不存在）")
        assertTrue("\"关闭预检结果\"" in bar, "关闭钮必须带 contentDescription「关闭预检结果」（LB-17）")
        assertTrue("\"预检结果：" in bar, "常驻条文案锚「预检结果：」在位")
        // probe 条不在 testExpanded 折叠区内（常驻 = 折叠收起也可见）：归一化后 probe 常驻块
        // 必须出现在折叠区 Column（if (testExpanded) {）之前——锚定折叠块开括号形态
        // （icon 三元里的 `if (testExpanded)` 是前置假命中，须跳过）
        val probeBlockIndex = bar.indexOf("if (probe != null)")
        val foldedIndex = bar.indexOf("if (testExpanded) {")
        assertTrue(probeBlockIndex in 0 until foldedIndex, "probe 常驻条必须移出折叠区（LB-17：在折叠区 Column 之前）")
        // 接线：Page 侧 onClearProbe = vm::clearProbe
        val page = normalizedCode(PAGE)
        assertTrue("onClearProbe = vm::clearProbe" in page, "Page 必须接线 onClearProbe = vm::clearProbe（LB-17）")
    }

    // ── 基建 ──

    /** 归一化：剥行注释 + 剥块注释 + 压空白——锚只对代码字面（施工注释属历史记述不计）。 */
    private fun normalizedCode(relPath: String): String =
        file(relPath).readText()
            .replace(Regex("/\\*+[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun file(relPath: String): File {
        val f = File(projectRoot(), relPath)
        assertTrue(f.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return f
    }

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val PKG = "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge"
        const val PAGE = "$PKG/LarkBridgePage.kt"
        const val BAR = "$PKG/OperationBar.kt"
    }
}
