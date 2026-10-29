package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SH-16 · 工具树空态四要素化源码锚守护（重设计批 10 · 2026-10-02）。
 *
 * 卡面 acceptance 两条：
 * 1. 空树显示「暂无工具」+ 学习提示 +「新增自定义命令」直达按钮；
 * 2. 点击直达按钮打开自定义命令编辑器。
 *
 * 🔴 改道（侦察勘误 TOP3）：JResult.Empty 全仓零构造点（JResult.kt 审计注释
 * 明示现网不可达——shelltools 链只产 Success/Failed/null），「StatusView Empty
 * 分支拆出」路线永不生效。实落 = Success 空列表前置 if（卡面括号备选项），
 * StatusView 组件本体不动——本守护锚 if (toolNodes.isEmpty()) 切片证改道落地。
 *
 * 与 XA-04 的分工边界（互不越界）：SH-16 = 无搜索词空树（锚在本文件
 * ShellToolsListSection 的状态拆解 when 区）；XA-04 = 有搜索词无匹配（锚在
 * ShellToolsList.kt）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsTreeEmptyStateGuardTest {

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
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `empty tree renders four element empty state`() {
        val src = source()
        assertTrue(
            "暂无工具" in src,
            "SH-16 守护：空树主文案必须为「暂无工具」（替换 Success 空列表渲染的空白）",
        )
        assertTrue(
            "MaterialSymbols.HANDYMAN" in src,
            "SH-16 守护：空态图标必须为 HANDYMAN（工具域语义——PageHomeMenu 工具 dock 同枚）",
        )
        assertTrue(
            "在配置目录放置 shell_tools.json 或新增自定义命令" in src,
            "SH-16 守护：必须带学习提示（NN/g 要素②——此处将来显示什么/怎么填充）",
        )
    }

    @Test
    fun `empty tree action opens custom editor directly`() {
        val src = source()
        assertTrue(
            "actionLabel = \"新增自定义命令\"" in src,
            "SH-16 守护：必须带直达按钮（NN/g 要素③——关键任务直达路径）",
        )
        // 直达按钮 → 编辑器：onOpenCustomEditor 回调（外壳层 showCustomEditor 状态
        // 经参数传入 section——卡面「showCustomEditor 状态已在外壳层直达」的落地形态）
        assertTrue(
            "onOpenCustomEditor = { showCustomEditor = true }" in src,
            "SH-16 守护：ShellToolsListSection 调用点必须接 onOpenCustomEditor = " +
                "{ showCustomEditor = true }（空树直达按钮 → 编辑器链路）",
        )
    }

    @Test
    fun `empty branch gates on success list not status view`() {
        val src = source()
        // 改道锚（状态语言收口后形态）：StatusView 装配退役，状态在消费点 when 拆解
        // （Failed = StatusBanner + 重试、Loading = 行内圈——孤岛语言对齐同页）；
        // 空态主门仍 = Success 空列表（JResult.Empty 现网不可达，分支同形兜底），
        // 四要素渲染下沉 ToolTreeOrEmpty 单源——shared 件 StatusView.kt 仍不动。
        val statusIndex = src.indexOf("when (val result = toolNodeState)")
        assertTrue(statusIndex >= 0, "SH-16 守护：状态拆解 when 装配点缺失（重构后请同步本守护）")
        val block = src.substring(statusIndex, minOf(src.length, statusIndex + 1400))
        assertTrue(
            "is JResult.Success -> ToolTreeOrEmpty" in block,
            "SH-16 守护：空树真实形态 = Success(空列表) → ToolTreeOrEmpty 渲染",
        )
        assertTrue(
            "is JResult.Empty -> ToolTreeOrEmpty" in block,
            "SH-16 守护：防御性 Empty 分支必须与 Success 空列表同形（改道兜底）",
        )
        // 四要素本体锚在 ToolTreeOrEmpty 函数体内（单源——Success/Empty 两分支共用）
        val helperIndex = src.indexOf("private fun ToolTreeOrEmpty")
        assertTrue(helperIndex >= 0, "SH-16 守护：ToolTreeOrEmpty 单源函数缺失（重构后请同步本守护）")
        val helperBlock = src.substring(helperIndex, minOf(src.length, helperIndex + 1400))
        assertTrue(
            "if (toolNodes.isEmpty())" in helperBlock,
            "SH-16 守护：空态必须以空列表前置 if 落地",
        )
        assertTrue(
            "EmptyStateView(" in helperBlock,
            "SH-16 守护：空态必须消费 EmptyStateView 四要素组件（NN/g 模板收口件）",
        )
        assertTrue(
            "onAction = onOpenCustomEditor" in helperBlock,
            "SH-16 守护：EmptyStateView 直达动作必须接 onOpenCustomEditor 回调",
        )
    }
}
