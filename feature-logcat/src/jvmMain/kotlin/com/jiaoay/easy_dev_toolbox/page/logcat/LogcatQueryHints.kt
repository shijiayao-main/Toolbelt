package com.jiaoay.easy_dev_toolbox.page.logcat

/**
 * 查询输入提示（纯函数，查询框 supportingText 消费）：查询串 → 下一步可用输入的
 * 引导文案；null = 无提示不打扰。口径与 [LogcatQueryParser] 同源：key 面取
 * [LogcatQueryParser.QUERY_KEY_TAG] / [LogcatQueryParser.QUERY_KEY_LEVEL] /
 * [LogcatQueryParser.QUERY_KEY_MSG]，级别档
 * 值域取 [LogcatLevel] entries，正则修饰符与大小写语义同其 KDoc 文法——不另造
 * 第二套口径，改文法必须连带改本提示。
 *
 * 触发面（保守：只在「下一步输入有明确值域」的状态出现，其余状态一律 null）：
 * - 空白输入 → 算子集概览；
 * - 尾随待操作数的 key 算子（`tag:` / `level:` / `msg:`）→ 该算子的值域提示。判定须确认
 *   key 是完整 token（左侧是串首/空白/组合算子字符——`stag:` 这类前缀粘连的未知
 *   key 不触发，交错误回执单独指路）。此类中间态解析必失败（缺操作数），错误回执
 *   先占 supportingText 槽，面板把本提示拼接在错误文案后补充值域指引——提示不覆盖
 *   错误、不改错误通道时序。
 *
 * 形态 = supportingText 行内文案：无 popup、无可点面，不抢焦点不挡输入。
 */
internal fun logcatQueryHint(query: String): String? {
    val q = query.trimEnd()
    if (q.isBlank()) {
        return "算子：${LogcatQueryParser.QUERY_KEY_TAG}: 词（tag 子串包含）｜" +
            "${LogcatQueryParser.QUERY_KEY_LEVEL}: 档（严重度 ≥ 档）｜" +
            "${LogcatQueryParser.QUERY_KEY_MSG}: 词（消息体子串包含）｜~/正则/｜" +
            "组合 & | ( )，- 前缀排除；裸词 = tag 包含"
    }
    return when {
        endsWithPendingKey(q, LogcatQueryParser.QUERY_KEY_LEVEL) ->
            "level: 后接 ${LogcatLevel.entries.joinToString("/") { it.letter }} 或级别全名（语义 = 严重度 ≥ 该档）"

        endsWithPendingKey(q, LogcatQueryParser.QUERY_KEY_TAG) ->
            "tag: 后接词（tag 子串，忽略大小写）或 ~/正则/（大小写敏感，忽略大小写用 (?i) 前缀）"

        endsWithPendingKey(q, LogcatQueryParser.QUERY_KEY_MSG) ->
            "msg: 后接词（消息体子串，忽略大小写）或 ~/正则/（大小写敏感，忽略大小写用 (?i) 前缀）"

        else -> null
    }
}

/**
 * 尾随待操作数 key 算子判定：`<key>:` 须为串尾完整 token——前一位是串首、空白、
 * 组合算子（`&` `|` `(` `)`）或排除前缀（`-`）才算 token 起点（token 边界与
 * [LogcatQueryParser] 的词分隔面同口径：分隔符 = 空白与组合算子字符；`-` 另计——
 * 文法中它是 unary 前缀，非词字符）。
 */
private fun endsWithPendingKey(query: String, key: String): Boolean {
    if (!query.endsWith("$key:", ignoreCase = true)) return false
    // key token 起点下标 = 串长 - 「key:」长；其前一位即 token 边界位
    val boundary = query.getOrNull(query.length - key.length - 2) ?: return true
    return boundary.isWhitespace() || boundary in "&|()-"
}
