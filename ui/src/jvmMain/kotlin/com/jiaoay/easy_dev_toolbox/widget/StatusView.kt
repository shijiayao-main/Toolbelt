package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.entity.JResult
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 通用结果状态视图：按 [JResult] 分发渲染——
 * [JResult.Loading] 与 null（数据未就绪）→ 居中加载圈；
 * [JResult.Empty] → 居中空态图标 + 「暂无数据」引导文案；
 * [JResult.Failed] → 错误提示文案；
 * [JResult.Success] → [contentView] 渲染数据内容。
 */

@Composable
fun <T> StatusView(
    modifier: Modifier = Modifier,
    result: JResult<T>?,
    contentView: @Composable (data: T) -> Unit,
) {
    Box(modifier) {
        when (result) {
            is JResult.Empty -> {
                EmptyStateView()
            }

            is JResult.Failed -> {
                FailedStatusView(data = result)
            }

            is JResult.Loading -> {
                LoadingStateView()
            }

            is JResult.Success<T> -> {
                contentView.invoke(result.data)
            }

            null -> {
                LoadingStateView()
            }
        }
    }
}

/** 加载态：hero 尺寸进度圈居中于可用区域（Loading 与 null 数据共用） */
@Composable
private fun LoadingStateView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(Dimens.iconHero))
    }
}

/** 空态：Inbox 图标 + 「暂无数据」引导文案，居中组合 */
@Composable
private fun EmptyStateView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppSymbolIcon(
                icon = MaterialSymbols.INBOX,
                contentDescription = null,
                size = Dimens.iconHero,
                tint = LocalToolboxSurfaceColors.current.onSurfaceFaint,
            )
            Spacer(modifier = Modifier.height(Dimens.space12))
            Text(
                text = "暂无数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedStatusView(data: JResult.Failed) {
    Text(
        text = data.msg ?: data.code.desc,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
