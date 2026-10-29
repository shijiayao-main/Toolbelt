package com.jiaoay.easy_dev_toolbox.page.shelltools.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.node.ShellToolsChildNode
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.node.ShellToolsParentNode
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView

@Composable
fun ShellToolsList(viewModel: ShellToolsViewModel, toolNodes: List<ToolNode>) {

    // 4.4 工具列表（搜索过滤：Parent/Child 均按 isVisible 过滤）
    val visibleNodes = toolNodes.filter { node ->
        when (node) {
            is ToolNode.Parent -> node.isVisible
            is ToolNode.Child -> node.isVisible
        }
    }

    // 搜索无命中主区不再整块空白——空态三要素
    // （text + learningHint + action「清空搜索」直达恢复完整树）；普通浏览
    // （无搜索词）树非空，不进此分支、行为不变。query 读 VM 状态流
    // （签名已收 viewModel，少一处穿参）
    if (visibleNodes.isEmpty()) {
        val query by viewModel.searchQuery.collectAsState()
        EmptyStateView(
            text = "没有匹配「$query」的工具",
            learningHint = "支持工具名与命令内容；清空搜索框恢复完整树",
            actionLabel = "清空搜索",
            onAction = { viewModel.onSearchQueryChange("") },
            fillParent = false,
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        // Parent/Child 混合列表按类型给 contentType：同类型节点复用同一组合缓存槽，避免跨类型复用重组开销
        items(
            visibleNodes,
            key = { it.id },
            contentType = { if (it is ToolNode.Parent) "parent" else "child" },
        ) { node ->
            when (node) {
                is ToolNode.Parent -> ShellToolsParentNode(
                    parent = node,
                    viewModel = viewModel,
                )

                is ToolNode.Child -> ShellToolsChildNode(
                    child = node,
                    viewModel = viewModel,
                )
            }
        }
    }
}