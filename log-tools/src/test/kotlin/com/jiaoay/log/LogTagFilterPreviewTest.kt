package com.jiaoay.log

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LogTagFilter.previewByTag] 截断预演契约测试：
 *
 * - 前 N 行内完整块的命中计数正确（与块内任一行 `find()` 命中即整块命中的
 *   [LogTagFilter.filterByTagBlock] 同核）；
 * - 截断落在块中间时尾块丢弃并计入 skippedTailBlocks（不参与命中统计——
 *   截断点无法判定块完整内容是否命中，命中行已见的尾块同样丢弃，防假阳）；
 * - 文件不足 N 行无截断，末块按完整块统计；
 * - 预演计数与全量跑的块口径一致性（N ≥ 文件行数时 matchedBlocks ==
 *   全量输出文件中的块数——钉死两入口无第二套块规则）。
 *
 * fixture 行号推算（每块 2 行，共 12 行）：块 1→1-2 / 块 2→3-4（命中）/
 * 块 3→5-6 / 块 4→7-8（命中）/ 块 5→9-10（命中）/ 块 6→11-12。
 */
class LogTagFilterPreviewTest {

    /** 6 块 fixture：每块 = 1 行时间头 + 1 行无时间戳续行，块 2/4/5 续行含目标 Tag */
    private fun sixBlockFile(dir: File): File {
        val content = buildString {
            for (i in 1..6) {
                appendLine("10-11 12:00:0$i.001 E/other-$i: block head")
                if (i in setOf(2, 4, 5)) appendLine("continuation line $i with MyTag hit")
                else appendLine("continuation line $i miss")
            }
        }
        return File(dir, "preview-fixture.txt").apply { writeText(content) }
    }

    private fun tempDir(): File = createTempDirectory("logtag-preview-test").toFile()

    @Test
    fun `matched count correct for complete blocks within first N lines`() {
        val file = sixBlockFile(tempDir())
        // 12 行 fixture，N=100 全覆盖：块 2/4/5 命中
        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "MyTag", maxLines = 100))
        assertEquals(6, ok.totalBlocks, "全覆盖时完整块数 = fixture 块数")
        assertEquals(3, ok.matchedBlocks, "块 2/4/5 命中")
        assertEquals(0, ok.skippedTailBlocks, "无截断无跳过")
        assertEquals(false, ok.truncated)
        assertEquals(12, ok.scannedLines)
        // 首个命中块预览 = 块 2 全量两行
        assertEquals(
            listOf("10-11 12:00:02.001 E/other-2: block head", "continuation line 2 with MyTag hit"),
            ok.firstMatchedBlockPreview,
        )
        assertEquals(2, ok.firstMatchedBlockLineCount)
    }

    @Test
    fun `tail block cut mid-block is dropped and counted as skipped even if its hit line was seen`() {
        val file = sixBlockFile(tempDir())
        // N=10：块 5 的命中行（第 10 行）已见，但块 5 尚未完结（末行在第 12 行区间之外）
        // ——截断在块中间 → 块 5 整块丢弃计跳过，命中数只含完整块 2/4
        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "MyTag", maxLines = 10))
        assertEquals(true, ok.truncated, "文件 12 行 > N=10")
        assertEquals(2, ok.matchedBlocks, "块 5 命中行已见但块不完整——丢弃不计命中（防假阳）")
        assertEquals(4, ok.totalBlocks, "块 1-4 完整；块 5 尾块丢弃、块 6 未入扫描")
        assertEquals(1, ok.skippedTailBlocks, "截断块中间 → 尾块丢弃计 1")
        assertEquals(10, ok.scannedLines)
    }

    @Test
    fun `truncation right after a time line still drops the one-line tail block`() {
        val file = sixBlockFile(tempDir())
        // N=11：第 11 行 = 块 6 的时间头行 → 尾块 = 仅含时间头的块 6 → 丢弃计 1；
        // 块 5 已完整（第 9-10 行），命中照常计入
        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "MyTag", maxLines = 11))
        assertEquals(3, ok.matchedBlocks, "块 2/4/5 完整命中")
        assertEquals(5, ok.totalBlocks, "块 1-5 完整；块 6 尾块丢弃")
        assertEquals(1, ok.skippedTailBlocks, "时间头刚起头的尾块同样丢弃（完整性不可判定）")
    }

    @Test
    fun `file shorter than N lines counts last block as complete`() {
        val file = sixBlockFile(tempDir())
        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "MyTag", maxLines = 999))
        assertEquals(false, ok.truncated, "文件 12 行 < N=999，无截断")
        assertEquals(0, ok.skippedTailBlocks)
        assertEquals(3, ok.matchedBlocks, "末块（块 6）按完整块统计——未命中不计，块 2/4/5 计入")
    }

    @Test
    fun `preview matched count equals full-run block count when N covers whole file`() {
        val dir = tempDir()
        val file = sixBlockFile(dir)
        // 口径一致性：N ≥ 全文件时预演命中块数 == 全量 filterByTagBlock 写出的块数
        val fullOutput = File(dir, "full-out.txt")
        LogTagFilter.filterByTagBlock(file, fullOutput, tagRegex = "MyTag")
        val fullBlockCount = fullOutput.readLines().count { it.isTimeLine() }
        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, "MyTag", maxLines = 12))
        assertEquals(fullBlockCount, ok.matchedBlocks, "预演与全量跑必须同口径（单源块规则）")
        assertEquals(3, fullBlockCount)
    }

    @Test
    fun `first matched block preview capped to limit with total line count`() {
        val dir = tempDir()
        // 单块 21 行（块内多行、无后续时间行 → 文件短于 N，末块完整命中）
        val content = buildString {
            appendLine("10-11 12:00:00.001 E/head: start")
            for (i in 1..20) appendLine("extra line $i MyTag")
        }
        val file = File(dir, "big-block.txt").apply { writeText(content) }
        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, "MyTag", maxLines = 500))
        assertEquals(1, ok.matchedBlocks)
        assertEquals(LogTagFilter.PREVIEW_BLOCK_PREVIEW_LINES, ok.firstMatchedBlockPreview.size, "预览行数封顶")
        assertEquals(21, ok.firstMatchedBlockLineCount, "总行数如实上报（预览截断不谎报块大小）")
    }

    @Test
    fun `invalid regex folds to Failure instead of throwing`() {
        val file = sixBlockFile(tempDir())
        val result = LogTagFilter.previewByTag(file, tagRegex = "[", maxLines = 10)
        assertTrue(result is TagPreviewResult.Failure, "非法正则折叠为 Failure（调用方免 try/catch）")
    }
}
