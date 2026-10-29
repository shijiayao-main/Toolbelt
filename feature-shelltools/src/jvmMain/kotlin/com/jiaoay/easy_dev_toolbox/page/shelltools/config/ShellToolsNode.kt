package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import androidx.compose.runtime.Immutable

/**
 * 展示层模型，UI 只依赖此模型，不直接引用 ShellToolNodeConfig
 */

/** 工具树节点（分组/工具）：全 val 不可变展示模型，Compose 渲染稳定性标注 */
@Immutable
sealed class ToolNode {
    abstract val id: String
    abstract val name: String

    @Immutable
    data class Parent(
        override val id: String,
        override val name: String,
        val children: List<ToolNode>,
        val isExpanded: Boolean = false,
        val isVisible: Boolean = true,
        /** 是否为可编辑的自定义分组（驱动头部"新增/导入/分享/重命名/删除"按钮） */
        val isEditable: Boolean = false,
        /** 是否为自定义区域的默认分组（不可删除，删除分组/跨组移动的工具迁移目标） */
        val isDefaultGroup: Boolean = false,
    ) : ToolNode()

    @Immutable
    data class Child(
        override val id: String,
        override val name: String,
        val body: ChildBody,
        /** 是否为自定义节点（驱动编辑/删除/分享/移动入口） */
        val isEditable: Boolean = false,
        /** 搜索过滤可见性（与 Parent.isVisible 既有模式对称；由 VM 搜索逻辑维护，渲染层按此过滤） */
        val isVisible: Boolean = true,
        /**
         * 行尾「×N 使用计数」（稿树内行恒有 pill mute 计数）。null = 无历史记录
         * （不渲染）；数据源 = ShellHistoryManager 按 id join 求和 useCount。
         */
        val useCount: Int? = null,
    ) : ToolNode()
}

/**
 * 命令内容匹配（搜索过滤扩展）：
 * 覆盖 Normal(value/desc)、MultiSelect(各 item 的 name/value/desc)、
 * Input(value/各 param 的 name/desc)、Chain(commands 逐条)；忽略大小写。
 */
fun bodyContains(body: ChildBody, query: String): Boolean {
    if (query.isEmpty()) return true
    return when (body) {
        is ChildBody.Normal ->
            body.value.contains(query, ignoreCase = true) ||
                body.desc.contains(query, ignoreCase = true)

        is ChildBody.MultiSelect -> body.items.any { item ->
            item.name.contains(query, ignoreCase = true) ||
                item.value.contains(query, ignoreCase = true) ||
                item.desc.contains(query, ignoreCase = true)
        }

        is ChildBody.Input ->
            body.value.contains(query, ignoreCase = true) ||
                body.params.any { param ->
                    param.name.contains(query, ignoreCase = true) ||
                        param.desc.contains(query, ignoreCase = true)
                }

        is ChildBody.Chain -> body.commands.any { it.contains(query, ignoreCase = true) }
    }
}

/** 工具命令体变体：全 val 不可变（含只读 List），Compose 渲染稳定性标注 */
@Immutable
sealed class ChildBody {
    @Immutable
    data class Normal(
        val value: String,
        val desc: String,
    ) : ChildBody()

    @Immutable
    data class MultiSelect(
        val items: List<MultiSelectItem>,
    ) : ChildBody() {
        @Immutable
        data class MultiSelectItem(
            val name: String,
            val value: String,
            val desc: String,
        )
    }

    @Immutable
    data class Input(
        val value: String,
        val params: List<InputParam>,
    ) : ChildBody() {
        @Immutable
        data class InputParam(
            val name: String,
            val desc: String,
        )
    }

    @Immutable
    data class Chain(
        val commands: List<String>,
    ) : ChildBody()
}
