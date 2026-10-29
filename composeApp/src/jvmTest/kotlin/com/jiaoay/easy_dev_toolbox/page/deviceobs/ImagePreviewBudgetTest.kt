package com.jiaoay.easy_dev_toolbox.page.deviceobs

import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内联图片预览双闸守护。
 *
 * 两道闸互补：字节闸挡「大文件」，像素闸挡「小字节、巨大尺寸」的解压炸弹
 * （如高度 65535 的 PNG 可远小于 64MB 却要 4 亿字节位图）。缺任一道都会让
 * 设备拉回的大图把进程拖进 OOM 或长时间卡顿。
 */
class ImagePreviewBudgetTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
    }

    private fun tempFile(name: String): File =
        File.createTempFile("preview-budget-$name", ".png").also { tempFiles += it }

    private fun writePng(file: File, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", file)
    }

    @Test
    fun `small image decodes successfully`() {
        val file = tempFile("small")
        writePng(file, 32, 32)
        val outcome = decodePreviewWithinBudget(file)
        assertTrue(outcome is ImagePreviewOutcome.Ok, "小图应正常解码，实际 $outcome")
    }

    @Test
    fun `missing file reports decode failure not crash`() {
        val missing = File(tempFiles.size.let { "/tmp/not-exist-${System.nanoTime()}.png" })
        assertEquals(ImagePreviewOutcome.DecodeFailed, decodePreviewWithinBudget(missing))
    }

    @Test
    fun `corrupt bytes report decode failure`() {
        val file = tempFile("corrupt")
        file.writeBytes(ByteArray(64) { (it * 7).toByte() })
        assertEquals(ImagePreviewOutcome.DecodeFailed, decodePreviewWithinBudget(file))
    }

    @Test
    fun `oversized byte count is rejected before reading`() {
        val file = tempFile("bytes")
        // 稀疏文件：只写末尾一字节把长度撑过闸值，不真占 64MB 磁盘空间。
        // 闸门在 readBytes 之前按 length() 判，故此形态足以验证「未读入即拒绝」。
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(IMAGE_PREVIEW_MAX_BYTES)
            raf.write(0)
        }
        assertTrue(file.length() > IMAGE_PREVIEW_MAX_BYTES, "前置：文件长度须超闸值（实际 ${file.length()}）")
        assertEquals(
            ImagePreviewOutcome.TooLarge,
            decodePreviewWithinBudget(file),
            "超字节闸必须在读入前拒绝（不得先 readBytes 再判）",
        )
    }

    @Test
    fun `oversized pixel count is rejected even when bytes are small`() {
        // 构造一个字节数很小但像素数超限的 PNG：宽高各 9000 → 8100 万像素 > 6400 万闸值，
        // 而纯色 PNG 压缩后通常仅几十 KB（远低于字节闸）——这正是像素闸要拦的形态。
        val file = tempFile("pixels")
        writePng(file, 9_000, 9_000)
        assertTrue(
            file.length() <= IMAGE_PREVIEW_MAX_BYTES,
            "前置：本用例须落在字节闸之内（实际 ${file.length()} 字节），否则测不到像素闸",
        )
        assertEquals(
            ImagePreviewOutcome.TooLarge,
            decodePreviewWithinBudget(file),
            "小字节大像素必须被像素闸拦下",
        )
    }
}
