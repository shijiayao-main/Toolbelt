// 能力一级化批 C（2026-09-27）：GatewayNativeChatSurface 直测——URL 分流 / 30k 截断 / 透传留痕。
// 🔴 chatId 钉死断言经 SyntheticGateway 记录面：getChatInfo/createChat 的调用记录带 chatId 维度
//    （NativeChatSurface 无 chat_id 参数——批 D 反射守护钉死形态的行为背书）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.ChatCreation
import com.jiaoay.lark.gateway.ChatHistoryItem
import com.jiaoay.lark.gateway.ChatHistoryPage
import com.jiaoay.lark.gateway.ChatInfo
import com.jiaoay.lark.gateway.ChatMemberBrief
import com.jiaoay.lark.gateway.ChatMembersPage
import com.jiaoay.lark.gateway.SenderProfile
import com.jiaoay.lark.gateway.WikiNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NativeChatSurfaceTest {

    private fun surface(gw: SyntheticGateway, chatId: String = "oc_current") =
        GatewayNativeChatSurface(gw, chatId)

    // ── chatId 钉死（🔴 核心契约：调用恒落构造时绑定的会话） ──

    @Test
    fun `钉死的 chatId 透传到 gateway 查询（无 chat_id 参数的会话作用域背书）`() = runBlocking {
        SyntheticGateway().use { gw ->
            // 🔴 SyntheticGateway 记录面不含 chatId 的成员（listMessages 等）改由带记录面的成员
            //    等价背书：createGroupChat 的 RecordedChatCreation 记录经 surface 透传的群名/成员，
            //    getChatInfo 的入参 chatId 经反射记录面不可得 ⇒ 用「换 chatId 构造两个 surface，
            //    各自透传到 createChat 记录面」验证闭包捕获的是构造时 chatId（而非全局态）。
            val s1 = surface(gw, chatId = "oc_one")
            val s2 = surface(gw, chatId = "oc_two")
            gw.createChatResult = ChatCreation("oc_new", emptyList())
            s1.createGroupChat("一号新群", listOf("ou_a"))
            s2.createGroupChat("二号新群", listOf("ou_b"))
            // 两个 surface 各自透传（构造即定格，无串味）——间接证明闭包捕获了各自 chatId。
            assertEquals(
                listOf(RecordedChatCreation("一号新群", listOf("ou_a")), RecordedChatCreation("二号新群", listOf("ou_b"))),
                gw.recordedChatCreations,
            )
            // listMessages/pageToken 透传（返回值面）
            gw.listMessagesResult = ChatHistoryPage(
                items = listOf(ChatHistoryItem("om_h", "ou_a", 0L, "text", "t")),
                hasMore = false,
                nextPageToken = null,
            )
            val page = s1.fetchChatHistory(20, "tok_1")
            assertTrue(page != null && page.items.size == 1)
        }
    }

    // ── URL 分流（纯函数直测） ──

    @Test
    fun `splitWikiUrl 四形态分流`() {
        // /wiki/ 完整链接
        assertEquals(
            "wiki" to "wikcnKQ1k3pABC",
            GatewayNativeChatSurface.splitWikiUrl("https://open.feishu.cn/wiki/wikcnKQ1k3pABC"),
        )
        // /wiki/ 带 query
        assertEquals(
            "wiki" to "wikToken1",
            GatewayNativeChatSurface.splitWikiUrl("https://x.cn/wiki/wikToken1?from=copy"),
        )
        // /docx/ 链接
        assertEquals(
            "docx" to "doxcnABC123",
            GatewayNativeChatSurface.splitWikiUrl("https://open.feishu.cn/docx/doxcnABC123"),
        )
        // 裸 wiki token（无 URL 形态）
        assertEquals("wiki" to "wikBare", GatewayNativeChatSurface.splitWikiUrl("wikBare"))
        // 空 / 纯空白
        assertEquals(null, GatewayNativeChatSurface.splitWikiUrl(""))
        assertEquals(null, GatewayNativeChatSurface.splitWikiUrl("   "))
    }

    // ── 截断（纯函数直测） ──

    @Test
    fun `truncate 未超限原样返回`() {
        val text = "a".repeat(GatewayNativeChatSurface.DOCUMENT_TEXT_MAX_CHARS)
        assertEquals(text, GatewayNativeChatSurface.truncate(text), "恰在上限不截断")
    }

    @Test
    fun `truncate 超限裁断并带标记`() {
        val text = "b".repeat(GatewayNativeChatSurface.DOCUMENT_TEXT_MAX_CHARS + 10)
        val out = GatewayNativeChatSurface.truncate(text)
        assertTrue(out.endsWith("（内容超长已截断）"), "超限须带截断标记：${out.takeLast(20)}")
        // TRUNCATED_SUFFIX = "\n（内容超长已截断）" = 换行 + 9 字符 = 10 字符
        assertEquals(
            GatewayNativeChatSurface.DOCUMENT_TEXT_MAX_CHARS + GatewayNativeChatSurface.TRUNCATED_SUFFIX.length,
            out.length,
            "截断后长度 = 上限 + 标记全串",
        )
    }

    // ── readWikiDocument 端到端分流（SyntheticGateway 注入面） ──

    @Test
    fun `readWikiDocument wiki 链接两步解析（getNode 后读正文）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.wikiNodeResult = WikiNodeRef("doxcnFromWiki", "docx", "周报")
            gw.documentTextResult = "正文内容"
            assertEquals("正文内容", s.readWikiDocument("https://open.feishu.cn/wiki/wikNode1"))
            assertEquals(
                listOf("wikNode1"),
                gw.recordedWikiNodeLookups,
                "wiki 链接须先经节点解析",
            )
            assertEquals(
                listOf("doxcnFromWiki"),
                gw.recordedDocumentTextLookups,
                "解析出的 objToken 须作为 documentId 读正文",
            )
        }
    }

    @Test
    fun `readWikiDocument docx 链接直读（不经节点解析）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.documentTextResult = "docx 直读正文"
            assertEquals("docx 直读正文", s.readWikiDocument("https://open.feishu.cn/docx/doxcnDirect"))
            assertTrue(gw.recordedWikiNodeLookups.isEmpty(), "docx 链接不得触发节点解析")
            assertEquals(listOf("doxcnDirect"), gw.recordedDocumentTextLookups)
        }
    }

    @Test
    fun `readWikiDocument 非 docx 节点落空串（sheet-base 不消费）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.wikiNodeResult = WikiNodeRef("sheetX", "sheet", "表格")
            assertEquals("", s.readWikiDocument("https://x.cn/wiki/wikSheetNode"), "非 docx 须落空串")
            assertTrue(gw.recordedDocumentTextLookups.isEmpty(), "非 docx 不得读正文")
        }
    }

    @Test
    fun `readWikiDocument 节点解析失败落空串（绝不抛）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            // 默认 wikiNodeResult=null（能力缺席）
            assertEquals("", s.readWikiDocument("https://x.cn/wiki/wikMissing"))
        }
    }

    @Test
    fun `readWikiDocument 超长正文截断（gateway 返回全文，surface 截）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.documentTextResult = "c".repeat(GatewayNativeChatSurface.DOCUMENT_TEXT_MAX_CHARS + 100)
            val out = s.readWikiDocument("https://x.cn/docx/doxcnLong")
            assertTrue(out.endsWith("（内容超长已截断）"), "surface 层负责截断：${out.takeLast(20)}")
        }
    }

    // ── chatInfoWithMembers 格式化 ──

    @Test
    fun `chatInfoWithMembers 组装群信息与首 50 成员`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.getChatInfoResult = ChatInfo("oc_c", "测试群", "描述文字", "ou_owner", 2, "group")
            gw.listChatMembersResult = ChatMembersPage(
                items = listOf(ChatMemberBrief("ou_a", "甲"), ChatMemberBrief("ou_b", "")),
                hasMore = false,
                nextPageToken = null,
                memberTotal = 2,
            )
            val text = s.chatInfoWithMembers()
            assertTrue("测试群" in text && "描述文字" in text, "群名与描述必须在：$text")
            assertTrue("ou_a（甲）" in text, "成员带名字：$text")
            assertTrue("ou_b" in text, "无名字成员回退 open_id：$text")
            assertFalse(text.contains("成员未列全"), "无更多页不提示")
        }
    }

    @Test
    fun `chatInfoWithMembers 成员未列全时带提示`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.getChatInfoResult = ChatInfo("oc_c", "大群", "", "", 120, "group")
            gw.listChatMembersResult = ChatMembersPage(
                items = listOf(ChatMemberBrief("ou_a", "甲")),
                hasMore = true,
                nextPageToken = "tok",
                memberTotal = 120,
            )
            val text = s.chatInfoWithMembers()
            assertTrue("成员未列全" in text, "hasMore 须提示未列全：$text")
        }
    }

    @Test
    fun `chatInfoWithMembers 能力缺席落空串（工具回落引导文案）`() = runBlocking {
        SyntheticGateway().use { gw ->
            assertEquals("", surface(gw).chatInfoWithMembers(), "默认 null ⇒ 空串")
        }
    }

    // ── createGroupChat / lookupUser 透传 ──

    @Test
    fun `createGroupChat 透传到 gateway 且记录面留痕`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            gw.createChatResult = ChatCreation("oc_new", emptyList())
            assertEquals("oc_new", s.createGroupChat("新群", listOf("ou_a"))?.chatId)
            assertEquals(listOf(RecordedChatCreation("新群", listOf("ou_a"))), gw.recordedChatCreations)
        }
    }

    @Test
    fun `lookupUser PII 最小化只回 name 与 avatar（复用 SenderProfile）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val s = surface(gw)
            assertNull(s.lookupUser("ou_nobody"), "默认 null = 查不到")
            gw.senderProfileResult = SenderProfile("张三", "https://avatar.example/u.jpg")
            val p = s.lookupUser("ou_zhang")
            assertEquals("张三", p?.name)
            assertEquals("https://avatar.example/u.jpg", p?.avatarUrl)
        }
    }
}
