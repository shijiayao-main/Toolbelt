package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * 卡片标题头：larkbridge 十四张配置卡「首行 bodySmall 标题」配方的布局化收敛。
 * 标题行占据整行；可选 [trailing] 尾部操作区（刷新按钮 / Switch / 计数徽标等）。
 *
 * 配方：`Row(fillMaxWidth, centerVertically) { Text(title, style, weight(1f)) , trailing() }`，
 * 与既有卡片手写形态逐点对齐（BridgeUsageStatsCard 等注释自述「对齐同配方」）。
 * [style] 默认 bodySmall 保持原签名兼容；需要 titleSmall 级小节头时显式传入
 * （参数化激活，替代调用点手写 Row）。
 */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = style,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
