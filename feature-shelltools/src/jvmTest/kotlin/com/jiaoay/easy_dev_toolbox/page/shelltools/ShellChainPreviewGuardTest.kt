package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Chain 节点步骤预览源码锚守护。
 *
 * ChainChildNode 不得退回单个 Button 仅显「链式执行 · N 步」——执行前须能
 * 确认链内命令（链式是高风险批量操作，root/remount 类误触回放 = 整链重跑）。
 *
 * 🔴 结构契约：Button 内不可嵌独立可点钮（嵌套 clickable 双响应）——
 * 用 Surface 容器（主区 clickable 执行 + 行尾展开钮独立 clickable，hover 渐显）；
 * 展开符号用 EXPAND_MORE/EXPAND_LESS 切换
 * （ToolCallCard.kt / OperationBar.kt 先例，「CHEVRON」非精确符号名）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellChainPreviewGuardTest {

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

    private fun childNodeSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/node/ShellToolsChildNode.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun chainBlock(): String {
        val src = childNodeSource()
        val start = src.indexOf("fun ChainChildNode(")
        assertTrue(start >= 0, "SH-12 守护：ChainChildNode 函数缺失")
        return src.substring(start)
    }

    @Test
    fun `chain node signature carries commands list param`() {
        val block = chainBlock()
        assertTrue(
            Regex("commands:\\s*List<String>") in block.replace("\n    ", " "),
            "SH-12 守护：ChainChildNode 签名必须含 commands: List<String>（预览数据源）",
        )
    }

    @Test
    fun `chain node is container not nested clickable button`() {
        val block = chainBlock()
        // 结构契约锚：主区 clickable + 独立展开钮——不得回潮单 Button 包整个卡片
        // （Button 内嵌 IconButton = 嵌套 clickable 双响应）
        assertTrue(
            "clickable(enabled = !isLoading, onClick = onClick)" in block,
            "SH-12 守护：Chain 主区必须用 Modifier.clickable 承载执行（容器重构锚——" +
                "单 Button 回潮 = 展开钮嵌套 clickable 双响应）",
        )
        assertTrue(
            "animateContentSize(animationSpec = AppMotion.softSpring())" in block,
            "SH-12 守护：展开态尺寸过渡必须 animateContentSize(softSpring)（ShellToolsParentNode 先例）",
        )
        assertTrue(
            "EXPAND_LESS" in block && "EXPAND_MORE" in block,
            "SH-12 守护：展开钮须 EXPAND_MORE/EXPAND_LESS 切换（ToolCallCard.kt:124 先例——" +
                "「CHEVRON」非库内精确符号名）",
        )
    }

    @Test
    fun `chain preview renders mono lines`() {
        val block = chainBlock()
        assertTrue(
            "DesktopTypography.mono" in block,
            "SH-12 守护：步骤预览必须 mono 等宽",
        )
        assertTrue(
            "expanded && commands.isNotEmpty()" in block,
            "SH-12 守护：预览须展开态门控（默认收起不占位）",
        )
    }

    @Test
    fun `both call sites pass commands`() {
        // 树区调用点（ShellToolsChildNode.kt 内）+ 历史卡调用点（ShellHistoryPanel.kt）
        // ——两调用点都必须补 commands 参
        val childSrc = childNodeSource()
        // 第一个 ChainChildNode( 出现 = 树区调用点（第二个是 fun 定义）
        val treeCall = childSrc.substringAfter("ChainChildNode(").substringBefore("fun ChainChildNode")
        assertTrue(
            "commands = body.commands" in treeCall,
            "SH-12 守护：树区调用点必须传 commands = body.commands",
        )

        val historyFile = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryPanel.kt",
        )
        assertTrue(historyFile.isFile, "源码锚文件缺失：${historyFile.path}")
        val historySrc = historyFile.readText()
        // 历史卡不直引 ChainChildNode——ChainHistoryCard 自持展开预览
        // （commands 数据链 = item.commands 仍在函数体内）。
        // 验收本体（mono 步骤预览）以「函数区含 item.commands + mono 预览」钉住。
        val historyCard = historySrc.substringAfter("private fun ChainHistoryCard")
        assertTrue(
            "item.commands" in historyCard.take(2000),
            "SH-12 守护：ChainHistoryCard 必须自持 commands 数据链（预览数据源 = item.commands）",
        )
        assertTrue(
            "DesktopTypography.mono" in historyCard,
            "SH-12 守护：历史卡展开预览必须 mono 等宽",
        )
    }
}
