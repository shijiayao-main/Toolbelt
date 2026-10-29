package com.jiaoay.easy_dev_toolbox.page.tools.hash

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 哈希/校验和引擎验收：算法正确性用公开已知向量断言（RFC/维基口径），
 * 文件流式路径用「同内容文本哈希 ↔ 文件哈希相等」交叉背书（跨 64KB 分块
 * 边界多档内容），失败面逐条显式断言。
 */
class HashChecksumEngineTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "hash-engine-test-${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── 文本哈希已知向量 ──

    @Test
    fun `md5 matches known vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hashText("", HashAlgorithm.MD5), "MD5 空串公开向量")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hashText("abc", HashAlgorithm.MD5), "MD5 abc 公开向量")
        assertEquals("7eca689f0d3389d9dea66ae112e5cfd7", hashText("你好", HashAlgorithm.MD5), "MD5 中文 UTF-8 向量")
    }

    @Test
    fun `sha1 and sha256 match known vectors`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hashText("abc", HashAlgorithm.SHA_1), "SHA-1 abc 公开向量")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashText("abc", HashAlgorithm.SHA_256), "SHA-256 abc 公开向量")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hashText("", HashAlgorithm.SHA_256), "SHA-256 空串公开向量")
    }

    @Test
    fun `crc32 matches known vectors`() {
        assertEquals("00000000", hashText("", HashAlgorithm.CRC32), "CRC32 空串")
        assertEquals("414fa339", hashText("The quick brown fox jumps over the lazy dog", HashAlgorithm.CRC32), "CRC32 全字母句公开向量（0x414FA339）")
        assertEquals("cbf43926", hashText("123456789", HashAlgorithm.CRC32), "CRC32 数字串公开向量（0xCBF43926）")
    }

    @Test
    fun `hex lengths are fixed per algorithm`() {
        for (algorithm in HashAlgorithm.entries) {
            val expectedLength = when (algorithm) {
                HashAlgorithm.MD5 -> 32
                HashAlgorithm.SHA_1 -> 40
                HashAlgorithm.SHA_256 -> 64
                HashAlgorithm.CRC32 -> 8
            }
            assertEquals(expectedLength, hashText("any content", algorithm).length, "${algorithm.label} 输出长度恒定")
        }
    }

    // ── 文件流式哈希 ──

    @Test
    fun `file hash equals text hash of same content across chunk boundary`() {
        // 200KB 内容（> 3 个 64KB 分块）：流式分块与单次摘要口径必须一致
        val content = buildString {
            repeat(25_600) { append("0123456789") }
        }
        val file = File(tempDir, "chunked.bin").apply { writeText(content) }
        val result = hashFile(file.absolutePath)
        assertTrue(result is HashFileResult.Ok, "常规文件哈希不应失败")
        for (algorithm in HashAlgorithm.entries) {
            assertEquals(
                hashText(content, algorithm),
                result.hashes[algorithm],
                "${algorithm.label} 文件流式哈希与文本哈希同内容必须相等（跨分块）",
            )
        }
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toLong(), result.byteCount, "byteCount = 实际字节数")
    }

    @Test
    fun `file hash small file round trips all algorithms`() {
        val file = File(tempDir, "small.txt").apply { writeText("abc") }
        val result = hashFile(file.absolutePath)
        assertTrue(result is HashFileResult.Ok)
        val ok = result
        assertEquals(hashText("abc", HashAlgorithm.MD5), ok.hashes[HashAlgorithm.MD5])
        assertEquals(hashText("abc", HashAlgorithm.SHA_256), ok.hashes[HashAlgorithm.SHA_256])
        assertEquals(3L, ok.byteCount)
    }

    @Test
    fun `file hash failures are explicit`() {
        val blank = hashFile("   ")
        assertTrue(blank is HashFileResult.Failure && blank.reason.contains("路径为空"), "空路径显式失败：$blank.reason")

        val missing = hashFile(File(tempDir, "no-such-file.bin").absolutePath)
        assertTrue(missing is HashFileResult.Failure && missing.reason.contains("不存在"), "缺失文件显式失败")

        val directory = hashFile(tempDir.absolutePath)
        assertTrue(directory is HashFileResult.Failure, "目录不得当文件哈希（isFile 防线）")
    }

    @Test
    fun `file hash algorithm subset only computes requested algorithms`() {
        val file = File(tempDir, "subset.txt").apply { writeText("subset content") }
        val result = hashFile(file.absolutePath, algorithms = listOf(HashAlgorithm.CRC32))
        assertTrue(result is HashFileResult.Ok)
        assertEquals(setOf(HashAlgorithm.CRC32), result.hashes.keys, "只算请求的算法档")
    }
}
