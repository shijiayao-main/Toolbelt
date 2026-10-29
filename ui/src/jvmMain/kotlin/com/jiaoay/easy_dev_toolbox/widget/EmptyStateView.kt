package com.jiaoay.easy_dev_toolbox.widget

import com.jiaoay.easy_dev_toolbox.widget.decor.appDashedBorder
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * P3 · 空态唯一语言（NN/g 四要素）：三套旧语言（本组件单要素 / StatusView.Empty /
 * 裸 bodySmall 文本）全部收口到本签名。
 *
 * 四要素（后三可选——首要素恒备）：
 * 1. [text]：系统状态一句短文案（「还没有任务」）
 * 2. [learningHint]：学习提示——此处将来显示什么/怎么填充
 * 3. [actionLabel] + [onAction]：关键任务直达路径（「去拖入文件」）
 * 4. [processing]：**true 时整块不渲染**——批量拖入后首卡建成前禁止「还没有任务」
 *    （NN/g 点名 particularly harmful 的闪现空态），由调用方在处理中态隐藏空态。
 *
 * 语义槽图标为 Material Symbol 蛇形名（String，默认 "inbox"），渲染收口 [AppSymbolIcon]。
 */
@Composable
fun EmptyStateView(
    text: String,
    modifier: Modifier = Modifier,
    icon: String = MaterialSymbols.INBOX,
    fillParent: Boolean = true,
    learningHint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    processing: Boolean = false,
    /**
     * 虚线降级盒容器（虚线只给降级/空态语义——docs/ui-style-guide.md DashedBox 条目）。true 时容器挂 appDashedBorder
     * 虚线描边——默认 false 保持既有消费面零回归，空态语境消费点逐个开启。
     */
    dashed: Boolean = false,
    /**
     * 图标尺寸覆写（参数化两全）——
     * null = 默认 [Dimens.emptyStateIcon]（22，components/tools-suite 稿）；
     * workbench 域空态传 40。
     */
    iconSize: androidx.compose.ui.unit.Dp? = null,
    /**
     * 主文案 SemiBold 档（四签空态规格 fs-14/600 + ghost 卡容器）：
     * true = 主文案 SemiBold；调用方配 dashed = true 成空态
     * 完整形态。默认 false 零回归。
     */
    emphasized: Boolean = false,
) {
    // 要素④：处理中不渲染空态（调用方传入；渲染面收口在组件内部，避免各页各判）
    if (processing) return

    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppSymbolIcon(
                icon = icon,
                contentDescription = null,
                // 默认 22px；iconHero 40 保留 hero 场景，workbench 域经 iconSize 传 40
                size = iconSize ?: Dimens.emptyStateIcon,
                tint = LocalToolboxSurfaceColors.current.onSurfaceFaint,
            )
            Spacer(modifier = Modifier.height(Dimens.space12))
            Text(
                text = text,
                style = if (emphasized) {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 要素②：学习提示（此处将来显示什么/怎么填充——不重复主文案）
            if (learningHint != null) {
                Spacer(modifier = Modifier.height(Dimens.space4))
                Text(
                    text = learningHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                )
            }
            // 要素③：关键任务直达路径（可选；与 onAction 成对出现才渲染）
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(Dimens.space12))
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
    val containerModifier = if (dashed) {
        modifier
            .appDashedBorder(
                color = LocalToolboxSurfaceColors.current.borderHairline,
                shape = AppShapes.large,
            )
            .padding(Dimens.space20)
    } else {
        modifier
    }
    if (fillParent) {
        Box(containerModifier.fillMaxSize(), contentAlignment = Alignment.Center, content = { content() })
    } else {
        Box(containerModifier.fillMaxWidth(), contentAlignment = Alignment.Center, content = { content() })
    }
}

