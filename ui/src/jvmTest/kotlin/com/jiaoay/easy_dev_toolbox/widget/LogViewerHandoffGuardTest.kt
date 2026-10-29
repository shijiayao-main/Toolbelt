package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 查看器「送往 Tag 过滤」入口守护（源码锚——渲染件无 Compose 运行时直测缝，
 * [LogViewerDeepAnalyzeGuardTest] 同款形态）：
 *
 * - **入口经 TaskPipelineHandoff 单源**：目标查
 *   [com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff] 单张映射表
 *   （查看器不自持命令 id——漂移即断链）；offer 直调接法 = logcat 产物先例；
 * - **渲染门控（Ready 态「送往 Tag 过滤」）**：isWired 门控按钮缺席 + Ready 态才
 *   渲染（Failed 态不投：加密档由「送 XLog 解密」衔接承接、非加密失败由失败回执
 *   引导；Drifted 态索引失效不投）；
 * - **渲染门控（Failed 态「送 XLog 解密」）**：仅加密档 Failed 态渲染（判型经引擎
 *   Failure.encrypted typed 档位，禁止失败文案字符串匹配），isWired 门控按钮缺席；
 * - **映射表登记锚**：LOG_VIEWER / LOG_VIEWER_ENCRYPTED 来源 key 在映射表内，
 *   目标命令 id 在 LogKitContributions 注册表内（palette 解析落空即衔接静默失效，
 *   id 锚防漂移）。
 */
class LogViewerHandoffGuardTest {

    @Test
    fun `viewer offers tag filter handoff via single-source mapping table`() {
        val source = logViewerDialogSource()
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff" in source,
            "查看器须引入 TaskPipelineHandoff（「送往 Tag 过滤」唯一投递面，不得另造第二套）",
        )
        assertTrue(
            "TaskPipelineHandoff.nextToolTargetOf(TaskPipelineHandoff.SourcePageId.LOG_VIEWER)" in source,
            "查看器目标须经 nextToolTargetOf 查映射表单源（本件不自持映射）",
        )
        assertTrue(
            "TaskPipelineHandoff.offer(target, filePath)" in source,
            "查看器投递须直调 offer（接法 = logcat 产物「送往 Tag 过滤」先例）",
        )
        assertFalse(
            "TaskPipelineHandoff.wire" in source,
            "查看器是生产侧只 offer；wire 单点在宿主 main.kt",
        )
        assertFalse(
            "logkit.tag-filter" in source,
            "查看器不得自持目标命令 id 字符串（id 由映射表单源持有，自持即漂移面）",
        )
    }

    @Test
    fun `viewer tag filter entry is gated by wiring and ready state`() {
        val source = logViewerDialogSource()
        assertTrue(
            "uiState == ViewerUiState.Ready && TaskPipelineHandoff.isWired" in source,
            "Ready 态「送往 Tag 过滤」入口渲染须双门控：宿主未接线按钮缺席 + 仅打开" +
                "成功态可投（Failed 态不投——加密档由「送 XLog 解密」衔接承接）",
        )
    }

    @Test
    fun `viewer encrypted failed entry is gated by typed flag and wiring`() {
        val source = logViewerDialogSource()
        // 判型单源：加密档判据 = 引擎 Failure.encrypted typed 档位直传，禁止字符串匹配
        assertTrue(
            "(uiState as? ViewerUiState.Failed)?.encrypted == true" in source,
            "加密档 Failed 判据须取引擎 typed 档位（判型单源，失败文案改字不得断链）",
        )
        assertTrue(
            "failedEncrypted && TaskPipelineHandoff.isWired" in source,
            "Failed 态「送 XLog 解密」入口渲染须双门控：宿主未接线按钮缺席 + 仅加密档" +
                "Failed 态渲染（非加密失败不渲染，维持失败回执引导现状）",
        )
        assertTrue(
            "TaskPipelineHandoff.nextToolTargetOf(TaskPipelineHandoff.SourcePageId.LOG_VIEWER_ENCRYPTED)" in source,
            "加密档衔接目标须经 nextToolTargetOf 查映射表单源（本件不自持映射）",
        )
        assertFalse(
            "logkit.xlog-decrypt" in source,
            "查看器不得自持目标命令 id 字符串（id 由映射表单源持有，自持即漂移面）",
        )
    }

    @Test
    fun `mapping table registers viewer source with the live tag filter target`() {
        val core = sourceAt(
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/TaskPipelineHandoff.kt",
        )
        assertTrue(
            "const val LOG_VIEWER = \"LogViewer\"" in core,
            "映射表须登记查看器专属来源 key（外来文件无生产页身份，按读面产物语义登记）",
        )
        val entryAnchor = "SourcePageId.LOG_VIEWER to NextToolTarget("
        val entryStart = core.indexOf(entryAnchor)
        assertTrue(entryStart >= 0, "映射表须含 LOG_VIEWER 条目（缺席即查看器入口静默失效）")
        // 条目切片：锚点起到右括号收口（NextToolTarget 构参块），漂移混入相邻条目即断言失真
        val entry = core.substring(entryStart).substringBefore(")")
        listOf(
            "label = \"送往 Tag 过滤\"" to "条目文案（动作动词开头，与先例条目同款）",
            "commandId = \"logkit.tag-filter\"" to "目标 = palette「按 Tag 过滤日志」带参命令 id",
            "fileParamId = \"file_path\"" to "文件路径落点参数（与目标命令 FILE 型参数逐字一致）",
        ).forEach { (needle, why) ->
            assertTrue(needle in entry, "LOG_VIEWER 条目缺「$needle」——$why")
        }

        // Failed 态加密件条目：目标 = XLog 解密（回管线主干前半段）
        val encryptedAnchor = "SourcePageId.LOG_VIEWER_ENCRYPTED to NextToolTarget("
        val encryptedStart = core.indexOf(encryptedAnchor)
        assertTrue(encryptedStart >= 0, "映射表须含 LOG_VIEWER_ENCRYPTED 条目（缺席即 Failed 态衔接静默失效）")
        val encryptedEntry = core.substring(encryptedStart).substringBefore(")")
        listOf(
            "label = \"送 XLog 解密\"" to "条目文案（动作动词开头）",
            "commandId = \"logkit.xlog-decrypt\"" to "目标 = palette「解密 XLog 设备日志」带参命令 id",
            "fileParamId = \"file_path\"" to "加密件路径落点参数（与目标命令 FILE 型参数逐字一致）",
        ).forEach { (needle, why) ->
            assertTrue(needle in encryptedEntry, "LOG_VIEWER_ENCRYPTED 条目缺「$needle」——$why")
        }
    }

    @Test
    fun `target command id resolves in the palette registry`() {
        val contributions = sourceAt(
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logkit/LogKitContributions.kt",
        )
        assertTrue(
            "id = \"logkit.tag-filter\"" in contributions,
            "目标命令 id=logkit.tag-filter 须在 LogKitContributions 注册表内" +
                "（改名/退役即 palette 解析落空——衔接静默失效）",
        )
        assertTrue(
            "id = \"file_path\"" in contributions,
            "目标命令须保留 file_path 参数（查看器投递的文件路径落点）",
        )
        assertTrue(
            "id = \"logkit.xlog-decrypt\"" in contributions,
            "Failed 态衔接目标 id=logkit.xlog-decrypt 须在 LogKitContributions 注册表内" +
                "（改名/退役即 palette 解析落空——衔接静默失效）",
        )
    }

    private fun logViewerDialogSource(): String = sourceAt(
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/LogViewerDialog.kt",
    )

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
        }
        val file = File(dir, relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
