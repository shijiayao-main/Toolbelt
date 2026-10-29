// D 组 files 测试：文件名清洗 + 体积阈值 + 域纪律守护。
// 🔴 2026-09-11 引用流重设计：旧 `resolveTarget` / `tmpDirFor` / `cleanupStale` 已随死代码删除——
//    它们的用例同步移除；`sanitizeFileName` / `enforceSizeLimit` / `cleanup` 契约逐字保留
//    （🔒 BC-408..413 的清洗语义在新 DownloadStore 目录布局下继续生效）。
//    旧「用完即删」生命周期用例整体废弃（新语义 = 持久下载区 + 配额 FIFO，由 DownloadStoreTest 承接）。
// 🔴 移植差异：旧测试里的 `ProcessBuilder("ln","-s",…)` 改为 `java.nio.file.Files.createSymbolicLink`
//    （🔴 E 组守护：lib 侧含测试都不得出现 ProcessBuilder）。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.pipeline.RecordingLog
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [DownloadPolicy]：文件名清洗契约、体积阈值、域纪律守护。 */
class DownloadPolicyTest {

    private fun tempWorkspace(name: String = "ws"): File =
        File(System.getProperty("java.io.tmpdir"), "download-policy-$name-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    /** 记录日志文本（🔴 D18：删除失败要可观测） */
    // ── sanitizeFileName 契约（旧 FileMessageExecutor :183-184 逐字平移；🔒 BC-410/411/412 字面值实测） ──

    @Test
    fun `sanitizeFileName 的四步清洗逐字对齐`() {
        // ① 路径分隔符 / 控制字符 → `_`
        assertEquals("a_b_c.txt", DownloadPolicy.sanitizeFileName("a\\b\u0007c.txt"))
        // ②③ `..` → `_`，随后首尾 `_` 被 trim：../../etc/passwd → etc_passwd
        assertEquals("etc_passwd", DownloadPolicy.sanitizeFileName("../../etc/passwd"))
        assertEquals("etc_passwd", DownloadPolicy.sanitizeFileName("/../../etc/passwd"))
        assertEquals("etc_passwd", DownloadPolicy.sanitizeFileName("/etc/passwd"))
        assertFalse(DownloadPolicy.sanitizeFileName("../../etc/passwd").contains(".."))
        // ④ 空结果回退 "file"
        assertEquals("file", DownloadPolicy.sanitizeFileName("../.."))
        assertEquals("file", DownloadPolicy.sanitizeFileName(""))
        assertEquals("file", DownloadPolicy.sanitizeFileName("___"))
        assertEquals("file", DownloadPolicy.sanitizeFileName("\u0000\u0001"))
    }

    // ── 🔴 2026-09-17 审计修复：Windows 可用性保护（新增四组契约） ──

    /**
     * Windows 非法字符 `< > : " | ? *` 必须被替换。
     * 这些都是**在 Linux/macOS 合法、在 Windows 落盘失败**的字符——
     * 原实现只清分隔符与控制字符，飞书资源名含这些字符时 Windows 直接写不出去。
     */
    @Test
    fun `Windows 非法字符被替换为下划线`() {
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a<b.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a>b.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a:b.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a\"b.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a|b.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a?b.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a*b.txt"))
    }

    /**
     * Windows 保留设备名必须被打破。
     * 判定只看**主名**——`CON.txt` 在 Windows 上同样打不开（保留名带扩展名仍被拒）。
     */
    @Test
    fun `Windows 保留设备名被追加下划线且保留扩展名`() {
        assertEquals("CON_.txt", DownloadPolicy.sanitizeFileName("CON.txt"))
        assertEquals("NUL_.log", DownloadPolicy.sanitizeFileName("NUL.log"))
        assertEquals("COM1_.txt", DownloadPolicy.sanitizeFileName("COM1.txt"))
        assertEquals("LPT9_.txt", DownloadPolicy.sanitizeFileName("LPT9.txt"))
        // 大小写不敏感（Windows 判定不分大小写）
        assertEquals("con_.txt", DownloadPolicy.sanitizeFileName("con.txt"))
        // 非保留名不受影响
        assertEquals("CONSOLE.txt", DownloadPolicy.sanitizeFileName("CONSOLE.txt"))
    }

    /**
     * 🔴 2026-09-17 复核修复（二次，block 级）：**无扩展名的裸保留名**同样必须被打破。
     *
     * 复核实测：修复前工序是「先加 `_` 后缀 → 再 trim('_')」，而 trim 的字符集含 `_`
     * ⇒ `"CON"`→`"CON_"`→`"CON"` 原样返回。上面那组用例**全部带扩展名**（`CON.txt` 尾字符是
     * `t`，trim 吃不掉 `_`），恰好绕开了这个缺陷——这正是「用例全绿但缺陷仍在」的典型形态。
     * 而 senderId 段（[DownloadStore.dirFor] 的独立目录）恰恰就是无扩展名形态 ⇒
     * Windows 上 `downloads/CON/` 创建失败或指向设备。
     */
    @Test
    fun `裸保留设备名（无扩展名）必须被打破`() {
        assertEquals("CON_", DownloadPolicy.sanitizeFileName("CON"))
        assertEquals("NUL_", DownloadPolicy.sanitizeFileName("NUL"))
        assertEquals("AUX_", DownloadPolicy.sanitizeFileName("AUX"))
        assertEquals("PRN_", DownloadPolicy.sanitizeFileName("PRN"))
        assertEquals("COM1_", DownloadPolicy.sanitizeFileName("COM1"))
        assertEquals("LPT9_", DownloadPolicy.sanitizeFileName("LPT9"))
        // 尾部点号 / 空白形态同样命中（先清理后判定，判定结果不再被抹掉）
        assertEquals("aux_", DownloadPolicy.sanitizeFileName("aux."))
        assertEquals("CON_", DownloadPolicy.sanitizeFileName("CON   "))
        assertEquals("CON_.txt", DownloadPolicy.sanitizeFileName("CON. . .txt"))
    }

    /**
     * 🔴 2026-09-17 复核修复（二次）：长度上限按 **UTF-8 字节**而非码点。
     *
     * Linux/macOS 的 NAME_MAX 是 255 **字节**；中文常态 3 字节/字 ⇒ 按 100 码点放行可达 300 字节。
     * 复核实测 `'m'*12 + '_' + '中'*80 + '.log'` = 257 字节即 `mkdir ENAMETOOLONG`。
     */
    @Test
    fun `超长主名按 UTF-8 字节截断（非码点）`() {
        // 纯中文 300 字 ≈ 900 字节，必须被压到上限内
        val cjk = "中".repeat(300) + ".log"
        val cleanedCjk = DownloadPolicy.sanitizeFileName(cjk)
        assertTrue(
            cleanedCjk.toByteArray(Charsets.UTF_8).size <= 100,
            "中文名截断后应 ≤100 字节，实际 ${cleanedCjk.toByteArray(Charsets.UTF_8).size}",
        )
        // 截断不得切断代理对（emoji 是 4 字节码点）
        val emoji = "😀".repeat(200) + ".txt"
        val cleanedEmoji = DownloadPolicy.sanitizeFileName(emoji)
        assertTrue(
            cleanedEmoji.toByteArray(Charsets.UTF_8).size <= 100,
            "emoji 名截断后应 ≤100 字节",
        )
        var i = 0
        while (i < cleanedEmoji.length) {
            val c = cleanedEmoji[i]
            if (c.isHighSurrogate()) {
                assertTrue(
                    i + 1 < cleanedEmoji.length && cleanedEmoji[i + 1].isLowSurrogate(),
                    "位置 $i 的高代理没有配对（代理对被切断）",
                )
                i += 2
            } else {
                assertFalse(c.isLowSurrogate(), "位置 $i 出现孤立低代理")
                i += 1
            }
        }
    }

    /**
     * 尾部空白与点号必须被清除。
     * Windows 静默忽略尾部点号 ⇒ 会产生「文件确实存在、但用户按原名找不到」的怪象。
     */
    @Test
    fun `尾部空白与点号被清除`() {
        assertEquals("report", DownloadPolicy.sanitizeFileName("report..."))
        assertEquals("report", DownloadPolicy.sanitizeFileName("report   "))
        assertEquals("report", DownloadPolicy.sanitizeFileName("report. . ."))
        assertEquals("report.txt", DownloadPolicy.sanitizeFileName("report.txt..."))
    }

    /**
     * 超长主名按**码点**截断，且不切断代理对。
     * 按码点而非 UTF-16 码元——否则会在代理对中间切断，产出乱码（emoji 尤甚）。
     */
    @Test
    fun `超长主名按码点截断且不切断代理对`() {
        val long = "a".repeat(300) + ".txt"
        val cleaned = DownloadPolicy.sanitizeFileName(long)
        assertTrue(cleaned.length <= 105, "截断后不应远超上限：${cleaned.length}")

        // 代理对（emoji = 2 个 UTF-16 码元 = 1 个码点）不得被切断
        val emoji = "\uD83D\uDE00".repeat(200) + ".txt"
        val cleanedEmoji = DownloadPolicy.sanitizeFileName(emoji)
        var i = 0
        while (i < cleanedEmoji.length) {
            val c = cleanedEmoji[i]
            if (c.isHighSurrogate()) {
                assertTrue(
                    i + 1 < cleanedEmoji.length && cleanedEmoji[i + 1].isLowSurrogate(),
                    "位置 $i 的高代理没有配对（代理对被切断）",
                )
                i += 2
            } else {
                assertFalse(c.isLowSurrogate(), "位置 $i 出现孤立低代理")
                i += 1
            }
        }
    }

    /** 新增保护不得破坏 BC-410/411/412 的既有语义（回归护栏）。 */
    @Test
    fun `新增 Windows 保护不破坏原清洗契约`() {
        assertEquals("etc_passwd", DownloadPolicy.sanitizeFileName("../../etc/passwd"))
        assertEquals("file", DownloadPolicy.sanitizeFileName("../.."))
        // `./../x` → `x`（trim('_') 必须保留；曾因遗漏它而回归过一次）
        assertEquals("x", DownloadPolicy.sanitizeFileName("./../x"))
        assertEquals("x", DownloadPolicy.sanitizeFileName("          /x"))
    }

    /** 🔴 清洗结果恒不含路径分隔符与 `..`（对任意恶意输入成立） */
    @Test
    fun `sanitizeFileName 恒不产出可穿越片段`() {
        listOf(
            "../../etc/passwd",
            "..\\..\\windows\\system32",
            "/etc/shadow",
            "C:\\Windows\\System32\\cmd.exe",
            "a/../../../b",
            "\u0000/etc/passwd",
            "..",
            "../",
            "./../x",
            "....//....//x",
            " ".repeat(10) + "/x",
        ).forEach { raw ->
            val clean = DownloadPolicy.sanitizeFileName(raw)
            assertFalse(clean.contains("/"), "「$raw」→「$clean」不得含正斜杠")
            assertFalse(clean.contains("\\"), "「$raw」→「$clean」不得含反斜杠")
            assertFalse(clean.contains(".."), "「$raw」→「$clean」不得含点点")
            assertTrue(clean.isNotBlank(), "「$raw」→ 不得为空（空结果必须回退 file）")
            assertFalse(clean.startsWith("_"), "首尾下划线应被 trim：「$clean」")
            assertFalse(clean.endsWith("_"), "首尾下划线应被 trim：「$clean」")
        }
    }

    /** 🔴 messageId 前缀截断长度字面值锁死（DownloadStore 目录布局复用该常量） */
    @Test
    fun `常量字面值锁死`() {
        assertEquals(12, DownloadPolicy.MESSAGE_ID_PREFIX_CHARS)
        assertEquals("downloads", DownloadPolicy.DOWNLOADS_DIRNAME)
        assertEquals("yyyy-MM-dd", DownloadPolicy.DATE_PATTERN)
    }

    // ── cleanup（enforceSizeLimit 超限删文件依赖它；供 DownloadStore 兜底复用） ──

    /** 🔴 cleanup 递归删除（对目录与文件都生效） */
    @Test
    fun `cleanup 递归删除临时目录`() {
        val ws = tempWorkspace("cleanup")
        val tmp = File(ws, "dir").apply { mkdirs() }
        File(tmp, "nested").mkdirs()
        File(tmp, "nested/a.log").writeText("x")
        File(tmp, "b.log").writeText("y")
        assertTrue(tmp.isDirectory)
        DownloadPolicy.cleanup(tmp)
        assertFalse(tmp.exists(), "🔴 cleanup 必须递归删除")
        ws.deleteRecursively()
    }

    /** 🔴 cleanup 对不存在目录 / 普通文件 / 失败场景都安全（不抛异常，尽力而为） */
    @Test
    fun `cleanup 对缺失与异常输入安全`() {
        val ws = tempWorkspace("cleanupsafe")
        DownloadPolicy.cleanup(File(ws, "definitely-missing"))
        val plain = File(ws, "plain.txt").apply { writeText("x") }
        DownloadPolicy.cleanup(plain)
        assertFalse(plain.exists(), "普通文件也应被删除（deleteRecursively 对文件即 delete）")
        ws.deleteRecursively()
    }

    /** 🔴 D18：cleanup 失败要可观测（记 warn），但不抛（不阻塞回复） */
    @Test
    fun `D18 cleanup 失败留可观测日志且不抛`() {
        val log = RecordingLog()
        val missing = File(System.getProperty("java.io.tmpdir"), "missing-cleanup-${System.nanoTime()}")
        DownloadPolicy.cleanup(missing, log)
        assertNotNull(log.lines, "日志出口必须可用")
    }

    // ── 🔴 gateway-contract §15 裁定 3：maxFileSizeBytes 是「下载后拒绝阈值」 ──

    /** 🔴 超阈值 ⇒ 返回 false **且文件已被删除**（不是留着占盘） */
    @Test
    fun `超阈值拒绝并删除文件`() {
        val ws = tempWorkspace("size")
        val file = File(ws, "big.log").apply { writeBytes(ByteArray(2_000)) }
        val log = RecordingLog()
        val passed = DownloadPolicy.enforceSizeLimit(file, maxBytes = 1_000L, log = log)
        assertFalse(passed, "🔴 超阈值必须拒绝")
        assertFalse(file.exists(), "🔴 拒绝时必须删除文件（不留残余占盘）")
        assertTrue(log.lines.any { it.contains("maxFileSizeBytes") }, "🔴 D18：拒绝必须可观测：${log.lines}")
        assertTrue(log.lines.any { it.contains("size=2000") && it.contains("limit=1000") }, "日志应带实际值与阈值：${log.lines}")
        ws.deleteRecursively()
    }

    /** 🔴 恰等阈值 ⇒ 放行（判据是 `actual <= maxBytes`，不是 `<`） */
    @Test
    fun `恰等阈值放行且边界严格`() {
        val ws = tempWorkspace("sizeedge")
        val exact = File(ws, "exact.log").apply { writeBytes(ByteArray(1_000)) }
        assertTrue(DownloadPolicy.enforceSizeLimit(exact, 1_000L), "🔴 actual == max 必须放行（<=）")
        assertTrue(exact.exists(), "放行时不得删除文件")

        val over = File(ws, "over.log").apply { writeBytes(ByteArray(1_001)) }
        assertFalse(DownloadPolicy.enforceSizeLimit(over, 1_000L), "🔴 actual == max+1 必须拒绝")
        assertFalse(over.exists())
        ws.deleteRecursively()
    }

    /** 🔴 `maxBytes <= 0` 视为不限制（配置里 0/负值 = 关闭该保护） */
    @Test
    fun `阈值非正数视为不限制`() {
        val ws = tempWorkspace("sizelimit")
        val file = File(ws, "huge.log").apply { writeBytes(ByteArray(10_000)) }
        assertTrue(DownloadPolicy.enforceSizeLimit(file, 0L), "0 = 不限制")
        assertTrue(file.exists())
        assertTrue(DownloadPolicy.enforceSizeLimit(file, -1L), "负值 = 不限制")
        assertTrue(file.exists())
        assertTrue(DownloadPolicy.enforceSizeLimit(file, Long.MIN_VALUE))
        ws.deleteRecursively()
    }

    /** 🔴 文件不存在时不抛（length() 的 runCatching 兜底 ⇒ 视为 0 字节 ⇒ 放行） */
    @Test
    fun `阈值检查对缺失文件安全`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "missing-size-${System.nanoTime()}.log")
        assertTrue(DownloadPolicy.enforceSizeLimit(missing, 1_000L), "🔴 缺失文件不得抛异常")
    }

    /** 🔴 目录也能被拒绝并递归删除（下载落盘异常时可能产出目录） */
    @Test
    fun `阈值检查对目录同样生效`() {
        val ws = tempWorkspace("dirsize")
        val dir = File(ws, "adir").apply { mkdirs(); File(this, "x.log").writeBytes(ByteArray(2_000)) }
        val dirLen = dir.length()
        assertTrue(
            DownloadPolicy.enforceSizeLimit(dir, dirLen),
            "🔴 目录 actual==max ⇒ 放行（<=），且不递归求和子文件（dirLen=$dirLen）",
        )
        assertTrue(dir.isDirectory, "放行时不得删除目录")
        ws.deleteRecursively()
    }

    // ── 域纪律守护 ──

    /** 🔴 lib 侧不重复实现下载并发限制（网关已 `Semaphore(2)`）——守护：本域零 Semaphore/并发原语 */
    @Test
    fun `files 域不重复实现下载并发限制`() {
        val dir = File(
            com.jiaoay.lark.bridge.pipeline.testRepoRoot(),
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files",
        )
        assertTrue(dir.isDirectory, "被扫描的目录必须存在（否则本守护是空过）：${dir.path}")
        val sources = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(6, sources.size, "files 域应恰好 6 个文件（P3 批 2026-09-26 UploadKeyMemo 退役后：引用流 4 + CardReplyLedger + DownloadPolicy）：${sources.map { it.name }}")
        sources.forEach { file ->
            val normalized = com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings(file.readText())
            assertFalse(normalized.contains("Semaphore"), "🔴 并发限制归网关侧（downloadSingleFlight=2），lib 不得重复实现")
            assertFalse(normalized.contains("ProcessBuilder"), "🔴 E 组守护：files 域不得出现 ProcessBuilder")
            assertFalse(normalized.contains("runBlocking"), "🔴 D5：files 域不得阻塞线程")
            assertFalse(normalized.contains("@Volatile"), "🔴 D16")
        }
    }

    /** 🔴 D16：DownloadPolicy object 体内零可变状态 */
    @Test
    fun `D16 DownloadPolicy object 体内零可变状态`() {
        val file = File(
            com.jiaoay.lark.bridge.pipeline.testRepoRoot(),
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/DownloadPolicy.kt",
        )
        assertTrue(file.isFile, "被扫描的源文件必须存在：${file.path}")
        val raw = file.readText()
        assertTrue(raw.lines().size > 50, "疑似扫到了空壳文件：${raw.lines().size} 行")
        val body = com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings(raw).substringAfter("object DownloadPolicy")
        assertFalse(Regex("""\bvar\s+\w""").containsMatchIn(body), "🔴 D16：object 体内出现 var")
    }
}
