// M7-G-C · 配置校验总览面板（CLI `check` 子命令的 UI 等价物）。
// 不改 lib：check 的语义 = 配置校验 + 全字段自解释输出，lib 侧 ConfigValidator.validate()（纯函数）
//    与 BridgeFields.ALL 都是公开可达的，ViewModel 已在用 ConfigValidator 产 issues。本面板只在 feature 侧回显。
// connection.appSecret 掩码「前 4 后 4」（见 ConfigCheckFormatter，与 CLI check 同规则）；掩码值绝不入日志/审计。
// 零白名单：间距走 Dimens、卡片走 SectionCard、无裸 dp / 裸动画 / Material 形状引用 / 浮起卡片。
// 字段行 4 行瘦身至 2-3 行（224 行文字墙 → ≤112 视觉行）；
//    校验结论容器条化（通过=successContainer+CheckCircle / 未过=errorContainer+Error）。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.status

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields

/**
 * 配置校验总览：顶部校验结论容器条（PASS/FAIL + issue 数），可展开的全字段自解释表
 * （label + id 复制按钮 / 生效值（等宽）/ 校验图标）。
 *
 * 整卡收成**单行 .lnk 文字链入口**——
 * 「配置校验总览 · 通过（0 处）/ 未过 N 处 →」（primary/error 着色）；
 * 点击展开**直出全字段表**（不取折叠 SectionCard + StatusBanner +
 * 二次展开钮三层形态）。避免全字段表与
 * 六分区编辑器信息重复淹没编辑器。
 */
@Composable
fun ConfigCheckPanel(
    config: BridgeConfig,
    issues: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pass = issues.isEmpty()

    // 单行 .lnk 文字链入口（点击展开直出全字段表）。
    // ⚠️ 有意偏离（已申报）：.lnk 通例恒 primary；实现未过态着 error——
    // 「未过」是失败语义（同族红徽标/error 槽语言），改良式偏离有意保留。
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(AppShapes.capsule)
                .clickable { expanded = !expanded }
                .padding(vertical = Dimens.space4),
        ) {
            Text(
                text = if (pass) {
                    "配置校验总览 · 通过（0 处）→"
                } else {
                    "配置校验总览 · 未过 ${issues.size} 处 →"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (pass) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (expanded) {
            // 展开态直出全字段表（未过时错误分区行自带 hint-err 红字——FieldCheckRow）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                BridgeFields.ALL.forEach { field ->
                    FieldCheckRow(field = field, config = config, issue = issues[field.id])
                }
            }
        }
    }
}

/**
 * 单字段回显行（瘦身版）：行 1 = label + id 复制按钮 + group·applyMode 元数据（labelSmall）；
 * 行 2 = 生效值（等宽、单行截断）+ 校验图标；有 issue 时行 3 红字。
 * **标签置于值上方、各自满宽**（与 BridgeStatusPanel.StatusRow 同一消解白名单的布局）。
 */
@Composable
private fun FieldCheckRow(
    field: BridgeField<*>,
    config: BridgeConfig,
    issue: String?,
) {
    val colors = LocalEasyToolboxColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        // 行 1：label + id 复制 + 元数据
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CopyIconButton(
                textProvider = { field.id },
                contentDescription = "复制字段 ID ${field.id}",
            )
            Text(
                text = "${field.group.name} · ${if (field.applyMode.name == "RESTART") "需重启生效" else "热生效"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 行 2：生效值（等宽）+ 校验图标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(
                text = renderFieldValue(field, config),
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppSymbolIcon(
                icon = if (issue != null) MaterialSymbols.ERROR else MaterialSymbols.CHECK_CIRCLE,
                contentDescription = null,
                size = Dimens.iconSmall,
                tint = if (issue != null) MaterialTheme.colorScheme.error else colors.success,
            )
        }
        // 行 3：校验问题（有则显）
        if (issue != null) {
            Text(
                text = issue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
