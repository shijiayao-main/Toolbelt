package com.jiaoay.easy_dev_toolbox.page.logworkbench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作台证据流截断行动作的查看器接线源码锚——LogWorkbenchPage.kt 截断条
 * `onViewFullArtifact` 装配块（EvidenceTimeline 尾参）。
 *
 * 失效形态：接线被删/被改后截断动作**静默退回旧行为**（只开产物目录——出应用进
 * 文件管理器，「核验报告关键依据行原文」的研判收尾动作丢行号跳转/级别导航/文本
 * 搜索）。动作闭包是 Composable 内局部态，无行为直测缝——按仓库 wiring 守护
 * 先例（源码切片钉死接线面，槽本体行为在 :core LogViewerRequestTest 背书）。
 *
 * 钉死锚（LogWorkbenchPage.kt）：
 * 1. 截断动作经 :core 单源槽 [com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest]
 *    发请求，请求路径 = activeEvidence 路径（当前产物单源，勿读截断副本）；
 * 2. 宿主未挂载查看器时降级 openInFolder + toast 明示原因，不静默 no-op
 *    （hostMounted = 降级判据真源）；
 * 3. 超窗跳转（目标时间戳不在前窗且产物截断）带时间戳直达路由：宿主挂载时
 *    offer(产物路径, 时间戳) 一步打开即定位；未挂载降级 toast 指截断条
 *    「查看完整产物」钮（指路对象自身有 hostMounted 降级，恒有效可达）；
 * 4. EvidenceTimeline（:ui 渲染面）行尾钮名与页内动作同源（查看完整产物）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-logworkbench:jvmTest --rerun-tasks --no-build-cache`。
 */
class WorkbenchTruncationViewerWiringGuardTest {

    @Test
    fun `truncation trailing action routes active evidence path to viewer slot`() {
        val source = logWorkbenchPageSource()
        // 装配块切片：onViewFullArtifact 实参区（到 onCancel 前）
        val block = source
            .substringAfter("onViewFullArtifact = {")
            .substringBefore("onCancel = vm::cancel")
        assertTrue(
            "LogViewerRequest.offer(path, runId = vm.viewerBookmarkRunId())" in block,
            "截断动作必须经 :core 查看器请求槽发打开请求（漏接 = 截断条仍只开产物目录，" +
                "完整产物进不了应用内查看器）；runId = 当前 run 书签锚（产物打开随 run 恢复书签）",
        )
        assertTrue(
            "activeEvidence?.let { path ->" in block,
            "查看器请求路径必须取 activeEvidence（当前产物单源——读其他副本会开错文件）",
        )
        assertTrue(
            "LogViewerRequest.hostMounted.value" in block,
            "截断动作必须先查宿主挂载态（漏判 = 宿主未接线时静默 no-op）",
        )
    }

    @Test
    fun `truncation action degrades to open folder with explicit toast when host not mounted`() {
        val source = logWorkbenchPageSource()
        val block = source
            .substringAfter("onViewFullArtifact = {")
            .substringBefore("onCancel = vm::cancel")
        assertTrue(
            "DesktopUtils.openInFolder(path)" in block,
            "宿主未挂载分支必须降级打开产物所在目录（可见回退动作，非静默 no-op）",
        )
        assertTrue(
            "应用内查看器未挂载，已打开产物所在目录" in block,
            "降级分支必须 toast 明示原因（用户须可感知为何走了文件管理器）",
        )
        assertTrue(
            "已在应用内查看器打开完整产物" in block,
            "查看器分支必须有点击回执（动作可感知语义——StatusSignals 通道）",
        )
    }

    @Test
    fun `out of window jump toast points to the actually available truncation action`() {
        val source = logWorkbenchPageSource()
        assertFalse(
            "请「打开报告文件」查看完整产物" in source,
            "超窗 toast 不得再指「打开报告文件」（死指针——该钮在报告生成期不渲染，" +
                "且报告文件 ≠ 被截断的产物文件）",
        )
        assertTrue(
            "可点截断条「查看完整产物」" in source,
            "超窗 toast 必须指截断条「查看完整产物」钮（与实际可用动作一致）",
        )
    }

    @Test
    fun `out of window click routes evidence path with timestamp to viewer slot`() {
        val source = logWorkbenchPageSource()
        // 超窗分支切片：freshIdx < 0 && result.truncated 起、jumpToTimestamp 函数尾
        val block = source
            .substringAfter("} else if (freshIdx < 0 && result.truncated) {")
            .substringBefore("\n    }\n")
        assertTrue(
            "LogViewerRequest.offer(file, initialTimestamp = timestamp, runId = vm.viewerBookmarkRunId())" in block,
            "超窗点击必须经 :core 查看器槽发起带时间戳的打开请求（漏接 = 查看器打开后" +
                "不定位，用户仍要手动输一遍时间戳跳转——直达价值全丢）；runId = 当前 run 书签锚",
        )
        assertTrue(
            "LogViewerRequest.hostMounted.value" in block,
            "超窗直达路由必须先查宿主挂载态（漏判 = 宿主未接线时请求静默蒸发）",
        )
        assertTrue(
            "已在应用内查看器打开并定位到该时间戳" in block,
            "直达路由必须 toast 回执已定位动作（页内未跳的原因 + 查看器已开已定位两态可感知）",
        )
        assertTrue(
            "可点截断条「查看完整产物」" in block,
            "宿主未挂载降级分支必须保持指路截断条动作（可见回退，非静默 no-op）",
        )
    }

    @Test
    fun `evidence timeline trailing button label matches viewer action`() {
        val source = evidenceTimelineSource()
        assertFalse(
            "onOpenArtifactDir" in source,
            "EvidenceTimeline 不得保留旧开目录参数名（截断行尾动作已迁移查看器语义）",
        )
        assertTrue(
            "Text(\"查看完整产物\")" in source,
            "EvidenceTimeline 截断条行尾钮文案 = 「查看完整产物」（页内 toast 指路对象，" +
                "两处文案同源勿漂移）",
        )
        assertTrue(
            "onViewFullArtifact: (() -> Unit)? = null" in source,
            "查看动作参数保留可空默认（未接能力的调用方不渲染行尾钮——契约不变）",
        )
    }

    // ── 源码锚 ──

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun logWorkbenchPageSource(): String {
        val file = File(
            projectRoot(),
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后须同步本守护）")
        return file.readText()
    }

    private fun evidenceTimelineSource(): String {
        val file = File(
            projectRoot(),
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/EvidenceTimeline.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
