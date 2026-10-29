package com.jiaoay.toolbelt.adb

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端点解析：只有「随包内置」一支，失败时不静默换支而是留下人话原因。
 */
class AdbEndpointResolverTest {

    private lateinit var tmp: File
    private val adbBytes = "fake-adb".toByteArray()
    private val noticeBytes = "notice".toByteArray()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("adb-resolver-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaser(): AdbBinaryReleaser = AdbBinaryReleaser(
        adbRootDir = File(tmp, "adb"),
        platform = AdbPlatform.LINUX,
        resourceLoader = { path ->
            when (path.substringAfterLast('/')) {
                "manifest.properties" -> buildString {
                    appendLine("version=37.0.1")
                    appendLine("platform=linux")
                    appendLine("files=adb,NOTICE.txt")
                    appendLine("sha256.adb=${sha256(adbBytes)}")
                    appendLine("sha256.NOTICE.txt=${sha256(noticeBytes)}")
                }.toByteArray()
                "adb" -> adbBytes
                "NOTICE.txt" -> noticeBytes
                else -> null
            }
        },
    )

    @Test
    fun `resolve returns bundled binary with dedicated port`() {
        val endpoint = AdbEndpointResolver(releaser = releaser()).resolve()
        val resolved = assertNotNull(endpoint, "内置 adb 应可用")
        assertTrue(resolved.executablePath.endsWith("adb/v37.0.1/adb"), "实际：${resolved.executablePath}")
        assertEquals(AdbEndpoint.BUNDLED_SERVER_PORT, resolved.serverPort)
        assertTrue(resolved.usesDedicatedServer)
    }

    @Test
    fun `release failure yields null and keeps the reason`() {
        val brokenReleaser = AdbBinaryReleaser(
            adbRootDir = File(tmp, "adb"),
            platform = AdbPlatform.LINUX,
            resourceLoader = { null },
        )
        val resolver = AdbEndpointResolver(releaser = brokenReleaser)
        assertNull(resolver.resolve(), "内置不可用时不得回退到别的来源")
        // 原因必须留下：否则「内置坏了」会表现为「没有设备」
        assertTrue(resolver.lastIssue?.isNotEmpty() == true)
    }

    @Test
    fun `conflict yields null with actionable hint`() {
        val foreign = File(tmp, "adb/user-custom")
        foreign.mkdirs()
        File(foreign, "adb").writeBytes("x".toByteArray())

        val resolver = AdbEndpointResolver(releaser = releaser())
        assertNull(resolver.resolve())
        assertTrue(resolver.lastIssue?.contains("无法识别") == true, "实际：${resolver.lastIssue}")
    }

    @Test
    fun `warmup releases without exposing endpoint`() {
        val releaser = releaser()
        AdbEndpointResolver(releaser = releaser).warmup()
        assertTrue(releaser.isVerified, "预热后应已释放并就绪")
        assertTrue(File(tmp, "adb/v37.0.1/adb").isFile)
    }

    @Test
    fun `repeated resolve reuses verified release`() {
        val resolver = AdbEndpointResolver(releaser = releaser())
        assertEquals(resolver.resolve(), resolver.resolve())
    }

    @Test
    fun `successful resolve clears a previous issue`() {
        val foreign = File(tmp, "adb/user-custom")
        foreign.mkdirs()
        File(foreign, "adb").writeBytes("x".toByteArray())

        val resolver = AdbEndpointResolver(releaser = releaser())
        resolver.resolve()
        assertNotNull(resolver.lastIssue)

        foreign.deleteRecursively()
        assertNotNull(resolver.resolve(), "障碍移除后应能解析出内置端点")
        assertNull(resolver.lastIssue, "成功解析后不再有失败提示")
    }
}
