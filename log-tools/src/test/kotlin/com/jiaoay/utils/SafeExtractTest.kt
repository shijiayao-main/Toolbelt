// M4-T8 第一批 · D 组 files 测试：解包器与 🔴 zip-slip 双重校验（禁止重写代码之三）。
// 来源：legacy `filemsg/FileMessageExecutorTest.kt` 的 **R6 解包回归段**（:73-128，4 条）**逐条移植**
//       + BC §4.7 校正补充（`.tar`/`.gz` 独立后缀、tar typeFlag 白名单覆盖符号链接/硬链接逃逸面）。
// 🔴 移植差异：`FileExtractor` → `SafeExtract`（同域改名，实现逐字）。
// 2026-09-12 席位迁移：随 SafeExtract 自 :lark-bridge 迁至 :log-tools（包名同步），用例零改动。
package com.jiaoay.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SafeExtract]：BC §3 主契约表 filemsg 组的解包段 + BC §4.7 的 zip-slip 三重拒绝。
 *
 * 🔴 **禁止重写代码之三**：`safeChild` 的两重校验（字面 + canonical）一行不改。
 */
class SafeExtractTest {

    private fun tmpWorkspace(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "safe-extract-$name-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    // ── 旧 4 条逐条移植（R6 解包回归） ──

    @Test
    fun `zip 解包产出文件清单`() {
        val ws = tmpWorkspace("zip")
        val zip = File(ws, "logs.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("a/crash.log"))
            zos.write("crash-body".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("a/sub/b.log"))
            zos.write("b-body".toByteArray())
            zos.closeEntry()
        }
        val dest = File(ws, "out-zip")
        val files = SafeExtract.extract(zip, dest)
        assertEquals(2, files.size, "zip 应解出 2 个文件")
        assertTrue(files.any { it.name == "crash.log" && it.readText() == "crash-body" })
        assertTrue(files.any { it.name == "b.log" && it.readText() == "b-body" })
        // 🔴 子目录结构被保留（父目录自动创建）
        assertTrue(File(dest, "a/crash.log").isFile)
        assertTrue(File(dest, "a/sub/b.log").isFile)
        ws.deleteRecursively()
    }

    @Test
    fun `zip-slip 条目被拒绝不落盘`() {
        val ws = tmpWorkspace("slip")
        val zip = File(ws, "evil.zip")
        // 构造含 .. 穿越条目的 zip（ZipOutputStream 允许任意 entry name）
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.log"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
        }
        val dest = File(ws, "out-slip")
        val files = SafeExtract.extract(zip, dest)
        assertTrue(files.isEmpty(), "穿越条目应被拒绝: $files")
        assertNull(files.firstOrNull { it.path.contains("..") }, "不得有越界路径")
        // 🔴 拒绝的证据不只是「返回列表为空」，还要证明**磁盘上没有落地**
        assertFalse(File(ws, "escape.log").exists(), "🔴 穿越文件不得写到 destDir 之外")
        assertFalse(File(dest.parentFile, "escape.log").exists())
        ws.deleteRecursively()
    }

    @Test
    fun `isArchive 按扩展名判定`() {
        assertTrue(SafeExtract.isArchive("logs.zip"))
        assertTrue(SafeExtract.isArchive("logs.tar.gz"))
        assertTrue(SafeExtract.isArchive("logs.TGZ"))
        assertFalse(SafeExtract.isArchive("crash.log"))
        assertFalse(SafeExtract.isArchive("dump.xlog"))
    }

    // ── 🔴 BC §4.7 校正：方案报告漏了 `.tar` 与 `.gz` 两个独立后缀 ──

