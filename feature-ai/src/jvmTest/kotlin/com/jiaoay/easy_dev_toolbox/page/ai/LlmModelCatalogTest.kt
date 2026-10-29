package com.jiaoay.easy_dev_toolbox.page.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LlmModelCatalog] 的响应解析测试（纯逻辑，零网络）。
 *
 * 覆盖三类网关形态：标准 OpenAI 结构、带额外字段的宽松结构、以及结构不符
 * ——后者必须显式抛错而非返回空列表（空列表与「拉取失败」在 UI 上文案不同，
 * 静默吞错会把网关故障显示成「端点没有模型」）。
 */
class LlmModelCatalogTest {

    @Test
    fun `parses standard openai list response`() {
        val body = """
            {"object":"list","data":[
              {"id":"mode-work","object":"model","created":1781771415,"owned_by":"llm"},
              {"id":"mode-coding","object":"model","created":1781771415,"owned_by":"llm"},
              {"id":"mode-data","object":"model","created":1781771415,"owned_by":"llm"}
            ]}
        """.trimIndent()

        assertEquals(listOf("mode-coding", "mode-data", "mode-work"), LlmModelCatalog.parseModelIds(body))
    }

    @Test
    fun `ignores unknown fields and deduplicates then sorts`() {
        val body = """
            {"object":"list","data":[
              {"id":"qwen-plus","extra":{"nested":true}},
              {"id":"deepseek-chat"},
              {"id":"qwen-plus"},
              {"id":"gpt-4o-mini"}
            ]}
        """.trimIndent()

        assertEquals(listOf("deepseek-chat", "gpt-4o-mini", "qwen-plus"), LlmModelCatalog.parseModelIds(body))
    }

    @Test
    fun `tolerates non object entries and non string ids`() {
        val body = """
            {"data":[{"id":"a"},"bare-string",{"id":123},{"id":null},{"no_id":true},{"id":"  "}]}
        """.trimIndent()

        // 非对象项、数字 id、null、空白 id 一律跳过；合法项保留
        assertEquals(listOf("a"), LlmModelCatalog.parseModelIds(body))
    }

    @Test
    fun `empty data array yields empty list`() {
        assertEquals(emptyList(), LlmModelCatalog.parseModelIds("""{"object":"list","data":[]}"""))
    }

    @Test
    fun `missing data array fails loudly`() {
        // 「确实没有模型」与「响应结构不符」必须可区分——后者抛错
        val e = assertFailsWith<IllegalStateException> {
            LlmModelCatalog.parseModelIds("""{"error":{"message":"unauthorized"}}""")
        }
        assertTrue(e.message!!.contains("data"), "报错须点明缺失的字段：${e.message}")
    }

    @Test
    fun `non json body fails loudly`() {
        assertFailsWith<IllegalStateException> { LlmModelCatalog.parseModelIds("<html>502 Bad Gateway</html>") }
    }

    @Test
    fun `json array root fails loudly`() {
        assertFailsWith<IllegalStateException> { LlmModelCatalog.parseModelIds("""[{"id":"a"}]""") }
    }
}
