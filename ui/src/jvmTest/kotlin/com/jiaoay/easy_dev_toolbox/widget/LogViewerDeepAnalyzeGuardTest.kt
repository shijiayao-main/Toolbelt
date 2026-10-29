package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 查看器「深度分析」入口守护（源码锚——渲染件无 Compose 运行时直测缝，
 * [LogViewerSelectionGuardTest] 同款形态）：
 *
 * - **入口存在且经同一 hook 单源**：LogViewerDialog 经
 *   [com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook] offer，
 *   宿主 AppViewModel 单点 wire——不得另造第二套投递链路；
 * - **渲染门控**：isWired 门控按钮缺席（logcat 送工作台同款）+ Ready 态
 *   才渲染（Failed 态不投：加密档由「送 XLog 解密」衔接承接，Drifted 态索引失效不投）；
 * - **症状预填文案**：与四胞胎页产物投递同款「<来源>深度分析」语义；
 * - **问题时间点透传边界**：offer 第三参 = [LogViewerDialog.initialTimestamp]
 *   （带定位打开时复现时刻直达工作台时间域，无定位 null = 不预填）；
 *   行号档（initialLine）不透传——行号不是时间点。
 */
class LogViewerDeepAnalyzeGuardTest {

    @Test
    fun `viewer offers deep analyze via single-source hook`() {
        val source = logViewerDialogSource()
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook" in source,
            "查看器须引入 DeepAnalyzeHook（研判链唯一投递槽，不得另造第二套）",
        )
        assertTrue(
            "DeepAnalyzeHook.offer(listOf(filePath), \"查看器打开的日志深度分析\", initialTimestamp)" in source,
            "查看器须以「查看器打开的日志深度分析」预填症状经 hook 直投（与四胞胎页" +
                "「<来源>深度分析」文案先例同语义），且第三参透传 initialTimestamp" +
                "（带定位打开的复现时刻直达工作台时间域）",
        )
        assertFalse(
            "DeepAnalyzeHook.wire" in source,
            "查看器是生产侧只 offer；wire 单点在宿主 AppViewModel init",
        )
    }

    @Test
    fun `deep analyze offer passes only timestamp slot not line slot`() {
        val source = logViewerDialogSource()
        val offerLine = source.lineSequence()
            .firstOrNull { "DeepAnalyzeHook.offer(" in it }
        assertNotNull(offerLine, "深度分析 offer 调用行缺失")
        assertTrue(
            "initialTimestamp" in offerLine!!,
            "offer 第三参须为 initialTimestamp（带定位打开 = 该时间戳；无定位 null = 不预填）",
        )
        assertFalse(
            "initialLine" in offerLine!!,
            "行号档（initialLine）不透传——行号不是时间点，不得作为问题时间点载荷",
        )
    }

    @Test
    fun `viewer deep analyze entry is gated by wiring and ready state`() {
        val source = logViewerDialogSource()
        assertTrue(
            "uiState == ViewerUiState.Ready && DeepAnalyzeHook.isWired" in source,
            "入口渲染须双门控：宿主未接线按钮缺席 + 仅打开成功态可投" +
                "（Failed 态不投——加密档由「送 XLog 解密」衔接承接）",
        )
    }

    @Test
    fun `host keeps single wire point for the hook`() {
        val host = sourceAt(
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/AppViewModel.kt",
        )
        assertTrue(
            "DeepAnalyzeHook.wire" in host,
            "宿主 AppViewModel 须保持 DeepAnalyzeHook 单点 wire" +
                "（查看器入口与四胞胎页/logcat 共用同一接线）",
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
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
