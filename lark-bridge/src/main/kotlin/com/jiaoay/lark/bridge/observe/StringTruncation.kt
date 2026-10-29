// observe：字符串截断工具。
// 🔴 审计的 `prompt`（200）与 `command`（单 argv 60）字段就是用它截断的——
//    它按 **Unicode 码点**计数且 `ellipsis` 可配为**空串**；换成 `String.take(n)` + `"…"` 会让存量
//    `audit.jsonl` 与新写入行的同一字段出现两种形态（emoji 被切成悬替代理 / 多出省略号），破坏双向兼容。
package com.jiaoay.lark.bridge.observe

/**
 * 字符串截断工具：按 Unicode 码点（code point）截断，不切断 UTF-16 代理对。
 *
 * 背景：String.take(n) 按 UTF-16 code unit 计数，emoji 等 BMP 外字符由代理对
 * （2 个 code unit）表达，截断点落在代理对中间会产生悬替代理（渲染为乱码/方框），
 * 且尾部可能恰好拆掉一个完整字符。本函数以 codePointCount 保证输出为合法字符串。
 *
 * 本 object 体内**无任何字段**（只有纯函数），不构成可变全局状态。
 */
object StringTruncation {

    /**
     * 按码点截断到最多 [maxChars] 个字符，超长时以 [ellipsis] 结尾。
     * [maxChars] 含省略号占位；空串原样返回。
     */
    fun truncate(text: String, maxChars: Int, ellipsis: String = "…"): String {
        if (maxChars <= 0) return ""
        if (text.codePointCount(0, text.length) <= maxChars) return text
        val limit = if (ellipsis.isNotEmpty()) maxChars - 1 else maxChars
        // 命中第 limit 个码点的结束偏移（不含）
        var charIndex = 0
        var counted = 0
        while (counted < limit && charIndex < text.length) {
            val cp = text.codePointAt(charIndex)
            charIndex += Character.charCount(cp)
            counted++
        }
        return text.substring(0, charIndex) + ellipsis
    }
}
