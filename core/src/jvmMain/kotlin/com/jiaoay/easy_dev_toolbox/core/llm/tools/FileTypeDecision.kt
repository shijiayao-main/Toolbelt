package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlinx.serialization.Serializable

/**
 * LLM 结构化判型的输出 schema（executeStructured<FileTypeDecision>）。
 *
 * kind 取值约定（本地嗅探 + LLM 综合判定后的路由结论）：
 * - "xlog" / "alog"：设备加密日志 → decrypt_file 工具
 * - "archive"：压缩包（zip/tar/gz 族）→ extract_archive 工具
 * - "text"：纯文本 → preview_text 工具
 * - "unknown"：未识别 → agent 给出处理建议
 */
@Serializable
data class FileTypeDecision(
    val kind: String,
    val decryptable: Boolean,
    val reason: String,
) {
    companion object {
        val KNOWN_KINDS = setOf(KIND_XLOG, KIND_ARCHIVE, KIND_TEXT, KIND_UNKNOWN)

        const val KIND_XLOG = "xlog"
        const val KIND_ARCHIVE = "archive"
        const val KIND_TEXT = "text"
        const val KIND_UNKNOWN = "unknown"

        /** LLM 返回非法 kind（超出约定集）时的兜底解析 */
        fun parse(raw: String): Result<FileTypeDecision> = runCatching {
            val json = kotlinx.serialization.json.Json.decodeFromString<FileTypeDecision>(raw)
            json.copy(kind = json.kind.lowercase().ifBlank { KIND_UNKNOWN })
        }
    }
}
