package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 侦察四件（log.stats / log.diff / log.view / log.cluster）守护：
 * 1. 目录注册 + Risk=READ_ONLY + uiHint=null（纯 agent 工具——命令面板面零变化）；
 * 2. stats 输出锚：块数/级别计数/Tag 频次/时间跨度逐项 + 未识别差值口径；
 * 3. diff 输出锚：hunk 数 + 首 hunk 行 + normalize 开关 + degraded 明示不静默；
 * 4. view 输出锚：窗口行号+内容逐项 + 续读指针 + 窗口行数钳制 + 加密拒绝；
 * 5. cluster 输出锚：模板串/计数/首末时间/行号样例 + 会计恒等式逐项 + 级别过滤
 *    参数化（默认 E/F）+ top-N 截断明示 + 行号引用截断明示；
 * 6. 输出长度硬上界：病态多 Tag 面 / 全异长行面 / 大窗口长行面 / 多模板长模板面
 *    断言 ≤ 上界字面值（4096 / 16384 / 16384 / 16384——与实现常量独立，防推导漂移静默放宽）；
 * 7. 围栏：四件路径参数均走围栏（围栏外 = DENIED 桌面默认文案）。
 */
class LogStatsDiffToolsTest {

    private val workDir = File(System.getProperty("java.io.tmpdir"), "log-stats-diff-${System.nanoTime()}")
        .apply { mkdirs(); deleteOnExit() }

    private fun fenceWith(vararg files: File): ToolExecutionContext = ToolExecutionContext(files.toList())

    // ── 注册与风险面 ──

