// 🔴 2026-09-17 审计修复配套守护：Windows 文件名清洗的「同一语义两份实现」防漂移。
//
// 背景：`DownloadPolicy.sanitizeFileName`（:lark-bridge）与 `LogTagFilter.sanitizeTagName`
// （:log-tools）是两个模块里**同一语义的独立实现**——前者管飞书下载落盘的文件名，
// 后者管日志工具输出文件名。2026-09-17 审计发现：后者早已补全了 Windows 非法字符与
// 保留设备名，前者漏了——于是「同一个 bug，一处修了一处没修」。
//
// 为什么不做成共享函数：`:lark-bridge` 以 `implementation` 单向依赖 `:log-tools`，
// 且对方的清洗函数是 `internal`（跨模块不可见）。把字面表提到公共位置需要动依赖方向
// 或新建模块，属于架构级改动，超出本次修复范围。故采用**各自持有 + 契约测试守护**——
// 与仓库既有的「各域复制同一字面表 + 跨模块契约测试」打法一致。
//
// 本测试用**源码扫描**比对两处的规则集：任一处增删字符而另一处没跟上时，这里先红。
// 🔴 验收纪律：一律 --rerun-tasks --no-build-cache（AGENTS.md「测试」节）。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.pipeline.testRepoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Windows 文件名清洗规则的跨模块一致性守护。
 *
 * 三个组：
 * - **A**：两处实现的 Windows 非法字符集必须一致（防止一边补了 `<>:"|?*` 另一边没补）；
 * - **B**：两处实现的保留设备名清单必须一致；
 * - **C**：两处都必须包含「尾部点号/空白清理」这一条（Windows 静默忽略尾部点号）。
 */
class WindowsFileNameContractTest {

    private val downloadPolicySrc: File by lazy {
        File(testRepoRoot(), "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/DownloadPolicy.kt")
    }
    private val logTagFilterSrc: File by lazy {
        File(testRepoRoot(), "log-tools/src/main/kotlin/com/jiaoay/log/LogTagFilter.kt")
    }

    /**
     * 从源码文本里抽出正则字符类中出现的「特殊字符」集合。
     *
     * 扫描策略：先按 marker 定位到「承载非法字符表的那一行」——两处实现的行形态不同
     * （DownloadPolicy 是 `private val WINDOWS_ILLEGAL_CHARS = Regex(...)` 单行；
     * LogTagFilter 是 `var name = raw.replace(Regex(...), "_")` 内联在函数体），
     * 故统一按「含 marker **且** 含字符类 `[...]`」定位，取第一个命中行。
     */
    private fun illegalCharsIn(src: String, marker: String): Set<Char> {
        val line = src.lines().firstOrNull { it.contains(marker) && it.contains('[') && it.contains(']') }
        assertTrue(line != null, "未找到承载非法字符表的行（marker=`$marker`）——实现可能被重命名或删除")
        val start = line!!.indexOf('[')
        val end = line.lastIndexOf(']')
        assertTrue(start in 0 until end, "该行内未找到字符类 [ ... ]：$line")
        val body = line.substring(start + 1, end)
        // 字符类里 `\\` 表示「反斜杠本身」；其余 `\\x` 是转义。这里只关心「专有字符」，
        // 故剔除全部反斜杠后取剩余字符（反斜杠单独判定）。
        return body.filter { it != '\\' }.toSet() + if (body.contains("\\\\")) setOf('\\') else emptySet()
    }

