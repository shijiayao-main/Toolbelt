// S1 · 「不静默丢失」：未支持消息类型的明确降级文案。
// 背景：InboundKind 实为 12 值，但 BuiltinHandlers 只对 TEXT（6 处）与 FILE（1 处）分支，
//    其余 9 种（IMAGE/AUDIO/VIDEO/POST/MERGE_FORWARD/STICKER/SHARE_CHAT/SHARE_USER/UNKNOWN）全部
//    落 Admission.runUnhandled ⇒ 群里发图 @机器人 = 零响应零提示——用户以为机器人坏了
//    （「任务超时后手动重试」的真实痛点同族）。
// 设计：
//    - **不新增配置字段**：降级文案走本文件的固定常量，避开 BridgeFieldsTest 的「BASIC 恰 5、
//      ADVANCED 恰 65」断言与 schema 迁移面；
//    - 文案为固定常量，无用户自定义通道（旧「用户模板优先」的兜底模板配置域已退役）；
//    - **穷尽 when 无 else**（先例：Admission.taskKindOf）：InboundKind 新增第 13 值时本文件编译失败，
//      强制补文案——「新增 kind 忘了告知用户」这一类静默从结构上不可能。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.InboundKind

/** 已有 handler 的 kind（文案由各自 handler 负责，不走未支持降级）。 */
private val HANDLED_KINDS = setOf(InboundKind.TEXT, InboundKind.FILE, InboundKind.CARD_ACTION)

/**
 * 「可识别但未支持」的入站 kind ⇒ 明确降级文案；null ⇒ 维持原语义（静默留痕）。
 *
 * 判定序（Admission.runUnhandled 按此消费）：
 * 1. kind 已有 handler ⇒ null（不该走到 runUnhandled；防御返回 null 不发文案）；
 * 2. 未知兜底 kind（UNKNOWN）⇒ null（无法解释的输入不发猜测性文案，审计已留痕）；
 * 3. 其余 9 种 ⇒ 一条「看得见、说明白、给出路」的降级文案。
 */
internal fun unsupportedKindNotice(kind: InboundKind): String? {
    if (kind in HANDLED_KINDS || kind == InboundKind.UNKNOWN) return null
    return "收到${kindLabel(kind)}，但暂不支持该消息类型～ 我目前能处理：文字、文件（日志/压缩包等）、卡片按钮。\n" +
        "试试把内容转成文字或文件发我？"
}

/**
 * [InboundKind] 的人读标签。🔴 穷尽 `when` 无 `else`：新增第 13 个 kind 时本函数编译失败，
 * 强制同步补标签（与 [unsupportedKindNotice] 同一道闸门；先例：`Admission.taskKindOf`）。
 */
internal fun kindLabel(kind: InboundKind): String = when (kind) {
    InboundKind.TEXT -> "文字消息"
    InboundKind.IMAGE -> "图片"
    InboundKind.FILE -> "文件"
    InboundKind.AUDIO -> "语音"
    InboundKind.VIDEO -> "视频"
    InboundKind.POST -> "富文本（图文混排）"
    InboundKind.MERGE_FORWARD -> "合并转发的聊天记录"
    InboundKind.STICKER -> "表情包"
    InboundKind.SHARE_CHAT -> "群名片分享"
    InboundKind.SHARE_USER -> "用户名片分享"
    InboundKind.CARD_ACTION -> "卡片按钮"
    InboundKind.UNKNOWN -> "无法识别的消息"
}
