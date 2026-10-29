package com.jiaoay.easy_dev_toolbox.page.settings

import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 图片提取失败反馈闭环守护——提取层单测。
 *
 * 契约：extractSeedFromImage 返回 Result<Color>，失败原因（可读中文文案）随
 * Result 上抛——页面侧 ExtractThemeState.Failed 消费渲染 StatusBanner
 * （失败不得只进 JLog 静默回弹）。
 *
 * 本测试钉提取层契约（UI 消费侧经状态机渲染，纯 Compose remember 态不经 VM）；
 * 真实小图走 ImageIO 解码全链路（零 mock）。
 */
class ExtractThemeStateTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("edt-extract-theme-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        // 测试自建临时文件（无 DataStore 在途写——非 UserHomeIsolation 场景）可直接删
        runCatching { tempDir.deleteRecursively() }
    }

    private fun writeValidPng(): File {
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        // 纯色图会被量化拒提主色（themeColorOrNull null 路径）——画双色棋盘保证可提取
        for (x in 0 until 16) {
            for (y in 0 until 16) {
                image.setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) 0xFF3366CC.toInt() else 0xFFEEDDAA.toInt())
            }
        }
        val file = File(tempDir, "valid.png")
        ImageIO.write(image, "png", file)
        return file
    }

    @Test
    fun `valid image extracts to a seed color`() {
        val file = writeValidPng()

        val result = extractSeedFromImage(file)

        assertTrue(result.isSuccess, "真实可解码图片提取成功（ImageIO → MaterialKolor 全链路）")
        assertNotNull(result.getOrNull(), "成功路径携带种子色（Done 态数据源）")
    }

    @Test
    fun `undecodable file fails with readable message`() {
        val file = File(tempDir, "fake.png")
        file.writeText("this is not an image")

        val result = extractSeedFromImage(file)

        // 核心判别：失败原因对 UI 可见（旧 Color? 实现此断言不存在——失败只进 JLog）
        assertTrue(result.isFailure, "非图片内容解不出图（ImageIO.read = null）")
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            message.contains("无法读取图片"),
            "失败文案面向用户可读（StatusBanner 渲染原文）：实际=$message",
        )
    }

    @Test
    fun `missing file fails instead of crashing`() {
        val file = File(tempDir, "not-exist.png")

        val result = extractSeedFromImage(file)

        assertTrue(result.isFailure, "文件不存在 = Result 失败面（不抛出——页面 fold 消费契约）")
    }

    @Test
    fun `extract state machine covers four states`() {
        // 状态机契约面（sealed 真源反射）：Idle/Extracting/Done/Failed 四面齐全——
        // 页面 Failed 分支渲染 StatusBanner 的编译期穷尽面由此钉死（加新态未接 UI 即此断言红）
        val stateNames = ExtractThemeState::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Idle", "Extracting", "Done", "Failed"),
            stateNames,
            "四面齐全（与 ConnectivityProbe 同构）——实际=$stateNames",
        )
        val failed = ExtractThemeState.Failed("无法读取图片: fake.png")
        assertTrue(
            failed.message.contains("无法读取图片"),
            "Failed 携带面向用户的失败文案",
        )
    }
}
