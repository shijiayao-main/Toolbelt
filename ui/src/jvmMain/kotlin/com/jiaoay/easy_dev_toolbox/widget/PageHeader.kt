package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 页面统一标题栏：大标题 + 右侧操作区。
 * 所有功能页统一使用，替代各页面手写的 headlineSmall 标题。
 *
 * 标题栏只余标题 + actions（无返回钮——返回由 palette/dock 承接，勿在此加回）。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        // 标题（headlineSmall 32sp 行高）垂直居中——Top 对齐时标题视觉偏下未对中
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            // 全局页题字号档：headlineSmall 18sp → headlineMedium
            // 20sp——--fs-20「页题」档（.pt font-size:var(--fs-20)），
            // 多域页头同规格、属全局骨架档
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        actions()
    }
}
