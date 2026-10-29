package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 网络查询工具域：`web.*`——agent 研判日志时的外部 grounding 面（查错误码/官方文档/AOSP 源码）。
 *
 * ## 形态：fetch-only
 * 只做「LLM 给 URL → 本工具拉页面转纯文本」；搜索 API 形态（需 API key）未做——
 * fetch-only 零 key 零维护者配合，查错误码时 LLM 通常能给 AOSP/官方文档 URL。
 *
 * ## 域名白名单（编译期常量单源）
 * [ALLOWED_HOSTS] 是唯一加域点：改一处常量即扩域，无运行时配置面（配置面会引入
 * 持久化/UI/守护锚三重成本，技术文档域集合稳定、扩域频度低）。白名单从结构上
 * 消掉大部分 SSRF 外呼面：域名拿不进白名单就发不出请求。
 *
 * ## SSRF 防线边界（KDoc 钉死，不冒充）
 * - 白名单外域名/重定向目标 → DENIED（重定向逐跳重新校验——跨白名单域拒绝，
 *   白名单内域间放行）；
 * - 域名解析到私网/保留段（[isDisallowedAddress]）→ DENIED——防 DNS 解析面被
 *   指到内网（解析后拒绝是浅防）；
 * - DNS rebinding 深防（解析与连接间的 TOCTOU）不在本工具防线内，不冒充已防。
 *
 * ## 输出上界（真门禁：超限即刷爆 agent 上下文，研判链不可用）
 * 正文经 [htmlToText] 剥离后恒经 [LogStatsDiffTools.capAt] 收口 ≤
 * [MAX_OUTPUT_CHARS] = 8K 字符（侦察四件同款配方：结构性上界不依赖剥离推导成立）；
 * 单测另以 8192 字面值独立断言（防实现常量漂移静默放宽）。超大结果兜底驱逐
 * 归 OutputCapMiddleware（本工具 8K 远低于其 80K 档，不会触发）。
 *
 * ## 失败回执纪律（幻觉面）
 * 超时/非 200/网络错误一律显式失败并如实说明——LLM 拿不到内容时禁止编造页面
 * 内容，回执文案明示「拉取失败」让 LLM 自行调整或承认查不到。
 */
object WebTools {

    const val FETCH_NAME = "web.fetch"

    /** web.* 家族名集——消费方 names 选择集单源引用（[LogStatsDiffTools.NAMES] 先例） */
    val WEB_NAMES: Set<String> = setOf(FETCH_NAME)

    /**
     * 域名白名单（编译期单源——加域改这一处常量）：AOSP 源码浏览器、Android
     * 源码/开发者文档、GitHub（库 issue/README）、askubuntu（Ubuntu 问答）、
     * launchpad.net（Ubuntu bug 追踪）、gitlab.gnome.org（GNOME 扩展与组件仓库）。
     * 条目按 host 精确匹配（忽略大小写）。
     *
     * **URL 即上行通道**：GET-only 无 body，但 URL 全由 LLM 构造——query/path 可夹带
     * 本机内容（路径/文件名/日志片段）发往白名单域。当前条目全为公共大站、请求 URL
     * 对攻击者不可观测，夹带无回音；这是**扩域硬判据**（M1）：扩任何新域必须先回答
     * 「该域是否观测/记录请求 URL」——用户生成内容域 / paste 站 / 可观测搜索日志域
     * **永不入表**，无答案的扩域提案不得过审（M1 扩域判据：隐私面/输入注入/无线配对/root 永不入域
     * 安全边界报告 §3.27）。
     */
    val ALLOWED_HOSTS: Set<String> = setOf(
        "android.googlesource.com",
        "source.android.com",
        "developer.android.com",
        "github.com",
        "askubuntu.com",
        "launchpad.net",
        "gitlab.gnome.org",
    )

    /** 输出长度结构性上界（字符）——真门禁，见对象 KDoc「输出上界」 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 重定向跟随上限（逐跳校验白名单；超限显式失败不静默） */
    internal const val MAX_REDIRECTS = 3

    // ── 工具：网页拉取（白名单 GET → 纯文本） ──

