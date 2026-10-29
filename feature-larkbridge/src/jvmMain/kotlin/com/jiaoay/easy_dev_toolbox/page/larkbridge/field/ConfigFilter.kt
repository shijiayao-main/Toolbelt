package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields

/**
 * 配置工具条的过滤态纯函数（结构真源登记文件见 docs/architecture.md「注释治理」节）。
 */
object ConfigFilter {

    /** @modified 保留字（大小写不敏感）。 */
    const val MENTION_MODIFIED = "@modified"

    /** @restart 保留字（大小写不敏感）。 */
    const val MENTION_RESTART = "@restart"

    /** 过滤结果：命中的字段声明 + 其所在分区标题（平铺结果行的「分区名前缀」）。 */
    data class Match(val field: BridgeField<*>, val zoneTitle: String)

    fun parse(query: String, config: com.jiaoay.lark.bridge.config.BridgeConfig): FilterState? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return when (q.lowercase()) {
            MENTION_MODIFIED -> FilterState.Modified(
                BridgeFields.ALL.filter { P5FieldGroups.isModified(it, config) },
            )
            MENTION_RESTART -> FilterState.Restart(
                BridgeFields.ALL.filter { it.applyMode == com.jiaoay.lark.bridge.config.ApplyMode.RESTART },
            )
            else -> {
                val needle = q.lowercase()
                val zoneTitleOf = P5FieldGroups.ZONES
                    .flatMap { zone -> zone.fieldIds.map { it to zone.title } }
                    .toMap()
                val hits = BridgeFields.ALL.filter { field ->
                    field.label.lowercase().contains(needle) ||
                        field.hint.lowercase().contains(needle) ||
                        field.id.lowercase().contains(needle)
                }
                FilterState.Keyword(hits.map { Match(it, zoneTitleOf[it.id] ?: "") })
            }
        }
    }

    /** 过滤态（sealed：三态互斥，UI 逐态渲染）。 */
    sealed interface FilterState {
        /** @modified：已修改字段（无分区归属语义——横切面）。 */
        data class Modified(val fields: List<BridgeField<*>>) : FilterState

        /** @restart：须重启生效字段（横切面）。 */
        data class Restart(val fields: List<BridgeField<*>>) : FilterState

        /** 关键词：命中字段 + 所在分区标题。 */
        data class Keyword(val matches: List<Match>) : FilterState
    }
}
