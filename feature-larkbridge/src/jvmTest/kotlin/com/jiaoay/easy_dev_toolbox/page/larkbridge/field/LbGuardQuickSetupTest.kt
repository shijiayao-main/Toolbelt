package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LB-11 / LB-18 守护（重设计批 9 · 2026-10-02）：快速上手卡「跳过引导」+ 会话记忆；
 * 搜索结果行「所在分区」可点跳转（含 :ui SectionCard 受控展开扩面）。
 *
 * 「有意留空」是合法形态（dryRun/静态回复用户工作区可为空，VM 工作区警示不阻断）——
 * 门控却视同未完成，快速上手卡对这类用户是常驻噪音且无跳过手段。LB-11 落地面
 * （单文件三改，纯 UI 态零 VM 零落盘）：
 * 1. ConfigEditor 顶层 `quickSetupDismissed` rememberSaveable（会话级，与
 *    advancedExpanded 同语义——不落盘重启恢复）；
 * 2. 判定短路 `!quickSetupDismissed && QuickSetupGate.showQuickSetup(config)`——
 *    dismissed 后卡与弱入口都消失、六分区直出（本会话）；
 * 3. QuickSetupCard 增 onSkip 参数 + 卡头 headerTrailing「跳过引导」（终验 P3 对稿钮位）。
 *
 * 🔴 QuickSetupGate 纯函数面零改动（短路在 UI 层）——QuickSetupGateTest 五用例照跑背书。
 * LB-18（同文件续锚）：「进入分区 →」文字钮 + zoneExpandOverride 受控展开 + :ui SectionCard
 * 两参扩面（expandedOverride/onExpandedChange 带默认零破坏）；@modified/@restart 横切面行
 * 无分区归属 ⇒ 无钮（zoneTitle 恒空，验收 2 零代码成立）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LbGuardQuickSetupTest {

    @Test
    fun `lb11 dismissed state with short circuit and skip button anchored`() {
        val code = normalizedCode(CONFIG_FIELD_RENDERER)
        // 会话级记忆变量（rememberSaveable——与 advancedExpanded 同语义，重启恢复门控）
        assertTrue(
            "var quickSetupDismissed by rememberSaveable { mutableStateOf(false) }" in code,
            "quickSetupDismissed 必须 rememberSaveable（LB-11：会话级记忆，重启恢复）",
        )
        // 判定短路：dismissed ⇒ 卡与弱入口都消失、六分区直出
        assertTrue(
            "!quickSetupDismissed && remember(config) { QuickSetupGate.showQuickSetup(config) }" in code,
            "quickSetup 判定必须含 !quickSetupDismissed && 短路（LB-11 验收：点跳过后卡消失六分区直出）",
        )
        // onSkip 参数 + 调用点接线
        assertTrue(
            "onSkip: () -> Unit" in code,
            "QuickSetupCard 必须增 onSkip 参数（LB-11）",
        )
        assertTrue(
            "onSkip = { quickSetupDismissed = true }" in code,
            "QuickSetupCard 调用点必须接线 onSkip = { quickSetupDismissed = true }（LB-11）",
        )
        // chead 形态锚：跳过钮挂卡头 headerTrailing
        // 槽（原徽章行尾——一行差；功能不变）
        assertTrue(
            "headerTrailing = {" in code && "TextButton(onClick = onSkip)" in code &&
                "\"跳过引导\"" in code,
            "「跳过引导」必须挂卡头 headerTrailing 槽（LB-11 + 终验 P3 对稿钮位）",
        )
    }

    @Test
    fun `lb11 quick setup gate pure function untouched`() {
        // 门控纯函数未动（短路在 UI 层）：showQuickSetup 判据保持三徽章组驱动
        val gate = normalizedCode(QUICK_SETUP_GATE)
        assertTrue(
            "fun showQuickSetup(config: BridgeConfig): Boolean = badgeGroups(config).any { !it.ready }" in gate,
            "QuickSetupGate.showQuickSetup 纯函数面零改动（LB-11 短路在 UI 层）——改动须评审本守护",
        )
        // 零落盘：门控文件不得出现 dismissed/落盘痕迹
        assertFalse(
            "quickSetupDismissed" in gate,
            "QuickSetupGate 不得持有 dismissed 态（LB-11：纯 UI 态，零 VM 零落盘）",
        )
    }

    // ── LB-18：搜索结果行「所在分区」可点跳转（重设计批 9 · 2026-10-02）──

    @Test
    fun `lb18 enter zone button on keyword matches with controlled expansion`() {
        val renderer = normalizedCode(CONFIG_FIELD_RENDERER)
        // 行尾文字钮（仿退出过滤行配方：clickable 无 indication + labelSmall primary）
        assertTrue(
            "\"进入分区 →\"" in renderer,
            "关键词命中行尾必须有「进入分区 →」文字钮（LB-18 验收 1：一键展开所在分区并退出过滤）",
        )
        assertTrue(
            "onEnterZone" in renderer,
            "FilterResultCard 必须有 onEnterZone 回调（LB-18）",
        )
        // 跳转 = 清搜索 + 置受控展开 override
        assertTrue(
            "zoneExpandOverride" in renderer,
            "ConfigEditor 必须持有 zoneExpandOverride 受控展开态（LB-18：SectionCard 内部态无法外部直达）",
        )
        assertTrue(
            "searchQuery = \"\"" in renderer,
            "跳转必须清空搜索框（退出过滤——LB-18 验收 1）",
        )
        // override 释放：用户手动收起时清空交还内部态（防「收不掉」死锁）
        assertTrue(
            "zoneExpandOverride = null" in renderer,
            "用户手动收起必须清 override 交还内部态（LB-18：防收不掉死锁）",
        )
        // :ui 扩面锚（LB-18 殿后：SectionCard 增受控两参带默认零破坏）
        val sectionCard = normalizedCode(SECTION_CARD)
        assertTrue(
            "expandedOverride: Boolean? = null" in sectionCard && "onExpandedChange: ((Boolean) -> Unit)? = null" in sectionCard,
            ":ui SectionCard 必须增 expandedOverride/onExpandedChange 两参带默认零破坏（LB-18 :ui 扩面）",
        )
    }

    @Test
    fun `lb18 cross cutting rows have no enter zone button`() {
        val renderer = normalizedCode(CONFIG_FIELD_RENDERER)
        // @modified/@restart 行 zoneTitle="" ⇒ 无钮（横切面无分区归属语义）——
        // 构造点恒传空 zoneTitle，跳转钮条件含 zoneTitle.isNotBlank()
        assertTrue(
            "ConfigFilter.Match(it, \"\")" in renderer,
            "@modified/@restart 构造 Match 恒空 zoneTitle（LB-18 验收 2：横切面行无钮——零代码成立）",
        )
        assertTrue(
            "match.zoneTitle.isNotBlank()" in renderer,
            "进入分区钮必须以 zoneTitle.isNotBlank() 为条件（LB-18 验收 2）",
        )
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
        const val PKG = "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field"
        const val CONFIG_FIELD_RENDERER = "$PKG/ConfigFieldRenderer.kt"
        const val QUICK_SETUP_GATE = "$PKG/QuickSetupGate.kt"
        const val SECTION_CARD = "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SectionCard.kt"
    }
}
