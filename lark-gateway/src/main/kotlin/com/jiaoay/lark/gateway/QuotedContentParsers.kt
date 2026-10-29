// 被引用消息 content 的 msgType 分流解析（引用即上下文）：纯函数族，与 SdkLarkGateway 类零状态耦合。
// 数据源 = `im.v1.message.get` 补查的原始 content（调用点在 SdkLarkGateway.fetchQuotedMessage）。
// 顶层 internal 纯函数（无状态 ⇒ 可直接单测，无需 mock SDK 响应对象）；Gson 仅本模块允许
//    （LarkSdkIsolationTest 黑名单按目录排除 lark-gateway，SDK 传递引入、无法移除）。
package com.jiaoay.lark.gateway

/**
 * 被引用消息 content 解析（引用即上下文）。
 * 按 msgType 分流：
 * - `"file"`：**双形态兼容**——
 *   ① 入站转换形态：类 XML 自闭合标签 `<file key="file_v3_xxx" name="crash.zip"/>`（SDK
 *   `FileMessageConverter` 产出）——⚠️ `message.get` 补查返回的是**原始 content**，
 *   ② 原始 JSON 形态 `{"file_key":"…","file_name":"…"}`（飞书消息体口径）。
 *   file 分支只有 XML 正则时，**补查路径的文件消息会解析失败、静默返回 null**——两条 content
 *   链路形态不对称是缺陷本体（text/interactive 分支本来就是 JSON，与 message.get 同形态）。
 * - `"text"`：JSON 字符串 `{"text":"hello"}`（飞书文档实证），经 SDK 传递引入的
 *   Gson 解析（Gson 仅允许本模块——LarkSdkIsolationTest 黑名单按目录排除 lark-gateway）；
 * - `"interactive"`：机器人卡片消息——引用机器人回复作上下文的高价值场景。
 *   两种形态：完整卡 JSON（schema 2.0 `body.elements[].tag=="markdown"` 的 `content`，自家
 *   [com.jiaoay.lark.gateway.LarkOutboundMapper.toCardJson] 与确认卡同构）⇒ 提取全部 markdown 正文；
 *   CardKit 流式实体引用（`{"type":"card","data":{"card_id":…}}`，正文在卡片实体里、message.get 拿不到）
 *   ⇒ 返回 null 回落 hint。
 * - 其余消息类型（image/post/sticker/…）：不支持引用上下文，返回 null。
 * @return 可解析时返回 [QuotedMessage]（File/Text 对应子类）；解析不出（属性缺失/JSON 非法/文本空白/
 *         卡片实体引用）返回 null
 */
internal fun parseQuotedContent(
    messageId: String,
    chatId: String,
    senderId: String,
    msgType: String,
    content: String,
): QuotedMessage? = when (msgType) {
    "file" -> parseQuotedFileContent(messageId, chatId, senderId, content)
    "text" -> parseQuotedTextContent(messageId, chatId, senderId, content)
    "interactive" -> parseQuotedCardContent(messageId, chatId, senderId, content)
    // 🔴 post：机器人 markdown 出站落成 post 富文本（SDK sendMarkdown → msgType="post"）
    //    ——用户引用机器人回复（非流式卡的普通回复/文件回执）时补查恒 miss，落「本地未取回」hint
    //    不补 post 分支则引用机器人回复时补查恒 miss。
    "post" -> parseQuotedPostContent(messageId, chatId, senderId, content)
    else -> null
}

/**
 * file content 解析（[parseQuotedContent] 的 file 分支）：解析出非空 fileKey 才返回，否则 null。
 * 双形态：先试入站转换形态（类 XML `<file key=… name=…/>`，正则），
 *    不中再试 `message.get` 原始 JSON `{"file_key":…,"file_name":…}`（Gson——本模块允许）。
 *    两形态取其一命中即返回；都不中 null。
 */
