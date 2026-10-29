package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * web.fetch 守护：
 * 1. 目录注册 + Risk=READ_ONLY + uiHint=null（纯 agent 工具——命令面板面零变化）；
 * 2. 域名白名单矩阵：白名单外 DENIED 人话回执（含白名单内容与加域指引）、
 *    非 http/https 与畸形 URL BAD_ARGS；
 * 3. SSRF 私网段纯函数矩阵（127/8、10/8、172.16/12、192.168/16、169.254/16、
 *    ::1、fe80::/10、任意本地、组播——公网地址阴性对照）+ 域名解析到私网拒绝；
 * 4. 重定向防线：白名单内放行、白名单外拒绝（回执指明目标域名）、超限显式失败；
 * 5. HTML 剥离纯函数 fixture（script/style/注释剥除、title+正文保留、实体解码）；
 * 6. 输出上界独立断言（≤ 8192 字面值——与实现常量独立，防推导漂移静默放宽）；
 * 7. 非 200 / 超时显式失败不静默给空文（回执明示失败，禁止编造页面内容）。
 *
 * 网络用例走本地 HttpServer/ServerSocket（FetchConfig 测试缝注入白名单与超时），
 * 不触外网；默认档用例只测拒绝路径（放行路径会真发请求）。
 */
class WebToolsTest {

    // ── 注册与风险面 ──

    @Test
    fun `web fetch 注册且风险只读、不进命令面板`() {
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "web.fetch" }
            ?: error("web.fetch 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "只读零产物，必须 READ_ONLY")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(setOf("web.fetch"), WebTools.WEB_NAMES, "names 集单源（消费方选择集引用此集合）")
        assertEquals(
            "web.fetch",
            AgentToolCatalog.resolve("web.fetch"),
            "目录实名必须可经 resolve 解析（「列了没注册」零容忍）",
        )
        assertEquals(
            setOf(
                "android.googlesource.com",
                "source.android.com",
                "developer.android.com",
                "github.com",
                "askubuntu.com",
                "launchpad.net",
                "gitlab.gnome.org",
            ),
            WebTools.ALLOWED_HOSTS,
            "域名白名单编译期常量单源（加域改这一处）",
        )
    }

    // ── 白名单矩阵（默认档——只测拒绝路径，放行路径会真发请求） ──

    @Test
    fun `白名单外域名 DENIED 人话回执`() = runBlocking {
        val result = WebTools.WebFetchTool().executeTyped(
            WebTools.WebFetchTool.Args("https://example.com/some/doc"),
        )
        val err = result as? ToolResult.Err ?: error("白名单外必须 DENIED，实际 $result")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("example.com" in err.message, "回执须指明被拒域名：${err.message}")
        assertTrue("android.googlesource.com" in err.message && "github.com" in err.message, "回执须列当前白名单：${err.message}")
        assertTrue("ALLOWED_HOSTS" in err.message, "回执须给出加域路径（改常量）：${err.message}")
    }

