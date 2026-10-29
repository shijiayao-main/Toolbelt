// :lark-gateway 出站映射纯函数；本文件签名禁 SDK 类型（gateway-contract.md §6.0 纪律 1），且不 import Gson（gateway-contract.md §15.2 M-2）。
// Outbound / CardSpec → 卡片 JSON（Map 形态给 cardkit）+ content JSON 字符串（给 im.message.create）。
package com.jiaoay.lark.gateway

/**
 * 出站映射（纯函数、无状态）。
 * 自带纯 Kotlin 最小 JSON 编码器 [encodeJson]，满足「mapper 零 SDK」+「LarkOutboundMapperTest 脱 SDK 单测转义往返」。
 * 🔴 唯一允许抛异常的点——streaming == true && buttons.isNotEmpty() ⇒ IllegalArgumentException（飞书 200810 硬互斥）。
 */
object LarkOutboundMapper {

    /**
     * 整卡 JSON（Map 形态，交给 cardkit create / im.message.create 的 content 字段）。
     * 🔴 字面值构成飞书卡片契约，一个都不能改。schema 必须 "2.0"（1.0 的 markdown 只支持子集）。
     * elementId 默认 Tuning.streamElementId，兼容单参/双参两种调用。
     * 🔴 首版**不启用** streaming_config（打字机动画）——实测 print_step 标量 ⇒ 10002、step ⇒ 11311，
     *    官方 PrintStepConfig 结构未核对清楚；250ms 节流已足够平滑，非流式必需，待结构核对后再启用（非阻塞）。config 键仅 streaming_mode/update_multi/summary。
     */
    fun toCardJson(spec: CardSpec, elementId: String = Tuning.streamElementId): Map<String, Any?> {
        // 流式卡禁放交互组件（飞书 200810 硬互斥，静默降级会让确认链路卡死到超时）——快速失败。
        require(!(spec.streaming && spec.buttons.isNotEmpty())) { "流式卡内禁放交互组件（飞书 200810）" }

        val elements = mutableListOf<Map<String, Any?>>()
        elements.add(linkedMapOf("tag" to "markdown", "element_id" to elementId, "content" to spec.markdown))
        // button 追加到 markdown 元素之后（gateway-contract.md §6.2.3）。🔴 behaviors 数组，禁 action 包裹（否则 230099）。
        for (b in spec.buttons) {
            elements.add(
                linkedMapOf(
                    "tag" to "button",
                    "text" to linkedMapOf("tag" to "plain_text", "content" to b.text),
                    "type" to if (b.primary) "primary" else "default",
                    "behaviors" to listOf(linkedMapOf("type" to "callback", "value" to linkedMapOf("key" to b.value))),
                )
            )
        }

        val config = linkedMapOf<String, Any?>(
            "streaming_mode" to spec.streaming,
            "update_multi" to true,
            // 🔴 首版**不启用** streaming_config（打字机动画；真机实测见上）。
            //    实测 print_step:5(标量) ⇒ 10002 "failed to unmarshal PrintStepConfig, type float64"；step:5 ⇒ 11311 "printStep is nil"
            //    ⇒ print_step 需要 PrintStepConfig 对象而非标量，官方结构未核对清楚。服务端 250ms 节流已实现平滑刷新，打字机动画非流式必需。
            //    待官方 PrintStepConfig 结构核对后再启用（非阻塞）。
            "summary" to linkedMapOf("content" to (spec.summary ?: spec.markdown.take(80))),
        )

        return linkedMapOf(
            "schema" to "2.0",
            "config" to config,
            "header" to linkedMapOf(
                "title" to linkedMapOf("tag" to "plain_text", "content" to spec.title),
                "template" to spec.titleColor.template,
            ),
            "body" to linkedMapOf("elements" to elements),
        )
    }

    /**
     * 流式卡的局部元素 JSON（供 cardkit patch 的 partial_element）。
     * 🔴 真机四组对照实证：partial_element **绝不能含 "tag" 字段**。
     *    P1{tag,element_id,content}=300312✗ / P2{element_id,content}=0✓ / P3{content}=0✓ / P4{tag,content}=300312✗ ⇒ 只要带 tag 就失败
     *    （300312 "ErrMsg: tag cannot be updated"）。采用 P2 形态：保留 element_id 更明确，键集合精确等于 {element_id, content}。
     */
    fun toPartialElementJson(markdown: String, elementId: String = Tuning.streamElementId): Map<String, Any?> =
        linkedMapOf("element_id" to elementId, "content" to markdown)