    @Test
    fun `四工具注册且风险只读、不进命令面板`() {
        val byName = AgentToolCatalog.all.associateBy { it.name }
        val stats = byName["log.stats"] ?: error("log.stats 未注册进 AgentToolCatalog")
        val diff = byName["log.diff"] ?: error("log.diff 未注册进 AgentToolCatalog")
        val view = byName["log.view"] ?: error("log.view 未注册进 AgentToolCatalog")
        val cluster = byName["log.cluster"] ?: error("log.cluster 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, stats.risk, "log.stats 只读零产物，必须 READ_ONLY")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, diff.risk, "log.diff 只读零产物，必须 READ_ONLY")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, view.risk, "log.view 只读零产物，必须 READ_ONLY")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, cluster.risk, "log.cluster 只读零产物，必须 READ_ONLY")
        assertNull(stats.uiHint, "log.stats 是纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertNull(diff.uiHint, "log.diff 是纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertNull(view.uiHint, "log.view 是纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertNull(cluster.uiHint, "log.cluster 是纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(
            setOf("log.stats", "log.diff", "log.view", "log.cluster"),
            LogStatsDiffTools.NAMES,
            "names 集单源（消费方选择集引用此集合）",
        )
    }

    // ── stats：输出锚 ──

    @Test
    fun `stats 输出锚：块数、级别、Tag 频次、时间跨度逐项`() = runBlocking {
        val input = File(workDir, "mixed.log").apply {
            // 头块无时间行（未识别面）；threadtime 与 brief 双形态混合
            writeText(
                "garbage line without timestamp\n" +
                    "2026-09-14 10:00:00.000  100 200 I TagA: start\n" +
                    "continuation\n" +
                    "2026-09-14 10:00:01.000  100 200 E TagB: boom\n" +
                    "2026-09-14 10:00:02.000  100 200 W TagA: warn\n" +
                    "2026-09-14 10:00:03.000 E/TagB: brief form\n",
            )
        }
        val result = LogStatsDiffTools.StatsTool(fenceWith(input))
            .execute(LogStatsDiffTools.StatsTool.Args(input.absolutePath))
        assertTrue("- 扫描行数: 6" in result, result)
        assertTrue("- 日志块总数: 5" in result, result)
        assertTrue(
            "- 时间跨度: 2026-09-14_10:00:00.000 ~ 2026-09-14_10:00:03.000（共 3000 毫秒）" in result,
            result,
        )
        assertTrue("- 级别计数（块）: V=0 D=0 I=1 W=1 E=2 F=0（未识别级别块数: 1）" in result, result)
        assertTrue("Tag 频次 Top 2/2（未识别 Tag 块数: 1）:" in result, result)
        assertTrue("  - TagA: 2" in result, result)
        assertTrue("  - TagB: 2" in result, result)
    }

    // ── stats：输出长度硬上界 ──

    @Test
    fun `stats 输出长度硬上界（病态多 Tag 面）`() = runBlocking {
        // 千个互异超长 Tag：无界渲染会刷爆 agent 上下文——Top N + Tag 名截断后必须收口
        val longTail = "x".repeat(400)
        val input = File(workDir, "many-tags.log").apply {
            writeText((0 until 1000).joinToString("\n") { "2026-09-14 10:00:00.000  1 2 I T$longTail$it: v" })
        }
        val result = LogStatsDiffTools.StatsTool(fenceWith(input))
            .execute(LogStatsDiffTools.StatsTool.Args(input.absolutePath))
        assertTrue(result.length <= 4096, "stats 输出超 KDoc 硬上界 4096 字符：实际 ${result.length}")
        assertTrue("其余 970 个 Tag 未列出" in result, "Top 30 截断必须明示剩余面：$result")
    }

    // ── diff：输出锚 ──

    @Test
    fun `diff 输出锚：hunk 数与首 hunk 行`() = runBlocking {
        val oldFile = File(workDir, "old.txt").apply {
            writeText(
                "2026-09-14 10:00:00.000 common\n" +
                    "2026-09-14 10:00:01.000 old-only\n" +
                    "2026-09-14 10:00:02.000 common2\n",
            )
        }
        val newFile = File(workDir, "new.txt").apply {
            writeText(
                "2026-09-14 10:00:00.000 common\n" +
                    "2026-09-14 10:00:01.000 new-only\n" +
                    "2026-09-14 10:00:02.000 common2\n",
            )
        }
        val result = LogStatsDiffTools.DiffTool(fenceWith(oldFile, newFile))
            .execute(LogStatsDiffTools.DiffTool.Args(oldFile.absolutePath, newFile.absolutePath))
        assertTrue("- 行数: old 3 / new 3" in result, result)
        assertTrue("- 差异 hunk 数: 1" in result, result)
        assertTrue("@@ -1,3 +1,3 @@" in result, result)
        assertTrue("  -2026-09-14 10:00:01.000 old-only" in result, result)
        assertTrue("  +2026-09-14 10:00:01.000 new-only" in result, result)
        assertTrue("degraded" !in result, "未退化不得出现 degraded 标记：$result")
    }

    @Test
    fun `diff normalize 开关：仅时间戳差异按行为对齐`() = runBlocking {
        val oldFile = File(workDir, "old-n.txt").apply {
            writeText("2026-09-14 10:00:00.000 alpha\n2026-09-14 10:00:01.000 beta\n")
        }
        val newFile = File(workDir, "new-n.txt").apply {
            writeText("2026-09-14 11:00:00.000 alpha\n2026-09-14 11:00:01.000 beta\n")
        }
        val context = fenceWith(oldFile, newFile)
        val raw = LogStatsDiffTools.DiffTool(context).execute(
            LogStatsDiffTools.DiffTool.Args(oldFile.absolutePath, newFile.absolutePath),
        )
        assertTrue("- 差异 hunk 数: 1" in raw, "精确比对下仅时间戳不同的行是差异行：$raw")
        val normalized = LogStatsDiffTools.DiffTool(context).execute(
            LogStatsDiffTools.DiffTool.Args(
                oldFile.absolutePath,
                newFile.absolutePath,
                normalize_timestamps = true,
            ),
        )
        assertTrue("全等（0 个差异 hunk）" in normalized, "归一化后应按行为全等：$normalized")
    }

    // ── diff：degraded 明示 + 窗口行截断 + 输出长度硬上界 ──

    @Test
    fun `diff degraded 明示、窗口行截断与输出硬上界`() = runBlocking {
        // 全异千行长行面：编辑距离超引擎对齐上限 ⇒ 整体替换退化 + 首 hunk 行数/单行双截断
        val filler = "x".repeat(500)
        val oldFile = File(workDir, "old-big.txt").apply {
            writeText((0 until 5000).joinToString("\n") { "old-line-$it-$filler" })
        }
        val newFile = File(workDir, "new-big.txt").apply {
            writeText((0 until 5000).joinToString("\n") { "new-line-$it-$filler" })
        }
        val result = LogStatsDiffTools.DiffTool(fenceWith(oldFile, newFile))
            .execute(LogStatsDiffTools.DiffTool.Args(oldFile.absolutePath, newFile.absolutePath))
        assertTrue("degraded: 是" in result, "超限退化必须明示（不静默）：$result")
        assertTrue("差异行数不可信" in result, result)
        assertTrue("仅显示前 40 行" in result, "首 hunk 窗口行数截断必须明示：$result")
        assertTrue("其余 9960 行未显示" in result, result)
        assertTrue("…" in result, "超长单行必须截断：$result")
        assertTrue(result.length <= 16_384, "diff 输出超 KDoc 硬上界 16384 字符：实际 ${result.length}")
    }

    // ── view：窗口内容锚 ──

    @Test
    fun `view 输出锚：行号与内容逐项、续读指针`() = runBlocking {
        val input = File(workDir, "view.log").apply {
            writeText((1..10).joinToString("\n") { "alpha-$it" })
        }
        val result = LogStatsDiffTools.ViewTool(fenceWith(input))
            .execute(LogStatsDiffTools.ViewTool.Args(input.absolutePath, start_line = 3, window_lines = 4))
        assertTrue("原文窗口（view.log，共 10 行）：start_line=3（1 基），请求 4 行" in result, result)
        (3..6).forEach { n ->
            assertTrue("  $n: alpha-$n" in result, "窗口第 $n 行必须行号+内容逐项呈现：$result")
        }
        assertTrue("  2: alpha-2" !in result, "窗口起点之前不得出现：$result")
        assertTrue("  7: alpha-7" !in result, "窗口终点之后不得出现：$result")
        assertTrue("（窗口共 4 行——继续查看用 start_line=7）" in result, "未到末尾必须给续读指针：$result")
    }

    @Test
    fun `view 窗口行数钳制到引擎上界与剩余行数`() = runBlocking {
        val input = File(workDir, "clamp.log").apply {
            writeText((1..6000).joinToString("\n") { "beta-$it" })
        }
        val over = LogStatsDiffTools.ViewTool(fenceWith(input))
            .execute(LogStatsDiffTools.ViewTool.Args(input.absolutePath, start_line = 1, window_lines = 9999))
        assertTrue("请求 5000 行" in over, "窗口行数请求必须钳制到引擎上界 5000：$over")
        val tail = LogStatsDiffTools.ViewTool(fenceWith(input))
            .execute(LogStatsDiffTools.ViewTool.Args(input.absolutePath, start_line = 5999, window_lines = 100))
        assertTrue("  5999: beta-5999" in tail && "  6000: beta-6000" in tail, tail)
        assertTrue("（已到文件末尾）" in tail, "剩余行数不足请求时窗口收口到末行：$tail")
        val badArgs = LogStatsDiffTools.ViewTool(fenceWith(input))
            .execute(LogStatsDiffTools.ViewTool.Args(input.absolutePath, start_line = 0))
        assertTrue("参数非法" in badArgs, "start_line < 1 必须按参数错误拒绝：$badArgs")
        val beyond = LogStatsDiffTools.ViewTool(fenceWith(input))
            .execute(LogStatsDiffTools.ViewTool.Args(input.absolutePath, start_line = 6001))
        assertTrue("超出总行数 6000" in beyond, "start_line 越上界必须如实报总行数：$beyond")
    }

    @Test
    fun `view 输出字符上界（大窗口长行面与单条超长行面）`() = runBlocking {
        // 大窗口长行：无界渲染会刷爆 agent 上下文——正文预算收窗 + 单行截断后必须收口
        val longLine = "x".repeat(2_000)
        val input = File(workDir, "long.log").apply {
            writeText((1..600).joinToString("\n") { "row-$it-$longLine" })
        }
        val result = LogStatsDiffTools.ViewTool(fenceWith(input))
            .execute(LogStatsDiffTools.ViewTool.Args(input.absolutePath, start_line = 1, window_lines = 5000))
        assertTrue(result.length <= 16_384, "view 输出超 KDoc 硬上界 16384 字符：实际 ${result.length}")
        assertTrue("输出字符上界收窗" in result, "预算收窗必须明示（不静默丢尾）：$result")
        assertTrue("继续查看用 start_line=" in result, "收窗必须给续读指针：$result")
        assertTrue("…" in result, "超长单行必须截断：$result")

        // 单条超长行：单行截断后不得独占输出
        val single = File(workDir, "single.log").apply { writeText("y".repeat(100_000)) }
        val singleResult = LogStatsDiffTools.ViewTool(fenceWith(single))
            .execute(LogStatsDiffTools.ViewTool.Args(single.absolutePath))
        assertTrue(singleResult.length <= 16_384, "单条超长行同样受硬上界：实际 ${singleResult.length}")
        assertTrue("…" in singleResult, "单条超长行必须截断：$singleResult")
    }

    @Test
    fun `view 加密段结构文件拒绝并引导先解密`() = runBlocking {
        // 段字节拼装自洽（magic 0x03 + 9B 段头 + 长度 + 尾标）——:log-tools 的合成工厂
        // 在其 test 源集，:core 测试不可引，故按段格式逐字节拼最小加密段
        val payload = "hello log".toByteArray()
        val header = java.nio.ByteBuffer.allocate(9).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(0x03.toByte()).putShort(0).put(8.toByte()).put(9.toByte())
            .putInt(payload.size).array()
        val xlog = File(workDir, "real.xlog").apply {
            writeBytes(header + ByteArray(4) + payload + byteArrayOf(0x00))
        }
        val result = LogStatsDiffTools.ViewTool(fenceWith(xlog))
            .execute(LogStatsDiffTools.ViewTool.Args(xlog.absolutePath))
        assertTrue("查看失败" in result, "加密段结构必须拒绝查看：$result")
        assertTrue("需先解密" in result, "拒绝文案必须引导先解密（引擎 Failure 语义透传）：$result")
    }

    // ── cluster：端到端输出锚（默认 E/F 级别过滤） ──

    @Test
    fun `cluster 端到端：模板串、计数、首末时间、行号样例与会计恒等式逐项`() = runBlocking {
        val input = File(workDir, "cluster-mixed.log").apply {
            writeText(
                "camera init start\n" +
                    "2026-10-11 08:00:01.000 E/CamService(1234): failed to open camera 0\n" +
                    "2026-10-11 08:00:05.000 E/CamService(1235): failed to open camera 1\n" +
                    "2026-10-11 08:00:09.000 E/CamService(1236): failed to open camera 2\n" +
                    "2026-10-11 08:00:02.000 W/SensorHub: heartbeat lost 3\n" +
                    "2026-10-11 07:59:00.000 E/CamService(1200): failed to open camera 9\n",
            )
        }
        val result = LogStatsDiffTools.ClusterTool(fenceWith(input))
            .execute(LogStatsDiffTools.ClusterTool.Args(input.absolutePath))
        assertTrue("错误聚类（cluster-mixed.log，级别过滤 E/F，模板 Top 10）：" in result, result)
        assertTrue("- 扫描行数: 6" in result, result)
        assertTrue("- 块总数: 6；级别命中候选块: 4" in result, "W 块与无级别裸头块被默认 E/F 过滤拦下（差值如实呈现）:$result")
        assertTrue(
            "- 候选块去向: 归并进簇 4 + 模板容量溢出 0 + 空消息头跳过 0 + 超长行跳过 0" in result,
            "会计恒等式四项逐项呈现不丢项：$result",
        )
        assertTrue("- 模板 Top 1/1（计数降序）:" in result, result)
        assertTrue("  - [4 次] E/CamService(<*>): failed to open camera <*>" in result, "pid/相机号折叠 <*>、计数为全量：$result")
        assertTrue(
            "    首/末: 2026-10-11_07:59:00.000 ~ 2026-10-11_08:00:09.000；" +
                "块首行号样例（1 基）: 2, 3, 4（共 4 处）" in result,
            "首末时间按值取 min/max、行号样例 1 基（log.view 同基续读锚）：$result",
        )
        assertTrue("W/SensorHub" !in result, "默认 E/F 过滤下 W 块不得进模板面：$result")
    }

    // ── cluster：级别参数化与坏参数显式失败 ──

    @Test
    fun `cluster levels 参数化：显式 W 档命中与非法级别显式失败`() = runBlocking {
        val input = File(workDir, "cluster-levels.log").apply {
            writeText(
                "2026-10-11 08:00:01.000 E/A: boom\n" +
                    "2026-10-11 08:00:02.000 W/SensorHub: heartbeat lost 3\n" +
                    "2026-10-11 08:00:03.000 W/SensorHub: heartbeat lost 7\n",
            )
        }
        val context = fenceWith(input)
        val wOnly = LogStatsDiffTools.ClusterTool(context)
            .execute(LogStatsDiffTools.ClusterTool.Args(input.absolutePath, levels = listOf("W")))
        assertTrue("级别过滤 W" in wOnly, "显式 levels 覆盖默认 E/F：$wOnly")
        assertTrue("W/SensorHub: heartbeat lost <*>" in wOnly, wOnly)
        assertTrue("[2 次]" in wOnly, wOnly)
        assertTrue("E/A" !in wOnly, "E 块在 W 档下被过滤：$wOnly")

        val badLevel = LogStatsDiffTools.ClusterTool(context)
            .execute(LogStatsDiffTools.ClusterTool.Args(input.absolutePath, levels = listOf("X")))
        assertTrue("非法级别: X" in badLevel, "非法级别字母必须显式失败（合法值清单随回执）：$badLevel")

        val badLimit = LogStatsDiffTools.ClusterTool(context)
            .execute(LogStatsDiffTools.ClusterTool.Args(input.absolutePath, limit = 0))
        assertTrue("参数非法" in badLimit, "limit ≤ 0 必须按参数错误拒绝：$badLimit")
    }

    // ── cluster：top-N 截断、行号引用截断与输出硬上界 ──

    @Test
    fun `cluster top-N 截断明示、行号引用截断明示与输出硬上界`() = runBlocking {
        // 300 个互异模板形态（字母标识防数字折叠同串）：模板容量 200 收口 + top-N 截断
        val letters = { i: Int ->
            var n = i
            val sb = StringBuilder()
            do {
                sb.append('a' + (n % 26))
                n /= 26
            } while (n > 0)
            sb.toString()
        }
        val input = File(workDir, "cluster-many.log").apply {
            writeText(
                (0..299).joinToString("\n") {
                    "2026-10-11 08:00:00.000 E/T: unique${letters(it)} marker tail"
                } + "\n",
            )
        }
        val result = LogStatsDiffTools.ClusterTool(fenceWith(input))
            .execute(LogStatsDiffTools.ClusterTool.Args(input.absolutePath, limit = 5))
        assertTrue("模板 Top 5/200（计数降序）:" in result, "limit 钳制到 5、簇数封顶 200：$result")
        assertTrue("模板容量溢出 100" in result, "容量尽溢出必须显式计数：$result")
        assertTrue("其余 195 个模板未列出" in result, "top-N 截断必须明示剩余面：$result")
        assertTrue(result.length <= 16_384, "cluster 输出超 KDoc 硬上界 16384 字符：实际 ${result.length}")

        // 单条超长模板：截断后不得独占输出
        val longInput = File(workDir, "cluster-long.log").apply {
            writeText("2026-10-11 08:00:00.000 E/T: ${"x".repeat(500)}\n")
        }
        val longResult = LogStatsDiffTools.ClusterTool(fenceWith(longInput))
            .execute(LogStatsDiffTools.ClusterTool.Args(longInput.absolutePath))
        assertTrue("…" in longResult, "超长模板串必须截断：$longResult")
        assertTrue(longResult.length <= 16_384, "长模板面同样受硬上界：实际 ${longResult.length}")

        // 行号引用截断：同型异常刷 600 次——样例只有 3 个且截断明示、count 不失真
        val repeatInput = File(workDir, "cluster-repeat.log").apply {
            writeText((1..600).joinToString("\n") { "2026-10-11 08:00:00.000 E/Rep: boom" } + "\n")
        }
        val repeatResult = LogStatsDiffTools.ClusterTool(fenceWith(repeatInput))
            .execute(LogStatsDiffTools.ClusterTool.Args(repeatInput.absolutePath))
        assertTrue("[600 次]" in repeatResult, "count 为全量计数——不因引用截断失真：$repeatResult")
        assertTrue("块首行号样例（1 基）: 1, 2, 3" in repeatResult, "样例只取前 3 个：$repeatResult")
        assertTrue("行号引用已达引擎上限被截断" in repeatResult, "引用截断必须明示：$repeatResult")
    }

    // ── 研判链 prompt 引导面 ──

    @Test
    fun `研判链 prompt 引导面含侦察四件与动线`() {
        // 工具进了名集但 prompt 不引导 = 装而不用（Agent 以存量工具面干活、盲 grep 耗迭代）
        LogStatsDiffTools.NAMES.forEach { name ->
            assertTrue(
                name in WorkbenchPrompts.ANALYZE_PROMPT,
                "ANALYZE_PROMPT 可用工具节必须引导 $name（名集与 prompt 引导面同批）",
            )
        }
        assertTrue(
            "统计→下钻→引用" in WorkbenchPrompts.ANALYZE_PROMPT,
            "引导动线须与工具描述符口径一致（LogStatsDiffTools 对象 KDoc 的研判动线）",
        )
        assertTrue(
            "研判前先对 E/F 行聚类" in WorkbenchPrompts.ANALYZE_PROMPT,
            "cluster 引导行须先指聚类后下钻（与描述符 Recon step 口径一致）",
        )
    }

    // ── 围栏 ──

    @Test
    fun `四件围栏拒绝围栏外路径`() = runBlocking {
        val inside = File(workDir, "inside.log").apply { writeText("x") }
        val outside = File(workDir, "outside.log").apply { writeText("x") }
        val context = fenceWith(inside)
        val stats = LogStatsDiffTools.StatsTool(context)
            .execute(LogStatsDiffTools.StatsTool.Args(outside.absolutePath))
        assertTrue("允许范围" in stats, "stats 围栏外路径必须拒绝（桌面默认围栏文案）：$stats")
        val diff = LogStatsDiffTools.DiffTool(context)
            .execute(LogStatsDiffTools.DiffTool.Args(inside.absolutePath, outside.absolutePath))
        assertTrue("允许范围" in diff, "diff 任一侧围栏外必须拒绝：$diff")
        val view = LogStatsDiffTools.ViewTool(context)
            .execute(LogStatsDiffTools.ViewTool.Args(outside.absolutePath))
        assertTrue("允许范围" in view, "view 围栏外路径必须拒绝（桌面默认围栏文案）：$view")
        val cluster = LogStatsDiffTools.ClusterTool(context)
            .execute(LogStatsDiffTools.ClusterTool.Args(outside.absolutePath))
        assertTrue("允许范围" in cluster, "cluster 围栏外路径必须拒绝（桌面默认围栏文案）：$cluster")
    }

    @Test
    fun `stats 围栏内文件不存在如实报错`() = runBlocking {
        val missing = File(workDir, "missing.log")
        val result = LogStatsDiffTools.StatsTool(fenceWith(missing))
            .execute(LogStatsDiffTools.StatsTool.Args(missing.absolutePath))
        assertTrue("文件不存在" in result, result)
    }
}
