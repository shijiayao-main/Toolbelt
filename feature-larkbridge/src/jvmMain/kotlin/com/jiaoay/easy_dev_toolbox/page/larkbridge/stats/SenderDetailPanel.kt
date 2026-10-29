package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.llm.KoogAgentStylePreset
import com.jiaoay.easy_dev_toolbox.core.llm.KoogUserStyleMap
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AvatarImage
import com.jiaoay.easy_dev_toolbox.widget.ChoiceField
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.bridge.observe.SenderStatsEntry
import kotlinx.coroutines.launch

/**
 * 用户详情二级页：四卡 = 用户信息 / 回复性格 / 访问状态 / 对话记录。
 * 🔴 对话原文不落盘（一旦落盘即不可逆的隐私暴露）——记录只显 audit 的 200 字摘要级字段。
 *
 * internal：由 [UsageStatsPanel] 页签内 [AnimatedContent]（`selectedOpenId`）切换进入；
 * 统计行/耗时文案走同包共享件 [StatLine] / [formatElapsed]（归属 UsageStatsPanel.kt）。
 */
@Composable
internal fun SenderDetailPanel(
    openId: String,
    senderEntries: List<SenderStatsEntry>,
    auditEntries: List<AuditEntryJson>,
    access: AccessConfig?,
    onAccessChange: (AccessConfig) -> Unit,
    onBack: () -> Unit,
) {
    val detail = remember(openId, senderEntries, auditEntries) {
        UsageStats.buildSenderDetail(openId, senderEntries, auditEntries)
    }
    if (detail == null) {
        // 理论不可达（列表行才可点进来）；防御空态
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
            TextButton(onClick = onBack) { Text("← 返回用户列表") }
            EmptyStateView(text = "该用户暂无记录", modifier = Modifier.fillMaxWidth(), fillParent = false)
        }
        return
    }

    val colors = LocalEasyToolboxColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
        TextButton(onClick = onBack) { Text("← 返回用户列表") }

        // ── 卡 1：用户信息 ──
        SectionCard(title = "用户信息", modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                AvatarImage(
                    url = detail.avatarUrl,
                    fallbackName = detail.userName.ifBlank { detail.senderOpenId },
                    size = Dimens.avatarProfile,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    Text(
                        text = detail.userName.ifBlank { detail.senderOpenId },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = detail.senderOpenId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CopyIconButton(
                            textProvider = { detail.senderOpenId },
                            contentDescription = "复制发送者 openId",
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                StatLine("总消息数", detail.messageCount.toString())
                StatLine("被拒次数", detail.rejectedCount.toString())
                StatLine("首次活跃", formatTimestamp(detail.firstActiveMillis))
                StatLine("最近活跃", formatTimestamp(detail.lastActiveMillis))
            }
        }

        // ── 卡 2：回复性格（per-user 档） ──
        SenderStyleCard(openId = openId)

        // ── 卡 3：信任状态（免确认授权快捷动作）──
        // 准入恒放行，此卡只回答「该用户的高风险操作是否免逐次确认」。
        if (access != null) {
            SectionCard(title = "信任状态", modifier = Modifier.fillMaxWidth()) {
                val trusted = access.trustedUsers.any { it.trim() == openId.trim() }
                val surface = LocalToolboxSurfaceColors.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    Text(
                        "信任用户",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(Dimens.fieldLabelWidth),
                    )
                    PillLabel(
                        text = if (trusted) "已信任" else "未信任",
                        container = if (trusted) colors.successContainer else surface.surfaceDeep,
                        contentColor = if (trusted) colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppButton(
                        label = if (trusted) "移出信任名单" else "加入信任名单",
                        busy = false,
                        kind = AppButtonKind.Secondary,
                        onClick = {
                            val action = if (trusted) {
                                SenderAccessAction.REMOVE_FROM_TRUSTED
                            } else {
                                SenderAccessAction.ADD_TO_TRUSTED
                            }
                            onAccessChange(UsageStats.applySenderAccess(access, openId, action))
                        },
                    )
                }
                Text(
                    text = "信任用户的高风险操作免逐次确认；未信任用户的越界/破坏性操作会被直接拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 卡 4：对话记录（audit 摘要级；🔴 原文不落盘——落盘即不可逆隐私暴露） ──
        SectionCard(title = "对话记录（最近 ${detail.records.size} 条摘要）", modifier = Modifier.fillMaxWidth()) {
            if (detail.records.isEmpty()) {
                EmptyStateView(text = "该用户暂无对话记录", modifier = Modifier.fillMaxWidth(), fillParent = false)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    if (detail.auditTruncated) {
                        Text(
                            text = "已截断（仅显示最近 ${UsageStats.SENDER_DETAIL_MAX_AUDIT} 条）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    detail.records.forEach { record -> SenderAuditRow(record) }
                }
            }
        }
    }
}

/** 卡 2 独立拆出：per-user 性格档（读映射 + 整表写）。 */
@Composable
private fun SenderStyleCard(openId: String) {
    val scope = rememberCoroutineScope()
    // 订阅式读（勿改回一次性快照读——openId 不变时 producer 不会重启，
    // onSelect 落盘后选中态将永不回显，点击看似无效）；null = 「跟随全局默认」哨兵。
    // 落盘成功才发射 ⇒ 选中态只在写真实生效后翻转（写失败保持旧档，不虚报已生效）。
    val currentStyle by produceState<String?>(initialValue = null, openId) {
        LlmConfigStore.agentStyleUserMapFlow().collect { entries ->
            value = KoogUserStyleMap.styleIdOf(entries, openId)
        }
    }
    val options = remember {
        // 「跟随全局默认」哨兵 "" 首项 + 10 档中 filter 掉 CUSTOM（per-user 不支持自定义档）
        listOf("") + KoogAgentStylePreset.entries.filter { it != KoogAgentStylePreset.CUSTOM }.map { it.id }
    }
    val labels = remember {
        mapOf("" to "跟随全局默认") + KoogAgentStylePreset.entries
            .filter { it != KoogAgentStylePreset.CUSTOM }
            .associate { it.id to it.label }
    }
    // 卡头「回复性格」+
    // chead 行尾 pill ai「只对该用户生效」（--ai×--ai-c 紫档）
    SectionCard(
        title = "回复性格",
        modifier = Modifier.fillMaxWidth(),
        headerTrailing = {
            PillLabel(
                text = "只对该用户生效",
                container = LocalEasyToolboxColors.current.aiAccentContainer,
                contentColor = LocalEasyToolboxColors.current.aiAccent,
            )
        },
    ) {
        ChoiceField(
            label = "该用户消息的回复性格档",
            options = options,
            selected = currentStyle ?: "",
            onSelect = { selected ->
                scope.launch {
                    val entries = LlmConfigStore.getAgentStyleUserMap()
                    val next = if (selected.isBlank()) {
                        KoogUserStyleMap.remove(entries, openId)
                    } else {
                        KoogUserStyleMap.upsert(entries, openId, selected)
                    }
                    LlmConfigStore.setAgentStyleUserMap(next)
                }
            },
            supportingText = "只对该用户生效；委托 CLI 的重任务不受影响；未配置用户走全局档（AI 设置页）",
            labels = labels,
        )
    }
}

/** 对话记录单行：时间 / 类型 / 指令摘要 / 状态 / 耗时。 */
@Composable
private fun SenderAuditRow(record: SenderAuditRecord) {
    // 状态英文枚举直出 → 中文语义胶囊（PillLabel 三态色对——success/error/mute）
    val statusLabel = when (record.status) {
        UsageStats.STATUS_SUCCESS -> "成功"
        UsageStats.STATUS_FAILURE -> "失败"
        UsageStats.STATUS_SKIPPED -> "跳过"
        else -> record.status
    }
    val statusColors = when (record.status) {
        UsageStats.STATUS_SUCCESS ->
            LocalEasyToolboxColors.current.successContainer to LocalEasyToolboxColors.current.success
        UsageStats.STATUS_FAILURE ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else ->
            LocalToolboxSurfaceColors.current.surfaceDeep to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${formatTimestamp(record.ts)} · ${record.kindLabel} · ${record.backendLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PillLabel(
                text = statusLabel,
                container = statusColors.first,
                contentColor = statusColors.second,
            )
            Text(
                text = " · ${formatElapsed(record.elapsedMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = record.prompt,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}

/** epoch millis → 本地时间（yyyy-MM-dd HH:mm）；null 显「未知」。 */
private fun formatTimestamp(millis: Long?): String =
    if (millis == null) "未知" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
