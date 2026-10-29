package com.jiaoay.easy_dev_toolbox.page.timestamp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「当前时间」卡折叠化接线源码锚守护。
 *
 * 验收面：「默认进时间戳签『当前时间』卡仅一行（秒值可复制）；点展开显
 * 三行；展开态切签再切回保持展开（会话内）」。落法（单行方案作废——
 * 折叠态零信息不合验收）：
 * 1. SectionCard collapsible = true + expandedOverride/onExpandedChange 受控态
 *    （页面 rememberSaveable 承接——切签回保持展开）；
 * 2. headerTrailing 槽承载秒值 + CopyIconButton（折叠态单行可复制）；
 * 3. 展开态 trailing 去重（首行即秒值，`if (!expanded)` 门控）；
 * 4. 心跳不动（nowMillis VM 单例，折叠只是渲染层）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-timestamp:jvmTest --rerun-tasks --no-build-cache`。
 */
class NowTimeCardCollapseWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "feature-timestamp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `now time card is collapsible with default collapsed state`() {
        val text = pageSource()
        // 折叠化锚：collapsible = true（标题行可点展开收起）+ 默认收起（页面态初值 false）
        val nowCardAnchor = text.indexOf("private fun NowTimeCard(")
        assertTrue(nowCardAnchor >= 0, "TL-09 守护：NowTimeCard 须为页面私有件（NowTimeCard( 调用区之后定义）")
        val cardBlock = text.substring(nowCardAnchor)
        assertTrue(
            "collapsible = true" in cardBlock,
            "TL-09 守护：NowTimeCard 的 SectionCard 必须 collapsible = true（折叠化主锚）",
        )
        assertTrue(
            Regex("""rememberSaveable \{ mutableStateOf\(false\) \}""").containsMatchIn(text),
            "TL-09 守护：折叠态须 rememberSaveable { mutableStateOf(false) }（默认折叠单行 + 切签回保持展开）",
        )
    }

    @Test
    fun `collapsed header trailing carries seconds value with copy`() {
        val text = pageSource()
        val nowCardAnchor = text.indexOf("private fun NowTimeCard(")
        val cardBlock = text.substring(nowCardAnchor)
        assertTrue(
            "headerTrailing = {" in cardBlock,
            "TL-09 守护：NowTimeCard 须以 headerTrailing 槽承载折叠态单行（单行方案作废——折叠态零信息不合验收）",
        )
        assertTrue(
            "CopyIconButton(" in cardBlock,
            "TL-09 守护：headerTrailing 秒值须配 CopyIconButton（默认单行秒值可复制）",
        )
        // 展开态去重：trailing 秒值与展开首行（秒 10 位）重复，须 if (!expanded) 门控
        assertTrue(
            Regex("""if \(!expanded\)""").containsMatchIn(cardBlock),
            "TL-09 守护：headerTrailing 秒值须以 if (!expanded) 门控（展开态与首行重复，去重）",
        )
    }

    @Test
    fun `expanded content keeps three rows via override wiring`() {
        val text = pageSource()
        val nowCardAnchor = text.indexOf("private fun NowTimeCard(")
        val cardBlock = text.substring(nowCardAnchor)
        // 展开显三行（原样复用 NowTimeRow）
        assertTrue(
            Regex("""NowTimeRow\(""").findAll(cardBlock).count() >= 3,
            "TL-09 守护：展开态须显三行 NowTimeRow（秒/毫秒/日志格式，现状复用）",
        )
        // 受控展开接线（切签回保持展开的承接面）
        assertTrue(
            "expandedOverride = expanded" in cardBlock && "onExpandedChange = onExpandedChange" in cardBlock,
            "TL-09 守护：折叠态须走 expandedOverride/onExpandedChange 受控接线" +
                "（页面 rememberSaveable 承接——切签回保持展开）",
        )
        // 心跳不动：nowMillis 数据源仍来自 VM（折叠只是渲染层）
        assertTrue(
            "nowMillis by viewModel.nowMillis.collectAsState()" in text,
            "TL-09 守护：nowMillis 心跳数据源不得动（VM 单例恒跑，折叠只是渲染层省行）",
        )
        assertFalse(
            Regex("""LaunchedEffect\(Unit\)[\s\S]*?delay\(500\)""").containsMatchIn(
                text.substring(text.indexOf("fun TimestampPageContent")),
            ),
            "TL-09 守护：页面内不得新增 500ms 心跳（VM 单例已有——双心跳会重复刷新）",
        )
    }
}
