package com.jiaoay.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 日志热路径性能上界（数量级粗上界口径）：三条路径分别覆盖「逐行时间戳解析」「全文件
 * 搜索逐窗 seek」「模板聚类的模板容量剪枝」——它们都是大文件上的单遍/逐窗处理，
 * 算法正确时耗时与输入规模近线性；一旦退化为重复全扫（如逐行重开流、模板表无界增长
 * 后逐条比对全表），耗时会随规模平方膨胀直到界面无响应。
 *
 * 断言只拦数量级回归：阈值 = 本机实测基线 × 约 10 倍，正常负载波动不红；
 * 失败信息带实测值，红了先复测归因（对照 docs/log-tools.md「性能基线」节口径）。
 */
class LogHotPathBudgetTest {

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    private fun writeLog(file: File, lineCount: Int, line: (Int) -> String): File {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (i in 0 until lineCount) {
                writer.write(line(i))
                writer.newLine()
            }
        }
        file.deleteOnExit()
        return file
    }

    /**
     * 逐行时间戳解析吞吐：块扫描/统计/查看器导航每行都要过一次行首时间戳契约，
     * 契约实现若退化为逐行现编正则，20 万行量级会拖慢整条管线一个数量级。
     * 上限 2 秒为本机实测（约 0.19–0.24 秒）的约 10 倍。
     */
    @Test
    fun `逐行时间戳解析二十万行线性完成`() {
        val lines = List(200_000) { i -> "08-27 09:00:00.${(i % 1000).toString().padStart(3, '0')} D/Tag$i: line $i" }
        val startedAt = System.nanoTime()
        var hits = 0
        for (line in lines) {
            if (LogTimestamp.parseLine(line, "2026") != null) hits++
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println("[hot-path] 时间戳解析 20 万行: ${elapsedMs}ms")
        assertEquals(lines.size, hits, "全部行形态合法，解析命中数应等于行数")
        assertTrue(
            elapsedMs < 2_000,
            "20 万行时间戳解析须线性完成；实测 ${elapsedMs}ms，上限 2000ms（基线约 190–240ms 的约 10 倍）",
        )
    }

    /**
     * 全文件搜索：查看器搜索每个请求都从起点逐窗 seek 扫到文件末（无命中即最坏路径），
     * 若窗口读取退化为每次从头顺序读，10 万行文件会变成 O(窗口数 × 文件) 的重复扫描。
     * 上限 1.5 秒为本机实测（约 0.15–0.17 秒）的约 10 倍。
     */
    @Test
    fun `十万行文件全量搜索无命中线性完成`() {
        val file = writeLog(File(tempDir("log-hot-path-search"), "search.log"), 100_000) { i ->
            "08-27 09:00:00.000 D/Tag${i % 50}(pid): 普通日志内容 pid=${i % 997} seq=$i"
        }
        val session = assertIs<LogViewer.OpenResult.Ok>(LogViewer.open(file)).session
        assertEquals(100_000, session.totalLines)

        val startedAt = System.nanoTime()
        val result = session.searchLines(0, "绝对不存在的关键词")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println("[hot-path] 10 万行全量搜索: ${elapsedMs}ms")
        assertTrue(result.matchedLineIndexes.isEmpty() && !result.truncated, "无命中应返回空集且不置截断")
        assertTrue(
            elapsedMs < 1_500,
            "10 万行全量搜索（最坏无命中路径）须线性完成；实测 ${elapsedMs}ms，上限 1500ms（基线约 150–170ms 的约 10 倍）",
        )
    }

    /**
     * 模板聚类：块逐块与同 token 长度组内的既有模板比对，比对成本受 [LogCluster.MAX_TEMPLATES]
     * 封顶才是可用的——模板表一旦无界，同一长度组内的模板数随块数增长即退化为平方。
     * 上限 1 秒为本机实测（约 0.08–0.11 秒）的约 10 倍。
     */
    @Test
    fun `两万块模板聚类模板表受容量约束线性完成`() {
        val file = writeLog(File(tempDir("log-hot-path-cluster"), "cluster.log"), 40_000) { i ->
            if (i % 2 == 0) {
                "08-27 09:0${i % 10}:00.000 E/Tag${i % 20}(pid): failure kind ${i % 7} at step ${i % 11} code ${i % 13}"
            } else {
                "    continuation detail ${i % 5} value ${i % 17}"
            }
        }
        val startedAt = System.nanoTime()
        val result = LogCluster.analyze(file)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println("[hot-path] 2 万块聚类: ${elapsedMs}ms")
        val ok = assertIs<ClusterResult.Ok>(result)
        assertNotNull(ok.templates.firstOrNull(), "聚类产物应至少含一个模板簇")
        assertTrue(ok.templates.size <= LogCluster.MAX_TEMPLATES, "模板簇数须受容量上界约束")
        assertTrue(
            elapsedMs < 1_000,
            "2 万块聚类（模板容量封顶）须随块数线性完成；实测 ${elapsedMs}ms，上限 1000ms（基线约 80–110ms 的约 10 倍）",
        )
    }
}
