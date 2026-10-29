// doDownload 的 HTTP Range 分片响应解析（206 Partial Content）：Content-Range 头提取 + 自洽性校验，
// 与 SdkLarkGateway 类零状态耦合；调用点在该类的 doDownload 分片循环（首片预检的数据源）。
package com.jiaoay.lark.gateway

/**
 * HTTP 206 分片响应的 `Content-Range: bytes <start>-<end>/<total>` 解析结果。
 * [total] 是文件总大小——**首片即得**，是「下载前预检」的数据源（见 SdkLarkGateway.doDownload KDoc）。
 */
internal data class ContentRange(
    val start: Long,
    val end: Long,
    val total: Long,
)

/**
 * 解析 `Content-Range` 头值（形如 `bytes 0-1023/2500`）。非匹配格式返回 null。
 * 🔴 自洽性校验：start ≤ end < total（违反即 null——服务器侧畸形响应按 DownloadFailed 处理，不猜）。
 *    RFC 语义 end 含端点（end - start + 1 = 本片字节数）；单位只接受小写 `bytes`（飞书实测形态）。
 */
internal fun parseContentRange(value: String): ContentRange? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("bytes ")) return null
    val match = Regex("""^bytes (\d+)-(\d+)/(\d+)$""").find(trimmed) ?: return null
    val (startStr, endStr, totalStr) = match.destructured
    val start = startStr.toLongOrNull() ?: return null
    val end = endStr.toLongOrNull() ?: return null
    val total = totalStr.toLongOrNull() ?: return null
    if (start < 0 || end < start || end >= total) return null
    return ContentRange(start, end, total)
}

/**
 * 响应头大小写不敏感取首值。
 * 🔴 OkHttp `Headers.toMultimap()` / HTTP/2 的键大小写不保证（实测小写 `content-range`；按单一形态写死则漏头、Range 分片解析恒失败）——
 *    SDK 的 `RawResponse.getHeaderFirstValue` 是 private，故自己扫描。
 */
internal fun headerValueOf(headers: Map<String, List<String>>, name: String): String? {
    val entry = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) } ?: return null
    return entry.value.firstOrNull()
}