    /**
     * 结束流式的 settings JSON：streaming_mode = false。供 cardkit settings 用（gateway-contract.md §6.2.1 / gateway-contract.md §4.2 ④）。
     * gateway-contract.md §15.2 M-4b：finalText 非空时补 summary.content（取前 80 字），与 gateway-contract.md §4.2 line 1052-1053 一致；
     *    null 时只置 streaming_mode=false（兼容 gateway-contract.md §6.2.1 的单参签名）。
     */
    fun toFinishSettingsJson(finalText: String? = null): Map<String, Any?> {
        val config = linkedMapOf<String, Any?>("streaming_mode" to false)
        if (finalText != null) config["summary"] = linkedMapOf("content" to finalText.take(80))
        return linkedMapOf("config" to config)
    }

    /**
     * 终态卡的 markdown 文本。终态文案与配色的渲染归 :lark-bridge 的 ProgressCardRenderer，
     * 网关只忠实取 CardSpec.markdown；若为空则回退到当前流式快照 fallback，避免终态卡空白。
     */
    fun toTerminalMarkdown(card: CardSpec, fallback: String): String = card.markdown.ifBlank { fallback }

    /** 按引用发送卡片时的 content 形态：{"type":"card","data":{"card_id":...}}。 */
    fun toCardRefContent(cardId: String): Map<String, Any?> =
        linkedMapOf("type" to "card", "data" to linkedMapOf("card_id" to cardId))

    /**
     * msg_type 字面值。markdown → "interactive"，否则 "text"。FILE → "file"；IMAGE → "image"。
     */
    fun msgTypeOf(kind: OutboundKind): String = when (kind) {
        OutboundKind.TEXT -> "text"
        OutboundKind.MARKDOWN, OutboundKind.CARD -> "interactive"
        OutboundKind.FILE -> "file"
        OutboundKind.IMAGE -> "image"
    }

    /**
     * content 字符串（im.message.create 的 body.content 是 JSON 字符串，不是对象）。
     * markdown 一律包成卡片（1.0 的 post 富文本渲染不全）。
     * 文本分片不在 mapper 内做（由 SDK OutboundConfig.textChunkLimit=3500 内部分片，gateway-contract.md §6.2.4）。
     */
    fun contentJsonOf(out: Outbound): String = when (out.kind) {
        OutboundKind.TEXT -> encodeJson(linkedMapOf("text" to (out.text ?: "")))
        OutboundKind.MARKDOWN -> encodeJson(toCardJson(CardSpec(title = "", markdown = out.text ?: "", streaming = false)))
        OutboundKind.CARD -> encodeJson(toCardJson(out.card!!))
        // FILE 由 SdkLarkGateway.sendFileByKey 直发（raw client msg_type="file"），content 形态在此唯一拼装
        // 🔴 不可走 SDK SendInput.file——其首参语义是本地路径/URL，file_key 必抛 UPLOAD_FAILED
        OutboundKind.FILE -> encodeJson(linkedMapOf("file_key" to (out.file?.fileKey ?: "")))
        // IMAGE：同 FILE 的 raw client 形态，msg_type="image" + content={"image_key":…}
        OutboundKind.IMAGE -> encodeJson(linkedMapOf("image_key" to (out.image?.imageKey ?: "")))
    }

    // ==================== 纯 Kotlin 最小 JSON 编码器（gateway-contract.md §15.2 M-2，零 SDK / 零 Gson）====================

    /** 把 Map/List/String/Number/Boolean/null 编码成紧凑 JSON 字符串（RFC 8259 转义）。 */
    internal fun encodeJson(value: Any?): String {
        val sb = StringBuilder()
        writeJson(value, sb)
        return sb.toString()
    }

    private fun writeJson(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> writeJsonString(value, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeJsonString(k.toString(), sb)
                    sb.append(':')
                    writeJson(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeJson(e, sb)
                }
                sb.append(']')
            }
            else -> writeJsonString(value.toString(), sb)
        }
    }

    private fun writeJsonString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}
