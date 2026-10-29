// M7-G-C · check 结果回显的纯函数格式化器（feature 侧，不依赖 Compose，可单测）。
// 语义与 CLI `check` 子命令逐字对齐（cli/CliSupport.formatFieldValue + maskSecret）：
//    字段 id / group / label / applyMode / 生效值 / 校验结果；secret（唯一 connection.appSecret）掩码「前 4 后 4」。
// 🔴 掩码值仅供 UI 回显，绝不写日志 / 审计（写日志即 appSecret 随日志不可逆扩散；本文件零日志调用）；UI 的 Secret 编辑器仍用全掩码 + 用户主动 reveal，
//    与此处的「前 4 后 4」是两个不同的展示场景（后者是只读总览，前者是可编辑输入）。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.status

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.Editor

/**
 * secret 掩码：前 4 后 4，中间 `*`（与 CLI `check` / config-domain-spec 附录 C / R17 同规则）。
 * 长度 <= 8 时全掩码（避免短密钥被前后 4 位还原）；空串原样返回。
 */
fun maskSecret(value: String): String {
    if (value.isEmpty()) return value
    if (value.length <= 8) return "*".repeat(value.length)
    return value.take(4) + "*".repeat(value.length - 8) + value.takeLast(4)
}

/**
 * 单字段的 check 回显值渲染（与 CLI `formatFieldValue` 同形）：
 * secret 掩码（前 4 后 4）；`Number(displayScale>1)` 附显示单位（如「字节原值 (MB)」）；
 * List 逗号连接；空白串渲染 `(空)`。
 */
fun renderFieldValue(field: BridgeField<*>, config: BridgeConfig): String {
    val value = field.get(config)
    if (field.secret) return maskSecret(value?.toString().orEmpty())
    val editor = field.editor
    if (editor is Editor.Number && editor.displayScale > 1 && value is Long) {
        return "$value (${value / editor.displayScale}${editor.unitSuffix})"
    }
    return when (value) {
        is List<*> -> if (value.isEmpty()) "(空)" else value.joinToString(", ")
        is String -> if (value.isBlank()) "(空)" else value
        null -> "(空)"
        else -> value.toString()
    }
}
