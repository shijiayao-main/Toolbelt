package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * server jar 随包释放器单测：全部用例在临时目录夹具上跑（目标路径经参数注入，
 * 不触 user.home 数据根）。四态覆盖 = 验收判据：首启释放 / 已存在同版本且哈希相符跳过
 * （用户自置优先序）/ 已在位文件哈希失配人话提示且不动文件 / 不同版本残留人话提示
 * 不覆盖 / 写失败显式失败 / 资源缺失显式失败。
 */
class ScrcpyServerJarReleaserTest {

    /** 官方 v5.0.1 SHA256SUMS 验签值（随包产物完整性契约，见 [ScrcpyServerJarReleaser] 类 KDoc） */
    private val expectedSha256 =
        "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"

    private val expectedSize = 733_930L

    private fun targetUnder(dir: File): File =
        dir.resolve("scrcpy").resolve("scrcpy-server-v${ScrcpyServerDeployer.SERVER_VERSION}")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `first launch releases bundled jar when target missing`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val target = targetUnder(dir)
            val outcome = ScrcpyServerJarReleaser.release(target)
            assertIs<ScrcpyServerJarReleaser.Outcome.Released>(outcome)
            assertTrue(target.isFile)
            assertEquals(expectedSize, target.length())
            // 随包产物完整性钉死：释放字节 == 官方 release 验签值
            assertEquals(expectedSha256, sha256(target))
        }
    }

    /** 随包资源字节（已在位文件正样本 / 隔离副本源——不触用户数据根） */
    private fun bundledJarBytes(): ByteArray =
        ScrcpyServerJarReleaser::class.java
            .getResourceAsStream(ScrcpyServerJarReleaser.RESOURCE_PATH)!!
            .use(InputStream::readBytes)

    @Test
    fun `existing same version target is used as is without overwrite`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val target = targetUnder(dir).apply {
                parentFile.mkdirs()
                writeBytes(bundledJarBytes())
            }
            val outcome = ScrcpyServerJarReleaser.release(target)
            assertIs<ScrcpyServerJarReleaser.Outcome.AlreadyPresent>(outcome)
            // 用户自置文件优先：字节原样（不覆盖不改写），哈希校验通过即直接使用
            assertContentEquals(bundledJarBytes(), target.readBytes())
        }
    }

    @Test
    fun `present file with mismatched sha256 fails explicitly with human pointer`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val target = targetUnder(dir).apply {
                parentFile.mkdirs()
                // 异版本自置/半写/损坏形态的字节——哈希必不符
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val outcome = ScrcpyServerJarReleaser.release(target)
            assertIs<ScrcpyServerJarReleaser.Outcome.Failed>(outcome)
            assertTrue("与随包版本不一致" in outcome.reason, "人话提示: ${outcome.reason}")
            assertTrue("删除该文件后重试" in outcome.reason, "指路「现在能做什么」: ${outcome.reason}")
            assertTrue("重新下载" in outcome.reason)
            // 用户文件仍不动（删否归用户）：字节原样
            assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
        }
    }

    @Test
    fun `stale different version residue conflicts without touching any file`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val stale = dir.resolve("scrcpy").resolve("scrcpy-server-v4.0").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(9, 9))
            }
            val target = targetUnder(dir)
            val outcome = ScrcpyServerJarReleaser.release(target)
            assertIs<ScrcpyServerJarReleaser.Outcome.Conflict>(outcome)
            assertEquals(listOf(stale), outcome.staleFiles)
            assertTrue("删除旧文件后重试" in outcome.hint, "人话提示: ${outcome.hint}")
            assertTrue("不会自动覆盖" in outcome.hint)
            // 不写不删：目标未创建、旧文件字节不动
            assertFalse(target.exists())
            assertContentEquals(byteArrayOf(9, 9), stale.readBytes())
        }
    }

    @Test
    fun `missing bundled resource fails explicitly without creating file`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val target = targetUnder(dir)
            val outcome = ScrcpyServerJarReleaser.release(target) { null }
            assertIs<ScrcpyServerJarReleaser.Outcome.Failed>(outcome)
            assertTrue("资源缺失" in outcome.reason)
            assertFalse(target.exists())
        }
    }

    @Test
    fun `resource read failure fails explicitly`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val target = targetUnder(dir)
            val outcome = ScrcpyServerJarReleaser.release(target) {
                throw IOException("boom")
            }
            assertIs<ScrcpyServerJarReleaser.Outcome.Failed>(outcome)
            assertTrue("读取失败" in outcome.reason)
        }
    }

    @Test
    fun `write failure fails explicitly and cleans partial file`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            // 父路径被同名普通文件占用 → mkdirs/写失败
            val blocker = dir.resolve("scrcpy").apply { writeBytes(byteArrayOf(0)) }
            val target = blocker.resolve("scrcpy-server-v${ScrcpyServerDeployer.SERVER_VERSION}")
            val outcome = ScrcpyServerJarReleaser.release(target)
            assertIs<ScrcpyServerJarReleaser.Outcome.Failed>(outcome)
            assertTrue("释放失败" in outcome.reason, "实际原因: ${outcome.reason}")
        }
    }

    @Test
    fun `same version present wins over stale residue`() {
        createTempDirectory("scrcpy-releaser-test").toFile().let { dir ->
            val scrcpyDir = dir.resolve("scrcpy").apply { mkdirs() }
            scrcpyDir.resolve("scrcpy-server-v4.0").writeBytes(byteArrayOf(7))
            val target = targetUnder(dir).apply { writeBytes(bundledJarBytes()) }
            val outcome = ScrcpyServerJarReleaser.release(target)
            assertIs<ScrcpyServerJarReleaser.Outcome.AlreadyPresent>(outcome)
            assertContentEquals(bundledJarBytes(), target.readBytes())
        }
    }

    @Test
    fun `resource path embeds server version bound to deployer`() {
        assertEquals(
            "/scrcpy/scrcpy-server-v${ScrcpyServerDeployer.SERVER_VERSION}",
            ScrcpyServerJarReleaser.RESOURCE_PATH,
        )
    }

    @Test
    fun `bundled resource is loadable and matches official release`() {
        val bytes = ScrcpyServerJarReleaser::class.java
            .getResourceAsStream(ScrcpyServerJarReleaser.RESOURCE_PATH)
            ?.use(InputStream::readBytes)
        assertTrue(bytes != null, "随包资源必须存在（打包面缺 jar = 开箱即用失效）")
        assertEquals(expectedSize, bytes.size.toLong())
        assertEquals(
            expectedSha256,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        // 生产校验常量钉死到官方验签值——常量漂移（升级未同步）即红
        assertEquals(expectedSha256, ScrcpyServerJarReleaser.EXPECTED_SHA256)
    }
}
