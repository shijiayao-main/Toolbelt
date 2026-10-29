package com.jiaoay.easy_dev_toolbox.widget.evidence

import androidx.compose.runtime.Composable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MarkdownTableBlock 表格渲染块验收断言：
 *
 * 时间线表格渲染件（与 EvidenceTimeline 同目录）——报告「## 完整时间线」段
 * `|` 分割行的列对齐网格。解析逻辑在 :core（EvidenceModelsTest timelineRows
 * 直测）；本件验收面（MarkdownTextTest 同款口径——:ui 无 skiko 运行时）：
 *
 * 1. **编译可达**：本文件引用 [MarkdownTableBlock] 可组合项——签名改写/文件
 *    删除即 :ui:jvmTest 编译失败；
 * 2. **配方源断言**：钉住「header labelMedium + surfaceVariant 微洗 / 数据行
 *    mono + 首列 primary / weight 均分 / 行时间戳命中即 clickable」结构——防
 *    配方被顺手改动；
 * 3. **守恒锚**：onJumpToTimestamp 可选参数（null = 行不可点）。
 */
class MarkdownTableBlockTest {

    /** 编译可达探针：签名改写/文件删除即 :ui:jvmTest 编译失败（不执行，只编译） */
    @Composable
    private fun compileReachabilityProbe() {
        MarkdownTableBlock(
            rows = listOf(
                listOf("时间", "事件"),
                listOf("09-19 10:29:50.000", "E/CameraManager: camera route failed"),
            ),
            onJumpToTimestamp = { },
        )
    }

    @Test
    fun `table block recipe stays pinned to mono grid with jump anchor`() {
        val source = markdownTableBlockSource()
        listOf(
            "rows: List<List<String>>" to "入参形态 = 行×格二维表（首行 header）",
            "onJumpToTimestamp: ((String) -> Unit)? = null" to "跳转回调可选（null = 行不可点）",
            "TIMESTAMP_PATTERN.find(cell)?.value" to "行时间戳锚 = TIMESTAMP_PATTERN 命中（跳转链数据源）",
            "MaterialTheme.typography.labelMedium" to "header 行字档 labelMedium（配方）",
            "DesktopTypography.mono" to "数据行 mono（时间戳列可读性优先）",
            "Modifier.weight(1f)" to "列宽 weight 均分（对齐语义最简配方）",
            "Modifier.clickable { onJumpToTimestamp(timestamp) }" to "时间戳行整行可点（复用报告引用跳转链）",
            "AppShapes.small" to "容器圆角走 token（零 dp 字面量——UiGuard RAW_DP）",
            "Dimens.space8" to "内边距走 token",
            "Dimens.space2" to "行距走 token",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "MarkdownTableBlock.kt 丢失配方要素「$needle」——$why",
            )
        }
    }

    private fun markdownTableBlockSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, SOURCE_PATH)
        assertTrue(file?.isFile() == true, "源码锚文件缺失：$SOURCE_PATH")
        return file.readText()
    }

    private companion object {
        const val SOURCE_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/MarkdownTableBlock.kt"
    }
}
