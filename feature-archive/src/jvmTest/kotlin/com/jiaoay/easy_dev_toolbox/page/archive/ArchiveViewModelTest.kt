package com.jiaoay.easy_dev_toolbox.page.archive

import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ArchiveViewModel] 的编排层测试（真实临时目录，无 GUI、无 LLM、无网络）。
 *
 * 走公开入口 [ArchiveViewModel.addFiles] 并观察 `tasks`，不直调 protected 的
 * `validate`/`process`（那是基类内部契约）。解包引擎本身与 zip-slip 三重拒绝已由
 * `log-tools` 的 `SafeExtractTest`（565 行）充分覆盖，此处只验证本 VM 的编排正确性：
 * 入口拒收、产物路径约定、失败如实上报、异常不逃逸。
 *
 * 🔴 测试闭包用 runBlocking 等待任务落态：`addFiles` 内部 `viewModelScope.launch`
 * 是异步的，直接断言会读到 Running 中间态。
 */
class ArchiveViewModelTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("archive-vm-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun zipFile(name: String, entries: Map<String, String>): File {
        val file = File(tmpDir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (entryName, content) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    /** 驱动一次 addFiles 并等到任务全部落终态（Success/Error），返回 VM */
    private fun runTasks(vararg files: File): ArchiveViewModel {
        val vm = ArchiveViewModel()
        runBlocking {
            vm.addFiles(files.toList())
            // 任务在 viewModelScope 内异步执行；轮询到无 Running 为止
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                vm.tasks.value.any { it.status is TaskUiStatus.Running }
            ) {
                kotlinx.coroutines.delay(20)
            }
        }
        return vm
    }

    // ── 入口拒收 ──

    @Test
    fun `non archive is rejected with message and no task created`() {
        val txt = File(tmpDir, "a.txt").apply { writeText("hello") }

        val vm = runTasks(txt)

        assertTrue(vm.tasks.value.isEmpty(), "非压缩包不应建卡")
        val error = vm.inputError.value
        assertNotNull(error, "应给出拒绝提示")
        assertTrue(error.contains("a.txt"), "提示须点出被拒文件名：$error")
    }

    /**
     * TL-01：Tier 1 判定面（[VolumeAndSevenZip.isTier1ArchiveName] 与分卷族）过
     * [ArchiveViewModel.validate] 二道闸——不落「不是支持的压缩格式」拒绝。
     * `.z01` / `.7z.001` 走分卷组卡路径、`.7z` 走普通建卡路径，三张卡都建。
     */
    @Test
    fun `tier1 archive names pass validation and build cards`() {
        val z01 = File(tmpDir, "t1a.z01").apply { writeBytes(byteArrayOf(1)) }
        val szSplit = File(tmpDir, "t1b.7z.001").apply { writeBytes(byteArrayOf(1)) }
        val sevenZip = File(tmpDir, "t1c.7z").apply { writeBytes(byteArrayOf(1)) }

        val vm = runTasks(z01, szSplit, sevenZip)

        assertEquals(null, vm.inputError.value, "Tier 1 名不得被 validate 拒收")
        assertEquals(3, vm.tasks.value.size, "三文件各建一卡（z01 组卡 / 7z 切分组卡 / 7z 普通卡）")
        val names = vm.tasks.value.map { it.fileName }
        // 2026-10-04 TL-D1 重锚：PKZip 组卡名 = 首卷文件名（t1a.z01——稿
        // 分卷组显示名 = 首卷基名 + 「.zNN 起」标注（camera_pack.z01 直演）；7z 切分组/普通卡仍 = 基名/文件名
        assertTrue(
            "t1a.z01" in names && "t1b.7z" in names && "t1c.7z" in names,
            "卡名应为组基名/文件名（PKZip 组=首卷文件名 TL-D1）：$names",
        )
        // 内容是占位字节：解压失败是预期的（本用例只钉 validate 放行面）
        vm.tasks.value.forEach { task ->
            assertTrue(
                task.status is TaskUiStatus.Error,
                "占位内容解压须落 Error（不卡 Running 不抛异常）：${task.fileName} -> ${task.status}",
            )
        }
    }

    /** TL-01：单文件 .7z 端到端（既有放行路径回归锚——真实容器解出内容）。 */
    @Test
    fun `single 7z file extracts end to end`() {
        val sevenZip = File(tmpDir, "real.7z")
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(sevenZip).use { out ->
            val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
            entry.name = "note.txt"
            entry.isDirectory = false
            out.putArchiveEntry(entry)
            out.write("7z 单文件 TL-01".toByteArray())
            out.closeArchiveEntry()
        }

        val vm = runTasks(sevenZip)
        val task = vm.tasks.value.single()
        val status = assertIs<TaskUiStatus.Success>(task.status, "单文件 7z 应解压成功：$task")
        assertTrue(File(status.outputPath).resolve("note.txt").isFile, "条目须解出")
    }

    @Test
    fun `supported formats pass validation case insensitively`() {
        val archives = listOf("a.zip", "b.tar", "c.tar.gz", "d.tgz", "e.gz", "F.ZIP")
            .map { name ->
                File(tmpDir, name).let { f ->
                    if (name.lowercase().endsWith(".gz") && !name.lowercase().endsWith(".tar.gz")) {
                        GZIPOutputStream(f.outputStream()).use { it.write("x".toByteArray()) }
                    } else {
                        f.writeText("x")
                    }
                    f
                }
            }

        val vm = runTasks(*archives.toTypedArray())

        assertEquals(null, vm.inputError.value, "支持格式不应报校验错误")
        assertEquals(archives.size, vm.tasks.value.size, "每个文件应各建一卡")
    }

    // ── 成功路径 ──

    @Test
    fun `zip extracts to sibling dir with suffix`() {
        val archive = zipFile("data.zip", mapOf("inner/readme.txt" to "hi"))

        val vm = runTasks(archive)
        val task = vm.tasks.value.single()
        val status = assertIs<TaskUiStatus.Success>(task.status, "应成功：$task")

        val expected = File(tmpDir, "data.zip${ArchiveViewModel.EXTRACTED_SUFFIX}")
        assertEquals(expected.absolutePath, status.outputPath)
        assertTrue(File(expected, "inner/readme.txt").isFile, "条目应被真正解出")
        assertEquals(tmpDir.absolutePath, expected.parentFile?.absolutePath, "产物应在源文件同级")
    }

    @Test
    fun `gzip single file extracts`() {
        val gz = File(tmpDir, "log.txt.gz")
        GZIPOutputStream(gz.outputStream()).use { it.write("payload".toByteArray()) }

        val vm = runTasks(gz)
        val status = assertIs<TaskUiStatus.Success>(vm.tasks.value.single().status)

        assertTrue(File(status.outputPath).isDirectory, "产物路径应为解压目录：${status.outputPath}")
    }

    @Test
    fun `zip slip entry does not escape but legal entries still extract`() {
        val archive = zipFile(
            "evil.zip",
            mapOf("../escaped.txt" to "should not escape", "ok.txt" to "fine"),
        )

        val vm = runTasks(archive)
        assertIs<TaskUiStatus.Success>(vm.tasks.value.single().status)

        assertFalse(File(tmpDir, "escaped.txt").exists(), "穿越条目不得逃逸出目标目录")
        assertTrue(
            File(tmpDir, "evil.zip${ArchiveViewModel.EXTRACTED_SUFFIX}/ok.txt").isFile,
            "合法条目仍应解出",
        )
    }

    // ── 失败如实上报（不卡 Running、不抛异常） ──

    @Test
    fun `corrupt archive reports error instead of hanging`() {
        val corrupt = File(tmpDir, "broken.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val vm = runTasks(corrupt)
        val task = vm.tasks.value.single()

        assertIs<TaskUiStatus.Error>(task.status, "损坏压缩包应落 Error 而非卡在 Running")
        assertFalse(task.status is TaskUiStatus.Running)
    }

    @Test
    fun `mixed batch keeps per file outcomes independent`() {
        val good = zipFile("good.zip", mapOf("a.txt" to "ok"))
        val bad = File(tmpDir, "bad.zip").apply { writeBytes(byteArrayOf(9, 9, 9)) }

        val vm = runTasks(good, bad)

        assertEquals(2, vm.tasks.value.size)
        val byName = vm.tasks.value.associateBy { it.fileName }
        assertIs<TaskUiStatus.Success>(byName.getValue("good.zip").status)
        assertIs<TaskUiStatus.Error>(byName.getValue("bad.zip").status)
    }

    // ── TL-10 · inputError 聚合（混拖多路错误并列，不再后组覆写前组） ──

    /**
     * TL-10 验收主用例：混拖「普通 zip + PKZip 组（缺中卷）+ -part 字母卷组」——
     * 两条组错并列展示（`\n` 分隔），普通 zip 照常建卡成功（错误不阻断另一路）；
     * 聚合后单条文案与单组场景**逐字等价**（acceptance 回归项：单组失败文案不变）。
     */
    @Test
    fun `mixed batch aggregates group errors alongside plain zip card`() {
        val plain = zipFile("agg-plain.zip", mapOf("p.txt" to "p"))
        val z01 = File(tmpDir, "agg.z01").apply { writeBytes(byteArrayOf(1)) }
        val z03 = File(tmpDir, "agg.z03").apply { writeBytes(byteArrayOf(3)) }
        val alpha = File(tmpDir, "aggalpha-partaa").apply { writeBytes(byteArrayOf(1)) }

        // 等价基准：单组字母卷的拒绝文案（聚合不得改变单条文案本身）
        val solo = runTasks(alpha)
        val soloError = solo.inputError.value
        assertNotNull(soloError, "单组字母卷须有拒绝文案")

        val vm = runTasks(plain, z01, z03, alpha)

        // 拒绝组不建卡；普通 zip 一张卡且成功（错误与建卡并存）
        assertEquals(1, vm.tasks.value.size, "混拖只应有普通 zip 一张卡：${vm.tasks.value.map { it.fileName }}")
        assertIs<TaskUiStatus.Success>(vm.tasks.value.single().status, "普通 zip 须照常解压成功（错误不阻断另一路）")

        // 两条组错并列：PKZip 缺中卷缺口 + 字母卷不支持
        val error = vm.inputError.value
        assertNotNull(error, "混拖两路组错须聚合提示")
        val lines = error.split("\n")
        assertEquals(2, lines.size, "两条组错须并列（不再覆写）：$error")
        assertTrue(lines.any { "缺少分卷" in it && ".z02" in it }, "PKZip 缺中卷原因须在场：$error")
        assertTrue(lines.any { it == soloError }, "字母卷文案须与单组场景逐字等价：$error vs $soloError")
    }

    /**
     * TL-10 语义增强回归：regulars 校验错误与分卷组错误并存时，前者不再被组错误
     * 覆写（改造前 super 先置 regulars 错、addSplitGroups 后置组错恒覆写前者）。
     */
    @Test
    fun `regulars validation error and split group error are both reported`() {
        val txt = File(tmpDir, "note.txt").apply { writeText("hello") }
        val alpha = File(tmpDir, "r-partaa").apply { writeBytes(byteArrayOf(1)) }

        val vm = runTasks(txt, alpha)

        assertTrue(vm.tasks.value.isEmpty(), "两路全拒，不建卡")
        val error = vm.inputError.value
        assertNotNull(error, "两路错误须聚合提示")
        val lines = error.split("\n")
        assertEquals(2, lines.size, "两路错误须并列：$error")
        assertTrue(lines.any { "note.txt" in it && "不是支持的压缩格式" in it }, "regulars 拒绝原因须在场：$error")
        assertTrue(lines.any { "字母卷" in it }, "分卷组拒绝原因须在场：$error")
    }

    /**
     * TL-10 截断口径：错误超过 [ArchiveViewModel.INPUT_ERROR_MAX_LINES] 条时取
     * 前 N 条并列 + 「…等 N 组」计数尾行（卡面 proposal 的超限口径）。
     */
    @Test
    fun `input error caps at max lines with count suffix`() {
        fun gapVolumes(base: String, ext: String): List<File> = listOf(
            File(tmpDir, "$base$ext.001").apply { writeBytes(byteArrayOf(1)) },
            File(tmpDir, "$base$ext.003").apply { writeBytes(byteArrayOf(3)) },
        )
        // 4 组错误：zip 数字卷缺中卷 ×2 + 7z 数字卷缺中卷 ×1 + 字母卷 ×1
        val fourGroups = gapVolumes("capA", ".zip") + gapVolumes("capB", ".zip") +
            gapVolumes("capC", ".7z") +
            listOf(File(tmpDir, "cap-partaa").apply { writeBytes(byteArrayOf(1)) })

        val vm = ArchiveViewModel()
        vm.addFiles(fourGroups)

        assertTrue(vm.tasks.value.isEmpty(), "四组全拒，不建卡")
        val error = vm.inputError.value
        assertNotNull(error, "四组错误须聚合提示")
        val lines = error.split("\n")
        assertEquals(ArchiveViewModel.INPUT_ERROR_MAX_LINES + 1, lines.size, "取前 N 条 + 计数尾行：$error")
        assertEquals("…等 4 组", lines.last(), "尾行须为总组数计数：$error")
        assertTrue(
            lines.dropLast(1).all { "缺少分卷" in it },
            "前 N 行须为被截前各组的原因（字母卷第 4 条被截）：$error",
        )
    }
}
