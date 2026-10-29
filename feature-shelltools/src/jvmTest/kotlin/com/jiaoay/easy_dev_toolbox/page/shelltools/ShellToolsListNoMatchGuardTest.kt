package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * XA-04 · 工具树搜索无匹配空态源码锚守护（重设计批 10 · 2026-10-02）。
 *
 * 修复前 ShellToolsList 的 visibleNodes 过滤后直接 LazyColumn——搜索无命中
 * 主区整块空白；修复 = 空分支 EmptyStateView 三要素（text「没有匹配…」+
 * learningHint + action「清空搜索」→ onSearchQueryChange("") 恢复完整树）。
 *
 * 与 SH-16 的分工边界（互不越界）：SH-16 = 无搜索词的空树空态（ShellToolsPage
 * 层）；本卡 = 有搜索词无匹配（ShellToolsList 层）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsListNoMatchGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    private fun source(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/ShellToolsList.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `empty visible nodes renders no match empty state`() {
        val src = source()
        assertTrue("没有匹配" in src, "XA-04 守护：无匹配空态文案锚缺失（搜索无命中主区整块空白回潮）")
        assertTrue("EmptyStateView(" in src, "XA-04 守护：空态必须走 EmptyStateView（空态唯一语言收口）")
        assertTrue("fillParent = false" in src, "XA-04 守护：空态占位须 fillParent = false（树区居中，不吞满屏）")
    }

    @Test
    fun `clear search action wired to view model`() {
        val src = source()
        assertTrue("清空搜索" in src, "XA-04 守护：清空搜索动作钮文案锚缺失")
        assertTrue(
            "onSearchQueryChange(\"\")" in src,
            "XA-04 守护：清空动作必须接 viewModel.onSearchQueryChange(\"\")（点按钮恢复完整树）",
        )
    }
}