internal fun parseQuotedFileContent(
    messageId: String,
    chatId: String,
    senderId: String,
    content: String,
): QuotedMessage.File? {
    // 形态①：SDK 入站转换产物（NormalizedMessage.content —— `<file key="…" name="…"/>`）
    val key = Regex("""key\s*=\s*"([^"]+)"""").find(content)?.groupValues?.get(1)?.trim()
    if (!key.isNullOrBlank()) {
        val name = Regex("""name\s*=\s*"([^"]*)"""").find(content)?.groupValues?.get(1)?.trim().orEmpty()
        return QuotedMessage.File(
            messageId = messageId,
            chatId = chatId,
            senderId = senderId,
            fileKey = key,
            fileName = name.ifBlank { "file" },
        )
    }
    // 形态②：message.get 原始 content（`{"file_key":"…","file_name":"…"}`）——修复前缺失的分支，
    //          缺此分支则「重启后补查文件消息永远静默失败」（file 分支只有 XML 形态时的缺陷本体）。
    val json = runCatching {
        com.google.gson.JsonParser.parseString(content).asJsonObject
    }.getOrNull() ?: return null
    val jsonKey = json.getAsJsonPrimitive("file_key")?.takeIf { it.isString }?.asString?.trim().orEmpty()
    if (jsonKey.isBlank()) return null
    val jsonName = json.getAsJsonPrimitive("file_name")?.takeIf { it.isString }?.asString?.trim().orEmpty()
    return QuotedMessage.File(
        messageId = messageId,
        chatId = chatId,
        senderId = senderId,
        fileKey = jsonKey,
        fileName = jsonName.ifBlank { "file" },
    )
}

/** text content 解析（[parseQuotedContent] 的 text 分支）：`{"text":…}` 取非空 text；JSON 非法返回 null。 */
internal fun parseQuotedTextContent(
    messageId: String,
    chatId: String,
    senderId: String,
    content: String,
): QuotedMessage.Text? = runCatching {
    val text = com.google.gson.JsonParser.parseString(content).asJsonObject
        .getAsJsonPrimitive("text")?.takeIf { it.isString }?.asString?.trim()
    if (text.isNullOrEmpty()) null else QuotedMessage.Text(messageId, chatId, senderId, text)
}.getOrNull()

/**
 * post（富文本）content 解析（[parseQuotedContent] 的 post 分支）。
 *
 * 形态：`{"title":…,"content":[[{"tag":"text","text":…},…],…]}`（段落二维数组）。
 * 机器人 markdown 出站经 SDK sendMarkdown 落成 post——**引用机器人回复**（普通文本回复/文件回执）的
 * 缺此分支则引用机器人回复时补查恒 miss（落「本地未取回该消息」hint）。
 * 提取规则：title 有则作首行；content 按段落序提取全部 `tag=="text"`/`tag=="post"`（嵌套引用子块）
 * 元素的 text 值，段落内以空格连接、段落间以换行连接；空正文返回 null（回落 hint，不猜）。
 */
internal fun parseQuotedPostContent(
    messageId: String,
    chatId: String,
    senderId: String,
    content: String,
): QuotedMessage.Text? = runCatching {
    val root = com.google.gson.JsonParser.parseString(content).asJsonObject
    val title = root.getAsJsonPrimitive("title")?.takeIf { it.isString }?.asString?.trim().orEmpty()
    val paragraphs = root.getAsJsonArray("content")?.mapNotNull { paragraph ->
        paragraph.asJsonArray.mapNotNull { node ->
            val obj = node.asJsonObject
            when (obj.get("tag")?.takeIf { it.isJsonPrimitive }?.asString) {
                "text", "post" -> obj.getAsJsonPrimitive("text")?.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }.orEmpty()
    val text = (if (title.isEmpty()) paragraphs else listOf(title) + paragraphs).joinToString("\n").trim()
    if (text.isEmpty()) null else QuotedMessage.Text(messageId, chatId, senderId, text)
}.getOrNull()

/**
 * interactive（卡片消息）content 解析（[parseQuotedContent] 的 interactive 分支）。
 * - 完整卡 JSON：提取 `body.elements[]` 里全部 `tag=="markdown"` 元素的 `content` 按序拼接
 *   （自家出站卡 [LarkOutboundMapper.toCardJson] 与确认卡同构——引用机器人回复即取其 markdown 正文）；
 * - CardKit 流式实体引用 `{"type":"card","data":{"card_id":…}}`：正文在卡片实体里，
 *   `im.v1.message.get` 取不到 ⇒ null 回落 hint；
 * - 非法 JSON / 空正文：null。
 */
internal fun parseQuotedCardContent(
    messageId: String,
    chatId: String,
    senderId: String,
    content: String,
): QuotedMessage.Text? = runCatching {
    val root = com.google.gson.JsonParser.parseString(content).asJsonObject
    // CardKit 流式实体引用：正文在卡片实体里，message.get 拿不到——不猜，回落 hint。
    if (root.has("type") && root.get("type").asString == "card") return@runCatching null
    val elements = root.getAsJsonObject("body")?.getAsJsonArray("elements") ?: return@runCatching null
    val text = elements.flatMap { el ->
        val obj = el.asJsonObject
        if (obj.get("tag")?.takeIf { it.isJsonPrimitive }?.asString == "markdown") {
            listOf(obj.getAsJsonPrimitive("content")?.takeIf { it.isString }?.asString.orEmpty())
        } else {
            emptyList()
        }
    }.joinToString("\n\n").trim()
    if (text.isEmpty()) null else QuotedMessage.Text(messageId, chatId, senderId, text)
}.getOrNull()
