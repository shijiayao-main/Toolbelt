package com.jiaoay.easy_dev_toolbox.core.llm

/**
 * OpenAI 兼容端点的 URL 规范化（客户端构造与模型列表拉取的**单一来源**）。
 *
 * 为何需要规范化（真机定位的坑）：
 * OpenAI 兼容生态对 `baseUrl` 有**两套互相冲突的惯例**——
 * - OpenAI 官方 SDK / 多数文档：baseUrl **含** `/v1`（如 `https://api.deepseek.com/v1`）；
 * - Koog：baseUrl **不含** `/v1`，其 `chatCompletionsPath` 默认值自带该段
 *   （`v1/chat/completions`），拼接后才是完整路径。
 *
 * 用户按前者填写（本项目 UI 提示语亦如此引导）时，Koog 会请求
 * `/v1/v1/chat/completions`——宽容网关（实测 llm-gateway）仍返 200 故不显形，
 * 严格网关则 404。故统一在此剥离尾部的版本段，让两套惯例都收敛到 Koog 语义。
 */
object LlmEndpointUrls {

    /** 官方端点（用户留空 baseUrl 时的兜底） */
    const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com"

    /** OpenAI 兼容协议的版本段——Koog 的路径常量自带该段，baseUrl 不得重复携带 */
    private const val API_VERSION_SEGMENT = "/v1"

    /**
     * 规范化为 Koog 语义的 baseUrl：剥掉尾部 `/v1` 版本段，空值回落官方端点。
     *
     * `https://host/v1`     → `https://host`
     * `https://host/api/v1` → `https://host/api`（非根路径的网关同样正确）
     * `https://host`        → 原样
     * `""`                  → 官方端点
     */
    fun normalizedBaseUrl(raw: String): String {
        val base = raw.trim().trimEnd('/').ifBlank { return DEFAULT_OPENAI_BASE_URL }
        if (!base.endsWith(API_VERSION_SEGMENT, ignoreCase = true)) return base
        return base.dropLast(API_VERSION_SEGMENT.length).trimEnd('/').ifBlank { DEFAULT_OPENAI_BASE_URL }
    }

    /**
     * 模型列表端点：规范化后补 `v1/models`。
     *
     * 两个惯例都能得到正确结果：含 `/v1` 的输入被剥离后补回（`…/v1/models`），
     * 不含的输入直接补（`…/v1/models`）。
     */
    fun modelsEndpoint(raw: String): String = "${normalizedBaseUrl(raw)}$API_VERSION_SEGMENT/models"
}
