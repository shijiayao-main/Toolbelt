package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.evidence.EvidenceCitationCheck
import com.jiaoay.easy_dev_toolbox.core.evidence.EvidenceRef
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 证据引用两态 chip 行为面 + 接线面（可验证报告段 2）：
 *
 * 行为面（[readEvidenceRef]/[evidenceChipTargetOf] 纯函数直测——临时产物文件夹具）：
 * - Verified 引用：窗口化回读摘要（引用区间一次流读）+ chip 直达目标 (文件名, 1 基起始行)；
 * - 校验失败（TimestampMismatch/LineRangeOutOfBounds/FileUnresolved）：不产 chip 目标
 *   且不产摘要——行号漂移防线（产物重跑覆盖后编造/漂移行号根本不可点）；
 * - 旧格式引用（无标记字段）：纯时间戳现状语义——全量摘要回读、零校验零注记零 chip。
 *
 * 接线面（源码切片守护——动作闭包无 Compose 运行时直测缝，切片配方同
 * WorkbenchTruncationViewerWiringGuardTest；LogViewerRequest 槽本体行为在
 * :core LogViewerRequestTest 背书）：chip 点击以 (解析路径, initialLine) 进查看器槽
 * 且先查 hostMounted；未挂载/解析失败降级 toast 可见不静默。
 *
 * 扫盘型切片会被 build cache 假绿：验收须 `--rerun-tasks --no-build-cache`。
 */
class WorkbenchEvidenceCitationChipTest {

    // ── 行为面：readEvidenceRef / evidenceChipTargetOf ──

    @Test
    fun `verified 引用窗口回读摘要并产 chip 直达目标`() {
        val path = evidenceFile(
            "tag.filter-camera.txt",
            listOf(
                "09-19 10:29:49.000 D/T: pre",
                "09-19 10:29:50.000 E/CameraManager: camera route failed",
                "  continuation without leading timestamp",
                "09-19 10:29:51.000 D/T: after",
            ),
        )
        val view = readEvidenceRef(
            ref = EvidenceRef(
                timestamp = "09-19 10:29:50.000",
                tag = null,
                snippet = null,
                fileName = "tag.filter-camera.txt",
                startLine = 2,
                endLine = 3,
            ),
            evidenceFiles = listOf(path),
        )
        assertEquals(EvidenceCitationCheck.Verified, view.check, "硬门禁双过（区间在界 + 区间行首时间戳相等）")
        assertEquals("CameraManager", view.ref.tag, "命中行 TAG 从引用区间窗内取")
        assertEquals("camera route failed", view.ref.snippet, "命中行消息首行摘要（续行无行首时间戳不参与命中）")
        assertEquals(
            "tag.filter-camera.txt" to 2,
            evidenceChipTargetOf(view.ref, view.check),
            "chip 直达目标 = (引用文件名, 引用区间起 1 基)",
        )
    }

    @Test
    fun `timestampMismatch 引用不产 chip 目标且不产摘要`() {
        // 产物重跑覆盖场景：确定性命名产物被重跑换血，区间指向的行内容已不是引用行
        val path = evidenceFile(
            "tag.filter-camera.txt",
            listOf(
                "09-19 10:40:01.000 D/T: 重跑后的行——旧内容已不在",
                "09-19 10:40:02.000 D/T: 也不是引用行",
            ),
        )
        val view = readEvidenceRef(
            ref = EvidenceRef(
                timestamp = "09-19 10:29:50.000",
                tag = null,
                snippet = null,
                fileName = "tag.filter-camera.txt",
                startLine = 1,
                endLine = 2,
            ),
            evidenceFiles = listOf(path),
        )
        assertEquals(EvidenceCitationCheck.TimestampMismatch, view.check)
        assertNull(view.ref.tag, "硬门禁未过不产摘要（窗口无命中行——失败引用不展示可能错位的 snippet）")
        assertNull(view.ref.snippet, "硬门禁未过不产摘要")
        assertNull(
            evidenceChipTargetOf(view.ref, view.check),
            "校验失败永不产 chip 直达目标（行号漂移防线——漂移行号根本不可点）",
        )
    }

    @Test
    fun `校验失败其余两态同样不产 chip 目标`() {
        val path = evidenceFile("a.txt", listOf("09-19 10:29:50.000 D/T: only line"))
        // LineRangeOutOfBounds：区间越出文件尾（产物截短场景）
        val outOfBounds = readEvidenceRef(
            ref = EvidenceRef("09-19 10:29:50.000", null, null, "a.txt", 5, 9),
            evidenceFiles = listOf(path),
        )
        assertEquals(EvidenceCitationCheck.LineRangeOutOfBounds, outOfBounds.check)
        assertNull(evidenceChipTargetOf(outOfBounds.ref, outOfBounds.check), "越界引用不可点")
        // FileUnresolved：引用文件名不在会话证据产物集
        val unresolved = readEvidenceRef(
            ref = EvidenceRef("09-19 10:29:50.000", null, null, "ghost.txt", 1, 1),
            evidenceFiles = listOf(path),
        )
        assertEquals(EvidenceCitationCheck.FileUnresolved, unresolved.check)
        assertNull(evidenceChipTargetOf(unresolved.ref, unresolved.check), "解析域外引用不可点")
    }

    @Test
    fun `旧格式引用保持纯时间戳现状语义`() {
        val path = evidenceFile(
            "full.txt",
            listOf("09-19 10:29:50.000 E/CameraManager: camera route failed"),
        )
        val view = readEvidenceRef(
            ref = EvidenceRef(timestamp = "09-19 10:29:50.000", tag = null, snippet = null),
            evidenceFiles = listOf(path),
        )
        assertNull(view.check, "旧格式（无标记字段）不校验——check = null")
        assertEquals("CameraManager", view.ref.tag, "摘要回读现状语义（全量扫描）不回退")
        assertEquals("camera route failed", view.ref.snippet, "摘要回读现状语义不回退")
        assertNull(evidenceChipTargetOf(view.ref, view.check), "旧格式引用零 chip（纯现状）")
    }

