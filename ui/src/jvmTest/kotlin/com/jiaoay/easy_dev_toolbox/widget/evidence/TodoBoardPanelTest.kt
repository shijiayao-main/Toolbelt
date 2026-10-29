package com.jiaoay.easy_dev_toolbox.widget.evidence

import androidx.compose.runtime.Composable
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoItem
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TodoBoardPanel 计划投影卡验收断言：
 *
 * 渲染件无 Compose 运行时直测缝（:ui 无 skiko 运行时——MarkdownTableBlockTest
 * 同款口径）：
 *
 * 1. **编译可达**：本文件引用 [TodoBoardPanel] 可组合项——签名改写/文件删除即
 *    :ui:jvmTest 编译失败；
 * 2. **配方源断言**：钉住「TonalCard 壳 + 头行计数 pill + 三态图标映射」结构
 *    （图标色统一 colors.info、行文 bodyMedium、图标 13dp）——防配方被顺手改动；
 * 3. **空态门控**：items 空 = 组件内 return 兜底（与调用方门控双保险——
 *    无 write_todos 调用或 items 空时中栏零渲染）；
 * 4. **落位契约**：消费点在 EvidenceTimeline 的 LazyColumn 之外的 Column 区
 *    （🔴 插 LazyColumn 内破坏 evidenceTimelineLineItemOffset 物理索引契约）。
 */
class TodoBoardPanelTest {

    /** 编译可达探针：签名改写/文件删除即 :ui:jvmTest 编译失败（不执行，只编译） */
    @Composable
    private fun compileReachabilityProbe() {
        TodoBoardPanel(
            items = listOf(
                TodoItem(content = "解密日志文件", status = TodoStatus.DONE),
                TodoItem(content = "定位 XPU 回传异常根因", status = TodoStatus.IN_PROGRESS),
                TodoItem(content = "输出五部分结构报告", status = TodoStatus.PENDING),
            ),
        )
    }

    @Test
    fun `todo board panel recipe stays pinned to tonal card with tri state icons`() {
        val source = todoBoardPanelSource()
        listOf(
            "TonalCard" to "卡壳必须走 TonalCard（AppCardStyles.tonal 统一配方）",
            "MaterialSymbols.PROGRESS_ACTIVITY" to "IN_PROGRESS 状态图标（卡面配方）",
            "MaterialSymbols.CHECK_CIRCLE" to "DONE 状态图标（卡面配方）",
            "MaterialSymbols.CIRCLE" to "PENDING 状态图标（Outlined 收件箱风格空圈——circle 字形自带描边）",
            "if (items.isEmpty()) return" to "空列表组件内 return 兜底（与调用方门控双保险）",
            "项 · " to "头行计数 pill 文案「N 项 · M 进行中」（预览稿 .chead 配方）",
            "tint = dayBattleTint(colors.info, colors.infoContainer)" to "三态图标色统一 --info（稿 :402-404 逐字）——昼战坐 infoContainer 档（§5.3，dayBattleTint 图形 3.0 线）",
            "style = MaterialTheme.typography.bodyMedium" to "content 行文 fs-13 常规字（非 mono——mono 是 kv/日志语域）",
            "Dimens.iconTiny" to "状态图标 13dp（稿 .ico.tiny 13px 逐字）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "TodoBoardPanel.kt 丢失配方要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `todo board panel renders outside lazy column in evidence timeline`() {
        val source = evidenceTimelineSource()
        // 落位契约（DOM 序）：
        // TodoBoard 在 StageAnchorBar **之上**（稿面 card → stagebar 序）、且在
        // LazyColumn 之外（LazyColumn items 只允许 ev-empty/ev-block-tool/ev-block-ai
        // 三键——插任何 item 都会破坏 evidenceTimelineLineItemOffset 物理索引契约）
        val anchorIdx = source.indexOf("StageAnchorBar(")
        val todoIdx = source.indexOf("TodoBoardPanel(items = todos)")
        val lazyIdx = source.indexOf("LazyColumn(")
        assertTrue(anchorIdx > 0, "StageAnchorBar 锚缺失（落位契约前提）")
        assertTrue(todoIdx in 0 until anchorIdx, "TodoBoardPanel 必须落在 StageAnchorBar 之上（稿面 DOM 序）")
        assertTrue(
            todoIdx < lazyIdx,
            "TodoBoardPanel 必须落在 LazyColumn 之外的 Column 区——插 LazyColumn 内破坏 offset 物理索引契约",
        )
        assertTrue(
            "todos: List<TodoItem> = emptyList()" in source,
            "EvidenceTimeline 增参 todos 须带默认值 emptyList()——既有消费面零破坏",
        )
    }

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$relativePath")
        return file.readText()
    }

    private fun todoBoardPanelSource(): String =
        source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/TodoBoardPanel.kt")

    private fun evidenceTimelineSource(): String =
        source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/EvidenceTimeline.kt")
}
