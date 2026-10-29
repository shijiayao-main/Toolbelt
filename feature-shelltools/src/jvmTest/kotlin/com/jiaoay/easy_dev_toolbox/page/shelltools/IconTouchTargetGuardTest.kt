package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * XA-20 源码锚守护（重设计批 11 · 2026-10-02）：图标钮命中区收敛 MICS 32 钉死。
 *
 * 卡面 acceptance：「grep 图标钮 size 字面量仅剩 minimumInteractiveSize 引用」。
 * 六文件（卡面 scope 4 + 白名单勘误补收 2）图标钮 Modifier.size(28/24/36.dp)
 * 全收 Dimens.minimumInteractiveSize（MICS 32 桌面紧凑档）：
 * - ShellOutputPanel 28dp×4（onStop/CopyIconButton/导出/清空——卡面「三连」漏 onStop）；
 * - ShellHistoryPanel 24dp×2 + 28dp×1（清空全部/多选切换/行尾删除）；
 * - ShellToolsParentNode 28dp×4（新增/分享/重命名/删除）；
 * - ShellToolsChildNode 28dp×5（分享/移动/编辑/删除/展开——卡面 scope 漏列、
 *   whitelist 有条目，按 whitelist 实况收编）；
 * - ShellCustomEditorDialog 24dp×1（删除参数——同上漏列收编）；
 * - TaskCard 36dp×2（打开文件夹/取消——路 1 TL-06 已先行收 MICS 32，本守护复钉；
 *   蜂群回归 P2 补射批 2026-10-06：TaskCard 三图标钮改走 IcBtnContainer（.ic-btn
 *   形态，逐字），MICS 32 消费移进组件本体——
 *   本守护对 TaskCard 改钉 IcBtnContainer 消费，四文件仍直钉 MICS）。
 *
 * 豁免面（不在收敛范围，不得误伤）：
 * - 20dp 紧凑档（ShellOutputPanel SearchField 清除钮 / ShellCustomEditorDialog
 *   删除选项钮）——「有据保留」有意低于 Material 最小目标，设计决策保留；
 * - 字形尺寸（iconSmall/iconMedium/iconLarge 显式传参）不受盒约束影响。
 *
 * UiArchitectureGuardTest rawDpAllowedValuesByPath 白名单已同批收缩
 * （值级 12→10 条：ShellHistoryPanel/ShellToolsParentNode 整条退役）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class IconTouchTargetGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 八文件源码（相对各模块 jvmMain/kotlin 根 + 模块 src 前缀；DeviceStatusBar
     *  随设备域升位迁 :ui widget/device/ 子包，其刷新钮 MICS 消费点随文件重指） */
    private val guardedFiles = listOf(
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/ShellOutputPanel.kt",
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt",
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/device/DeviceStatusBar.kt",
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryPanel.kt",
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/node/ShellToolsParentNode.kt",
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/node/ShellToolsChildNode.kt",
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/custom/ShellCustomEditorDialog.kt",
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/TaskCard.kt",
    )

    private fun sourceOf(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `icon buttons in guarded files use minimum interactive size`() {
        guardedFiles.forEach { path ->
            val text = sourceOf(path)
            val offenderLines = text.lines()
                .withIndex()
                .filter { (_, line) ->
                    Regex("""size\((28|24|36)\.dp\)""").containsMatchIn(line)
                }
                .map { (index, line) -> "L${index + 1}: ${line.trim()}" }
            assertTrue(
                offenderLines.isEmpty(),
                "XA-20 守护：$path 图标钮命中区残留 28/24/36dp 字面量——统一收 " +
                    "Dimens.minimumInteractiveSize（MICS 32）:\n${offenderLines.joinToString("\n")}",
            )
        }
    }

    @Test
    fun `mics consumption present in all six files`() {
        // 蜂群回归 2026-10-06 更新：ShellToolsParentNode 分组行操作钮改 zrow faint
        // 文字钮（逐字）——无 IconButton 后不再消费 MICS，
        // 移出本断言清单（其余五文件仍钉）
        // 蜂群回归 P2 补射批 2026-10-06 更新：TaskCard 三图标钮（打开文件夹/取消/⋯）
        // 改走 IcBtnContainer（.ic-btn raised 底+发丝边小钮
        // 稿面逐字）——MICS 32 命中区消费移进 IcBtnContainer 本体（KbdText.kt 内
        // .size(Dimens.minimumInteractiveSize)），TaskCard 文件不再直引 MICS。
        // 改钉 TaskCard 消费 IcBtnContainer（命中区统一意图不回退），其余四文件仍钉 MICS。
        val micsFiles = guardedFiles.filter {
            !it.endsWith("ShellToolsParentNode.kt") && !it.endsWith("TaskCard.kt")
        }
        micsFiles.forEach { path ->
            val text = sourceOf(path)
            assertTrue(
                "Dimens.minimumInteractiveSize" in text,
                "XA-20 守护：$path 须消费 Dimens.minimumInteractiveSize（MICS 32 统一命中区——" +
                    "五文件全收编，缺消费 = 收编回退）",
            )
        }
        // TaskCard 专钉：三图标钮走 IcBtnContainer（其本体消费 MICS 32）
        val taskCardText = sourceOf(guardedFiles.last())
        assertTrue(
            "IcBtnContainer" in taskCardText,
            "XA-20 守护：TaskCard 图标钮须走 IcBtnContainer（.ic-btn 形态，其本体消费 " +
                "Dimens.minimumInteractiveSize=MICS 32——缺消费 = 命中区统一回退）",
        )
    }

    @Test
    fun `shell tools page icon buttons all carry mics modifier`() {
        // 块级逐钮断言（ShellToolsPage + device/DeviceStatusBar——两文件均无 20dp
        // 紧凑档豁免面）：既有两断言（28/24/36 字面量拦截 + 文件级 MICS 在场）都拦不住
        //「IconButton 缺 MICS 修饰」的 M3 默认 40dp 盒——按 IconButton( 调用块扫描补上
        // 逐钮强制面；其余文件不扩展（避免误伤 ShellOutputPanel/ShellCustomEditorDialog
        // 的 20dp 紧凑档）
        val perButtonPaths = guardedFiles.filter {
            it.endsWith("ShellToolsPage.kt") || it.endsWith("device/DeviceStatusBar.kt")
        }
        perButtonPaths.forEach { path ->
            val src = sourceOf(path)
            val callChunks = src.split("IconButton(").drop(1)
            assertTrue(
                callChunks.isNotEmpty(),
                "XA-20 守护：$path IconButton( 调用点锚失效（零调用？请同步本守护）",
            )
            callChunks.forEachIndexed { index, chunk ->
                assertTrue(
                    "Dimens.minimumInteractiveSize" in chunk,
                    "XA-20 守护：$path 第 ${index + 1} 处 IconButton 缺 MICS 修饰" +
                        "（缺修饰 = 命中区吃 M3 默认 40dp 盒，与全页 MICS 32 两档并存）",
                )
            }
        }
    }

    @Test
    fun `faint text action hit area carries mics container`() {
        // FaintTextAction 热区 32dp 锚（分组头行尾 ＋/分享/编辑/删除——裸 labelSmall 文本
        // 命中约 20px 且含「删除分组」破坏性入口）：clickable 必须挂含 MICS 的容器
        //（视觉零变化——Text 保持原样式，热区由外层 Box minSize 外扩）
        val parentNodeSrc = sourceOf(guardedFiles.first { it.endsWith("ShellToolsParentNode.kt") })
        val slice = parentNodeSrc.substringAfter("private fun FaintTextAction")
        assertTrue(
            "Dimens.minimumInteractiveSize" in slice,
            "XA-20 守护：FaintTextAction 热区必须由 minimumInteractiveSize 容器承载" +
                "（labelSmall 裸文本命中约 20px，低于全页 32dp 纪律且含破坏性入口）",
        )
        assertTrue(
            Regex("""\.clickable\(onClick = onClick\)""").containsMatchIn(slice),
            "XA-20 守护：FaintTextAction 切片锚失效（clickable 形态漂移后请同步本守护）",
        )
    }

    @Test
    fun `whitelist stale icon button values removed`() {
        val guardSource = sourceOf(
            "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/UiArchitectureGuardTest.kt",
        )
        // 白名单值面收缩锚：六文件不得再登记 24/28/36 图标钮豁免（20/2/13/14/140 等非钮值保留）
        val staleEntries = listOf(
            Regex("""ShellHistoryPanel\.kt" to setOf\([^)]*"24""""              ) to "ShellHistoryPanel 24",
            Regex("""ShellHistoryPanel\.kt" to setOf\([^)]*"28""""              ) to "ShellHistoryPanel 28",
            Regex("""ShellCustomEditorDialog\.kt" to setOf\([^)]*"24""""        ) to "ShellCustomEditorDialog 24",
            Regex("""ShellToolsChildNode\.kt" to setOf\([^)]*"28""""            ) to "ShellToolsChildNode 28",
            Regex("""ShellToolsParentNode\.kt" to setOf\([^)]*"28""""           ) to "ShellToolsParentNode 28",
            Regex("""ShellOutputPanel\.kt" to setOf\([^)]*"28""""               ) to "ShellOutputPanel 28",
            Regex("""TaskCard\.kt" to setOf\([^)]*"36""""                       ) to "TaskCard 36",
        )
        val offenders = staleEntries
            .filter { (pattern, _) -> pattern.containsMatchIn(guardSource) }
            .map { (_, label) -> label }
        assertTrue(
            offenders.isEmpty(),
            "XA-20 守护：UiGuard rawDpAllowedValuesByPath 白名单残留已收编的图标钮豁免值：" +
                "$offenders（例外代码删除后白名单须同步清理——防腐化断言联动）",
        )
    }
}