    /** 从源码文本里抽出保留设备名清单（大写化的字符串字面量集合）。 */
    private fun reservedNamesIn(src: String, marker: String): Set<String> {
        val idx = src.indexOf(marker)
        assertTrue(idx >= 0, "未找到保留名清单 `$marker`——实现可能被重命名或删除")
        // 取 marker 之后到第一个 `)` 之间的全部 "XXX" 字面量
        val body = src.substring(idx, src.indexOf(')', idx))
        return Regex("\"([A-Z0-9]{3,4})\"").findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `A 两处实现的 Windows 非法字符集一致`() {
        val a = illegalCharsIn(downloadPolicySrc.readText(), "WINDOWS_ILLEGAL_CHARS")
        val b = illegalCharsIn(logTagFilterSrc.readText(), "raw.replace")

        // 两处都必须覆盖 Windows 的七个非法字符（`< > : " | ? *`）
        // 注意两侧正则的写法不同：DownloadPolicy 是完整字符类（含分隔符），
        // LogTagFilter 是内联 Regex("""[<>:"|?*/\\]""")，故只比对「七个专有字符」这一个交集口径。
        val windowsSeven = setOf('<', '>', ':', '"', '|', '?', '*')
        assertTrue(
            a.containsAll(windowsSeven),
            "DownloadPolicy 的非法字符集缺 Windows 专有字符：${windowsSeven - a}（实际：$a）",
        )
        assertTrue(
            b.containsAll(windowsSeven),
            "LogTagFilter 的非法字符集缺 Windows 专有字符：${windowsSeven - b}（实际：$b）",
        )
    }

    @Test
    fun `B 两处实现的保留设备名清单一致`() {
        val a = reservedNamesIn(downloadPolicySrc.readText(), "WINDOWS_RESERVED_NAMES = setOf")
        val b = reservedNamesIn(logTagFilterSrc.readText(), "WINDOWS_RESERVED_NAMES = setOf")

        val canonical = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
        assertTrue(a.isNotEmpty(), "DownloadPolicy 的保留名清单解析为空——扫描逻辑或实现形态变了")
        assertTrue(b.isNotEmpty(), "LogTagFilter 的保留名清单解析为空——扫描逻辑或实现形态变了")
        assertTrue(
            a.containsAll(canonical),
            "DownloadPolicy 的保留名清单不完整，缺：${canonical - a}",
        )
        assertTrue(
            b.containsAll(canonical),
            "LogTagFilter 的保留名清单不完整，缺：${canonical - b}",
        )
        assertTrue(a == b, "两处保留名清单已漂移：\n  DownloadPolicy=$a\n  LogTagFilter=$b")
    }

    @Test
    fun `C 两处实现都清理尾部点号与空白`() {
        val a = downloadPolicySrc.readText()
        val b = logTagFilterSrc.readText()

        // DownloadPolicy：trim { it == '_' || it.isWhitespace() || it == '.' }
        assertTrue(
            a.contains("it.isWhitespace() || it == '.'") || a.contains("it == '.' || it.isWhitespace()"),
            "DownloadPolicy 未清理尾部点号/空白——Windows 会静默忽略尾部点号，产生用户找不到的文件名",
        )
        // LogTagFilter：trim { it.isWhitespace() || it == '.' }
        assertTrue(
            b.contains("it.isWhitespace() || it == '.'") || b.contains("it == '.' || it.isWhitespace()"),
            "LogTagFilter 未清理尾部点号/空白",
        )
    }

    @Test
    fun `D 两处实现都做长度保护（DownloadPolicy 侧为本次新增）`() {
        // DownloadPolicy 新增长度上限（防 Windows 260 全路径突破 / 单段 NAME_MAX 突破）
        val a = downloadPolicySrc.readText()
        assertTrue(
            a.contains("MAX_BASENAME_BYTES"),
            "DownloadPolicy 缺少主名长度上限——下载区路径已四层嵌套，长文件名会突破 Windows 260 上限",
        )
        // 🔴 2026-09-17 复核修复：上限单位从**码点**改为 **UTF-8 字节**。
        //    NAME_MAX 是 255 字节而非码点，中文 3 字节/字 ⇒ 按码点放行可达 300 字节
        //    （复核实测 257 字节即 mkdir ENAMETOOLONG）。此断言随之由「按码点」改为「按字节」。
        assertTrue(
            a.contains("toByteArray(Charsets.UTF_8)") && a.contains("utf8SizeOfCodePoint"),
            "DownloadPolicy 的长度截断必须按 UTF-8 字节（NAME_MAX 是字节数；按码点会放过 3 倍超限的中文名）",
        )
        // 仍须保证不切断代理对（逐码点累加的实现方式保证这一点）
        assertTrue(
            a.contains("appendCodePoint") || a.contains("codePointCount") || a.contains("codePointAt"),
            "DownloadPolicy 的长度截断必须逐码点进行（避免切断代理对产出乱码）",
        )
    }
}
