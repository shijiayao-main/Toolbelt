package com.jiaoay.lark.bridge.config

import kotlinx.serialization.json.Json

/**
 * bridge-config.json 专用配方（[CDS §2]，唯一定义位置）。
 * 🔴 只用于配置文档；严禁被 audit.jsonl / sender_stats.jsonl 复用（config-domain-spec.md §16.7 禁令，用下方 [AuditJson]）。
 */
val BridgeJson = Json {
    ignoreUnknownKeys = true // 前向兼容：新版本写的键不被旧版本吃掉
    encodeDefaults = true // 整体写回时字段完整，便于人肉审阅
    explicitNulls = false // null 字段不写键
    classDiscriminator = "kind" // routes sealed 多态的判别字段
    prettyPrint = true // 人可读（15-30KB 文档，磁盘代价可忽略）
}

/**
 * 审计/统计 JSONL 专用配方（[CDS §16.7]），与 [BridgeJson] **严格分离、禁止互相引用**。
 *
 * 🔴 配方本身在此固化，防止后人误用 BridgeJson 破坏审计兼容性：
 * - encodeDefaults=false ⇒ 可空字段为 null 时【缺键】（与存量旧 schema 的缺键约定同源）；
 * - prettyPrint=false ⇒ jsonl 一行一条（BridgeJson 的 prettyPrint 会让 JsonlTailReader 按行解析直接崩）；
 * - 不设 classDiscriminator ⇒ 审计记录非多态 sealed，设了会污染 schema。
 */
val AuditJson = Json {
    ignoreUnknownKeys = true // 向后兼容旧审计行
    encodeDefaults = false // null 字段缺键
    explicitNulls = false // 同上，双保险
    prettyPrint = false // jsonl 必须单行紧凑
}
