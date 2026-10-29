package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码锚守护：输出过滤栏仅 hasOutput 渲染。
 *
 * OutputFilterBar 与 OutputList 同判据同位置条件渲染（if (hasOutput)）——
 * 空闲紧裹目标下（面板 KDoc「空闲时紧裹」自述）无输出时过滤框是死控件噪音，
 * 且同面板内两区块渲染策略须对称；filterKeyword 保留在 remember 中不清空——
 * 清空输出后过滤词保留，重新产生输出时过滤态自动恢复（docs/pages/shell-tools.md
 * 既有语义）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellOutputFilterGateGuardTest {

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

    private fun panelSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/ShellOutputPanel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `filter bar is gated by hasOutput`() {
        // 门控锚：OutputFilterBar( 调用行向上 10 行内含 if (hasOutput)（装配切片——
        // 「无输出时输出台仅头部+输入条」的代码面等价物；收起开关引入后门控到调用点
        // 隔 AnimatedVisibility+Column 装配 7 行，窗口 6→10 保判据本意、免行数脆断）
        val lines = panelSource().split('\n')
        val callIndex = lines.indexOfFirst { it.contains("OutputFilterBar(") && !it.trimStart().startsWith("//") && !it.contains("private fun OutputFilterBar") }
        assertTrue(callIndex >= 0, "SH-09 守护：OutputFilterBar 调用点锚失效——调用点漂移后请同步本守护")
        val window = lines.drop(maxOf(0, callIndex - 10)).take(10).joinToString("\n")
        assertTrue(
            "if (hasOutput)" in window,
            "SH-09 守护：OutputFilterBar 调用点必须包 if (hasOutput)（与 OutputList 同判据——" +
                "无输出时过滤框不渲染；退回无条件渲染即死控件噪音回潮）",
        )
    }

    @Test
    fun `filter keyword survives output clear via runtime session state`() {
        // 过滤词保留锚（过滤词上提 ShellToolsRuntime 后重锚）：Runtime 持有初值 ""、
        // 面板 collectAsState 订阅、面板侧唯一写入通道 onKeywordChange——
        // 清空输出后过滤词保留、恢复输出过滤态自动回归
        val src = panelSource()
        assertTrue(
            "val filterKeyword by ShellToolsRuntime.outputFilterKeyword.collectAsState()" in src,
            "SH-09 守护：面板必须从 ShellToolsRuntime.outputFilterKeyword 收集过滤词" +
                "（进程级持有——清空输出不清词，恢复输出后过滤态自动回归）",
        )
        val runtimeFile = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsRuntime.kt",
        )
        assertTrue(runtimeFile.isFile, "源码锚文件缺失：${runtimeFile.path}（重命名/移动后请同步本守护）")
        assertTrue(
            Regex("""outputFilterKeyword:\s*MutableStateFlow<String>\s*=\s*MutableStateFlow\(""\)""")
                .containsMatchIn(runtimeFile.readText()),
            "SH-09 守护：outputFilterKeyword 初值必须为空串（非预置过滤词）",
        )
        // 反向锚：面板侧 `outputFilterKeyword.value\s*=` 归一化后恰 1 处命中——
        // onKeywordChange 写入行（唯一写入通道）。计数形态下任何旁路赋值
        // （如 clearOutput 时重置过滤词）即第 2 命中 → 红（丢「过滤词保留」语义）
        val codeOnly = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val writeHits = Regex("""outputFilterKeyword\.value\s*=""").findAll(codeOnly).toList()
        assertTrue(
            writeHits.size == 1,
            "SH-09 守护：outputFilterKeyword 唯一写入通道 = onKeywordChange——" +
                "归一化后应恰 1 处写入命中，实际 ${writeHits.size} 处：" +
                writeHits.joinToString(" / ") {
                    "L${codeOnly.take(it.range.first).count { c -> c == '\n' } + 1}"
                } +
                "（旁路赋值如 clearOutput 时重置即丢「过滤词保留」语义）",
        )
    }
}
