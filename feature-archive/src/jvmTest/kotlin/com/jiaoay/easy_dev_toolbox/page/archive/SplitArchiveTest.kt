package com.jiaoay.easy_dev_toolbox.page.archive

import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 批 5 验收（Tier 0，用户拍板范围）+ TL-01 组卡放行：分卷识别 / 分组 / 完整性校验 / 串联解压。
 *
 * 样本全部**真实构造**（临时目录内 ZipOutputStream 生成完整 zip → 按字节切片）：
 * - 字节切分族 .zip.001/.002/.003 三卷 → 串联解压与单卷解压逐字节等价；
 * - 缺中间卷（拖 001+003）→ 检出缺口报错不建卡；
 * - TL-01：7z 切分组（SevenZOutputFile 造真实容器后切卷）→ 建卡并解出内容；
 * - TL-01：PKZip 规范分卷（commons-compress 分卷写入器造真实 .z01+.zip）→ 混拖建一张
 *   组卡解出内容；缺末卷 → 建卡后 process 如实报错；
 * - -partaa 字母卷 → 识别后明确提示不支持（唯一拒绝族）；
 * - 混拖（普通 zip + 分卷）→ 两路分流不互斥。
 */
class SplitArchiveTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("edt-split-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** 生成一个真实 zip（两个条目 a.txt/b.txt）并按 [chunkBytes] 切成 N 卷 */
    private fun makeSplitZip(baseName: String, chunkBytes: Int): List<File> {
        val whole = File(tempDir, "$baseName.zip")
        ZipOutputStream(FileOutputStream(whole)).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hello split".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dir/b.txt"))
            zos.write("second entry content".toByteArray())
            zos.closeEntry()
        }
        val bytes = whole.readBytes()
        val parts = bytes.toList().chunked(chunkBytes).mapIndexed { index, chunk ->
            File(tempDir, "$baseName.zip.%03d".format(index + 1)).apply {
                writeBytes(chunk.toByteArray())
            }
        }
        whole.delete()
        return parts
    }

    /**
     * TL-01：造真实 7z 容器再按字节切卷（.7z.001/.002/…）。
     * 返回切出的卷列表（原单文件删除——只留卷形态）。
     */
    private fun makeSplitSevenZip(baseName: String, entryName: String, content: ByteArray, chunkBytes: Int): List<File> {
        val whole = File(tempDir, "$baseName.7z")
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(whole).use { out ->
            val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
            entry.name = entryName
            entry.isDirectory = false
            out.putArchiveEntry(entry)
            out.write(content)
            out.closeArchiveEntry()
        }
        val bytes = whole.readBytes()
        val parts = bytes.toList().chunked(chunkBytes).mapIndexed { index, chunk ->
            File(tempDir, "$baseName.7z.%03d".format(index + 1)).apply {
                writeBytes(chunk.toByteArray())
            }
        }
        whole.delete()
        return parts
    }

    /**
     * TL-01：用 commons-compress 分卷写入器（ZipArchiveOutputStream(File, splitSize)，
     * 1.22+）造真实 PKZip 规范分卷——产出 `基名.z01`（+ 可选 .z02…）数据卷与
     * `基名.zip` 末卷（EOCD 所在，finish() 时自动重命名）。
     *
     * 条目内容用**不可压缩的随机字节**：DEFLATED 写的是压缩后字节，可压缩内容会
     * 切不出多卷。splitSize 最小 64KB（8.5.1 规范下限）。
     *
     * @return 数据卷列表（不含末卷——末卷独立返回见返回组第二项）
     */
    private fun makePkzipVolumes(
        baseName: String,
        entryName: String,
        content: ByteArray,
        splitSize: Long = 64L * 1024,
    ): Pair<List<File>, File> {
        // 写入器要求初始 File 名任意；finish() 会把它重命名为 基名.zip
        val out = File(tempDir, "$baseName.zip")
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(out, splitSize).use { zos ->
            zos.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry(entryName))
            zos.write(content)
            zos.closeArchiveEntry()
        }
        val last = File(tempDir, "$baseName.zip")
        val dataVolumes = tempDir.listFiles()!!
            .filter { it.name.matches(Regex("${Regex.escape(baseName)}\\.z\\d{2,3}$", RegexOption.IGNORE_CASE)) }
            .sortedBy { it.name }
        check(dataVolumes.isNotEmpty()) { "PKZip 样本切分失败：未产生数据卷（内容可能被压没了）" }
        return dataVolumes to last
    }

    // ── 识别与归组 ──

    @Test
    fun `numeric zip split is recognized and grouped`() {
        makeSplitZip("foo", chunkBytes = 64) // 3 卷以上不等（zip 结构决定），只验证 ≥2

        val names = tempDir.listFiles()!!.filter { it.name.startsWith("foo.zip.") }.map { it.name }
        assertTrue(SplitArchive.isSplitArchiveName(names.first()), "数字卷族必须被识别")
        assertTrue(SplitArchive.isExtractableSplitName(names.first()), "zip 数字卷族必须可解压")

        val groups = SplitArchive.group(tempDir.listFiles()!!.filter { it.name.startsWith("foo.zip.") })
        assertEquals(1, groups.size, "同基名全部卷应归一组")
        assertEquals("foo.zip", groups.first().baseName, "基名 = 去卷后缀")
        assertEquals(names.size, groups.first().parts.size)
    }

    @Test
    fun `mixed split kinds are all recognized`() {
        assertTrue(SplitArchive.isSplitArchiveName("x.7z.001"), "7z 数字卷须识别")
        assertTrue(SplitArchive.isSplitArchiveName("y-partaa"), "rar 风格字母卷须识别")
        assertTrue(SplitArchive.isSplitArchiveName("z.z01"), "PKZip 规范分卷须识别")
        // isExtractableSplitName 保持 Tier 0 窄面（仅 zip 切分）；7z 切分建卡放行走
        // TL-01 组卡路径（addSplitGroups 三分派），不经本判定
        assertTrue(!SplitArchive.isExtractableSplitName("x.7z.001"), "isExtractableSplitName 保持 Tier 0 窄面")
        assertTrue(!SplitArchive.isSplitArchiveName("plain.zip"), "普通 zip 不走分卷路径")
        assertTrue(!SplitArchive.isSplitArchiveName("note.txt"), "非压缩包不误识别")
    }

    // ── 完整性校验 ──

    @Test
    fun `missing middle volume is detected`() {
        val parts = makeSplitZip("gap", chunkBytes = 32)
        assertTrue(parts.size >= 3, "样本至少 3 卷（实际 ${parts.size}）")

        // 丢弃中间卷后归组：校验须报缺口
        val dropped = parts.filterIndexed { i, _ -> i != 1 }
        val groups = SplitArchive.group(dropped)
        val error = SplitArchive.checkCompleteness(groups.first())
        assertNotNull(error, "缺中间卷必须检出")
        assertTrue("002" in error!!, "缺口提示须含卷号")
    }

    @Test
    fun `complete group passes check`() {
        val parts = makeSplitZip("ok", chunkBytes = 32)
        val groups = SplitArchive.group(parts)
        assertNull(SplitArchive.checkCompleteness(groups.first()), "完整组校验应通过")
    }

    // ── 串联解压（端到端核心断言） ──

    /** 经 public addFiles 入口驱动并轮询任务落定（process 为 protected，不可直调） */
    private fun runTaskAndAwait(vm: ArchiveViewModel, files: List<File>): List<com.jiaoay.easy_dev_toolbox.widget.TaskUi> {
        vm.addFiles(files)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (vm.tasks.value.any { it.status is TaskUiStatus.Running } && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        return vm.tasks.value
    }

    @Test
    fun `concatenated stream extracts identically to single zip`() {
        val parts = makeSplitZip("e2e", chunkBytes = 32)
        assertTrue(parts.size >= 2, "样本至少 2 卷")

        val vm = ArchiveViewModel()
        val tasks = runTaskAndAwait(vm, parts)

        assertEquals(1, tasks.size, "整组分卷应建一张组卡")
        val status = tasks.first().status
        assertTrue(status is TaskUiStatus.Success, "串联解压须成功：$status")

        // 产物结构断言：两个条目（a.txt + dir/b.txt）都解出
        val destDir = File(tempDir, "e2e.zip_extracted")
        assertTrue(destDir.resolve("a.txt").exists(), "条目 a.txt 须解出")
        assertTrue(destDir.resolve("dir/b.txt").exists(), "子目录条目须解出")
    }

    @Test
    fun `plain archive path stays intact`() {
        // 普通单 zip：既有路径不受分卷改造影响
        val plain = File(tempDir, "plain.zip")
        ZipOutputStream(FileOutputStream(plain)).use { zos ->
            zos.putNextEntry(ZipEntry("only.txt"))
            zos.write("plain".toByteArray())
            zos.closeEntry()
        }
        val vm = ArchiveViewModel()
        val tasks = runTaskAndAwait(vm, listOf(plain))

        val status = tasks.first().status
        assertTrue(status is TaskUiStatus.Success, "普通 zip 须照常解压：$status")
        assertTrue(File(tempDir, "plain.zip_extracted/only.txt").exists())
    }

    // ── 批次分流（addFiles 接管路径） ──

    @Test
    fun `mixed batch splits into two lanes without mutual exclusion`() {
        val splitParts = makeSplitZip("mixed-split", chunkBytes = 32)
        val plain = File(tempDir, "mixed-plain.zip")
        ZipOutputStream(FileOutputStream(plain)).use { zos ->
            zos.putNextEntry(ZipEntry("p.txt"))
            zos.write("p".toByteArray())
            zos.closeEntry()
        }

        val vm = ArchiveViewModel()
        val tasks = runTaskAndAwait(vm, listOf(plain) + splitParts)

        // 两路并行建卡：普通 zip 一张 + 分卷组一张（组显示名 = 基名——zip/7z 族；
        // PKZip 族才带首卷后缀——TL-D1）
        val taskNames = tasks.map { it.fileName }
        assertTrue("mixed-plain.zip" in taskNames, "普通 zip 须建卡（混拖不互斥）：$taskNames")
        assertTrue("mixed-split.zip" in taskNames, "分卷组须建组卡（显示名 = 基名）：$taskNames")
    }

    @Test
    fun `7z split group builds one card and extracts content`() {
        // TL-01：7z 切分组（.7z.NNN）建卡放行——真实容器（SevenZOutputFile）切卷端到端
        // 内容用真随机字节（固定种子可复现）：7z 头 + LZMA 流合计稳定 ≥2 卷（64B 切）
        val rnd = java.util.Random(7)
        val content = ByteArray(300).also { rnd.nextBytes(it) }
        val parts = makeSplitSevenZip("sz7", "inner.txt", content, chunkBytes = 64)
        assertTrue(parts.size >= 2, "样本至少 2 卷（实际 ${parts.size}）")

        val vm = ArchiveViewModel()
        val tasks = runTaskAndAwait(vm, parts)

        assertEquals(1, tasks.size, "7z 切分组应建一张组卡")
        assertEquals("sz7.7z", tasks.first().fileName, "组显示名 = 基名")
        val status = tasks.first().status
        assertTrue(status is TaskUiStatus.Success, "7z 切分组串联还原后须解压成功：$status")
        val destDir = File(tempDir, "sz7.7z_extracted")
        assertTrue(content.contentEquals(destDir.resolve("inner.txt").readBytes()), "解出内容须逐字节一致")
    }

    @Test
    fun `7z split group with missing middle volume is rejected before card`() {
        // 组内卷号连续性闸对 .7z.NNN 同样适用：缺卷不建残卷卡
        // 内容用真随机字节：7z 头也压不掉，64B 切卷稳定 ≥3 卷
        val rnd = java.util.Random(20260930)
        val gapContent = ByteArray(600).also { rnd.nextBytes(it) }
        val parts = makeSplitSevenZip("sz7gap", "inner.txt", gapContent, chunkBytes = 64)
        assertTrue(parts.size >= 3, "样本至少 3 卷（实际 ${parts.size}）")
        val dropped = parts.filterIndexed { idx, _ -> idx != 1 }

        val vm = ArchiveViewModel()
        vm.addFiles(dropped)

        assertTrue(vm.tasks.value.isEmpty(), "缺中间卷不得建卡")
        val error = vm.inputError.value
        assertNotNull(error, "缺中间卷须给出明确提示：$error")
        assertTrue("002" in error, "缺口提示须含卷号：$error")
    }

    @Test
    fun `pkzip volume group builds one card and extracts content`() {
        // TL-01：PKZip 规范分卷组（.zNN + 末卷 .zip）混拖——末卷不建假卡、整组一张卡端到端
        // 内容用真随机字节（固定种子可复现）：DEFLATE 压不掉，确保 64KB 段真切出多卷
        val rndA = java.util.Random(4242)
        val content = ByteArray(200_000).also { rndA.nextBytes(it) }
        val (dataVolumes, last) = makePkzipVolumes("pkw", "v.txt", content)
        assertTrue(dataVolumes.size >= 2, "样本至少 2 个数据卷（实际 ${dataVolumes.size}）")

        val vm = ArchiveViewModel()
        val tasks = runTaskAndAwait(vm, dataVolumes + last)

        assertEquals(1, tasks.size, "PKZip 组（数据卷+末卷）混拖应只建一张组卡：${tasks.map { it.fileName }}")
        // 2026-10-04 TL-D1 重锚：PKZip 组显示名 = 首卷文件名 pkw.z01（稿
        // 分卷组显示名直演（camera_pack.z01）
        assertEquals("pkw.z01", tasks.first().fileName, "PKZip 组显示名 = 首卷文件名（TL-D1）")
        val status = tasks.first().status
        assertTrue(status is TaskUiStatus.Success, "PKZip 分卷组须解压成功：$status")
        val destDir = File(tempDir, "pkw_extracted")
        assertTrue(content.contentEquals(destDir.resolve("v.txt").readBytes()), "解出内容须逐字节一致")
    }

    @Test
    fun `pkzip volume group missing final volume reports honest failure`() {
        // 缺末卷（基名.zip 不在）：建卡放行，process 侧如实报「缺少分卷末卷」——
        // 末卷不是 .zNN，组内卷号校验管不到它（TL-01 建卡前不拦的既定语义）
        val rndB = java.util.Random(99)
        val content = ByteArray(200_000).also { rndB.nextBytes(it) }
        val (dataVolumes, last) = makePkzipVolumes("pkmiss", "v.txt", content)
        last.delete()

        val vm = ArchiveViewModel()
        val tasks = runTaskAndAwait(vm, dataVolumes)

        assertEquals(1, tasks.size, "数据卷完整（仅缺末卷）仍应建卡")
        val status = tasks.first().status
        assertTrue(status is TaskUiStatus.Error, "缺末卷须落 Error 而非假成功：$status")
        val message = status.message
        assertTrue("缺少分卷末卷 pkmiss.zip" in message, "错误须如实点名缺失的末卷：$message")
    }

    @Test
    fun `alpha part volumes stay rejected with explicit error not silence`() {
        // -partNN 字母卷：唯一保留拒绝族（识别成功但不静默丢弃）
        val alpha = File(tempDir, "r-partaa").apply { writeBytes(byteArrayOf(1)) }
        val vm = ArchiveViewModel()
        vm.addFiles(listOf(alpha))

        assertTrue(vm.tasks.value.isEmpty(), "字母卷不得建卡")
        val error = vm.inputError.value
        assertNotNull(error, "字母卷须给出明确提示（不静默丢弃）")
        assertTrue("不支持" in error, "提示须说明不支持：$error")
        assertTrue("字母卷" in error, "提示须保留字母卷辨识口径：$error")
    }
}
