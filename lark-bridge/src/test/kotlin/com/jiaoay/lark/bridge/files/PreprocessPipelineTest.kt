// 引用流重设计（2026-09-11）机制 C 单测：PreprocessPipeline（固定三步管道）。
// 覆盖：扩展名作用域 / 三步固定序 / xlog fake seam / CustomCommand argv 与占位符 / 失败降级 / tokenize。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.pipeline.RecordingLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.FileMessageConfig
import com.jiaoay.lark.bridge.config.RuntimeConfig
import com.jiaoay.lark.bridge.config.WorkspaceConfig
import com.jiaoay.lark.bridge.exec.LaunchResult
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreprocessPipelineTest {

    private fun workspace(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "preprocess-$name-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun config(
        fileMessage: FileMessageConfig = FileMessageConfig(),
    ): BridgeConfig = BridgeConfig(
        workspace = WorkspaceConfig(root = ""),
        fileMessage = fileMessage,
    )

    // ── 1) AutoExtract ──

    @Test
    fun `zip 落盘-autoExtract 开启-产物进 extracted 子目录`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("extract")
        val workDir = ws.apply { mkdirs() }
        val zip = makeZip(workDir, "pack.zip", "inner.txt" to "hello")
        val pipeline = PreprocessPipeline({ config() }, BridgeLog.Silent)
        val artifacts = pipeline.process(zip, workDir)
        assertEquals(1, artifacts.size)
        assertTrue(artifacts.single().path.contains("extracted"), "产物应在 extracted 子目录：${artifacts.single().path}")
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `autoExtract 关闭-不解压`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("noextract")
        val zip = makeZip(ws, "pack.zip", "inner.txt" to "hello")
        val pipeline = PreprocessPipeline({ config(FileMessageConfig(autoExtract = false)) }, BridgeLog.Silent)
        val artifacts = pipeline.process(zip, ws)
        assertTrue(artifacts.isEmpty())
        assertFalse(File(ws, PreprocessPipeline.EXTRACTED_DIR).isDirectory)
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `非压缩包-isArchive 不命中-零产物`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("plain")
        val plain = File(ws, "note.log").apply { writeText("x") }
        val pipeline = PreprocessPipeline({ config() }, BridgeLog.Silent)
        assertTrue(pipeline.process(plain, ws).isEmpty())
        ws.deleteRecursively()
        Unit
    }

    // ── 2) XlogDecode（fake seam） ──

    @Test
    fun `xlog 文件-开关开启且私钥在位-产物进 decoded 子目录`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("xlog")
        val xlog = File(ws, "main.xlog").apply { writeBytes(ByteArray(16)) }
        val decoded = mutableListOf<Triple<String, File, File>>()
        val pipeline = PreprocessPipeline(
            configProvider = { config(FileMessageConfig(xlogPrivateKey = "aabb")) },
            log = BridgeLog.Silent,
            xlogDecoder = { key, input, output -> decoded += Triple(key, input, output); output.writeText("decoded") },
        )
        val artifacts = pipeline.process(xlog, ws)
        assertEquals(1, decoded.size, "seam 应被调用一次")
        assertEquals("aabb", decoded.single().first, "私钥透传")
        assertEquals(1, artifacts.size)
        assertTrue(artifacts.single().name == "main.log")
        assertTrue(artifacts.single().parentFile.name == PreprocessPipeline.DECODED_DIR)
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `xlog 私钥为空-跳过并留 warn`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("xlognokey")
        val xlog = File(ws, "main.xlog").apply { writeBytes(ByteArray(4)) }
        val log = RecordingLog()
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(xlogPrivateKey = "")) },
            log,
            xlogDecoder = { _, _, _ -> error("不应被调用") },
        )
        assertTrue(pipeline.process(xlog, ws).isEmpty())
        assertTrue(log.lines.any { it.contains("私钥为空") }, "缺私钥必须可观测：${log.lines}")
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `xlog seam 抛异常-降级不中断`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("xlogfail")
        val xlog = File(ws, "main.alog").apply { writeBytes(ByteArray(4)) }
        val log = RecordingLog()
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(xlogPrivateKey = "k")) },
            log,
            xlogDecoder = { _, _, _ -> throw IllegalStateException("bad key") },
        )
        assertTrue(pipeline.process(xlog, ws).isEmpty(), "解密失败降级：artifacts 空、主文件路径由调用方保底")
        assertTrue(log.lines.any { it.contains("xlog 解密失败") })
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `alog 同样命中 xlog 解密`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("alog")
        val alog = File(ws, "main.alog").apply { writeBytes(ByteArray(4)) }
        val calls = mutableListOf<File>()
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(xlogPrivateKey = "k")) },
            BridgeLog.Silent,
            xlogDecoder = { _, input, output -> calls += input; output.writeText("ok") },
        )
        assertEquals(1, pipeline.process(alog, ws).size)
        ws.deleteRecursively()
        Unit
    }

    // ── 3) CustomCommand（fake runner） ──

    @Test
    fun `customCommand 占位符替换且 argv 拆分正确`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("custom")
        val input = File(ws, "data.xlog").apply { writeBytes(ByteArray(4)) }
        val argvSeen = mutableListOf<List<String>>()
        val timeoutSeen = mutableListOf<Pair<Long, Long>>()
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(preprocessCommand = "tool --key \"a b\" {input} --out {output}")) },
            BridgeLog.Silent,
            runProgram = { argv, workDir, totalMs, idleMs ->
                argvSeen += argv
                timeoutSeen += totalMs to idleMs
                // fake 行为：写出 {output} 文件以模拟命令产出
                val out = argv.last()
                File(out).writeText("done")
                LaunchResult(exitCode = 0, timedOut = false, idleTimedOut = false, killed = false)
            },
        )
        val artifacts = pipeline.process(input, ws)
        val argv = argvSeen.single()
        assertEquals(listOf("tool", "--key", "a b", input.absolutePath, "--out", File(ws, "${PreprocessPipeline.PREPROCESSED_DIR}/data.out").absolutePath), argv,
            "🔴 引号内空白不拆、占位符替换为绝对路径")
        assertEquals(1, artifacts.size, "fake 写出 output ⇒ artifacts 收录")
        assertTrue(artifacts.single().parentFile.name == PreprocessPipeline.PREPROCESSED_DIR)
        // 超时复用 runtime 口径：taskTimeoutSeconds * 1000 / agentIdleTimeoutSeconds * 1000
        val rt = RuntimeConfig()
        assertEquals(rt.taskTimeoutSeconds * 1000L to rt.agentIdleTimeoutSeconds * 1000L, timeoutSeen.single())
        ws.deleteRecursively()
        Unit
    }

    /**
     * 🔴 2026-09-17 审计修复 §8.1.3：扩展名匹配补 `.` 前缀——裸 `endsWith("log")` 会误命中
     * `catalog`/`dialog`（用户配 `log` 的意图恒是 `.log`）；同时 `log` 与 `.log` 两种写法都兼容。
     */
    @Test
    fun `preprocessExtensions 裸扩展名不误命中同后缀文件名`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("scope-dot")
        // catalog 以 "log" 结尾但扩展名是 .catalog 语义上的整名——不得命中 `log` 配置
        val cat = File(ws, "catalog").apply { writeBytes(ByteArray(4)) }
        var called = false
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(preprocessCommand = "t {input}", preprocessExtensions = "log")) },
            BridgeLog.Silent,
            runProgram = { _, _, _, _ -> called = true; LaunchResult(0, false, false, false) },
        )
        assertTrue(pipeline.process(cat, ws).isEmpty())
        assertTrue(!called, "🔴 「catalog」的扩展名段不是 .log，配 log 不得命中（旧裸 endsWith 会误命中）")
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `preprocessExtensions 裸扩展名与带点写法等价命中`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("scope-equiv")
        val log = File(ws, "a.log").apply { writeBytes(ByteArray(4)) }
        var called = false
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(preprocessCommand = "t {input}", preprocessExtensions = "log")) },
            BridgeLog.Silent,
            runProgram = { _, _, _, _ -> called = true; LaunchResult(0, false, false, false) },
        )
        pipeline.process(log, ws)
        assertTrue(called, "🔴 配 `log` 应命中 a.log（补点后等价于 .log）")
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `preprocessExtensions 圈定作用域-扩展名不命中跳过命令`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("scope")
        val input = File(ws, "data.bin").apply { writeBytes(ByteArray(4)) }
        var called = false
        val pipeline = PreprocessPipeline(
            {
                config(
                    FileMessageConfig(
                        preprocessCommand = "t {input}",
                        preprocessExtensions = ".xlog,.alog",
                    ),
                )
            },
            BridgeLog.Silent,
            runProgram = { _, _, _, _ -> called = true; LaunchResult(0, false, false, false) },
        )
        assertTrue(pipeline.process(input, ws).isEmpty())
        assertTrue(!called, "🔴 .bin 不在 .xlog,.alog 作用域内，命令不得被调用")
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `命令退出码非零-warn 继续且 output 存在仍收录`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("exitcode")
        val input = File(ws, "data.txt").apply { writeText("x") }
        val log = RecordingLog()
        val pipeline = PreprocessPipeline(
            { config(FileMessageConfig(preprocessCommand = "t {input} {output}")) },
            log,
            runProgram = { argv, _, _, _ ->
                File(argv.last()).writeText("partial")
                LaunchResult(exitCode = 3, timedOut = false, idleTimedOut = false, killed = false)
            },
        )
        val artifacts = pipeline.process(input, ws)
        assertTrue(log.lines.any { it.contains("退出码 3") }, "非零退出码可观测：${log.lines}")
        assertEquals(1, artifacts.size, "output 存在 ⇒ 仍收录（尽力而为）")
        ws.deleteRecursively()
        Unit
    }

    @Test
    fun `runProgram 未注入-自定义命令跳过并 warn`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("norunner")
        val input = File(ws, "data.txt").apply { writeText("x") }
        val log = RecordingLog()
        val pipeline = PreprocessPipeline({ config(FileMessageConfig(preprocessCommand = "t {input}")) }, log)
        assertTrue(pipeline.process(input, ws).isEmpty())
        assertTrue(log.lines.any { it.contains("未注入 runProgram") })
        ws.deleteRecursively()
        Unit
    }

    // ── 固定序（zip 内含 xlog 时两步可叠加） ──

    @Test
    fun `固定序-三步对同一主文件按序叠加产出`() = kotlinx.coroutines.runBlocking {
        val ws = workspace("order")
        val zip = makeZip(ws, "pack.zip", "inner.xlog" to "raw")
        val pipeline = PreprocessPipeline(
            {
                config(
                    FileMessageConfig(
                        xlogPrivateKey = "k",
                        preprocessCommand = "t {input} {output}",
                    ),
                )
            },
            BridgeLog.Silent,
            xlogDecoder = { _, _, out -> out.writeText("decoded") },
            runProgram = { argv, _, _, _ ->
                File(argv.last()).writeText("cmd")
                LaunchResult(0, false, false, false)
            },
        )
        val artifacts = pipeline.process(zip, ws)
        // 步 1：AutoExtract 作用于主文件 pack.zip ⇒ inner.xlog 解出；
        // 步 2：XlogDecode 只作用于主文件本身（pack.zip 非 .xlog/.alog 结尾 ⇒ 不命中——
        //       压缩包**内容**不递归解密，仅解压产物由 agent 自行读取）；
        // 步 3：CustomCommand preprocessExtensions 空白 = 全部 ⇒ pack.zip 命中。
        assertEquals(2, artifacts.size, "解压产物 1 + custom 产物 1（xlog 不命中主文件）")
        assertTrue(artifacts[0].parentFile.name == PreprocessPipeline.EXTRACTED_DIR)
        assertTrue(artifacts[1].parentFile.name == PreprocessPipeline.PREPROCESSED_DIR)
        ws.deleteRecursively()
        Unit
    }

    // ── tokenize（纯函数） ──

    @Test
    fun `tokenize 尊重单双引号且空串与纯空白安全`() {
        assertEquals(listOf("tool", "--key", "a b"), PreprocessPipeline.tokenize("""tool --key "a b""""))
        assertEquals(listOf("x 'y' z"), PreprocessPipeline.tokenize("\"x 'y' z\""))
        assertEquals(emptyList(), PreprocessPipeline.tokenize(""))
        assertEquals(emptyList(), PreprocessPipeline.tokenize("   "))
        assertEquals(listOf("a", "b"), PreprocessPipeline.tokenize("  a   b  "))
        // 未闭合引号：吞到结尾不抛（引号是分隔语义，不是字面字符）
        assertEquals(listOf("ab"), PreprocessPipeline.tokenize("a'b"))
    }

    // ── 辅助 ──

    private fun makeZip(dir: File, name: String, vararg entries: Pair<String, String>): File {
        val zipFile = File(dir, name)
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            entries.forEach { (entryName, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zipFile
    }
}
