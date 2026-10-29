// M7-G-C · check 回显格式化器的守护测试：钉死 secret「前 4 后 4」掩码规则 + 明文绝不外泄 + Number 显示单位。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.status

import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigCheckFormatterTest {

    @Test
    fun `maskSecret 前 4 后 4 中间星号（长度 大于 8）`() {
        assertEquals("abcd****ijkl", maskSecret("abcdefghijkl"))
        assertEquals("abcd*ijkl", maskSecret("abcdefghijkl".let { it.take(4) + "X" + it.takeLast(4) }))
    }

    @Test
    fun `maskSecret 长度 小于等于 8 全掩码（短密钥不被前后 4 位还原）`() {
        assertEquals("********", maskSecret("12345678"))
        assertEquals("***", maskSecret("abc"))
    }

    @Test
    fun `maskSecret 空串原样返回`() {
        assertEquals("", maskSecret(""))
    }

    @Test
    fun `appSecret 字段回显走前 4 后 4 掩码且明文绝不出现（G-C 核心）`() {
        val secret = "abcdefghijkl"
        val config = DefaultBridgeConfig.default()
            .let { it.copy(connection = it.connection.copy(appSecret = secret)) }
        val field = BridgeFields.ALL.first { it.id == "connection.appSecret" }
        assertTrue(field.secret, "connection.appSecret 必须标记 secret=true")

        val rendered = renderFieldValue(field, config)
        assertEquals("abcd****ijkl", rendered)
        // 🔴 明文 / 中段 绝不出现在回显值里
        assertFalse(rendered.contains(secret), "回显值泄露了完整 appSecret 明文")
        assertFalse(rendered.contains("efgh"), "回显值泄露了 appSecret 中段")
    }

    @Test
    fun `maxFileSizeBytes 走 displayScale 附显示单位（与 CLI check 同形）`() {
        val config = DefaultBridgeConfig.default()
            .let { it.copy(fileMessage = it.fileMessage.copy(maxFileSizeBytes = 20 * 1048576L)) }
        val field = BridgeFields.ALL.first { it.id == "fileMessage.maxFileSizeBytes" }
        assertEquals("${20 * 1048576L} (20 MB)", renderFieldValue(field, config))
    }

    @Test
    fun `空 List 与空白 String 渲染为占位（不显空串）`() {
        val config = DefaultBridgeConfig.default()
            .let { it.copy(workspace = it.workspace.copy(extraRoots = emptyList())) }
        val extraRoots = BridgeFields.ALL.first { it.id == "workspace.extraRoots" }
        assertEquals("(空)", renderFieldValue(extraRoots, config))
    }
}
