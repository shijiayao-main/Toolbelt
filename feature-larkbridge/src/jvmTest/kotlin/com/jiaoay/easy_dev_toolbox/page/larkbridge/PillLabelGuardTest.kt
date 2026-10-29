package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DS-11 守护（重设计批 3 · 2026-10-01）：PillLabel 胶囊徽标共享件收编——桥接域部分。
 *
 * 与 :ui 侧 PillLabelTest（件本身的编译可达/值断言/配方源断言）共同完成 DS-11 全卡。
 * 本测试类只覆盖**桥接域消费面**：
 *
 * 1. **私有件清零**：ConfigFieldRenderer.StatusBadge / RestartPill 两私有散写件删除，
 *    全部调用点改引共享件 [com.jiaoay.easy_dev_toolbox.widget.PillLabel]；
 * 2. **二次合成底清零（DL-M10 / 卡面 acceptance）**：`warningContainer.copy(alpha = …)`
 *    全仓生产代码零命中——warningContainer 定义已含 bgSubtle 预洗（EasyToolboxColors），
 *    再 copy 属双洗底；同批顺手裁定：BotCardHeader 的 aiAccentContainer.copy(bgSubtle)
 *    属实色槽二次洗底，同款直出（卡面未列，扩面如实上报）。
 *    🔴 success/errorContainer.copy(bgSubtle) 是实色槽的**合法一次洗底**（侦察勘误口径：
 *    successContainer=0xFFB7EFC0 实色 / errorContainer 走 M3 scheme），保留不动、
 *    不进清零断言；
 * 3. **bgSegTrack 非法挪用清零（DS-11 扩面 5 处 + XA-16 1 处，全仓终验）**：bgSegTrack 是
 *    分段控件轨道 token（Alpha.kt 语义绑定 SegmentedControl），pill 洗底挪用属跨语义借档
 *    （XA-16 判定①）——本批清零 BotCapabilityCard 4 处 + ConfigFieldRenderer 1 处 +
 *    CockpitPanel 1 处（XA-16）；清零后全仓生产命中仅剩 SegmentedControl.kt 合法轨道。
 *    XA-16 剩余秒 pill 双态源码锚在同类 [CockpitRemainingPillGuardTest]（XA-16 专属 commit）。
 * 4. **LB-21（重设计批 7.5 · 补卡）**：CockpitPanel 待确认卡头「N 项进行中」badge——
 *    headerTrailing 槽 + PillLabel + warning 系色对（设计稿 .badge --warn×--warn-c），
 *    N = pendingViews.size 动态；零 pending 时整卡不组合（调用方 if 门控）即零渲染。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class PillLabelGuardTest {

    // ── DS-11：私有件清零 + 共享件引用 ──

    @Test
    fun `status badge and restart pill private widgets are gone`() {
        val text = file(FIELD_RENDERER).readText()
        // 重启 pill 渲染件（RestartPillLabel）在 FieldRow.kt——色对与文案锚随消费点走
        val rowText = file(FIELD_ROW).readText()
        assertFalse(
            "private fun StatusBadge(" in text || "private fun StatusBadge(" in rowText,
            "StatusBadge 私有件必须删除（DS-11：配方共享化进 PillLabel，散写件不得复活）",
        )
        assertFalse(
            "private fun RestartPill(" in text || "private fun RestartPill(" in rowText,
            "RestartPill 私有散写件必须删除（DS-11：改引 PillLabel(container=warningContainer, contentColor=warning)）",
        )
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.widget.PillLabel" in text,
            "ConfigFieldRenderer 必须引 PillLabel（已修改徽标/错误红徽标/快速上手徽章三消费点）",
        )
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.widget.PillLabel" in rowText,
            "FieldRow 必须引 PillLabel（重启 pill 消费点）",
        )
        // 「重启生效」pill 的语义色对不变：warningContainer 底 + warning 字（像素级等价的色对锚）
        assertTrue(
            "\"重启生效\"" in rowText,
            "重启 pill 文案锚「重启生效」缺失——文案不得随收编漂移",
        )
        assertTrue(
            "container = colors.warningContainer" in rowText,
            "重启 pill 必须 warningContainer 直出（DL-M10 预洗槽不再 copy）",
        )
    }

    @Test
    fun `bot capability card pills reference pill label and mute tier`() {
        val text = file(BOT_CAPABILITY_CARD).readText()
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.widget.PillLabel" in text,
            "BotCapabilityCard 必须引 PillLabel（头部 aiAccent pill + 工具名 pill 流 + scopeHint + GrantPill 收编）",
        )
        // 灰态 pill 换源后必须走 surface slot（.pill.mute：surfaceDeep 实底）
        assertTrue(
            "surfaceDeep" in text,
            "BotCapabilityCard 灰态 pill 必须 surfaceDeep 实底（.pill.mute 中性档，替代 onSurface×bgSegTrack 非法挪用）",
        )
        // 头部 aiAccent pill 直出实色 container（扩面裁定如实上报——原 copy(bgSubtle) 属二次洗底）；
        // 终验 review P2：色对经 BotCardHeader 参数化由调用点传入（能力中心改中性灰）
        assertTrue(
            "pillContainer = LocalEasyToolboxColors.current.aiAccentContainer" in text,
            "机器人主卡头部 aiAccent pill 必须 aiAccentContainer 直出（DL-M10 实色槽不再 copy）",
        )
    }

    // ── DS-11：warningContainer.copy 二次合成底全仓清零（DL-M10）──

    @Test
    fun `warning container double wash is eradicated repo wide`() {
        val offenders = productionKotlinFiles().filter {
            it.readText().contains("warningContainer.copy")
        }
        assertTrue(
            offenders.isEmpty(),
            "DL-M10 双洗底清零失败——warningContainer.copy(alpha=…) 生产命中未清：$offenders" +
                "（warningContainer 定义已含 bgSubtle 预洗，再 copy 属双洗底；DS-11 卡面 acceptance）",
        )
    }

    // ── DS-11 扩面 + XA-16 合并核：bgSegTrack 全仓清零（仅剩 SegmentedControl 合法轨道）──

    @Test
    fun `bg seg track misuse is eradicated repo wide`() {
        val legalPaths = setOf(
            // 合法轨道：SegmentedControl 容器胶囊底（token 语义绑定件）
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SegmentedControl.kt",
        )
        // 精确锚 AppAlpha.bgSegTrack（代码引用形态）：改动注释里的文字提及（无 AppAlpha. 前缀）
        // 不算生产消费点——DS-11/XA-16 施工注释均以「原 onSurface×bgSegTrack」形式记述历史。
        val offenders = productionKotlinFiles()
            .filter { it.relativeTo(projectRoot()).path !in legalPaths }
            .filter { "AppAlpha.bgSegTrack" in it.readText() }
        assertTrue(
            offenders.isEmpty(),
            "bgSegTrack 非法挪用清零失败——AppAlpha.bgSegTrack 生产命中未清（应仅剩 SegmentedControl 合法轨道）：$offenders" +
                "（bgSegTrack 是分段控件轨道 token 被 pill 洗底挪用——DS-11 扩面清零 BotCapabilityCard" +
                " 4 处 + ConfigFieldRenderer 1 处、XA-16 清零 CockpitPanel 1 处）",
        )
    }

    // ── XA-16：剩余秒 pill 双态源码锚 → 独立测试类 CockpitRemainingPillGuardTest（同包同 commit 族）──

    // ── LB-21（重设计批 7.5 · 补卡）：待确认卡头「N 项进行中」badge ──

    @Test
    fun `cockpit pending card header renders in-progress count badge`() {
        val text = file(COCKPIT_PANEL).readText()
        // PendingConfirmationsCard 切片锚：从卡头到首个待确认行之间的卡头区
        val anchor = "SectionCard(\n        title = \"待你确认\","
        val anchorIndex = text.indexOf(anchor)
        assertTrue(
            anchorIndex >= 0,
            "PendingConfirmationsCard 卡头切片锚漂移（SectionCard title = 待你确认 改写后须同步本守护）",
        )
        val headerSlice = text.substring(anchorIndex, anchorIndex + 800)
        // badge 本体：headerTrailing 槽挂 PillLabel「N 项进行中」，N = pendingViews.size 动态
        assertTrue(
            "headerTrailing = {" in headerSlice,
            "待确认卡头必须有 headerTrailing badge 槽（LB-21：分区目录行同款槽位语言）",
        )
        assertTrue(
            "\"\${pendingViews.size} 项进行中\"" in headerSlice,
            "badge 文案必须「N 项进行中」且 N = pendingViews.size 动态计数（LB-21 验收：稿面 larkbridge-cockpit）",
        )
        assertTrue(
            "PillLabel(" in headerSlice,
            "badge 必须经 PillLabel 渲染（DS-11 胶囊徽标唯一配方，不得散写私有件）",
        )
        // 色对：warning 系（设计稿 .badge = --warn×--warn-c；与剩余秒超时态同族语义）
        assertTrue(
            "container = colors.warningContainer" in headerSlice,
            "badge 底必须 warningContainer 直出（DL-M10 container 直出，LB-21 卡头紧迫语义）",
        )
        assertTrue(
            "contentColor = colors.warning" in headerSlice,
            "badge 字色必须 warning（设计稿 --warn 对 --warn-c 色对）",
        )
    }

    // ── 扫盘基建（扫生产 .kt：src/jvmMain + 纯 JVM 模块 src/main；剥 build 与测试目录）──

    /**
     * 生产 Kotlin 源全集（扫盘守护面）：项目根下各模块的 src/jvmMain 与 src/main，
     * 排除 build 产物与测试源（jvmTest/androidUnitTest 等非生产面）。
     */
    private fun productionKotlinFiles(): List<File> {
        val root = projectRoot()
        val modules = listOf(
            "composeApp/src", "core/src", "ui/src",
            "feature-larkbridge/src", "feature-logworkbench/src", "feature-logkit/src",
            "feature-shelltools/src", "feature-timestamp/src", "feature-ai/src",
            "feature-archive/src", "feature-voice/src", "feature-mcp/src",
            "feature-quest/src", "lark-bridge/src", "lark-gateway/src", "lark-ws/src",
            "log-tools/src", "test-fixtures/src",
        )
        return modules.flatMap { dir ->
            File(root, dir).walkTopDown()
                .onEnter { !it.name.equals("build", ignoreCase = true) }
                .filter { it.isFile && it.extension == "kt" }
                // 只扫生产源集：路径含 /jvmMain/ 或 /main/（测试源集 jvmTest/test 不在扫盘面）
                .filter { it.absolutePath.contains("/jvmMain/") || it.absolutePath.contains("/main/") }
                .toList()
        }
    }

    // ── 锚文件（缺失即红——重命名/移动后须同步本守护路径）──

    private fun file(relPath: String): File {
        val file = File(projectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file
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
        const val FIELD_RENDERER =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/ConfigFieldRenderer.kt"
        const val FIELD_ROW =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/FieldRow.kt"
        const val BOT_CAPABILITY_CARD =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/BotCapabilityCard.kt"
        const val COCKPIT_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanel.kt"
    }
}
