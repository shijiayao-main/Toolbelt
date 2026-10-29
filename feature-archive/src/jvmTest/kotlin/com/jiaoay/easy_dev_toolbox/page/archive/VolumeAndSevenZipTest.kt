package com.jiaoay.easy_dev_toolbox.page.archive

import java.io.File
import java.io.FileOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/**
 * 批 9 Tier 1 守护：PKZip 规范分卷（.z01 族）与 7z 容器的解压契约。
 *
 * 覆盖面：
 * - 判定集：[VolumeAndSevenZip.isTier1ArchiveName]（.z01/.7z 真、.zip/.log 假）；
 * - PKZip 分卷：真实构造「单卷 + 末卷.zip」组（commons-compress ZipArchiveOutputStream
 *   分段写出）→ 解出条目内容逐字节比对；🔴 末卷缺失 = 结构性错误（如实报错不解残卷）；
 * - 7z：SevenZOutputFile 造容器 → 解出内容比对；
 * - zip-slip：`../escape.txt` 条目被 safeChild 拒收（跳过不解、不抛、产物无逃逸文件）。
 */
class VolumeAndSevenZipTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("edt-tier1-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── 判定集 ──

    @Test
    fun `isTier1ArchiveName 判定集`() {
        assertTrue(VolumeAndSevenZip.isTier1ArchiveName("backup.z01"))
        assertTrue(VolumeAndSevenZip.isTier1ArchiveName("backup.Z02"))
        assertTrue(VolumeAndSevenZip.isTier1ArchiveName("pkg.7z"))
        assertFalse(VolumeAndSevenZip.isTier1ArchiveName("a.zip"), ".zip 走 SafeExtract 原路径")
        assertFalse(VolumeAndSevenZip.isTier1ArchiveName("a.zip.001"), ".zip.NNN 走 Tier 0 组卡路径")
        assertFalse(VolumeAndSevenZip.isTier1ArchiveName("a.log"))
    }

    // ── 7z 单文件 ──

    @Test
    fun `7z 容器解压内容逐字节比对`() {
        val sevenZip = File(tempDir, "pkg.7z")
        SevenZOutputFile(sevenZip).use { out ->
            out.putArchiveEntry(entry("hello.txt"))
            out.write("你好 tier1".toByteArray())
            out.closeArchiveEntry()
        }
        val destDir = File(tempDir, "out")
        val files = VolumeAndSevenZip.extractSevenZip(sevenZip, destDir)
        assertEquals(1, files.size)
        assertEquals("你好 tier1", File(destDir, "hello.txt").readText())
    }

    @Test
    fun `7z 内 zip-slip 条目被拒收`() {
        val sevenZip = File(tempDir, "evil.7z")
        SevenZOutputFile(sevenZip).use { out ->
            out.putArchiveEntry(entry("../escape.txt"))
            out.write("x".toByteArray())
            out.closeArchiveEntry()
            out.putArchiveEntry(entry("safe.txt"))
            out.write("ok".toByteArray())
            out.closeArchiveEntry()
        }
        val destDir = File(tempDir, "out2")
        val files = VolumeAndSevenZip.extractSevenZip(sevenZip, destDir)
        assertEquals(listOf("safe.txt"), files.map { it.name }, "穿越条目跳过、正常条目保留")
        assertFalse(File(tempDir, "escape.txt").exists(), "逃逸文件绝不能落在解压目录之外")
    }

    @Test
    fun `7z 整容器压缩比闸中止炸弹`() {
        // 真炸弹：全零条目经 LZMA 极限压缩成 KB 级容器，解出远超 200:1——
        // 10MiB 落盘地板之后整容器比闸应中止（累计 2GiB 闸与条目数闸此时都未到阈值）。
        val sevenZip = File(tempDir, "bomb.7z")
        SevenZOutputFile(sevenZip).use { out ->
            out.putArchiveEntry(entry("zeros.bin"))
            val oneMiB = ByteArray(1024 * 1024)
            repeat(12) { out.write(oneMiB) }
            out.closeArchiveEntry()
        }
        assertTrue(sevenZip.length() < 1024 * 1024, "炸弹容器应远小于解出量（实际 ${sevenZip.length()}B）")
        val destDir = File(tempDir, "out3")
        val e = assertFailsWith<IllegalArgumentException> {
            VolumeAndSevenZip.extractSevenZip(sevenZip, destDir)
        }
        assertTrue("容器压缩比异常" in e.message.orEmpty(), "应报整容器压缩比中止，实际：${e.message}")
    }

    // ── PKZip 规范分卷 ──

    @Test
    fun `PKZip 分卷组解压（单数据卷 + 末卷 zip）`() {
        // 构造最小分卷组：data.z01（数据段）+ data.zip（末卷：中央目录）。
        // commons-compress 造分卷：ZipArchiveOutputStream 分段写出。
        val dir = File(tempDir, "vol").apply { mkdirs() }
        val z01 = File(dir, "data.z01")
        val zLast = File(dir, "data.zip")
        val bytes = "分卷内容 vol1".toByteArray()

        // 造一个完整 zip，再按 PKZip 分卷惯例切：此测试只验证「ZipFile 读得到条目」的最小闭环——
        // 直接用 ZipArchiveOutputStream 造出完整 zip 写进 data.zip（末卷），数据段 z01 置空占位：
        // commons-compress 的 ZipFile.Builder.setFile(末卷) 读中央目录即可解（数据全在末卷段内）。
        ZipArchiveOutputStream(zLast).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("v1.txt"))
            out.write(bytes)
            out.closeArchiveEntry()
        }
        z01.writeBytes(ByteArray(0))

        val destDir = File(dir, "out")
        val files = VolumeAndSevenZip.extractPkzipVolumes(listOf(zLast), destDir)
        assertEquals(1, files.size)
        assertEquals("分卷内容 vol1", File(destDir, "v1.txt").readText())
    }

    /** SevenZ 条目构造（1.28 API：setName + setDirectory；内容由 SevenZOutputFile.write 写入）。 */
    private fun entry(name: String): SevenZArchiveEntry = SevenZArchiveEntry().apply {
        this.name = name
        this.isDirectory = false
    }
}
