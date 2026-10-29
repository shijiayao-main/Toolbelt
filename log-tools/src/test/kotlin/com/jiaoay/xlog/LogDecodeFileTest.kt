package com.jiaoay.xlog

import com.jiaoay.xlog.codec.EcdhKeyAgreement
import com.jiaoay.xlog.codec.TeaCipher
import com.jiaoay.xlog.helpers.XLogTestFactory.concat
import com.jiaoay.xlog.helpers.XLogTestFactory.segment
import com.jiaoay.xlog.helpers.XLogTestFactory.teaEncrypt
import com.jiaoay.xlog.helpers.XLogTestFactory.zlib
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 端到端: 合成 .xlog 文件 → LogDecode.decodeFile → 输出文本 */
class LogDecodeFileTest {

    private fun decodeToString(bytes: ByteArray, privateKey: String = ""): String {
        val dir = Files.createTempDirectory("end2end").toFile()
        val logFile = File(dir, "input.xlog")
        val outFile = File(dir, "input.txt")
        logFile.writeBytes(bytes)
        try {
            LogDecode().decodeFile(privateKey = privateKey, logFile = logFile, outFile = outFile)
            return outFile.readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `end-to-end plain segments through streaming path`() {
        val bytes = concat(
            segment(mark = 0x03, seq = 1, payload = "[Asia/Shanghai]-1700000000000 first\n".toByteArray()),
            segment(mark = 0x08, seq = 2, cryptKey = ByteArray(64), payload = "plain\n".toByteArray()),
        )
        assertEquals(
            "11-15 06:13:20.000 first\nplain\n",
            decodeToString(bytes)
        )
    }

    @Test
    fun `end-to-end zlib compressed segment`() {
        val content = "[Asia/Shanghai]-1700000000000 compressed line\n".toByteArray()
        val bytes = segment(mark = 0x04, payload = zlib(content))
        assertEquals(
            "11-15 06:13:20.000 compressed line\n",
            decodeToString(bytes)
        )
    }

    /** 加密压缩段(0x07): 载荷 = teaEncrypt(zlib(plain)), 端到端用合成密钥解出原文 */
    @Test
    fun `end-to-end encrypted zlib segment with synthetic key pair`() {
        val keyPair = EcdhKeyAgreement.generateKeyPair()
        // 设备端 TEA 密钥 = 协商结果; 用对端私钥(这里同一方自协商)推出与解码端一致的 key
        val teaKey = EcdhKeyAgreement.agree(
            publicKeyBytes = hexToBytes(keyPair.publicKey),
            privateKeyHex = keyPair.privateKey,
        )
        val content = "[Asia/Shanghai]-1700000000000 secret line\n".toByteArray()
        val payload = teaEncrypt(zlib(content), teaKey)
        val bytes = segment(
            mark = 0x07,
            cryptKey = hexToBytes(keyPair.publicKey),
            payload = payload,
        )
        assertEquals(
            "11-15 06:13:20.000 secret line\n",
            decodeToString(bytes, privateKey = keyPair.privateKey)
        )
    }

    /** TEA 加密由测试工厂完成, 与 TeaCipher.decrypt 严格互逆(交叉验证) */
    @Test
    fun `teaEncrypt and TeaCipher are exact inverses`() {
        val key = ByteArray(16) { (it * 5 + 3).toByte() }
        val original = "cross check".toByteArray()
        val decrypted = TeaCipher.decrypt(teaEncrypt(original, key), key)
        assertContentEquals(original, decrypted)
    }

    @Test
    fun `missing private key with encrypted segment throws legacy message`() {
        val keyPair = EcdhKeyAgreement.generateKeyPair()
        val teaKey = EcdhKeyAgreement.agree(hexToBytes(keyPair.publicKey), keyPair.privateKey)
        val bytes = segment(
            mark = 0x07,
            cryptKey = hexToBytes(keyPair.publicKey),
            payload = teaEncrypt(zlib("x".toByteArray()), teaKey),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            decodeToString(bytes, privateKey = "")
        }
        assertEquals("the log is encrypted and the private key needs to be entered", error.message)
    }

    /** 原子写: 半途失败(密钥错)不 truncate 在盘旧产物, 且 .tmp 残留删净 */
    @Test
    fun `failed decode keeps old product and leaves no temp file`() {
        val dir = Files.createTempDirectory("end2end-atomic").toFile()
        val logFile = File(dir, "in.xlog")
        val outFile = File(dir, "in.txt")
        try {
            val keyPair = EcdhKeyAgreement.generateKeyPair()
            val teaKey = EcdhKeyAgreement.agree(hexToBytes(keyPair.publicKey), keyPair.privateKey)
            val bytes = segment(
                mark = 0x07,
                cryptKey = hexToBytes(keyPair.publicKey),
                payload = teaEncrypt(zlib("x".toByteArray()), teaKey),
            )
            logFile.writeBytes(bytes)
            outFile.writeText("旧产物内容——失败必须保全")
            assertFailsWith<IllegalArgumentException> {
                LogDecode().decodeFile(privateKey = "", logFile = logFile, outFile = outFile)
            }
            assertEquals("旧产物内容——失败必须保全", outFile.readText(), "半途失败保旧产物（原子替换写契约）")
            assertTrue(
                dir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty(),
                "失败路径 .tmp 残留删净（按前缀扫产物的消费面不误判）",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 密钥预校验提前: 打开输出前即抛（非加密文件空私钥不误伤——照常直通） */
    @Test
    fun `blank key on encrypted file fails before opening output`() {
        val dir = Files.createTempDirectory("end2end-precheck").toFile()
        val logFile = File(dir, "in.xlog")
        val outFile = File(dir, "in.txt")
        try {
            val keyPair = EcdhKeyAgreement.generateKeyPair()
            val teaKey = EcdhKeyAgreement.agree(hexToBytes(keyPair.publicKey), keyPair.privateKey)
            logFile.writeBytes(
                segment(
                    mark = 0x07,
                    cryptKey = hexToBytes(keyPair.publicKey),
                    payload = teaEncrypt(zlib("x".toByteArray()), teaKey),
                ),
            )
            val error = assertFailsWith<IllegalArgumentException> {
                LogDecode().decodeFile(privateKey = "", logFile = logFile, outFile = outFile)
            }
            assertEquals("the log is encrypted and the private key needs to be entered", error.message)
            assertTrue(!outFile.exists(), "预校验失败不落任何输出（含 temp）")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `outFile is created even for empty input`() {
        val dir = Files.createTempDirectory("end2end-empty").toFile()
        val logFile = File(dir, "in.xlog")
        val outFile = File(dir, "out.txt")
        logFile.createNewFile()
        try {
            LogDecode().decodeFile("", logFile, outFile)
            assertTrue(outFile.exists())
            assertEquals("", outFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** zstd 大段端到端: >1MB 解压结果完整(截断修复的文件级回归) */
    @Test
    fun `end-to-end large zstd segment is complete`() {
        val bigLine = "z".repeat(2 * 1024 * 1024)
        val content = (bigLine + "\n").toByteArray()
        val compressed = com.github.luben.zstd.Zstd.compress(content)
        val bytes = segment(mark = 0x0D, cryptKey = ByteArray(64), payload = compressed)
        val output = decodeToString(bytes)
        assertEquals(2 * 1024 * 1024 + 1, output.length)
        assertTrue(output.endsWith("\n"))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = hex.substring(i * 2, (i + 1) * 2).toInt(16).toByte()
        }
        return bytes
    }
}