    @Test
    fun `摘要取自引用区间窗内命中行而非全量首现`() {
        // 窗口化回读的可观察差异：同时间戳在文件更早行也出现时，旧全量扫描取首个
        // 命中（诱饵行）；区间回读只取引用区间内的命中行——摘要语义随引用标记收窄
        val path = evidenceFile(
            "tag.filter-camera.txt",
            listOf(
                "09-19 10:29:50.000 E/DecoyTag: 诱饵——同时间戳首现于别行",
                "09-19 10:29:51.000 D/T: filler",
                "09-19 10:29:52.000 D/T: filler",
                "09-19 10:29:53.000 D/T: filler",
                "09-19 10:29:50.000 E/CameraManager: 引用区间内的真命中行",
            ),
        )
        val view = readEvidenceRef(
            ref = EvidenceRef(
                timestamp = "09-19 10:29:50.000",
                tag = null,
                snippet = null,
                fileName = "tag.filter-camera.txt",
                startLine = 5,
                endLine = 5,
            ),
            evidenceFiles = listOf(path),
        )
        assertEquals(EvidenceCitationCheck.Verified, view.check)
        assertEquals("CameraManager", view.ref.tag, "摘要来自引用区间窗内命中行（非全量首现诱饵行）")
        assertEquals("引用区间内的真命中行", view.ref.snippet)
    }

    // ── 接线面：chip 点击 → LogViewerRequest（hostMounted 守卫） ──

    @Test
    fun `chip 点击以正确 path 与 initialLine 进查看器槽且先查宿主挂载态`() {
        val block = citeToViewerBlock()
        assertTrue(
            "LogViewerRequest.offer(file, initialLine = startLine, runId = vm.viewerBookmarkRunId())" in block,
            "chip 直达必须以 (解析路径, 引用起始行, 当前 run 书签锚) 进 :core 查看器槽" +
                "（initialLine 1 基——查看器分页到该行；runId = run 级书签持久化锚）",
        )
        assertTrue(
            "LogViewerRequest.hostMounted.value" in block,
            "offer 前必须查宿主挂载态（漏判 = 宿主未接线时请求静默蒸发）",
        )
        assertTrue(
            "evidenceFiles.firstOrNull" in block,
            "文件名→会话证据产物集解析必须取当前 evidenceFiles（引用标记只写裸文件名，页面侧解析成路径）",
        )
    }

    @Test
    fun `行号档 offer 全页唯一出现点在 citeToViewer 守卫块内`() {
        val source = logWorkbenchPageSource()
        assertEquals(
            1,
            source.split("initialLine = startLine").size - 1,
            "行号档 offer 唯一出现点 = citeToViewer（chip 专用通路——校验未过的引用无任何行号 offer 面可走）",
        )
    }

    @Test
    fun `hostMounted false 时降级 toast 可见不静默 no-op`() {
        val block = citeToViewerBlock()
        assertTrue(
            "应用内查看器未挂载" in block,
            "宿主未挂载分支必须有降级 toast 明示原因（不静默 no-op——超窗直达先例同款降级形态）",
        )
        assertTrue(
            "StatusSignals.show" in block,
            "降级分支经 StatusSignals 通道呈现（用户可感知为何未开查看器）",
        )
    }

    @Test
    fun `引用文件名解析失败 toast 明示不静默 no-op`() {
        val block = citeToViewerBlock()
        assertTrue(
            "不在当前会话产物集" in block,
            "文件名解析失败分支必须有 toast 明示（禁静默 no-op）",
        )
    }

    @Test
    fun `chip 渲染以 Verified 为唯一可点态且降级态保持时间戳链`() {
        val source = reportPanelSource()
        assertTrue(
            "evidenceChipTargetOf(" in source,
            "两态判定必须走纯函数单一决策源（渲染面不自行判 Verified）",
        )
        assertTrue(
            "onCiteToViewer(citeFile, citeLine)" in source,
            "chip 可点态点击直达回调（fileName + 1 基起始行）",
        )
        assertTrue(
            "onJumpToTimestamp(ref.timestamp)" in source,
            "降级态时间戳行保持现状跳转链（时间戳链保持——校验失败不牺牲既有导航语义）",
        )
        assertTrue(
            "行号未验" in source,
            "校验失败/未验的带区间引用必须有 faint 注记（可见标注——编造行号被标出）",
        )
        assertTrue(
            "ref.fileName != null && ref.startLine != null" in source,
            "注记只出在带区间引用上（旧格式 ref 无区间字段连注记都不出——纯现状零注记）",
        )
    }

    // ── 夹具与源码锚 ──

    /** 临时产物文件（每用例独立目录——互不串扰；进程退出清理） */
    private fun evidenceFile(name: String, lines: List<String>): String {
        val dir = Files.createTempDirectory("wb-cite-chip").toFile()
        dir.deleteOnExit()
        return dir.resolve(name).apply {
            writeText(lines.joinToString("\n") + "\n")
            deleteOnExit()
        }.absolutePath
    }

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun logWorkbenchPageSource(): String =
        source(
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt",
        )

    private fun reportPanelSource(): String =
        source(
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchReportPanel.kt",
        )

    /** citeToViewer 函数体切片（函数体内部无 4 空格闭合大括号——首个闭合即函数尾） */
    private fun citeToViewerBlock(): String =
        logWorkbenchPageSource()
            .substringAfter("fun citeToViewer(")
            .substringBefore("\n    }\n")

    private fun source(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
