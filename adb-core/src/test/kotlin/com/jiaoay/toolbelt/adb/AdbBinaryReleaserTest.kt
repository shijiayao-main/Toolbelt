package com.jiaoay.toolbelt.adb

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 释放器四条契约：幂等释放、哈希校验、版本升级清理自家旧产物、认不出的文件 Conflict。
 *
 * 全部用内存资源注入——测试不依赖构建期下载的十余 MB 二进制，也不碰真实用户数据目录。
 */
class AdbBinaryReleaserTest {

    private lateinit var tmp: File

    /** 假二进制内容（Linux 侧可执行位检查用真文件，内容无所谓） */
    private val adbBytes = "fake-adb-binary".toByteArray()
    private val noticeBytes = "fake-notice".toByteArray()

    private var loadCount = 0

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("adb-releaser-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(
        version: String = "37.0.1",
        platform: AdbPlatform = AdbPlatform.LINUX,
    ): String {
        val files = if (platform == AdbPlatform.WINDOWS) {
            listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt")
        } else {
            listOf("adb", "NOTICE.txt")
        }
        return buildString {
            appendLine("version=$version")
            appendLine("platform=${platform.resourceKey}")
            appendLine("files=${files.joinToString(",")}")
            appendLine("sha256.adb=${sha256(adbBytes)}")
            appendLine("sha256.adb.exe=${sha256(adbBytes)}")
            appendLine("sha256.AdbWinApi.dll=${sha256("dll".toByteArray())}")
            appendLine("sha256.AdbWinUsbApi.dll=${sha256("dll".toByteArray())}")
            appendLine("sha256.NOTICE.txt=${sha256(noticeBytes)}")
        }
    }

    private fun releaser(
        platform: AdbPlatform = AdbPlatform.LINUX,
        manifestVersion: String = "37.0.1",
    ): AdbBinaryReleaser = AdbBinaryReleaser(
        adbRootDir = File(tmp, "adb"),
        platform = platform,
        resourceLoader = { path ->
            loadCount++
            val name = path.substringAfterLast('/')
            when (name) {
                "manifest.properties" -> manifest(manifestVersion, platform).toByteArray()
                "adb", "adb.exe" -> adbBytes
                "NOTICE.txt" -> noticeBytes
                "AdbWinApi.dll", "AdbWinUsbApi.dll" -> "dll".toByteArray()
                else -> null
            }
        },
    )

    @Test
    fun `first release writes files and reports released`() {
        val outcome = releaser().ensureReleased()
        val released = assertIs<AdbReleaseOutcome.Released>(outcome)
        assertTrue(released.executable.isFile)
        assertEquals("adb", released.executable.name)
        assertTrue(File(tmp, "adb/v37.0.1/NOTICE.txt").isFile)
        // 释放印记：下次启动据此确认是自家产物
        assertTrue(File(tmp, "adb/v37.0.1/manifest.properties").isFile)
    }

    @Test
    fun `unix executable bit is set on release`() {
        val outcome = releaser().ensureReleased()
        val executable = assertIs<AdbReleaseOutcome.Released>(outcome).executable
        // jar 资源不携带权限，可执行位只能在落盘时补——漏了它 adb 根本起不来
        assertTrue(executable.canExecute(), "释放后的 adb 必须带可执行位")
    }

    @Test
    fun `second call short-circuits without re-reading resources`() {
        val releaser = releaser()
        assertIs<AdbReleaseOutcome.Released>(releaser.ensureReleased())
        val loadsAfterFirst = loadCount
        val outcome = releaser.ensureReleased()
        assertIs<AdbReleaseOutcome.AlreadyPresent>(outcome)
        // 解析端每 5 秒轮询一次、每条命令前再读一次：重复调用不得重算哈希与重读资源
        assertEquals(loadsAfterFirst, loadCount, "首验通过后不得再读资源")
    }

    @Test
    fun `already present validates content on first call`() {
        val dir = File(tmp, "adb/v37.0.1")
        dir.mkdirs()
        File(dir, "adb").writeBytes(adbBytes)
        File(dir, "NOTICE.txt").writeBytes(noticeBytes)
        val outcome = releaser().ensureReleased()
        assertIs<AdbReleaseOutcome.AlreadyPresent>(outcome)
    }

