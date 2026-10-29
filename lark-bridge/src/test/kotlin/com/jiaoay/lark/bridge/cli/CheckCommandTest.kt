// M5-T10 测试：check 子命令——全字段自解释输出（计数以 BridgeFields.ALL 为准，批 7 后为 70）+ secret 掩码（前 4 后 4）+ 有 issue 退出码非 0。
// 🔴 check 是「配置全部来自单一文档」的自解释出口，也是 S7（slf4j ServiceLoader）的验证载体。
// 🔴 CheckCommandTest 断言：输出含全部字段 id（计数以 BridgeFields.ALL 为准）、connection.appSecret 被掩码（输出里不出现明文）、有 issue ⇒ 退出码非 0。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.parse
import com.jiaoay.lark.bridge.config.BridgeFields
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("cli-check-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

private class CheckCapture {
    val baos = ByteArrayOutputStream()
    val out = PrintStream(baos, true)
    val exits = mutableListOf<Int>()
    fun exit(code: Int) {
        exits.add(code)
    }

    fun text(): String = baos.toString(Charsets.UTF_8.name())
}

private fun runCheck(root: Path, vararg extra: String): CheckCapture {
    val cap = CheckCapture()
    CheckCommand(cap.out, cap::exit).parse((listOf("--data-dir", root.toString()) + extra).toList())
    return cap
}

class CheckCommandTest {

    @Test
    fun `默认配置 ⇒ 打印全部 47 个字段 id + Validation PASS + 不退出`() {
        val cap = runCheck(tempRoot("all"))
        val text = cap.text()
        assertEquals(
            47, BridgeFields.ALL.size, "字段总数基线应为 47（xlogDecodeEnabled 退役后）")
        for (field in BridgeFields.ALL) {
            assertTrue(text.contains(field.id), "check 输出应含字段 id：${field.id}")
        }
        assertTrue(text.contains("Validation: PASS"), "默认配置应无 issue，实际输出片段=${text.take(200)}")
        assertTrue(cap.exits.isEmpty(), "无 issue 时不应触发退出，实际=${cap.exits}")
    }

    @Test
    fun `🔴 secret 字段 fileMessage-xlogPrivateKey 掩码且不含明文（2026-09-11 引用流新增）`() {
        val plain = "abcd1234efgh5678" // 16 chars → 掩码 "abcd" + "*"*8 + "5678"
        val cap = runCheck(tempRoot("xlogmask"), "--set", "fileMessage.xlogPrivateKey=$plain")
        val text = cap.text()
        assertFalse(text.contains(plain), "🔴 输出绝不能含 xlogPrivateKey 明文")
        val masked = plain.take(4) + "*".repeat(plain.length - 8) + plain.takeLast(4)
        assertTrue(text.contains(masked), "输出应含掩码值 $masked")
    }

    @Test
    fun `🔴 secret 字段 connection-appSecret 掩码前 4 后 4（输出不含明文）`() {
        val plain = "AK12SECRET34WXYZ56" // 18 chars → 掩码 "AK12" + "*"*10 + "YZ56"
        val cap = runCheck(tempRoot("mask"), "--set", "connection.appSecret=$plain")
        val text = cap.text()
        assertFalse(text.contains(plain), "🔴 输出绝不能含 appSecret 明文")
        val masked = plain.take(4) + "*".repeat(plain.length - 8) + plain.takeLast(4)
        assertTrue(text.contains(masked), "输出应含掩码值 $masked")
    }

    @Test
    fun `短 secret（长度不超过 8）全掩码，绝不泄漏任何原字符`() {
        val plain = "abc123" // 6 chars → "******"
        val cap = runCheck(tempRoot("shortmask"), "--set", "connection.appSecret=$plain")
        val text = cap.text()
        assertFalse(text.contains(plain), "短 secret 也不得出现明文")
        assertTrue(text.contains("******"), "短 secret 应全掩码")
    }

    @Test
    fun `🔴 有校验 issue ⇒ Validation FAIL + 退出码非 0（配置域 2）`() {
        // autoStart=true 且 appId/appSecret 均空 ⇒ 触发两条必填 issue（ConfigValidator §8.2）。
        val cap = runCheck(tempRoot("issue"), "--set", "connection.autoStart=true")
        val text = cap.text()
        assertTrue(text.contains("Validation: FAIL"), "应报 FAIL，实际=${text.take(200)}")
        assertTrue(text.contains("connection.appId"), "应列出 appId 必填 issue")
        assertEquals(listOf(2), cap.exits, "有 issue ⇒ 退出码 2（配置域），实际=${cap.exits}")
    }

    @Test
    fun `Number 字段带显示单位（附录 C：字节原值 (MB)）`() {
        val cap = runCheck(tempRoot("unit"), "--set", "fileMessage.maxFileSizeBytes=104857600")
        val text = cap.text()
        assertTrue(
            text.contains("104857600 (100 MB)"),
            "maxFileSizeBytes 应渲染为『字节原值 (100 MB)』，实际未命中",
        )
    }
}