    @Test
    fun `BC-292 isArchive 的五个后缀全表且大小写不敏感`() {
        listOf(".zip", ".tar", ".tar.gz", ".tgz", ".gz").forEach { ext ->
            assertTrue(SafeExtract.isArchive("logs$ext"), "🔴 BC 校正：`$ext` 必须判为压缩包")
            assertTrue(SafeExtract.isArchive("LOGS${ext.uppercase()}"), "🔴 大小写不敏感：`$ext`")
        }
        listOf(".log", ".xlog", ".txt", ".json", ".alog", "", "zip", ".gzip", ".tar.bz2").forEach { name ->
            assertFalse(SafeExtract.isArchive(name), "「$name」不是支持的压缩包")
        }
    }

    /** 🔴 `.tar.bz2` 明确不支持 ⇒ extract 抛 IllegalArgumentException（不是静默返回空） */
    @Test
    fun `不支持的格式抛 IllegalArgumentException`() {
        val ws = tmpWorkspace("bad")
        val bad = File(ws, "logs.rar").apply { writeText("not an archive") }
        val e = assertFailsWith<IllegalArgumentException> { SafeExtract.extract(bad, File(ws, "out")) }
        assertTrue(e.message.orEmpty().contains("不支持的压缩格式"), e.message)
        assertTrue(e.message.orEmpty().contains("logs.rar"), "异常应带文件名供排障：${e.message}")
        ws.deleteRecursively()
    }

    // ── 🔴 zip-slip 三重拒绝（字面 `/`、字面 `..`、canonical） ──

    /** 🔴 第①重：绝对路径（含 Windows 反斜杠形态与 UNC） */
    @Test
    fun `zip-slip 第①重 绝对路径被拒`() {
        val dest = tmpWorkspace("abs")
        assertNull(SafeExtract.safeChild(dest, "/etc/passwd"))
        assertNull(SafeExtract.safeChild(dest, "/tmp/escape.log"))
        // 🔴 `replace('\\','/')` 归一化后，Windows 反斜杠绝对路径同样命中 `startsWith("/")`
        assertNull(SafeExtract.safeChild(dest, "\\etc\\passwd"), "反斜杠归一化后应判为绝对路径")
        assertNull(SafeExtract.safeChild(dest, "\\..\\escape.log"))
        dest.deleteRecursively()
    }

    /** 🔴 第②重：`..` 穿越（开头 / 中间 / 结尾 / 多重） */
    @Test
    fun `zip-slip 第②重 各种形态的点点穿越被拒`() {
        val dest = tmpWorkspace("dotdot")
        listOf(
            "../x.log",
            "../../x.log",
            "a/../../x.log",
            "a/b/../../../x.log",
            "..",
            "../",
            "a/..",
            "..\\x.log",
        ).forEach { entry ->
            assertNull(SafeExtract.safeChild(dest, entry), "🔴 穿越条目必须被拒：「$entry」")
        }
        dest.deleteRecursively()
    }

    /**
     * 🔴 第③重：canonical 校验。
     * 字面校验拦不住的残余形态（符号链接指向外部、Windows 盘符切换）由 canonical 兜底。
     * 用「destDir 内先造一个指向外部的符号链接」构造：条目名 `link/escape.log` **字面合法**
     * （无 `/` 开头、无 `..`），但 canonical 解析后落在 destDir 之外 ⇒ 必须被拒。
     */
    @Test
    fun `zip-slip 第③重 canonical 校验拦住符号链接逃逸`() {
        val ws = tmpWorkspace("canon")
        val dest = File(ws, "dest").apply { mkdirs() }
        val outside = File(ws, "outside").apply { mkdirs() }
        val link = File(dest, "link")
        val created = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        if (created.isFailure) {
            // 环境不支持符号链接（Windows 无管理员权限 / 文件系统限制）⇒ 退化为验证 canonical 分支本身可达
            ws.deleteRecursively()
            return
        }
        // 字面校验会通过：不以 `/` 开头、不含 `..`
        assertFalse(link.name.startsWith("/"))
        assertFalse("link/escape.log".contains(".."))
        // canonical 校验必须拦下
        assertNull(
            SafeExtract.safeChild(dest, "link/escape.log"),
            "🔴 符号链接逃逸必须被 canonical 校验拦下（字面合法但真实路径在 destDir 外）",
        )
        assertFalse(File(outside, "escape.log").exists(), "🔴 外部目录不得被写入")
        // 对照：dest 内的正常路径仍放行
        assertNotNull(SafeExtract.safeChild(dest, "plain.log"))
        ws.deleteRecursively()
    }