    @Test
    fun `非 http https 与畸形 URL 拒绝`() = runBlocking {
        val tool = WebTools.WebFetchTool()
        listOf(
            "ftp://github.com/repo",
            "github.com/repo", // 无 scheme 的裸地址
            "not a url at all",
            "",
        ).forEach { url ->
            val err = tool.executeTyped(WebTools.WebFetchTool.Args(url)) as? ToolResult.Err
                ?: error("[$url] 必须拒绝，实际拉取成功")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code, "[$url] 应 BAD_ARGS：${err.message}")
        }
    }

    // ── SSRF：私网段纯函数矩阵 + 解析后拒绝 ──

    @Test
    fun `私网段判据矩阵`() {
        val disallowed = listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.169.254", "0.0.0.0", "::1", "fe80::1", "224.0.0.1",
        )
        disallowed.forEach { literal ->
            assertTrue(
                WebTools.isDisallowedAddress(InetAddress.getByName(literal)),
                "$literal 属私网/保留段，必须判拒",
            )
        }
        listOf("8.8.8.8", "142.250.190.78", "2606:4700::1111").forEach { literal ->
            assertTrue(
                !WebTools.isDisallowedAddress(InetAddress.getByName(literal)),
                "$literal 是公网地址，不得误拒",
            )
        }
    }

    @Test
    fun `白名单域名解析到私网拒绝`() = runBlocking {
        // localhost 恒解析到 127.0.0.1——白名单命中但私网段拒绝（防线独立于白名单）
        val tool = WebTools.WebFetchTool(
            config = WebTools.FetchConfig(allowedHosts = setOf("localhost")),
        )
        val err = tool.executeTyped(WebTools.WebFetchTool.Args("http://localhost:1/doc")) as? ToolResult.Err
            ?: error("白名单域名解析到私网必须拒绝")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("内网" in err.message, "回执须说明私网拒绝原因：${err.message}")
    }

    // ── HTML 剥离纯函数 fixture ──

    @Test
    fun `HTML 剥离：script style 注释剥除，title 与正文保留，实体解码`() {
        val html = """
            <html><head><title>Test &amp; Title</title>
            <style>body { color: red; }</style>
            <script>var secret = "secretScriptMarker";</script></head>
            <body><!-- comment marker -->
            <p>Fatal signal 11 &lt;SEGV&gt; in A &amp; B</p>
            <div>Line two<br/>after break</div>
            <script>anotherScriptMarker()</script>
            </body></html>
        """.trimIndent()
        val text = WebTools.htmlToText(html)
        assertTrue("secretScriptMarker" !in text, "script 内容必须剥除")
        assertTrue("anotherScriptMarker" !in text, "body 内 script 内容必须剥除")
        assertTrue("color: red" !in text, "style 内容必须剥除")
        assertTrue("comment marker" !in text, "HTML 注释必须剥除")
        assertTrue("<p>" !in text && "<div>" !in text, "标签必须剥除")
        assertTrue("Fatal signal 11 <SEGV> in A & B" in text, "正文保留且实体解码：\n$text")
        assertTrue("Line two" in text && "after break" in text, "br/div 后正文分行保留")
        assertEquals("Test & Title", WebTools.extractTitle(html), "title 提取且实体解码")
    }

    @Test
    fun `未闭合 script 尾部截断兜底`() {
        val text = WebTools.htmlToText("<p>visible</p><script>unterminatedTailMarker")
        assertTrue("visible" in text, "script 之前的正文保留")
        assertTrue("unterminatedTailMarker" !in text, "未闭合 script 开标签之后的尾部必须丢弃")
    }

    // ── 网络行为（本地服务器 + FetchConfig 测试缝） ──

    /** 本地白名单测试档（host=127.0.0.1，私网拦截关闭，短超时） */
    private val localConfig = WebTools.FetchConfig(
        allowedHosts = setOf("127.0.0.1"),
        blockPrivateAddresses = false,
        requestTimeoutMillis = 5_000,
    )

    private fun localServer(handler: (HttpExchange) -> Unit): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test
    fun `白名单内 200 拉取并剥离为纯文本`() = runBlocking {
        val html = "<html><head><title>Signal Doc</title><script>hideMe()</script></head>" +
            "<body><p>Fatal signal 11 (SIGSEGV)</p></body></html>"
        val (server, base) = localServer { exchange -> respond(exchange, 200, html) }
        try {
            val result = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/doc.html"))
            val ok = result as? ToolResult.Ok ?: error("200 必须成功，实际 $result")
            assertTrue("标题: Signal Doc" in ok.summary, "标题行须在：${ok.summary}")
            assertTrue("Fatal signal 11 (SIGSEGV)" in ok.summary, "正文须在：${ok.summary}")
            assertTrue("hideMe" !in ok.summary, "script 内容不得进结果")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `白名单内重定向放行（相对地址逐跳校验）`() = runBlocking {
        val (server, base) = localServer { exchange ->
            when (exchange.requestURI.path) {
                "/start" -> {
                    exchange.responseHeaders.add("Location", "/final")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                else -> respond(exchange, 200, "<html><body><p>redirected body</p></body></html>")
            }
        }
        try {
            val result = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/start"))
            val ok = result as? ToolResult.Ok ?: error("白名单内重定向必须放行，实际 $result")
            assertTrue("redirected body" in ok.summary, "重定向后正文须在：${ok.summary}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `重定向到白名单外域名拒绝且回执指明目标`() = runBlocking {
        val (server, base) = localServer { exchange ->
            exchange.responseHeaders.add("Location", "http://evil.example/steal")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        try {
            val err = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/start")) as? ToolResult.Err
                ?: error("跨白名单域重定向必须拒绝")
            assertEquals(ToolResult.ErrCode.DENIED, err.code)
            assertTrue("evil.example" in err.message, "回执须指明重定向目标域名：${err.message}")
            assertTrue("白名单" in err.message, "回执须说明白名单拒绝原因：${err.message}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `重定向超限显式失败`() = runBlocking {
        // 端口在 server 启动后才可知，handler 用可变槽回填（请求必然晚于启动）
        var loopPort = 0
        val (server, base) = localServer { exchange ->
            // 恒自指重定向（绝对地址回自身——白名单内放行，靠跳数上限收口）
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$loopPort/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        loopPort = server.address.port
        try {
            val err = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/loop")) as? ToolResult.Err
                ?: error("重定向环必须显式失败"
                )
            assertEquals(ToolResult.ErrCode.FAILED, err.code)
            assertTrue("重定向" in err.message && "跳" in err.message, "回执须说明跳数超限：${err.message}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `非 200 显式失败不静默给空文`() = runBlocking {
        val (server, base) = localServer { exchange -> respond(exchange, 404, "not found page") }
        try {
            val err = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/missing")) as? ToolResult.Err
                ?: error("404 必须显式失败"
                )
            assertEquals(ToolResult.ErrCode.FAILED, err.code)
            assertTrue("404" in err.message, "回执须含状态码：${err.message}")
            assertTrue("禁止编造" in (err.hint ?: ""), "回执须明示不得编造内容：${err.message}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `输出恒不超过上界字面值 8192（独立断言，防实现常量漂移）`() = runBlocking {
        val huge = "<html><body>" + "x".repeat(200_000) + "</body></html>"
        val (server, base) = localServer { exchange -> respond(exchange, 200, huge) }
        try {
            val result = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/big"))
            val text = result.render()
            // 8192 是字面值独立断言——与 WebTools.MAX_OUTPUT_CHARS 常量独立（防漂移静默放宽）
            assertTrue(text.length <= 8192, "输出 ${text.length} 超上界 8192——结构性上界失守")
            assertTrue("输出超上界已截断" in text, "截断须带显式标记")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `请求超时显式失败`() = runBlocking {
        // 接受连接但既不读也不写也不关——客户端只能等请求级超时（提前关连接会变成
        // connection reset 而不是超时，测不到 HttpTimeoutException 路径）
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val held = mutableListOf<java.net.Socket>()
        val holdThread = Thread {
            try {
                while (true) {
                    held += server.accept()
                }
            } catch (_: java.io.IOException) {
                // server.close() 后 accept 抛错退出——挂着不响应即测试目的
            }
        }
        holdThread.isDaemon = true
        holdThread.start()
        try {
            val tool = WebTools.WebFetchTool(
                config = WebTools.FetchConfig(
                    allowedHosts = setOf("127.0.0.1"),
                    blockPrivateAddresses = false,
                    requestTimeoutMillis = 300,
                ),
            )
            val err = tool.executeTyped(
                WebTools.WebFetchTool.Args("http://127.0.0.1:${server.localPort}/slow"),
            ) as? ToolResult.Err ?: error("超时必须显式失败")
            assertEquals(ToolResult.ErrCode.FAILED, err.code)
            assertTrue("超时" in err.message, "回执须说明超时：${err.message}")
        } finally {
            server.close()
        }
    }

    @Test
    fun `剥离后无正文显式失败`() = runBlocking {
        val (server, base) = localServer { exchange ->
            respond(exchange, 200, "<html><head><script>only script</script></head><body></body></html>")
        }
        try {
            val err = WebTools.WebFetchTool(config = localConfig)
                .executeTyped(WebTools.WebFetchTool.Args("$base/empty")) as? ToolResult.Err
                ?: error("剥离后空正文必须显式失败，不得返回空 Ok"
                )
            assertEquals(ToolResult.ErrCode.FAILED, err.code)
            assertTrue("无正文" in err.message, "回执须说明剥离后无正文：${err.message}")
        } finally {
            server.stop(0)
        }
    }
}
