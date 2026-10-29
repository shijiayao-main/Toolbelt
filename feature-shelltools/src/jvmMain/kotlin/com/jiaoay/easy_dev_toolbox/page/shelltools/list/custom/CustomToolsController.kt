package com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom

import com.jiaoay.easy_dev_toolbox.entity.JResult
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsConfigRepository
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolNodeConfig
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 自定义命令控制器（page 层适配器）：
 * 职责收敛为展示状态 StateFlow、展开状态记忆、UI 语义转发；
 * 三源合并、CRUD、迁移、并发控制全部委托 core 层 [ShellToolsConfigRepository]。
 */
class CustomToolsController(
    private val repository: ShellToolsConfigRepository = sharedRepository,
) {

    companion object {
        /**
         * 进程级共享 Repository 实例。
         * Repository 的 Mutex 为实例级，多 Controller 并发时必须共享同一实例才能串行化。
         */
        private val sharedRepository = ShellToolsConfigRepository()
    }

    private val _toolNodeStateFlow: MutableStateFlow<JResult<List<ToolNode>>?> = MutableStateFlow(null)
    val toolNodeStateFlow: StateFlow<JResult<List<ToolNode>>?> = _toolNodeStateFlow.asStateFlow()

    /** 主配置加载错误提示（回退默认配置时非空），供 UI 顶部 banner 展示 */
    private val _mainConfigError = MutableStateFlow<String?>(null)
    val mainConfigError: StateFlow<String?> = _mainConfigError.asStateFlow()

    /** 展开状态记忆：fetch 刷新后回填，避免 CRUD 后折叠被重置 */
    private val expandedParentIds = MutableStateFlow<Set<String>>(emptySet())

    private var firstLoadDone = false

    /**
     * fetch 后的节点变换钩子（单点收口）：fetch 重建节点后 isVisible 全落 true，
     * 搜索过滤态会与「框有词、树全量」失同步——VM 注入按非空 query 重放过滤的
     * 变换（null = 恒等不碰树）。进页/刷新钮/七类 CRUD 全部 fetch 路径都被本钩子覆盖。
     */
    var nodeTransform: ((List<ToolNode>) -> List<ToolNode>)? = null

    // ── 配置加载（三源合并，逻辑在 Repository） ──

    suspend fun fetchShellToolsConfig() {
        val loaded = repository.load()
        val expanded = expandedParentIds.value
        val firstLoad = !firstLoadDone

        val nodes = loaded.nodes.map { node ->
            if (node is ToolNode.Parent) {
                when {
                    // 首次加载：含工具的自定义分组默认展开
                    firstLoad && node.isEditable && node.children.isNotEmpty() && node.id !in expanded -> {
                        expandedParentIds.update { it + node.id }
                        node.copy(isExpanded = true)
                    }

                    node.id in expanded -> node.copy(isExpanded = true)

                    else -> node
                }
            } else {
                node
            }
        }
        firstLoadDone = true
        val transformed = nodeTransform?.invoke(nodes) ?: nodes
        _toolNodeStateFlow.value = JResult.success(transformed)
        _mainConfigError.value = loaded.mainConfigError
    }

    // ── 节点树状态操作 ──

    fun updateNodeList(block: (ToolNode) -> ToolNode) {
        _toolNodeStateFlow.update { data ->
            if (data !is JResult.Success<List<ToolNode>>) {
                return@update data
            }
            JResult.success(data.data.map(block))
        }
    }

    /** 展开/折叠分组并记忆状态（含主配置分组，刷新后保持） */
    fun toggleParentExpanded(parentId: String) {
        var nowExpanded = false
        updateNodeList { node ->
            if (node is ToolNode.Parent && node.id == parentId) {
                nowExpanded = !node.isExpanded
                node.copy(isExpanded = nowExpanded)
            } else {
                node
            }
        }
        expandedParentIds.update { current ->
            if (nowExpanded) current + parentId else current - parentId
        }
    }

    // ── 自定义命令 CRUD（转发 Repository，操作后刷新） ──

    suspend fun upsertTool(node: ShellToolNodeConfig, groupId: String?) {
        repository.upsertTool(node, groupId)
        fetchShellToolsConfig()
    }

    suspend fun deleteTool(id: String) {
        repository.deleteTool(id)
        fetchShellToolsConfig()
    }

    /** @return 新分组 id；null 表示名称已被占用 */
    suspend fun createGroup(name: String): String? {
        val id = repository.createGroup(name) ?: return null
        fetchShellToolsConfig()
        return id
    }

    suspend fun renameGroup(id: String, newName: String): Boolean {
        val ok = repository.renameGroup(id, newName)
        if (ok) fetchShellToolsConfig()
        return ok
    }

    suspend fun deleteGroup(id: String): Boolean {
        val ok = repository.deleteGroup(id)
        if (ok) fetchShellToolsConfig()
        return ok
    }

    suspend fun moveTool(toolId: String, targetGroupId: String) {
        repository.moveTool(toolId, targetGroupId)
        fetchShellToolsConfig()
    }

    /** @return 导入的工具数；-1 表示片段解析失败 */
    suspend fun importJson(json: String): Int {
        val count = repository.import(json)
        if (count > 0) fetchShellToolsConfig()
        return count
    }

    suspend fun export(ids: List<String>): String {
        return repository.export(ids)
    }
}