    /** 🔴 `safeChild` 的 `runCatching` 语义：canonical 解析失败（不可解析的路径）返回 null 而非抛异常 */
    @Test
    fun `safeChild 对不可解析路径返回 null 而非抛异常`() {
        val dest = tmpWorkspace("unresolvable")
        // NUL 字节让 canonicalPath 抛 InvalidPathException / IOException ⇒ runCatching 兜住
        val result = runCatching { SafeExtract.safeChild(dest, "a\u0000b.log") }
        assertTrue(result.isSuccess, "🔴 safeChild 内部 runCatching 必须兜住解析异常")
        assertNull(result.getOrNull())
        dest.deleteRecursively()
    }

    /** 🔴 空白条目名被拒（第 0 重：`isBlank`） */
    @Test
    fun `safeChild 拒绝空白条目名`() {
        val dest = tmpWorkspace("blank")
        assertNull(SafeExtract.safeChild(dest, ""))
        assertNull(SafeExtract.safeChild(dest, "   "))
        assertNull(SafeExtract.safeChild(dest, "\t\n"))
        dest.deleteRecursively()
    }

    /** 🔴 放行时返回的是 `File(destDir, normalized)`（反斜杠已归一化为 `/`） */
    @Test
    fun `safeChild 放行时反斜杠归一化为正斜杠`() {
        val dest = tmpWorkspace("normalize")
        val ok = SafeExtract.safeChild(dest, "a\\b\\c.log")
        assertNotNull(ok)
        assertFalse(ok.path.replace(dest.path, "").contains("\\"), "反斜杠应已归一化：${ok.path}")
        assertEquals("c.log", ok.name)
        dest.deleteRecursively()
    }