    @Test
    fun `tampered file is reported as failed not silently accepted`() {
        val dir = File(tmp, "adb/v37.0.1")
        dir.mkdirs()
        File(dir, "adb").writeBytes("tampered".toByteArray())
        File(dir, "NOTICE.txt").writeBytes(noticeBytes)
        val outcome = releaser().ensureReleased()
        val failed = assertIs<AdbReleaseOutcome.Failed>(outcome)
        assertTrue(failed.reason.contains("校验不符"), "实际：${failed.reason}")
    }

    @Test
    fun `upgrade cleans own stale version dir instead of conflicting`() {
        // 旧版本目录内容是本应用上一版释放的产物（带释放印记）
        val stale = File(tmp, "adb/v36.0.0")
        stale.mkdirs()
        File(stale, "adb").writeBytes(adbBytes)
        File(stale, "NOTICE.txt").writeBytes(noticeBytes)
        File(stale, "manifest.properties").writeText(manifest("36.0.0"))

        val outcome = releaser().ensureReleased()
        // 应用升级后内置 adb 必须自动可用；把自家旧产物当冲突会让设备功能静默全灭
        assertIs<AdbReleaseOutcome.Released>(outcome)
        assertFalse(stale.exists(), "自家旧版本目录应被清理")
        assertTrue(File(tmp, "adb/v37.0.1/adb").isFile)
    }

    @Test
    fun `unrecognized directory under adb root yields conflict`() {
        val foreign = File(tmp, "adb/my-custom-adb")
        foreign.mkdirs()
        File(foreign, "adb").writeBytes("user-owned".toByteArray())

        val outcome = releaser().ensureReleased()
        val conflict = assertIs<AdbReleaseOutcome.Conflict>(outcome)
        assertTrue(conflict.hint.isNotEmpty(), "冲突必须带人话处置建议")
        assertFalse(File(tmp, "adb/v37.0.1").exists(), "冲突时不得写入")
    }

    @Test
    fun `missing resource reports failed with actionable reason`() {
        val brokenReleaser = AdbBinaryReleaser(
            adbRootDir = File(tmp, "adb"),
            platform = AdbPlatform.LINUX,
            resourceLoader = { null },
        )
        val failed = assertIs<AdbReleaseOutcome.Failed>(brokenReleaser.ensureReleased())
        assertTrue(failed.reason.contains("manifest.properties"), "实际：${failed.reason}")
    }

    @Test
    fun `unknown platform fails without throwing`() {
        val failed = assertIs<AdbReleaseOutcome.Failed>(
            AdbBinaryReleaser(File(tmp, "adb"), platform = null).ensureReleased(),
        )
        assertTrue(failed.reason.contains("不支持"))
    }

    @Test
    fun `windows release does not require executable bit`() {
        val outcome = releaser(platform = AdbPlatform.WINDOWS).ensureReleased()
        val released = assertIs<AdbReleaseOutcome.Released>(outcome)
        assertEquals("adb.exe", released.executable.name)
        assertTrue(File(tmp, "adb/v37.0.1/AdbWinApi.dll").isFile)
        assertTrue(File(tmp, "adb/v37.0.1/AdbWinUsbApi.dll").isFile)
    }

    @Test
    fun `discard removed released directory and clears verification`() {
        val releaser = releaser()
        assertIs<AdbReleaseOutcome.Released>(releaser.ensureReleased())
        assertTrue(releaser.isVerified)
        assertTrue(releaser.discardReleased())
        assertFalse(releaser.isVerified)
        assertFalse(File(tmp, "adb/v37.0.1").exists())
    }

    @Test
    fun `externally deleted file is rebuilt after verification`() {
        val releaser = releaser()
        val released = assertIs<AdbReleaseOutcome.Released>(releaser.ensureReleased())
        released.executable.delete()
        val outcome = releaser.ensureReleased()
        assertIs<AdbReleaseOutcome.Released>(outcome)
        assertTrue(released.executable.isFile)
    }
}