    class WebFetchTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val config: FetchConfig = FetchConfig(),
    ) : StructuredSimpleTool<WebFetchTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = FETCH_NAME,
        description = "Fetch a whitelisted public web page (AOSP source / Android docs / GitHub / " +
            "Ubuntu Q&A on askubuntu / Launchpad bugs / GNOME GitLab) as plain text, capped at " +
            "8K chars. Use when you have a concrete URL: unknown error codes, API/doc lookups, " +
            "source lines, known-issue lookups for desktop-env problems. Do NOT use it for " +
            "chit-chat or when unsure about the URL — a failed fetch is reported as-is, " +
            "never invent page content.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription(
                "Full URL to fetch (http/https). Host must be in the whitelist: " +
                    "android.googlesource.com / source.android.com / developer.android.com / " +
                    "github.com / askubuntu.com / launchpad.net / gitlab.gnome.org",
            )
            val url: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            fetch(args.url.trim())
        }

        private fun fetch(rawUrl: String): ToolResult {
            if (rawUrl.isBlank()) {
                return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "url 必填")
            }
            var current = normalizeUri(rawUrl)
                ?: return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "URL 无法解析：$rawUrl（仅支持 http/https 完整地址）")
            var hops = 0
            while (true) {
                validate(current)?.let { return it }
                val request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMillis(config.requestTimeoutMillis))
                    .header("User-Agent", "Toolbelt-web-fetch")
                    .GET()
                    .build()
                val response = try {
                    CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                } catch (e: HttpTimeoutException) {
                    return fetchFailure(current, "请求超时（${config.requestTimeoutMillis}ms 内无响应）")
                } catch (e: IOException) {
                    return fetchFailure(current, "网络错误：${e.message ?: e.javaClass.simpleName}")
                } catch (e: IllegalArgumentException) {
                    return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "URL 非法：${e.message ?: current.toString()}")
                }
                val status = response.statusCode()
                if (status in 300..399) {
                    if (hops >= MAX_REDIRECTS) {
                        return fetchFailure(current, "重定向超过 $MAX_REDIRECTS 跳，已停止跟随")
                    }
                    val location = response.headers().firstValue("Location").orElse(null)
                        ?: return fetchFailure(current, "HTTP $status 重定向但缺少 Location 头，无法跟随")
                    current = resolveRedirect(current, location)
                        ?: return ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "重定向地址无法解析：$location",
                        )
                    hops++
                    continue
                }
                if (status != 200) {
                    return fetchFailure(current, "返回 HTTP $status，未获取到内容")
                }
                return renderBody(current, response.body() ?: "")
            }
        }

        /** URL 解析（仅 http/https；null = 解析失败） */
        private fun normalizeUri(rawUrl: String): URI? = try {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase()
            if (scheme !in setOf("http", "https") || uri.host == null) null else uri
        } catch (_: Exception) {
            null
        }

        /** 相对/绝对 Location 解析为下一跳地址（null = 解析失败） */
        private fun resolveRedirect(base: URI, location: String): URI? = try {
            base.resolve(location)
        } catch (_: Exception) {
            null
        }

        /**
         * 单跳校验：白名单（host 忽略大小写精确匹配）+ 私网段解析拒绝。
         * 返回 null = 放行；非 null = 拒绝/失败回执（白名单外含加域指引的人话文案）。
         */
        private fun validate(uri: URI): ToolResult? {
            val host = uri.host?.lowercase() ?: return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "URL 缺少主机名")
            if (host !in config.allowedHosts.map { it.lowercase() }.toSet()) {
                return ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "URL 域名「$host」不在白名单内（当前白名单：${config.allowedHosts.joinToString("、")}）" +
                        "——本工具只能拉取白名单内域名的公开网页。需要新增域名：" +
                        "修改 core/llm/tools/WebTools.kt 的 ALLOWED_HOSTS 常量（编译期单源，无运行时配置面）",
                )
            }
            if (config.blockPrivateAddresses) {
                val addresses = try {
                    InetAddress.getAllByName(host)
                } catch (e: IOException) {
                    return fetchFailure(uri, "域名解析失败：${e.message ?: host}")
                } catch (_: Exception) {
                    return fetchFailure(uri, "域名解析失败：$host")
                }
                if (addresses.any(::isDisallowedAddress)) {
                    return ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "域名「$host」解析到内网/保留地址，已拒绝（SSRF 防线：白名单域名也不得指向私网段）",
                    )
                }
            }
            return null
        }

        /** 200 响应渲染：HTML 剥离为纯文本，非文本内容显式拒绝，恒 8K capAt 收口 */
        private fun renderBody(uri: URI, body: String): ToolResult {
            if (body.isBlank()) {
                return fetchFailure(uri, "响应正文为空")
            }
            val looksHtml = body.contains("<html", ignoreCase = true) || body.contains("<body", ignoreCase = true) ||
                body.contains("<div", ignoreCase = true)
            val text = if (looksHtml) htmlToText(body) else body
            if (text.isBlank()) {
                return fetchFailure(uri, "页面剥离后无正文内容（可能整页都是脚本/样式）")
            }
            val out = if (looksHtml) {
                val title = extractTitle(body)
                if (title.isBlank()) text else "标题: $title\n\n$text"
            } else {
                text
            }
            return ToolResult.Ok(capAt(out))
        }

        /** 失败回执（如实说明——LLM 禁止据此编造页面内容） */
        private fun fetchFailure(uri: URI, reason: String): ToolResult = ToolResult.Err(
            ToolResult.ErrCode.FAILED,
            "拉取 $uri 失败：$reason",
            hint = "请确认地址可公开访问后再重试；拉取失败时如实告知用户查不到，禁止编造页面内容",
        )

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)

        companion object {
            /**
             * 共享 HTTP 客户端（线程安全，进程级单例）。Redirect.NEVER——重定向由
             * [WebFetchTool] 逐跳手动跟随并校验白名单（跨白名单域必须拒绝，自动跟随
             * 会绕过校验）。连接超时固定 10s；请求级超时随 [FetchConfig]。
             */
            private val CLIENT: HttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        }
    }

    // ── 纯函数面（直测缝：不触网络） ──

    /**
     * 拉取配置（测试缝：注入本地服务器 host/关私网拦截/缩超时——生产消费方恒用
     * 默认档，白名单真源仍是 [ALLOWED_HOSTS] 编译期常量）。
     * 声明位置约束：必须保持在 [WebFetchTool] 类声明**之后**——无类体花括号的数据类
     * 若排在工具类之前，会被 ToolFenceCoverageGuardTest 的类声明正则跨声明误收为
     * 工具类（`[^{]*?` 跨过无花括号声明直达 `StructuredSimpleTool<`）。
     */
    data class FetchConfig(
        val allowedHosts: Set<String> = ALLOWED_HOSTS,
        val blockPrivateAddresses: Boolean = true,
        val requestTimeoutMillis: Long = 20_000,
    )

    /**
     * 私网/保留段判据（SSRF 解析面拒绝）：回环（127/8、::1）/内网单播（10/8、
     * 172.16/12、192.168/16）/链路本地（169.254/16、fe80::/10）/任意本地/组播。
     * 公网地址返回 false。
     */
    internal fun isDisallowedAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress

    /**
     * HTML → 纯文本剥离纯函数（title 与正文由调用方分取）：
     * 1. 剥 `<script>/<style>` 成对块（含未闭合的尾部截断兜底）与 HTML 注释；
     * 2. 块级结束标签（p/div/li/tr/标题/pre 等）与 `<br>` 换行为行界；
     * 3. 其余标签整段剥除；
     * 4. 解码常见实体（`&amp;` 最后解码，防 `&amp;lt;` 双重解码）；
     * 5. 行首尾修剪 + 压缩连续空行。
     * 已知近似（取舍）：畸形嵌套标签不重建 DOM，按正则剥离——8K capAt 兜底，
     * 剥离质量以「正文可见、脚本/样式不可见」为验收线，不做浏览器级保真。
     */
    internal fun htmlToText(html: String): String {
        var text = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style\\s*>"), " ")
            // 未闭合的 script/style：开标签之后全部视为脚本/样式内容丢弃
            .replace(Regex("(?is)<script\\b[^>]*>.*$"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*$"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|td|th|h[1-6]|pre|blockquote|section|article|ul|ol|table)>"), "\n")
            .replace(Regex("(?s)<[^>]*>"), " ")
            .let(::decodeEntities)
        text = text.lineSequence()
            .joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }
        return text.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** `<title>` 提取（无标题返回空串；实体同正文解码口径） */
    internal fun extractTitle(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.let(::decodeEntities)
            .orEmpty()

    /** 常见实体解码（`&amp;` 最后解码，防 `&amp;lt;` 双重解码） */
    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /** `web.fetch` 目录描述符。uiHint = null：纯 agent 工具（研判链自用），不进命令面板。 */
    val WEB_FETCH: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = FETCH_NAME,
        description = "Fetch a whitelisted public web page (AOSP source / Android docs / GitHub / " +
            "Ubuntu & GNOME knowledge bases) as plain text (8K cap) for error codes, docs, " +
            "source lines and known-issue lookups — concrete URLs only",
        risk = AgentToolCatalog.Risk.READ_ONLY,
    ) { _, sink, _ ->
        WebFetchTool(sink)
    }
}
