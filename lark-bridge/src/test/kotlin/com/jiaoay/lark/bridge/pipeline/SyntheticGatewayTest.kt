// M4-T7 测试清单 ⑭：SyntheticGatewayTest——「dry-run 与测试共用替身」自身的可编程性与记录正确性。
// 🔴 后续依赖方：M4-T9 门面装配的 dry-run、M5-T10 CLI 的 `handle-event --dry-run`、M6-T13 的 UI 六态预览。
//    替身若不可靠，这三处会同时静默失真 ⇒ 本文件把替身当**被测对象**逐成员钉住。
// 🔴 关键同形要求：DROP_OLDEST 的 trySend 恒成功，丢弃必须由占用计数推断（gateway-contract §15.2 M-16），
//    与 SdkLarkGateway.offerInbound 同形；where 标签必须是 inbound（与生产同一张 where 表）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardTitleColor
import com.jiaoay.lark.gateway.ChatHistoryItem
import com.jiaoay.lark.gateway.ChatHistoryPage
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.GatewayConnectionState
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.GatewayException
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.LarkCredential
import com.jiaoay.lark.gateway.LarkPolicy
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.Outbound
import com.jiaoay.lark.gateway.OutboundKind
import com.jiaoay.lark.gateway.SendResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SyntheticGatewayTest {

    private val cred = LarkCredential("cli_synthetic_test", "secret_do_not_leak", "feishu")
    private val chat = LarkTarget.Chat("oc_synthetic")
    private val user = LarkTarget.User("ou_synthetic_user")

    private fun tempDir(tag: String) =
        Files.createTempDirectory("syn-gw-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

    // ================================================================== ① 契约完整性

    @Test
    fun `🔴 实现 MessageGateway 全部 29 成员（反射穷尽，一个不少）`() {
        val declared = MessageGateway::class.java.methods.filter { it.declaringClass == MessageGateway::class.java }
        // 🔴 29 成员 = 3 个属性 getter（connectionState / inbound / signals）+ 25 个方法 + close（AutoCloseable）。
        //    反射会看到重名项（Kotlin 为继承的 close 生成了不止一个声明）⇒ 按名字去重后才是契约面的真实大小。
        //    🔴 过滤 `$` 合成方法（Kotlin suspend 默认实现生成 `$suspendImpl` + `access$` 桥接）。
        val memberNames = declared.map { it.name }.filter { !it.contains('$') }.distinct().sorted()
        assertEquals(29, memberNames.size, "契约面必须是 29 个成员，实际 $memberNames")
        assertEquals(
            listOf(
                "close", "connect", "createChat", "disconnect", "downloadResource", "fetchQuotedMessage",
                "getChatInfo", "getConnectionState", "getInbound", "getSignals", "inviteChatMembers",
                "listChatMembers", "listMessages", "listReactions", "openCardStream", "readDocumentText",
                "readReceipts", "recallMessage", "registerApp", "resolveSenderName",
                "resolveSenderProfile", "resolveWikiNode", "send",
                "setReaction", "updateCard", "updateDownloadSettings", "updatePolicy", "uploadFile",
                "uploadImage",
            ),
            memberNames,
            "🔴 成员名单逐字对齐 gateway-contract §2.6 + 任务 15 新增 + 引用补查（2026-09-11）"
                + " + 产物回传 uploadFile（2026-09-12）+ resolveSenderProfile（2026-09-14 用户身份链批）"
                + " + updateCard（2026-09-15 确认卡已答态批）+ updateDownloadSettings（2026-09-15 热更修复批）"
                + " + 能力一级化批 A 九成员 recallMessage/uploadImage/listMessages/getChatInfo/listChatMembers"
                + "/createChat/inviteChatMembers/listReactions/readReceipts（2026-09-27）"
                + " + 能力一级化批 C 两成员 resolveWikiNode/readDocumentText（2026-09-27）"
                + "（新增/删减成员必须让本断言红）",
        )

        val impl = SyntheticGateway::class.java.methods
        // 🔴 同样过滤 `$` 合成方法（Kotlin 为 suspend 默认实现生成 `$suspendImpl` + `access$` 桥接，
        //    它们不是契约成员，替身继承默认实现即可，不要求逐一 override）。
        declared.filter { !it.name.contains('$') }
            .distinctBy { it.name + it.parameterTypes.joinToString { p -> p.name } }.forEach { m ->
            assertTrue(
                impl.any { it.name == m.name && it.parameterTypes.contentEquals(m.parameterTypes) },
                "替身缺少契约成员：${m.name}(${m.parameterTypes.joinToString { it.simpleName }})",
            )
        }
        assertTrue(impl.any { it.name == "close" && it.parameterTypes.isEmpty() }, "替身缺少 close()")

        // 🔴 类型层面必须真是 MessageGateway（否则「12 成员」只是同名而非契约实现）
        assertTrue(MessageGateway::class.java.isAssignableFrom(SyntheticGateway::class.java))
        SyntheticGateway().use { gw ->
            assertNotNull(gw.connectionState)
            assertNotNull(gw.inbound)
            assertNotNull(gw.signals)
        }
    }

    @Test
    fun `初始态 Idle，全部记录面与计数为空，冷流订阅后能收到灌入的消息`() = runBlocking {
        SyntheticGateway().use { gw ->
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value, "初值必须是 Idle")
            assertTrue(gw.recordedSends.isEmpty())
            assertTrue(gw.recordedReactions.isEmpty())
            assertTrue(gw.recordedDownloads.isEmpty())
            assertTrue(gw.recordedStreams.isEmpty())
            assertTrue(gw.recordedPolicies.isEmpty())
            assertTrue(gw.recordedCredentials.isEmpty())
            assertTrue(gw.outboundTexts.isEmpty())
            assertTrue(gw.outboundCards.isEmpty())
            assertTrue(gw.recordedQuotedFetches.isEmpty(), "引用补查记录面初值必须为空")
            assertEquals(0, gw.connectCount)
            assertEquals(0, gw.disconnectCount)
            assertEquals(0, gw.closeCount)
            assertEquals(0L, gw.inboundEmitCount)
            assertEquals(0L, gw.inboundDroppedCount)
            assertEquals(0L, gw.signalEmitCount)

            // 🔴 inbound 是冷流但有通道缓冲：订阅前灌入的消息也必须能收到
            val received = async(Dispatchers.Default) { gw.inbound.take(2).toList() }
            delay(30L)
            assertTrue(gw.emitInbound(msg(messageId = "om_s1", text = "first")))
            assertTrue(gw.emitInbound(msg(messageId = "om_s2", text = "second")))
            val got = withTimeoutOrNull(5_000L) { received.await() }
            assertNotNull(got, "冷流必须在超时前给出 2 条")
            assertEquals(listOf("om_s1", "om_s2"), got.map { it.messageId })
            assertEquals(2L, gw.inboundEmitCount)
            assertEquals(0L, gw.inboundDroppedCount)
        }
    }

    /** 引用补查注入面（2026-09-11 引用即上下文）：可编程返回 + 调用记录，端到端回放的取回通道。 */
    @Test
    fun `fetchQuotedMessage 注入面-默认 null 可编程返回并记录调用`() = runBlocking {
        SyntheticGateway().use { gw ->
            assertNull(gw.fetchQuotedMessage("om_q1"), "默认（未注入）必须返回 null = 补查未取回")
            assertEquals(listOf("om_q1"), gw.recordedQuotedFetches, "调用必须被记录")

            val text = com.jiaoay.lark.gateway.QuotedMessage.Text(
                messageId = "om_q2", chatId = "oc_x", senderId = "ou_bob", text = "被引用的原文",
            )
            gw.quotedMessageResult = text
            assertEquals(text, gw.fetchQuotedMessage("om_q2"))
            assertEquals(listOf("om_q1", "om_q2"), gw.recordedQuotedFetches)
        }
    }

    // ================================================================== ①b 批 A 九成员的替身行为（2026-09-27 能力一级化）

    @Test
    fun `批A 撤回与图片上传-默认成功且留记录，可注入失败，resetRecords 清空`() = runBlocking {
        SyntheticGateway().use { gw ->
            // 默认：撤回成功 + 合成 image_key
            assertTrue(gw.recallMessage("om_rc1"), "默认撤回必须成功（替身可编程性）")
            assertEquals(listOf(RecordedRecall("om_rc1")), gw.recordedRecalls)
            val imgKey = gw.uploadImage(tempDir("img").resolve("pic.png"))
            assertEquals("image_key_synthetic_1", imgKey, "默认图片上传必须返回合成 image_key")
            assertEquals(1, gw.recordedImageUploads.size)

            // 注入失败
            gw.recallResult = false
            gw.uploadImageResult = false
            assertFalse(gw.recallMessage("om_rc2"), "注入失败后撤回须返回 false")
            assertNull(gw.uploadImage(tempDir("img").resolve("broken.png")), "注入失败后上传须返回 null")
            assertEquals(2, gw.recordedRecalls.size, "失败也留记录")
            assertEquals(2, gw.recordedImageUploads.size, "失败也留记录")

            // resetRecords 清空四张记录表（含批 A 新增的）
            gw.resetRecords()
            assertTrue(gw.recordedRecalls.isEmpty())
            assertTrue(gw.recordedImageUploads.isEmpty())
        }
    }

    @Test
    fun `批A 七个查询与操作成员-默认 null 可编程返回，createChat 与 invite 留记录`() = runBlocking {
        SyntheticGateway().use { gw ->
            // 🔴 默认全 null = 能力缺席（L2 未授权 / 未接线的降级语义，上层据此回落引导文案）
            assertNull(gw.listMessages("oc_1"))
            assertNull(gw.getChatInfo("oc_1"))
            assertNull(gw.listChatMembers("oc_1"))
            assertNull(gw.createChat("新群", listOf("ou_a")))
            assertNull(gw.inviteChatMembers("oc_1", listOf("ou_a")))
            assertNull(gw.listReactions("om_1"))
            assertNull(gw.readReceipts("om_1"))

            // 记录面：建群与拉人是**操作型**成员，必须留痕（dry-run 报告可见）
            gw.createChatResult = com.jiaoay.lark.gateway.ChatCreation("oc_new", emptyList())
            gw.createChat("周会群", listOf("ou_a", "ou_b"))
            gw.inviteChatMembersResult = com.jiaoay.lark.gateway.ChatMemberOpResult(true, emptyList())
            gw.inviteChatMembers("oc_new", listOf("ou_c"))

            // 可编程返回逐个生效
            gw.listMessagesResult = ChatHistoryPage(
                items = listOf(ChatHistoryItem("om_h1", "ou_a", 1L, "text", "历史正文")),
                hasMore = false,
                nextPageToken = null,
            )
            gw.getChatInfoResult = com.jiaoay.lark.gateway.ChatInfo("oc_1", "群名", "描述", "ou_owner", 3, "group")
            gw.listChatMembersResult = com.jiaoay.lark.gateway.ChatMembersPage(
                items = listOf(com.jiaoay.lark.gateway.ChatMemberBrief("ou_a", "甲")),
                hasMore = false,
                nextPageToken = null,
                memberTotal = 1,
            )
            gw.listReactionsResult = listOf(com.jiaoay.lark.gateway.ReactionBrief("THUMBSUP", "ou_a"))
            gw.readReceiptsResult = com.jiaoay.lark.gateway.ReadReceiptPage(listOf("ou_a"), false, null)

            assertEquals("历史正文", gw.listMessages("oc_1")?.items?.single()?.textBrief)
            assertEquals("群名", gw.getChatInfo("oc_1")?.name)
            assertEquals("甲", gw.listChatMembers("oc_1")?.items?.single()?.name)
            // 🔴 操作型成员的调用记录是**累计**的：前面「默认 null 断言」阶段的调用同样留痕
            assertEquals(
                listOf(
                    RecordedChatCreation("新群", listOf("ou_a")),
                    RecordedChatCreation("周会群", listOf("ou_a", "ou_b")),
                ),
                gw.recordedChatCreations,
            )
            assertEquals(
                listOf(RecordedChatInvite("oc_1", listOf("ou_a")), RecordedChatInvite("oc_new", listOf("ou_c"))),
                gw.recordedChatInvites,
            )
            assertTrue(gw.inviteChatMembers("oc_1", emptyList())!!.ok)
            assertEquals("THUMBSUP", gw.listReactions("om_1")?.single()?.emojiKey)
            assertEquals(listOf("ou_a"), gw.readReceipts("om_1")?.readOpenIds)

            // resetRecords 清空（含批 A 新增的两张操作记录表）
            gw.resetRecords()
            assertTrue(gw.recordedChatCreations.isEmpty())
            assertTrue(gw.recordedChatInvites.isEmpty())

            // ── 能力一级化批 C（2026-09-27）：wiki/docx 两成员同形态 ──
            // 🔴 先断「记录面初值空」再调用（调用即留痕——本用例的断言面是累计语义）
            assertTrue(gw.recordedWikiNodeLookups.isEmpty())
            assertTrue(gw.recordedDocumentTextLookups.isEmpty())
            assertNull(gw.resolveWikiNode("wik_x"), "默认 null = 能力缺席")
            assertNull(gw.readDocumentText("dox_x"))
            gw.wikiNodeResult = com.jiaoay.lark.gateway.WikiNodeRef("dox_real", "docx", "周报文档")
            gw.documentTextResult = "文档正文"
            assertEquals("dox_real", gw.resolveWikiNode("wik_x")?.objToken)
            assertEquals("文档正文", gw.readDocumentText("dox_real"))
            // 记录面**累计**（与建群/拉人同款）：能力缺席断言阶段的调用同样留痕
            assertEquals(listOf("wik_x", "wik_x"), gw.recordedWikiNodeLookups, "wiki 调用必须留痕（分流断言用）")
            assertEquals(listOf("dox_x", "dox_real"), gw.recordedDocumentTextLookups)
        }
    }

    // ================================================================== ② connect / disconnect / close

    @Test
    fun `🔴 connect 三态映射到 GatewayConnectionState（Connected、Failed、BlockedByPeerInstance）`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.connectResult = GatewayConnectResult.Connected("ou_bot_x", "BotX")
            val ok = gw.connect(cred, LarkPolicy())
            assertTrue(ok is GatewayConnectResult.Connected)
            assertEquals(GatewayConnectionState.Connected("ou_bot_x", "BotX"), gw.connectionState.value)
            assertEquals(1, gw.connectCount)

            gw.connectResult =
                GatewayConnectResult.Failed(GatewayErrorCode.ConnectFatal, larkCode = 99991672, larkMsg = "app not published")
            val failed = gw.connect(cred, LarkPolicy())
            assertTrue(failed is GatewayConnectResult.Failed)
            assertFalse(failed.retryable, "ConnectFatal 不可重试")
            assertEquals(
                GatewayConnectionState.Disconnected(GatewayErrorCode.ConnectFatal, "app not published"),
                gw.connectionState.value,
            )

            // 🔴 D19 联动：被同 appId 的另一实例占用 ⇒ 独立的第六态，不是 Disconnected
            gw.connectResult = GatewayConnectResult.BlockedByPeerInstance
            assertEquals(GatewayConnectResult.BlockedByPeerInstance, gw.connect(cred, LarkPolicy()))
            assertEquals(GatewayConnectionState.BlockedByPeerInstance, gw.connectionState.value)
            assertEquals(3, gw.connectCount)
        }
    }

    @Test
    fun `connect 记录凭据与策略；updatePolicy 追加而不覆盖历史`() = runBlocking {
        SyntheticGateway().use { gw ->
            val p1 = LarkPolicy(requireMention = true)
            gw.connect(cred, p1)
            assertEquals(listOf(cred), gw.recordedCredentials)
            assertEquals(listOf(p1), gw.recordedPolicies)

            val p2 = LarkPolicy(requireMention = false, respondToMentionAll = false)
            gw.updatePolicy(p2)
            // 🔴 追加语义：dry-run 报告要能看到「下推过哪些策略」的完整历史，不是只看最后一次
            assertEquals(listOf(p1, p2), gw.recordedPolicies)
            assertEquals(1, gw.recordedCredentials.size, "updatePolicy 不带凭据 ⇒ 不得追加凭据记录")
            assertEquals(1, gw.connectCount, "updatePolicy 不是 connect")

            // 🔴 R17：凭据的 toString 已掩码，替身不得成为泄漏面
            val rendered = gw.recordedCredentials.joinToString()
            assertFalse(rendered.contains("secret_do_not_leak"), "凭据不得明文出现在字符串化输出里：$rendered")
            assertFalse(gw.toString().contains("secret_do_not_leak"))
        }
    }

    @Test
    fun `disconnect 回 Idle 并计数；close 置 Idle、关两条通道且可重复调用`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.connectResult = GatewayConnectResult.Connected("ou_b", "B")
            gw.connect(cred, LarkPolicy())
            assertTrue(gw.connectionState.value is GatewayConnectionState.Connected)

            gw.disconnect()
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value)
            assertEquals(1, gw.disconnectCount)

            val signals = async(Dispatchers.Default) { gw.signals.take(1).toList() }
            delay(30L)
            assertTrue(gw.emitSignal(GatewaySignal.Reconnecting), "close 前信号通道必须可写")
            assertEquals(listOf(GatewaySignal.Reconnecting), withTimeoutOrNull(3_000L) { signals.await() })

            gw.close()
            assertEquals(1, gw.closeCount)
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value, "close 后不得残留 Connected（UI 会误显示在线）")
            // 🔴 close 幂等：D20 优雅关停里 stop() 与 shutdown hook 都可能调它
            gw.close()
            gw.close()
            assertEquals(3, gw.closeCount, "close 计数照实累加（可观测），但不得抛异常")
            // 🔴 通道已关 ⇒ 再灌入不抛异常，只是不再被接受
            assertFalse(gw.emitInbound(msg(text = "after close")), "close 后入站必须失败而不是抛异常")
            assertFalse(gw.emitSignal(GatewaySignal.Reconnected))
        }
    }

    @Test
    fun `setState 可直接摆六态（T13 的 UI 六态预览与 BlockedByPeerInstance 联动都靠它）`() {
        SyntheticGateway().use { gw ->
            val six = listOf(
                GatewayConnectionState.Idle,
                GatewayConnectionState.Connecting,
                GatewayConnectionState.Connected("ou_b", "B"),
                GatewayConnectionState.Reconnecting,
                GatewayConnectionState.Disconnected(GatewayErrorCode.ConnectRetryable, "net"),
                GatewayConnectionState.BlockedByPeerInstance,
            )
            assertEquals(6, six.map { it::class }.distinct().size, "六态必须是六个不同类型")
            six.forEach { state ->
                gw.setState(state)
                assertEquals(state, gw.connectionState.value)
            }
            assertEquals(0, gw.connectCount, "setState 是直摆，不得计入 connect")
        }
    }

    // ================================================================== ③ send 的可编程性与记录

    @Test
    fun `send 记录 target、out、时间戳，text 与 card 分视图`() = runBlocking {
        var now = 1_700_000_000_000L
        SyntheticGateway(clock = { now }).use { gw ->
            val r1 = gw.send(chat, Outbound.text("pong"))
            assertTrue(r1.ok)
            assertEquals(SyntheticGateway.DEFAULT_MESSAGE_ID, r1.messageId)

            val card = CardSpec(title = "任务", titleColor = CardTitleColor.GREEN, markdown = "**done**")
            now += 500L
            gw.send(user, Outbound.card(card))
            gw.send(chat, Outbound.markdown("## 进度"))

            assertEquals(3, gw.recordedSends.size)
            val first = gw.recordedSends[0]
            assertEquals(chat, first.target)
            assertEquals(OutboundKind.TEXT, first.out.kind)
            assertEquals("pong", first.text)
            assertNull(first.card)
            assertEquals(1_700_000_000_000L, first.atMillis, "时间戳必须取自注入的 clock（虚拟时间可断言）")
            assertEquals(1_700_000_000_500L, gw.recordedSends[1].atMillis)
            assertEquals(user, gw.recordedSends[1].target)

            // 🔴 分视图：文本与卡片各自的断言入口（T9 dry-run 的报告输出直接用这两个）
            assertEquals(listOf("pong", "## 进度"), gw.outboundTexts)
            assertEquals(listOf(card), gw.outboundCards)
            assertEquals(listOf("chat_id", "open_id", "chat_id"), gw.recordedSends.map { it.target.receiveIdType })
        }
    }

    @Test
    fun `sendResult 注入失败：返回失败且自发 SendFailed 信号，chatId 取自 LarkTarget-Chat`() = runBlocking {
        SyntheticGateway().use { gw ->
            val signals = async(Dispatchers.Default) { gw.signals.take(1).toList() }
            delay(30L)

            gw.sendResult =
                SendResult(ok = false, messageId = null, error = GatewayErrorCode.RateLimited, larkCode = 11232, larkMsg = "too many requests")
            val result = gw.send(chat, Outbound.text("boom"))
            assertFalse(result.ok)
            assertEquals(GatewayErrorCode.RateLimited, result.error)
            assertEquals(1, gw.recordedSends.size, "🔴 失败也要留记录（审计与 dry-run 报告都要看见这次尝试）")

            val got = withTimeoutOrNull(3_000L) { signals.await() }
            assertNotNull(got, "发送失败必须自发一条信号（与真网关的可观测行为同形）")
            val signal = got.single()
            assertTrue(signal is GatewaySignal.SendFailed, "实际 $signal")
            assertEquals(GatewayErrorCode.RateLimited, signal.code)
            assertEquals("too many requests", signal.msg)
            assertEquals("oc_synthetic", signal.chatId, "chatId 必须从 LarkTarget-Chat 反推")
        }
    }

    @Test
    fun `send 失败且目标是 User 时 SendFailed 的 chatId 为 null（不得伪造 chatId）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val signals = async(Dispatchers.Default) { gw.signals.take(1).toList() }
            delay(30L)
            gw.sendResult = SendResult(ok = false, messageId = null, error = GatewayErrorCode.TargetRevoked, larkMsg = "user gone")
            gw.send(user, Outbound.text("hi"))
            val signal = withTimeoutOrNull(3_000L) { signals.await() }.orEmpty().single()
            assertTrue(signal is GatewaySignal.SendFailed)
            assertNull(signal.chatId, "LarkTarget-User 没有 chatId ⇒ 必须为 null")
            assertEquals(GatewayErrorCode.TargetRevoked, signal.code)
        }
    }

    @Test
    fun `sendHandler 覆盖 sendResult（按 out 内容动态决定，可编程限流与分场景失败）`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.sendResult = SendResult(ok = true, messageId = "om_default")
            gw.sendHandler = { _, out ->
                if (out.text?.contains("限流") == true) {
                    SendResult(ok = false, messageId = null, error = GatewayErrorCode.RateLimited, larkCode = 11232)
                } else {
                    SendResult(ok = true, messageId = "om_dynamic")
                }
            }
            assertEquals("om_dynamic", gw.send(chat, Outbound.text("正常")).messageId)
            val limited = gw.send(chat, Outbound.text("触发限流"))
            assertFalse(limited.ok)
            assertEquals(GatewayErrorCode.RateLimited, limited.error)

            // 🔴 handler 置回 null ⇒ 回落到静态 sendResult（可编程性必须可撤销）
            gw.sendHandler = null
            assertEquals("om_default", gw.send(chat, Outbound.text("再次正常")).messageId)
        }
    }

    @Test
    fun `sendDelayMillis 让 send 挂起指定时长（测频控挂起不阻塞 intake 的可编程性）`() = runBlocking {
        SyntheticGateway().use { gw ->
            assertEquals(0L, gw.sendDelayMillis, "默认零延迟 ⇒ 常规测试不被拖慢")
            gw.sendDelayMillis = 400L
            val started = System.nanoTime()
            gw.send(chat, Outbound.text("slow"))
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsed >= 380L, "必须真的挂起：elapsed=${elapsed}ms")
            assertEquals(1, gw.recordedSends.size, "挂起后仍要完成记录")
        }
    }

    // ================================================================== ④ 卡片流替身

    @Test
    fun `openCardStream 返回 SyntheticCardStream 并登记；tryReplace 覆盖、flushTerminal 定稿`() = runBlocking {
        SyntheticGateway().use { gw ->
            val initial = CardSpec(title = "运行中", titleColor = CardTitleColor.GREY, markdown = "", streaming = true)
            val sink = gw.openCardStream(chat, initial) as SyntheticCardStream
            assertEquals(listOf<SyntheticCardStream>(sink), gw.recordedStreams)
            assertEquals(chat, sink.target)
            assertEquals(initial, sink.initial)
            assertTrue(sink.isOpen)
            assertEquals(1, sink.segmentIndex, "segmentIndex 从 1 开始（契约 StreamSegmentRotated）")

            assertTrue(sink.tryReplace("第一帧"))
            assertTrue(sink.tryReplace("第二帧"))
            assertEquals("第二帧", sink.latest(), "滚动窗口：latest 恒等于最后一次替换")
            assertEquals(2, sink.replaceCount)

            val terminal = CardSpec(title = "完成", titleColor = CardTitleColor.GREEN, markdown = "第二帧")
            sink.flushTerminal(terminal)
            assertEquals(terminal, sink.terminal)
            assertFalse(sink.isOpen, "🔴 终态卡发出后流必须关闭")
            // 🔴 关闭后 replace 必须被拒（200810 的架构级消解：终态之后不再有流式写入）
            assertFalse(sink.tryReplace("迟到的快照"))
            assertEquals("第二帧", sink.latest(), "被拒的快照不得覆盖最新帧")
        }
    }

    @Test
    fun `SyntheticCardStream 的 close 幂等，未发终态时用最新快照补一次终态卡`() = runBlocking {
        SyntheticGateway().use { gw ->
            val initial = CardSpec(title = "运行中", markdown = "", streaming = true)
            val sink = gw.openCardStream(chat, initial) as SyntheticCardStream
            sink.tryReplace("只写了一半")

            sink.close()
            assertEquals(1, sink.closedTimes)
            assertFalse(sink.isOpen)
            val patched = sink.terminal
            assertNotNull(patched, "🔴 平移网关语义：未发终态就 close ⇒ 用当前快照补一次终态，卡片不得停在流式态")
            assertEquals("只写了一半", patched.markdown)
            assertEquals(initial.title, patched.title, "补终态沿用初始卡的标题")

            sink.close()
            sink.close()
            assertEquals(3, sink.closedTimes, "close 计数照实累加（幂等性证据）")
            assertEquals(patched, sink.terminal, "🔴 重复 close 不得覆盖已定稿的终态卡")
        }
    }

    @Test
    fun `已发终态后再 close 不覆盖终态卡（幂等的另一半）`() = runBlocking {
        SyntheticGateway().use { gw ->
            val sink = gw.openCardStream(chat, CardSpec(title = "T", markdown = "", streaming = true)) as SyntheticCardStream
            sink.tryReplace("正文")
            val terminal = CardSpec(title = "DONE", titleColor = CardTitleColor.GREEN, markdown = "正文")
            sink.flushTerminal(terminal)
            sink.close()
            assertEquals(terminal, sink.terminal, "close 不得用快照覆盖显式终态卡")
        }
    }

    @Test
    fun `SyntheticCardStream 的 rotateSegment 递增 segmentIndex 并清空快照（StreamSegmentRotated 链路）`() {
        SyntheticGateway().use { gw ->
            val sink = gw.openCardStream(chat, CardSpec(title = "T", markdown = "", streaming = true)) as SyntheticCardStream
            sink.tryReplace("第一段卡的内容")
            assertEquals(1, sink.segmentIndex)

            sink.rotateSegment()
            assertEquals(2, sink.segmentIndex)
            assertEquals("", sink.latest(), "轮换到新卡实体 ⇒ 快照从头开始")
            assertEquals(0, sink.replaceCount)
            assertTrue(sink.isOpen, "轮换不关流")

            sink.tryReplace("第二段卡的内容")
            assertEquals("第二段卡的内容", sink.latest())
            sink.rotateSegment()
            assertEquals(3, sink.segmentIndex)
        }
    }

    @Test
    fun `onReplace 回调可观测每一次覆盖（ProgressAccumulator 接线用）`() = runBlocking {
        val seen = mutableListOf<String>()
        val sink = SyntheticCardStream(chat, CardSpec(title = "T", markdown = ""), onReplace = { seen.add(it) })
        sink.tryReplace("a")
        sink.tryReplace("b")
        assertEquals(listOf("a", "b"), seen)
        sink.close()
        assertTrue(sink.terminal != null)
    }

    // ================================================================== ⑤ 下载 / 表情回应 / 注册

    @Test
    fun `downloadResource 成功写盘（默认与自定义 payload）、失败不写盘，两种情况都留记录`() = runBlocking {
        val root = tempDir("download")
        SyntheticGateway().use { gw ->
            // 默认 payload；dest 的父目录不存在也必须被创建（T8 的 FileExtractor 依赖这一点）
            val dest = root.resolve("nested/dir/file.bin")
            assertTrue(gw.downloadResource("om_f1", "file_key_1", dest).ok)
            assertTrue(Files.exists(dest))
            assertEquals(
                SyntheticGateway.DEFAULT_DOWNLOAD_PAYLOAD,
                String(Files.readAllBytes(dest), Charsets.UTF_8),
            )

            // 自定义 payload（T8 用它伪造 zip / 图片）
            val zipBytes = listOf<Byte>(0x50, 0x4B, 0x03, 0x04)
            gw.downloadPayload = zipBytes.toByteArray()
            val zip = root.resolve("a.zip")
            assertTrue(gw.downloadResource("om_f2", "file_key_2", zip).ok)
            assertEquals(zipBytes, Files.readAllBytes(zip).toList())

            // 失败：不写盘，但记录照留（可观测）
            gw.downloadResult = false
            val missing = root.resolve("never.bin")
            val failed = gw.downloadResource("om_f3", "file_key_3", missing)
            assertFalse(failed.ok)
            assertFalse(Files.exists(missing), "失败不得留下半成品文件")
            assertEquals(null, failed.error, "🔴 未注入 downloadError 时不得虚构错误码（resolver 回落通用文案）")

            assertEquals(3, gw.recordedDownloads.size)
            assertEquals(listOf("om_f1", "om_f2", "om_f3"), gw.recordedDownloads.map { it.messageId })
            assertEquals(listOf("file_key_1", "file_key_2", "file_key_3"), gw.recordedDownloads.map { it.fileKey })
            assertEquals(listOf(dest, zip, missing), gw.recordedDownloads.map { it.dest })
        }
    }

    /** downloadError 旋钮（2026-09-12 平台 100MB 限传导）：注入的错误码须透传进结果（resolver 据此渲染文案）。 */
    @Test
    fun `downloadResource 失败时 downloadError 注入的错误码透传`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.downloadResult = false
            gw.downloadError = com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge
            val r = gw.downloadResource("om_f4", "file_key_4", tempDir("dl-err").resolve("x.bin"))
            assertFalse(r.ok)
            assertEquals(
                com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge, r.error,
                "注入错误码须透传（合成「平台限」场景供上层文案测试用）",
            )
        }
    }

    @Test
    fun `setReaction 记录三字段并可注入失败`() = runBlocking {
        SyntheticGateway().use { gw ->
            assertTrue(gw.setReaction("om_r1", "THUMBSUP", true))
            assertTrue(gw.setReaction("om_r1", "THUMBSUP", false))
            assertEquals(
                listOf(RecordedReaction("om_r1", "THUMBSUP", true), RecordedReaction("om_r1", "THUMBSUP", false)),
                gw.recordedReactions,
            )
            gw.reactionResult = false
            assertFalse(gw.setReaction("om_r2", "OK", true))
            assertEquals(3, gw.recordedReactions.size, "失败也留记录")
            assertEquals("om_r2", gw.recordedReactions.last().messageId)
        }
    }

    @Test
    fun `registerApp 默认回调 QR 与合成凭据；registerHandler 可完全接管`() = runBlocking {
        SyntheticGateway().use { gw ->
            val qrs = mutableListOf<Pair<String, Int>>()
            val got = gw.registerApp { url, sec -> qrs.add(url to sec) }
            assertEquals(listOf(SyntheticGateway.DEFAULT_QR_URL to SyntheticGateway.DEFAULT_QR_EXPIRE_SECONDS), qrs)
            assertEquals(SyntheticGateway.DEFAULT_APP_ID, got.appId)
            assertEquals(SyntheticGateway.DEFAULT_APP_SECRET, got.appSecret)

            // 自定义：真实扫码流的「等用户在飞书 App 上确认」用替身模拟
            gw.registerHandler = { onQr ->
                onQr("https://open.feishu.cn/custom/qr", 42)
                LarkCredential("cli_custom", "secret_custom", "lark")
            }
            val customQrs = mutableListOf<Pair<String, Int>>()
            val custom = gw.registerApp { url, sec -> customQrs.add(url to sec) }
            assertEquals(listOf("https://open.feishu.cn/custom/qr" to 42), customQrs)
            assertEquals(LarkCredential("cli_custom", "secret_custom", "lark"), custom)
            assertEquals(0, gw.connectCount, "registerApp 不是 connect")
        }
    }

    @Test
    fun `failingRegisterHandler 抛 GatewayException（契约里唯一允许抛的成员，测 RegisterFailed 到 RegisterAppFailed 的映射）`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.registerHandler = SyntheticGateway.failingRegisterHandler(GatewayErrorCode.RegisterFailed, "扫码超时")
            val error: GatewayException = try {
                gw.registerApp { _, _ -> }
                throw AssertionError("必须抛 GatewayException")
            } catch (thrown: GatewayException) {
                thrown
            }
            assertEquals(GatewayErrorCode.RegisterFailed, error.code)
            assertEquals("扫码超时", error.message)
            // 🔴 契约交叉校验：网关侧 RegisterFailed 必须能映射到 lib 侧的 RegisterAppFailed（GatewayErrorMapping 穷尽 when）
            assertEquals(
                com.jiaoay.lark.bridge.config.BridgeErrorCode.RegisterAppFailed,
                com.jiaoay.lark.bridge.config.GatewayErrorMapping.toBridge(GatewayErrorCode.RegisterFailed),
            )
        }
    }

    // ================================================================== ⑥ 🔴 入站丢弃可观测（与生产同形）

    @Test
    fun `🔴 emitInbound 满时用占用计数推断丢弃：计数递增并发 Dropped(inbound, n) 信号`() = runBlocking {
        SyntheticGateway(inboundCapacity = 4).use { gw ->
            val signals = async(Dispatchers.Default) { gw.signals.take(2).toList() }
            delay(30L)

            // 🔴 无消费者 ⇒ 第 5、6 条必然挤掉最旧（DROP_OLDEST 的 trySend 恒成功，只能靠占用计数判丢弃）
            val accepted = (1..6).map { i -> gw.emitInbound(msg(messageId = "om_$i")) }
            assertEquals(listOf(true, true, true, true, false, false), accepted)
            assertEquals(6L, gw.inboundEmitCount, "emit 计数统计全部调用（含被挤掉的）")
            assertEquals(2L, gw.inboundDroppedCount, "🔴 D18：丢弃必须可观测")

            val dropped = withTimeoutOrNull(3_000L) { signals.await() }
            assertNotNull(dropped, "每次丢弃都必须发一条 Dropped 信号")
            assertEquals(
                listOf(GatewaySignal.Dropped("inbound", 1L), GatewaySignal.Dropped("inbound", 2L)),
                dropped,
                "droppedTotal 必须单调递增（累计量而非增量）",
            )

            // 🔴 消费者把 4 条取走 ⇒ 占用回落 ⇒ 再次入队不丢
            val drained = async(Dispatchers.Default) { gw.inbound.take(4).toList() }
            val got = withTimeoutOrNull(3_000L) { drained.await() }
            assertNotNull(got)
            // DROP_OLDEST 挤掉了 om_1/om_2 ⇒ 通道里留的是最新的 4 条
            assertEquals(listOf("om_3", "om_4", "om_5", "om_6"), got.map { it.messageId })
            assertTrue(gw.emitInbound(msg(messageId = "om_7")), "占用回落后必须重新可入队")
            assertEquals(2L, gw.inboundDroppedCount, "没有新丢弃")
        }
    }

    @Test
    fun `🔴 INBOUND_WHERE 与 SdkLarkGateway 的 where 标签逐字一致，且不同于 InboundQueue-WHERE`() {
        assertEquals("inbound", SyntheticGateway.INBOUND_WHERE)
        // 🔴 桥接侧第二个有界队列的标签必须是另一个值，两者不得混用（否则排障分不清是哪一级溢出）
        assertEquals("intake", InboundQueue.WHERE)
        assertFalse(SyntheticGateway.INBOUND_WHERE == InboundQueue.WHERE)
        assertEquals(InboundQueue.CAPACITY, SyntheticGateway.INBOUND_CAPACITY, "两级入站队列容量同值 512")

        // 🔴 与生产网关的源码交叉校验：SdkLarkGateway 必须真的用 inbound 这个字面值
        val gatewaySource = File(
            testRepoRoot(),
            "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt",
        )
        assertTrue(gatewaySource.isFile, "网关源文件必须存在（否则本断言无意义）：$gatewaySource")
        val literal = "GatewaySignal.Dropped(\"${SyntheticGateway.INBOUND_WHERE}\""
        assertTrue(
            gatewaySource.readText(Charsets.UTF_8).contains(literal),
            "SdkLarkGateway 必须产出与替身同一张 where 表：找不到 $literal",
        )
    }

    @Test
    fun `emitAll 返回未被挤掉的条数，且逐条计入 inboundEmitCount`() = runBlocking {
        SyntheticGateway(inboundCapacity = 8).use { gw ->
            // 🔴 先不挂消费者：占用只增不减 ⇒ 断言确定（并发消费会让「 accepted 条数」变成竞态值）
            val accepted = gw.emitAll((1..20).map { msg(messageId = "om_b$it") })
            assertEquals(8, accepted, "容量 8 ⇒ 前 8 条入队，后 12 条挤掉最旧")
            assertEquals(20L, gw.inboundEmitCount)
            assertEquals(12L, gw.inboundDroppedCount)

            val drained = async(Dispatchers.Default) { gw.inbound.take(8).toList() }
            val got = withTimeoutOrNull(3_000L) { drained.await() }
            assertNotNull(got)
            assertEquals((13..20).map { "om_b$it" }, got.map { it.messageId }, "留下的是最新 8 条")
            // 占用回落到 0 ⇒ 再来 8 条全部入队
            assertEquals(8, gw.emitAll((21..28).map { msg(messageId = "om_b$it") }))
            assertEquals(12L, gw.inboundDroppedCount, "没有新丢弃")
        }
    }

    @Test
    fun `emitSignal 满即丢不递归发 Dropped（M-17：低频信号，丢弃计数归 metrics 域）`() = runBlocking {
        SyntheticGateway(signalCapacity = 2).use { gw ->
            // 🔴 信号通道同样是 DROP_OLDEST ⇒ trySend 恒成功（挤掉最旧），只在通道 close 后才返回 false。
            //    这就是 M-17「signal 通道丢弃计数归 M4 metrics 域」的原因：丢弃在通道内部发生，无法从返回值判定。
            val accepted = (1..6).map { gw.emitSignal(GatewaySignal.Reconnecting) }
            assertEquals(List(6) { true }, accepted, "DROP_OLDEST 下 emitSignal 只在关闭后才可能返回 false")
            assertEquals(6L, gw.signalEmitCount, "🔴 计数统计全部调用")

            // 🔴 关键：信号通道溢出**不得**再往自己发 Dropped（否则递归自激）；通道里只应剩最新的 2 条
            val drained = async(Dispatchers.Default) { gw.signals.take(2).toList() }
            assertEquals(
                listOf(GatewaySignal.Reconnecting, GatewaySignal.Reconnecting),
                withTimeoutOrNull(3_000L) { drained.await() },
                "信号通道里只应有原始信号，没有任何 Dropped",
            )

            gw.close()
            assertFalse(gw.emitSignal(GatewaySignal.Reconnected), "关闭后才返回 false")
            assertEquals(7L, gw.signalEmitCount)
        }
    }

    // ================================================================== ⑦ 记录面维护

    @Test
    fun `resetRecords 清空出站记录但不动计数与连接态`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.connectResult = GatewayConnectResult.Connected("ou_b", "B")
            gw.connect(cred, LarkPolicy())
            gw.send(chat, Outbound.text("one"))
            gw.setReaction("om_x", "OK", true)
            gw.openCardStream(chat, CardSpec(title = "T", markdown = ""))
            gw.downloadResource("om_y", "k", tempDir("reset").resolve("f.bin"))
            assertEquals(1, gw.recordedSends.size)

            gw.resetRecords()
            assertTrue(gw.recordedSends.isEmpty())
            assertTrue(gw.recordedReactions.isEmpty())
            assertTrue(gw.recordedStreams.isEmpty())
            assertTrue(gw.recordedDownloads.isEmpty())
            assertTrue(gw.recordedPolicies.isEmpty())
            assertTrue(gw.recordedCredentials.isEmpty())
            assertTrue(gw.outboundTexts.isEmpty())
            assertTrue(gw.outboundCards.isEmpty())

            // 🔴 不影响生命周期计数与连接态（一个测试内多轮断言靠这一点）
            assertEquals(1, gw.connectCount)
            assertEquals(GatewayConnectionState.Connected("ou_b", "B"), gw.connectionState.value)
            assertTrue(gw.send(chat, Outbound.text("two")).ok, "清空后仍可继续出站")
            assertEquals(listOf("two"), gw.outboundTexts)
        }
    }

    @Test
    fun `记录面返回快照而非活引用（并发追加不得让断言方读到半更新状态）`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.send(chat, Outbound.text("a"))
            val snapshot = gw.recordedSends
            assertEquals(1, snapshot.size)
            gw.send(chat, Outbound.text("b"))
            assertEquals(1, snapshot.size, "🔴 已取出的列表必须是快照，不随后续出站变化")
            assertEquals(2, gw.recordedSends.size)
        }
    }

    @Test
    fun `toString 含状态与关键计数，且不含凭据明文（日志可排障又不泄漏）`() = runBlocking {
        SyntheticGateway().use { gw ->
            gw.connectResult = GatewayConnectResult.Connected("ou_b", "B")
            gw.connect(cred, LarkPolicy())
            gw.send(chat, Outbound.text("hello"))
            gw.emitInbound(msg(text = "x"))
            val rendered = gw.toString()
            assertTrue(rendered.contains("Connected"), "状态要能一眼看见：$rendered")
            assertTrue(rendered.contains("sends=1"), rendered)
            assertTrue(rendered.contains("inbound=1"), rendered)
            assertTrue(rendered.contains("dropped=0"), rendered)
            assertFalse(rendered.contains("secret_do_not_leak"), "🔴 R17：toString 不得泄漏 appSecret")
        }
    }
}
