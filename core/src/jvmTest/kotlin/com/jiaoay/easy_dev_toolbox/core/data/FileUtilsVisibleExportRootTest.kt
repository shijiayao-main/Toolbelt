package com.jiaoay.easy_dev_toolbox.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 可见产物树根解析契约：默认 `<home>/Documents/Toolbelt`；用户配置（经
 * [FileUtils.applyVisibleExportRoot]）非空时优先于默认根（home 参数缝不参与解析）；
 * blank 配置 = 回默认根；子目录访问器的 mkdirs 链自建 Documents 目录（Linux 无
 * xdg-user-dirs 环境的兜底）。
 *
 * 单源是进程级 var——每用例后必须复位（[tearDown]），防跨测试污染。
 */
class FileUtilsVisibleExportRootTest {

    @AfterTest
    fun tearDown() {
        FileUtils.applyVisibleExportRoot(null)
    }

    @Test
    fun `默认根解析为 Documents 下的 Toolbelt`() {
        val home = tempHome()
        assertEquals(
            File(File(home, "Documents"), "Toolbelt"),
            FileUtils.getVisibleExportRoot(home),
        )
    }

    @Test
    fun `子目录访问器挂默认根下且 mkdirs 链自建 Documents`() {
        val home = tempHome()
        val dir = FileUtils.getReportsExportDir(home)
        assertEquals(
            File(File(File(home, "Documents"), "Toolbelt"), "reports"),
            dir,
        )
        assertTrue(dir.isDirectory, "子目录访问器返回前 mkdirs（含缺失的 Documents 链）")
    }

    @Test
    fun `配置非空时优先于默认根且 home 缝不参与解析`() {
        val home = tempHome()
        val configured = tempHome()
        FileUtils.applyVisibleExportRoot(configured.absolutePath)
        assertEquals(
            configured,
            FileUtils.getVisibleExportRoot(home),
            "用户配置的绝对路径优先，home 参数被忽略（配置语义是绝对根）",
        )
        val dir = FileUtils.getReportsExportDir(home)
        assertEquals(File(configured, "reports"), dir, "子目录挂配置根下")
        assertTrue(dir.isDirectory, "子目录访问器返回前 mkdirs")
    }

    @Test
    fun `blank 与 null 配置回默认根且应用时 trim`() {
        val home = tempHome()
        val expectedDefault = File(File(home, "Documents"), "Toolbelt")
        FileUtils.applyVisibleExportRoot("   ")
        assertEquals(expectedDefault, FileUtils.getVisibleExportRoot(home), "空白串 = 未配置")
        FileUtils.applyVisibleExportRoot(null)
        assertEquals(expectedDefault, FileUtils.getVisibleExportRoot(home), "null = 清除配置")

        val configured = tempHome()
        FileUtils.applyVisibleExportRoot("  ${configured.absolutePath}  ")
        assertEquals(configured, FileUtils.getVisibleExportRoot(home), "配置值应用时 trim")
    }

    private fun tempHome(): File =
        Files.createTempDirectory("fileutils-visible-root-test").toFile()
}
