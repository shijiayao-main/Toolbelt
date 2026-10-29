// 历史消息 content 的正文摘要解析（listMessages 的 textBrief 数据源）：顶层纯函数（无状态 ⇒ 可直接单测，
// 无需 mock SDK 响应对象），与 SdkLarkGateway 类零状态耦合；调用点在该类的 listMessages。
// Gson 仅本模块允许（同 QuotedContentParsers.kt 先例——LarkSdkIsolationTest 黑名单按目录排除 lark-gateway，
// SDK 传递引入、无法移除）；坏 JSON / 属性缺失一律落兜底，绝不抛。
package com.jiaoay.lark.gateway

/**
 * 历史消息 content（`im.v1.message.list` 的 body.content）按 msgType 解析成正文摘要。
 * - `"text"`：取 `{"text":…}` 的 text；
 * - `"post"`：尽力取嵌套文本段（title + 各段落 `tag=="text"` 的 text，与 parseQuotedPostContent 同规则）；
 * - `"file"` / `"image"` / `"media"`：取 file_name（无则落不支持形态）；
 * - 其余 / 解析失败：`[不支持类型 <msgType>]`（msgType 空白时落 `[空消息]`）。
 *
 * 纯函数、runCatching 包裹——坏 JSON / 畸形结构不炸，恒返回非 null 字符串。
 */
internal fun parseHistoryTextBrief(msgType: String, content: String): String {
    val fallback = if (msgType.isBlank()) "[空消息]" else "[不支持类型 $msgType]"
    if (content.isBlank()) return fallback
    return runCatching {
        val root = com.google.gson.JsonParser.parseString(content).asJsonObject
        when (msgType) {
            "text" -> root.getAsJsonPrimitive("text")?.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            "post" -> parseHistoryPostBrief(root)
            "file", "image", "media" ->
                root.getAsJsonPrimitive("file_name")?.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { "[文件 $it]" }
            else -> null
        } ?: fallback
    }.getOrDefault(fallback)
}

/** post 富文本的正文提取（[parseHistoryTextBrief] 的 post 分支）：title + 各段 text 节点，段落间换行。 */
private fun parseHistoryPostBrief(root: com.google.gson.JsonObject): String? {
    val title = root.getAsJsonPrimitive("title")?.takeIf { it.isString }?.asString?.trim().orEmpty()
    val paragraphs = root.getAsJsonArray("content")?.mapNotNull { paragraph ->
        paragraph.asJsonArray.mapNotNull { node ->
            val obj = node.asJsonObject
            // 只取文本类节点（tag=="text"，嵌套引用子块 tag=="post" 同规则）——链接/表情等其余 tag 不混进正文。
            when (obj.get("tag")?.takeIf { it.isJsonPrimitive }?.asString) {
                "text", "post" -> obj.getAsJsonPrimitive("text")?.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }.orEmpty()
    val text = (if (title.isEmpty()) paragraphs else listOf(title) + paragraphs).joinToString("\n").trim()
    return text.takeIf { it.isNotEmpty() }
}