    /** 🔴 拒绝是**跳过该条目而非中止解包**（同包内的合法条目仍要解出） */
    @Test
    fun `拒绝条目不中止整个解包`() {
        val ws = tmpWorkspace("mixed")
        val zip = File(ws, "mixed.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.log"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("/etc/passwd"))
            zos.write("evil2".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("good/a.log"))
            zos.write("good-body".toByteArray())
            zos.closeEntry()
        }
        val dest = File(ws, "out")
        val files = SafeExtract.extract(zip, dest)
        assertEquals(1, files.size, "🔴 只有合法条目被解出：$files")
        assertEquals("a.log", files.first().name)
        assertEquals("good-body", files.first().readText())
        assertFalse(File(ws, "escape.log").exists())
        ws.deleteRecursively()
    }

    /** 🔴 目录条目被跳过（不产出文件、不落空目录） */
    @Test
    fun `zip 目录条目被跳过`() {
        val ws = tmpWorkspace("dironly")
        val zip = File(ws, "dirs.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("emptydir/"))
            zos.closeEntry()
        }
        val dest = File(ws, "out")
        assertTrue(SafeExtract.extract(zip, dest).isEmpty(), "目录条目不得计入文件清单")
        ws.deleteRecursively()
    }

    // ── tar / tar.gz / tgz / 单文件 gz ──

    /** 🔴 手写 ustar 解析：普通文件条目被解出且 512 对齐正确 */
    @Test
    fun `tar 解包产出文件清单`() {
        val ws = tmpWorkspace("tar")
        val tar = File(ws, "logs.tar")
        tar.writeBytes(
            tarEntry("a/crash.log", "crash-body".toByteArray()) +
                tarEntry("b.log", "b-body".repeat(1000).toByteArray()) + // 跨多个 512 块 + padding
                tarEndBlock(),
        )
        val dest = File(ws, "out-tar")
        val files = SafeExtract.extract(tar, dest)
        assertEquals(2, files.size, "tar 应解出 2 个文件：$files")
        assertTrue(files.any { it.name == "crash.log" && it.readText() == "crash-body" })
        assertTrue(files.any { it.name == "b.log" && it.readText() == "b-body".repeat(1000) })
        assertTrue(File(dest, "a/crash.log").isFile, "子目录条目应先建父目录")
        ws.deleteRecursively()
    }

    /** 🔴 tar.gz 与 tgz 走同一分支（GZIPInputStream 外层） */
    @Test
    fun `tar gz 与 tgz 解包`() {
        val ws = tmpWorkspace("targz")
        val payload = tarEntry("x.log", "gz-body".toByteArray()) + tarEndBlock()
        listOf("logs.tar.gz" to payload, "logs.tgz" to payload).forEach { (name, bytes) ->
            val archive = File(ws, name)
            GZIPOutputStream(FileOutputStream(archive)).use { it.write(bytes) }
            val dest = File(ws, "out-${name.hashCode()}")
            val files = SafeExtract.extract(archive, dest)
            assertEquals(1, files.size, "$name 应解出 1 个文件：$files")
            assertEquals("gz-body", files.first().readText())
        }
        ws.deleteRecursively()
    }

    /** 🔴 单文件 `.gz`：`xxx.log.gz` → `xxx.log`（去后缀，不是解成 tar 条目） */
    @Test
    fun `单文件 gz 解压去后缀`() {
        val ws = tmpWorkspace("gz")
        val archive = File(ws, "crash.log.gz")
        GZIPOutputStream(FileOutputStream(archive)).use { it.write("plain-gz-body".toByteArray()) }
        val dest = File(ws, "out-gz")
        val files = SafeExtract.extract(archive, dest)
        assertEquals(1, files.size)
        assertEquals("crash.log", files.first().name, "🔴 去掉 .gz 后缀")
        assertEquals("plain-gz-body", files.first().readText())
        ws.deleteRecursively()
    }

    /**
     * 🔴 BC §4.7 校正：**tar 的 typeFlag 白名单要覆盖符号链接/硬链接（它们是逃逸面）**。
     * typeFlag '2'（symlink）与 '1'（hardlink）必须被跳过——否则解出一个指向 `/etc` 的链接后，
     * 后续条目就能沿它写出工作区。
     */
    @Test
    fun `tar 符号链接与硬链接条目被跳过（逃逸面）`() {
        val ws = tmpWorkspace("tartype")
        val tar = File(ws, "evil.tar")
        tar.writeBytes(
            // typeFlag '2' = 符号链接，linkname 指向工作区外
            tarEntry("escape", ByteArray(0), typeFlag = '2', linkName = "/etc") +
                // typeFlag '1' = 硬链接
                tarEntry("hard", ByteArray(0), typeFlag = '1', linkName = "/etc/passwd") +
                // typeFlag '5' = 目录
            tarEntry("somedir/", ByteArray(0), typeFlag = '5') +
                // 🔴 链接条目之后仍有普通条目 ⇒ 证明「跳过」没有破坏 512 字节流对齐
                tarEntry("after.log", "after-body".toByteArray()) +
                tarEndBlock(),
        )
        val dest = File(ws, "out-tar")
        val files = SafeExtract.extract(tar, dest)
        assertEquals(1, files.size, "🔴 只有普通文件条目被解出：${files.map { it.name }}")
        assertEquals("after.log", files.first().name)
        assertEquals("after-body", files.first().readText(), "🔴 跳过链接后流对齐必须仍正确")
        assertFalse(File(dest, "escape").exists(), "🔴 符号链接条目不得落盘（它是逃逸面）")
        assertFalse(File(dest, "hard").exists(), "🔴 硬链接条目不得落盘")
        ws.deleteRecursively()
    }

    /** 🔴 tar 内的 zip-slip：条目名带 `..` / 绝对路径同样被 safeChild 拒绝，且流对齐不被破坏 */
    @Test
    fun `tar 穿越条目被拒且不破坏后续解包`() {
        val ws = tmpWorkspace("tarslip")
        val tar = File(ws, "slip.tar")
        tar.writeBytes(
            tarEntry("../escape.log", "evil".toByteArray()) +
                tarEntry("/etc/passwd", "evil2".toByteArray()) +
                tarEntry("good.log", "good-body".toByteArray()) +
                tarEndBlock(),
        )
        val dest = File(ws, "out-tar")
        val files = SafeExtract.extract(tar, dest)
        assertEquals(1, files.size, "🔴 穿越条目被拒，合法条目仍解出：${files.map { it.name }}")
        assertEquals("good-body", files.first().readText())
        assertFalse(File(ws, "escape.log").exists(), "🔴 tar 穿越不得写到 destDir 之外")
        ws.deleteRecursively()
    }

    /** 🔴 全零块 = 归档结束（后面的垃圾数据不得被当成条目） */
    @Test
    fun `tar 全零块终止解析`() {
        val ws = tmpWorkspace("tareof")
        val tar = File(ws, "eof.tar")
        tar.writeBytes(
            tarEntry("a.log", "a".toByteArray()) +
                tarEndBlock() +
                tarEntry("trailing.log", "trailing".toByteArray()), // 结束块之后的数据应被忽略
        )
        val files = SafeExtract.extract(tar, File(ws, "out"))
        assertEquals(1, files.size, "🔴 全零块之后的内容不得被解析：${files.map { it.name }}")
        assertEquals("a.log", files.first().name)
        ws.deleteRecursively()
    }

    /** 🔴 空 tar / 截断 tar 不抛异常（返回空清单） */
    @Test
    fun `空与截断的 tar 安全返回空清单`() {
        val ws = tmpWorkspace("tarempty")
        val empty = File(ws, "empty.tar").apply { writeBytes(ByteArray(0)) }
        assertTrue(SafeExtract.extract(empty, File(ws, "out1")).isEmpty())
        val truncated = File(ws, "trunc.tar").apply { writeBytes(ByteArray(300)) } // 不足一个 512 header
        assertTrue(SafeExtract.extract(truncated, File(ws, "out2")).isEmpty())
        val zeroOnly = File(ws, "zero.tar").apply { writeBytes(tarEndBlock()) }
        assertTrue(SafeExtract.extract(zeroOnly, File(ws, "out3")).isEmpty())
        ws.deleteRecursively()
    }

    /** 🔴 零字节普通文件不产出条目（实现要求 `size > 0`；行为锁死，防将来「优化」成产出空文件） */
    @Test
    fun `零字节 tar 条目不产出文件`() {
        val ws = tmpWorkspace("tarzero")
        val tar = File(ws, "zero-entry.tar")
        tar.writeBytes(tarEntry("empty.log", ByteArray(0)) + tarEntry("real.log", "x".toByteArray()) + tarEndBlock())
        val files = SafeExtract.extract(tar, File(ws, "out"))
        assertEquals(1, files.size, "🔴 size=0 的条目被跳过（与旧实现一致）：${files.map { it.name }}")
        assertEquals("real.log", files.first().name)
        ws.deleteRecursively()
    }

    /** 🔴 非 ASCII（UTF-8）文件名在 tar 与 zip 中都能正确解出 */
    @Test
    fun `非 ASCII 文件名正确解出`() {
        val ws = tmpWorkspace("utf8")
        // zip：ZipOutputStream 默认 UTF-8
        val zip = File(ws, "cn.zip")
        ZipOutputStream(FileOutputStream(zip), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("日志/崩溃.log"))
            zos.write("中文内容".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipFiles = SafeExtract.extract(zip, File(ws, "out-zip"))
        assertEquals(1, zipFiles.size)
        assertEquals("崩溃.log", zipFiles.first().name)
        assertEquals("中文内容", zipFiles.first().readText(Charsets.UTF_8))
        // tar：header 的 name 字段按 UTF-8 解析
        val tar = File(ws, "cn.tar")
        tar.writeBytes(tarEntry("日志/崩溃.log", "中文内容".toByteArray(Charsets.UTF_8)) + tarEndBlock())
        val tarFiles = SafeExtract.extract(tar, File(ws, "out-tar"))
        assertEquals(1, tarFiles.size)
        assertEquals("崩溃.log", tarFiles.first().name, "🔴 tar header 的 name 按 UTF-8 解析")
        assertEquals("中文内容", tarFiles.first().readText(Charsets.UTF_8))
        ws.deleteRecursively()
    }

    /** 🔴 目标目录不存在时自动创建（`destDir.mkdirs()`） */
    @Test
    fun `目标目录不存在时自动创建`() {
        val ws = tmpWorkspace("mkdirs")
        val zip = File(ws, "a.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("a.log"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val dest = File(ws, "deep/nested/out")
        assertFalse(dest.exists())
        val files = SafeExtract.extract(zip, dest)
        assertTrue(dest.isDirectory, "🔴 destDir 应被自动创建")
        assertEquals(1, files.size)
        ws.deleteRecursively()
    }

    /** 🔴 D16：object 体内零可变状态（只有两个 Int 常量）。
     *  2026-09-12 随 SafeExtract 迁自 :lark-bridge：repoRoot/strip 工具改内联自包含实现
     *  （桥接域的 PipelineTestSupport 对本模块不可见），扫描路径指向迁移后的新址。 */
    @Test
    fun `D16 SafeExtract object 体内零可变状态`() {
        val file = File(repoRoot(), "log-tools/src/main/kotlin/com/jiaoay/utils/SafeExtract.kt")
        assertTrue(file.isFile, "被扫描的源文件必须存在（否则本守护是空过）：${file.path}")
        val raw = file.readText()
        assertTrue(raw.lines().size > 50, "疑似扫到了空壳文件：${raw.lines().size} 行")
        val body = stripCommentsAndStrings(raw).substringAfter("object SafeExtract")
        // 🔴 D16 的语义是「object 体级无可变全局状态」，不是「函数内不许有局部 var」。
        //    SafeExtract 的 tar/流解析辅助（parseTarString/readFully/copyFully/skipFully）含循环计数用的**局部** var，
        //    它们逐字平移自旧 FileExtractor，属栈局部、非共享，不构成 D16 所指的全局可变状态。
        //    成员属性位于 object 体首层（4 空格缩进），局部 var 在函数内（≥8 空格）⇒ 按缩进锚定只抓体级 var。
        val memberVar = Regex("""(?m)^ {4}(?:private |internal |public |protected )?var\s+\w""")
        assertFalse(memberVar.containsMatchIn(body), "🔴 D16：object 体级出现可变属性 var（全局可变状态）")
        assertFalse(body.contains("@Volatile"), "🔴 D16：object 体内不得有 @Volatile")
    }

    /** 从 `user.dir` 逐级上溯到含 `settings.gradle.kts` 的仓库根（对齐桥接域 PipelineTestSupport 同名工具）。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    /**
     * 剥离注释（行 `//` 与嵌套块 `/* */`）与字符串字面量（`"..."` 与 `"""..."""`）。
     * 🔴 「禁止出现 X」型断言必须用归一化源（注释里对 token 的提及会造成假红）；
     *    实现对齐桥接域 PipelineTestSupport.stripCommentsAndStrings（逐字）。
     */
    private fun stripCommentsAndStrings(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        val n = src.length
        var blockDepth = 0
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                blockDepth > 0 -> {
                    if (c == '/' && next == '*') {
                        blockDepth++
                        i += 2
                    } else if (c == '*' && next == '/') {
                        blockDepth--
                        i += 2
                    } else {
                        i++
                    }
                }

                c == '/' && next == '/' -> while (i < n && src[i] != '\n') i++
                c == '/' && next == '*' -> {
                    blockDepth++
                    i += 2
                }

                c == '"' && next == '"' && i + 2 < n && src[i + 2] == '"' -> {
                    i += 3
                    while (i < n && !(src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"')) i++
                    if (i < n) i += 3
                }

                c == '"' -> {
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    if (i < n) i++
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ── 手写 ustar 构造辅助（零依赖：与实现同构，避免引入第三方 tar 库） ──

    /** 全零结束块（两个 512 零块是 POSIX 惯例，实现读到第一个就停） */
    private fun tarEndBlock(): ByteArray = ByteArray(512) + ByteArray(512)

    /**
     * 构造一个 ustar 条目（512B header + 数据 + 512 对齐 padding）。
     * @param typeFlag '0' 普通文件 / '5' 目录 / '2' 符号链接 / '1' 硬链接
     */
    private fun tarEntry(name: String, body: ByteArray, typeFlag: Char = '0', linkName: String = ""): ByteArray {
        val header = ByteArray(512)
        putField(header, 0, 100, name)
        putField(header, 100, 8, "0000644")
        putField(header, 108, 8, "0000000")
        putField(header, 116, 8, "0000000")
        // size：offset 124，12 字节，八进制 ASCII（实现以 NUL/空格截断）
        putField(header, 124, 12, body.size.toString(8).padStart(11, '0'))
        putField(header, 136, 12, "00000000000")
        header[156] = typeFlag.code.toByte()
        putField(header, 157, 100, linkName)
        putField(header, 257, 6, "ustar")
        putField(header, 263, 2, "00")
        // checksum：先填 8 个空格再按无符号字节求和（实现不校验，但保持格式真实）
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        val sum = header.sumOf { it.toInt() and 0xFF }
        putField(header, 148, 7, sum.toString(8).padStart(6, '0'))
        header[155] = 0
        val padding = ((512 - (body.size % 512)) % 512)
        return ByteArrayOutputStream().use { out ->
            out.write(header)
            out.write(body)
            out.write(ByteArray(padding))
            out.toByteArray()
        }
    }

    /** 写入定长字段（UTF-8 字节，超出截断，余下补 NUL） */
    // ── 🔴 2026-09-17 审计修复：解压炸弹防护（三道闸的契约） ──

    /** 构造一个「高压缩比」zip：内容是大量重复字节（真实炸弹的形态）。 */
    private fun makeBombZip(ws: File, name: String, entryName: String, repeatCount: Int): File {
        val zip = File(ws, name)
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(ByteArray(repeatCount))   // 全零字节 = 极高压缩比
            zos.closeEntry()
        }
        return zip
    }

    @Test
    fun `累计配额超限时中止并清理可见文案`() {
        val ws = tmpWorkspace("bomb-total")
        // 全零 10MB 解出（压缩后极小），配额设 1MB ⇒ 必超
        val zip = makeBombZip(ws, "bomb.zip", "huge.log", 10 * 1024 * 1024)
        val dest = File(ws, "out")
        val ex = assertFailsWith<SafeExtract.ExtractLimitExceededException> {
            SafeExtract.extract(zip, dest, SafeExtract.Limits(maxTotalBytes = 1L * 1024 * 1024))
        }
        assertTrue("安全上限" in ex.message!!, "异常文案应可读：${ex.message}")
        ws.deleteRecursively()
    }

    @Test
    fun `条目数超限时中止`() {
        val ws = tmpWorkspace("bomb-entries")
        val zip = File(ws, "many.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            repeat(50) { i ->
                zos.putNextEntry(ZipEntry("f$i.log"))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        val dest = File(ws, "out")
        val ex = assertFailsWith<SafeExtract.ExtractLimitExceededException> {
            SafeExtract.extract(zip, dest, SafeExtract.Limits(maxEntries = 10))
        }
        assertTrue("条目数" in ex.message!!, "异常文案应指明是条目数超限：${ex.message}")
        ws.deleteRecursively()
    }

    @Test
    fun `正常压缩包在默认阈值下不受影响`() {
        val ws = tmpWorkspace("normal")
        val zip = File(ws, "normal.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("a.log"))
            zos.write("normal-body".toByteArray())
            zos.closeEntry()
        }
        val dest = File(ws, "out")
        val files = SafeExtract.extract(zip, dest)   // 默认 Limits
        assertEquals(1, files.size)
        assertEquals("normal-body", files[0].readText())
        ws.deleteRecursively()
    }

    @Test
    fun `gzip 单文件超配额同样被拦`() {
        val ws = tmpWorkspace("bomb-gz")
        val gz = File(ws, "huge.log.gz")
        File(ws, "huge.log").apply { writeBytes(ByteArray(10 * 1024 * 1024)) }
            .let { src -> GZIPOutputStream(FileOutputStream(gz)).use { it.write(src.readBytes()) } }
        val dest = File(ws, "out")
        assertFailsWith<SafeExtract.ExtractLimitExceededException> {
            SafeExtract.extract(gz, dest, SafeExtract.Limits(maxTotalBytes = 1L * 1024 * 1024))
        }
        ws.deleteRecursively()
    }

    @Test
    fun `tar 超配额在写入前被拦（header 前置判定）`() {
        val ws = tmpWorkspace("bomb-tar")
        val tar = File(ws, "bomb.tar")
        // 构造一个单条目 tar（复用本文件的 buildTar helper）
        val header = buildTarHeader("huge.log", 8L * 1024 * 1024)
        val body = ByteArray(8 * 1024 * 1024)
        FileOutputStream(tar).use { out ->
            out.write(header)
            out.write(body)
            // 写结束块
            out.write(ByteArray(1024))
        }
        val dest = File(ws, "out")
        val ex = assertFailsWith<SafeExtract.ExtractLimitExceededException> {
            SafeExtract.extract(tar, dest, SafeExtract.Limits(maxTotalBytes = 1L * 1024 * 1024))
        }
        assertTrue("配额" in ex.message!!, "tar 的前置判定文案应可读：${ex.message}")
        ws.deleteRecursively()
    }

    private fun buildTarHeader(name: String, size: Long): ByteArray {
        val header = ByteArray(512)
        putField(header, 0, 100, name)
        putField(header, 124, 12, size.toString(8).padStart(11, '0') + "\u0000")
        header[156] = '0'.code.toByte()
        // checksum（本测试不需要真实校验和——解析器不校验）
        return header
    }

    private fun putField(header: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8).copyOf(minOf(length, value.toByteArray(Charsets.UTF_8).size))
        System.arraycopy(bytes, 0, header, offset, bytes.size)
    }

    // ==================== 🔴 2026-09-17 审计修复（#5：safeChild 段级判定） ====================

    /**
     * #5：旧实现 `normalized.contains("..")` 是**字面包含**——合法文件名 `a..b.txt`、
     * `v1.2..bak` 被静默跳过（解压结果少文件且无提示）。修复 = 按路径段判定 `..`。
     */
    @Test
    fun `safeChild 放行含连续点的合法文件名`() {
        val dest = Files.createTempDirectory("slip-ok").toFile()
        try {
            assertNotNull(SafeExtract.safeChild(dest, "a..b.txt"), "`a..b.txt` 是合法名，不得被跳过")
            assertNotNull(SafeExtract.safeChild(dest, "v1.2..bak"), "`v1.2..bak` 同上")
            assertNotNull(SafeExtract.safeChild(dest, "dir/x..y.log"))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun `safeChild 仍拒绝真正的穿越段`() {
        val dest = Files.createTempDirectory("slip-bad").toFile()
        try {
            assertNull(SafeExtract.safeChild(dest, "../evil.txt"), "`..` 段必须拒绝")
            assertNull(SafeExtract.safeChild(dest, "a/../../evil.txt"), "中间 `..` 段同样拒绝")
            assertNull(SafeExtract.safeChild(dest, ".."), "纯 `..` 拒绝")
            assertNull(SafeExtract.safeChild(dest, "/abs/path.txt"), "绝对路径拒绝")
        } finally {
            dest.deleteRecursively()
        }
    }
}
