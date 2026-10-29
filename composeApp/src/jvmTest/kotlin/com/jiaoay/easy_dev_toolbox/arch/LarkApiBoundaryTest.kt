// L3 恒排除守护——负清单升格为红构建门禁。
//
// 🔴 这是「负清单」守护：MessageGateway 的成员名层面——新增成员若落在 L3 能力面（替用户身份 /
//    凭证读取 / 群生命周期 update·delete·解散·改名 / 踢人 / 全局搜索 / pin），本测试红。
//    与 SyntheticGatewayTest 的「29 成员正向穷尽」互补：那边钉「有哪些」，这边钉「不允许有哪些」。
//
// 🔴 L3 清单来源 = 设计稿 L3 定义：
//    替用户身份（user-token OAuth / authen/v1）、凭证读取、群生命周期（update/delete/解散/改名）、
//    踢人（removeChatMembers/deleteChatMembers）、全局搜索（search.v2 仅 user-token）、
//    pin 消息（scope 未确认一并延后）。
//
// 🔴 归一化配方：本测试不扫源码、只反射接口成员名（normalizeKotlinSource 不适用）；扫盘与
// 白名单基线在 ChannelSeamGuardTest。kotlin.test 框架、测试名无 `/` 与 `.`。
package com.jiaoay.easy_dev_toolbox.arch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LarkApiBoundaryTest {

    // ── A 组：L3 负清单（成员名层面，反射 MessageGateway::class 全部成员）──

    /**
     * A：MessageGateway 成员名不得落在 L3 能力面。
     *
     * 反射取 `MessageGateway::class.java` 全部声明方法（含属性 getter）+ Kotlin 属性，按名字归集，
     * 对每条 L3 禁入规则断言零命中。命名规则用**词元级**匹配（camelCase 拆词），避免子串误报
     * （如 `updateDownloadSettings` 含 "update" 但它是 download 域热更、与群生命周期无关——
     * 按「update + Chat/Life」词元共现判定，不按裸子串）。
     */
    @Test
    fun `A message gateway has no L3 capability members`() {
        val gateway = Class.forName(GATEWAY_INTERFACE_FQCN)
        val memberNames = gateway.methods.map { it.name }
            .plus(gateway.fields.map { it.name })
            .filter { !it.contains('$') }
            .distinct()
            .sorted()
        assertTrue(
            memberNames.size >= MIN_GATEWAY_MEMBERS,
            "MessageGateway 成员数异常（${memberNames.size} < $MIN_GATEWAY_MEMBERS）——接口定位/反射失效，守护静默化风险",
        )

        val offenders = mutableListOf<String>()
        for (name in memberNames) {
            val tokens = camelTokens(name)
            for (rule in L3_NAME_RULES) {
                if (rule.matches(name, tokens)) {
                    offenders.add("$name ⇢ ${rule.label}")
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("MessageGateway 出现 L3 能力面成员（能力一级化分级模型：L3 恒排除，无配置载体）：")
                    offenders.forEach { appendLine("  - $it") }
                    appendLine("L3 面 = 替用户身份 / 凭证读取 / 群生命周期（update/delete/解散/改名）/ 踢人 / 全局搜索 / pin。")
                    appendLine("新成员确属 L1/L2 时请改掉歧义命名（避开 L3 词元共现）；确要扩 L3 面则属产品决策——")
                    appendLine("停下回报 orchestrator，不得先斩后奏改本测试。")
                },
            )
        }
    }

    /**
     * A-2：L3 词元规则自身的健全性自检——用内联正反样本锁死匹配语义：
     * 正样本（必须命中）是 L3 成员的典型命名，负样本（必须不命中）是既有 29 成员里含相近子串的名字
     * （防规则过宽误伤既有合法成员——`updateDownloadSettings`/`downloadResource`/`updatePolicy` 等）。
     */
    @Test
    fun `A L3 name rules do not false-positive existing gateway members`() {
        // 必须命中（L3 命名形态）
        val l3Samples = listOf(
            "searchMessages", "globalSearch", "searchApp",           // 全局搜索
            "pinMessage", "unpinMessage", "updateChatPin",           // pin（含 unpin）
            "removeChatMembers", "deleteChatMembers",                // 踢人
            "deleteChat", "updateChat", "updateChatInfo",            // 群生命周期
            "searchAppMessages",
        )
        for (name in l3Samples) {
            val tokens = camelTokens(name)
            val hit = L3_NAME_RULES.firstOrNull { it.matches(name, tokens) }
            assertTrue(hit != null, "L3 样本「$name」必须被某条规则命中（规则过窄=漏守），tokens=$tokens")
        }
        // 必须不命中（既有 29 成员中与 L3 词元相近的全部成员名——实测提取）
        val legalMembers = listOf(
            "close", "connect", "createChat", "disconnect", "downloadResource", "fetchQuotedMessage",
            "getChatInfo", "getConnectionState", "getInbound", "getSignals", "inviteChatMembers",
            "listChatMembers", "listMessages", "listReactions", "openCardStream", "readDocumentText",
            "readReceipts", "recallMessage", "registerApp", "resolveSenderName",
            "resolveSenderProfile", "resolveWikiNode", "send",
            "setReaction", "updateCard", "updateDownloadSettings", "updatePolicy", "uploadFile",
            "uploadImage",
        )
        for (name in legalMembers) {
            val tokens = camelTokens(name)
            val hit = L3_NAME_RULES.firstOrNull { it.matches(name, tokens) }
            assertTrue(hit == null, "既有合法成员「$name」被规则「${hit?.label}」误伤（规则过宽）")
        }
    }

    // ── B 组：正向事实锚（防 29 成员被误删/改名而负清单空转）──

    /**
     * B：正向锚——当前 29 成员名单逐字对齐（与 `SyntheticGatewayTest` 同一名单、双处独立钉死：
     * 那边守「替身必须实现」，这边守「负清单的对照基线不漂移」）。
     * recallMessage/uploadImage 等成员在名单里显式存在——被误删即红。
     */
    @Test
    fun `B message gateway member inventory matches batch C 29 baseline`() {
        val gateway = Class.forName(GATEWAY_INTERFACE_FQCN)
        val memberNames = gateway.methods.map { it.name }
            .plus(gateway.fields.map { it.name })
            .filter { !it.contains('$') }
            .distinct()
            .sorted()
        assertEquals(
            GATEWAY_MEMBERS_BATCH_C,
            memberNames,
            "MessageGateway 契约面名单漂移（基线 29 成员）——新增/删减/改名成员须同步本名单与 SyntheticGatewayTest",
        )
        assertEquals(29, memberNames.size, "契约面恰 29 成员")
    }

    /**
     * B-2：L1/L2 能力锚——关键成员必须真实在位（抽 8 个锚：L1 出站面 uploadImage/
     * recallMessage，L2 面 listMessages/getChatInfo/createChat，resolveWikiNode/readDocumentText）。
     * 与 B 的全名单断言冗余是有意的：本组失败信息直指「哪个能力位消失」，排障更快。
     */
    @Test
    fun `B capability tier members remain in place`() {
        val gateway = Class.forName(GATEWAY_INTERFACE_FQCN)
        val memberNames = gateway.methods.map { it.name }.filter { !it.contains('$') }.toSet()
        val anchors = mapOf(
            "recallMessage" to "L1 recall_own_message 工具的 gateway 面",
            "uploadImage" to "L1 send_image 工具的 gateway 面",
            "listMessages" to "L2 fetch_chat_history 的 gateway 面",
            "getChatInfo" to "L2 get_chat_info 的 gateway 面",
            "createChat" to "L2 create_group_chat 的 gateway 面",
            "resolveWikiNode" to "L2 read_wiki_document 的 wiki 步",
            "readDocumentText" to "L2 read_wiki_document 的 docx 步",
            "setReaction" to "react_emoji 工具的 gateway 面（既有）",
        )
        anchors.forEach { (name, why) ->
            assertTrue(name in memberNames, "能力锚消失：$name（$why）——误删即红")
        }
    }

    // ── 实现 ──

    /** camelCase 拆词（小写流分段）：`removeChatMembers` → [remove, chat, members]。 */
    private fun camelTokens(name: String): List<String> =
        name.split("(?<=[a-z0-9])(?=[A-Z])".toRegex())
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }

    /** 一条 L3 禁入命名规则：词元共现判定（label 供失败信息定位）。 */
    private class L3NameRule(val label: String, val predicate: (List<String>) -> Boolean) {
        fun matches(rawName: String, tokens: List<String>): Boolean = predicate(tokens)
    }

    private companion object {
        const val GATEWAY_INTERFACE_FQCN = "com.jiaoay.lark.gateway.MessageGateway"

        /** 契约面成员数下限（基线 29；跌破即反射/定位失效） */
        const val MIN_GATEWAY_MEMBERS = 29

        /**
         * 契约面 29 成员名单（与 `SyntheticGatewayTest` 的逐字名单同源——反射名去重排序后对齐；
         * 那边是「替身必须实现」的契约穷尽，这边是「负清单对照基线不漂移」的正向锚，双处独立钉死）。
         */
        val GATEWAY_MEMBERS_BATCH_C = listOf(
            "close", "connect", "createChat", "disconnect", "downloadResource", "fetchQuotedMessage",
            "getChatInfo", "getConnectionState", "getInbound", "getSignals", "inviteChatMembers",
            "listChatMembers", "listMessages", "listReactions", "openCardStream", "readDocumentText",
            "readReceipts", "recallMessage", "registerApp", "resolveSenderName",
            "resolveSenderProfile", "resolveWikiNode", "send",
            "setReaction", "updateCard", "updateDownloadSettings", "updatePolicy", "uploadFile",
            "uploadImage",
        )

        /**
         * L3 禁入词元规则（规划稿 L3 清单的命名投影）：
         * - 全局搜索：`search` 词元（gateway 面不出现任何 search——「搜全部消息/全局」语义；既有成员零 search；
         *   词元内含子串兜底防 `researchFoo` 之类的连写形态漏守）；
         * - pin：`pin` 词元（scope 未确认，一并延后；pin/unpin 都算——词元内含子串兜底同上）；
         * - 踢人：remove+members 共现（invite=拉人是 L2 合法，remove=踢人恒 L3）；
         * - 群生命周期：update/delete 词元 + chat 对象词元共现（updateDownloadSettings/updateCard 不含 chat 不命中；
         *   deleteChat/删除群、updateChat/改名都命中；recall 是撤回消息不是群生命周期，不命中）。
         * - 替用户身份 / 凭证读取：命名层面难有稳定词元（OAuth 调用形态是「同 API 换 token」而非新成员名），
         *   由 LarkSdkIsolationTest（SDK 面）+ SensitiveFilePolicy（文件面）双轨守护，此处不设词元规则。
         */
        val L3_NAME_RULES = listOf(
            L3NameRule("L3 全局搜索（search 仅 user-token，第一轮排除）") { tokens ->
                "search" in tokens || tokens.any { it.contains("search") }
            },
            L3NameRule("L3 pin 消息（scope 未确认，延后；pin/unpin 都算）") { tokens ->
                "pin" in tokens || tokens.any { it.endsWith("pin") || it.contains("pin") }
            },
            L3NameRule("L3 踢人（remove + members 共现）") { tokens ->
                "remove" in tokens && "members" in tokens
            },
            L3NameRule("L3 群生命周期（update/delete + chat 对象词元共现；不含「chat」实义动词的 update* 不命中）") { tokens ->
                ("update" in tokens || "delete" in tokens) && "chat" in tokens
            },
        )
    }
}
