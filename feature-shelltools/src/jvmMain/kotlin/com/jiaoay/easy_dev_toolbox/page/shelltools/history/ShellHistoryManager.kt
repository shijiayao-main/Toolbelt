package com.jiaoay.easy_dev_toolbox.page.shelltools.history

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellHistorySortMode
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shell 命令历史管理：列表状态、排序展示、去重合并、防抖持久化与退出 flush。
 * 从 ShellToolsViewModel 拆出，VM 只做转发。
 */
@OptIn(FlowPreview::class)
class ShellHistoryManager(
    private val store: LocalDataManager = ShellToolsDataStore.store,
) {

    companion object {
        private const val MAX_HISTORY_SIZE = 30

        /** 防抖持久化间隔 */
        private const val PERSIST_DEBOUNCE_MS = 1000L
    }

    private val _historyFlow = MutableStateFlow<List<ShellHistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<ShellHistoryItem>> = _historyFlow.asStateFlow()

    private val _sortMode = MutableStateFlow(ShellHistorySortMode.MostUsed)
    val sortMode: StateFlow<ShellHistorySortMode> = _sortMode.asStateFlow()

    /** 排序组合用内部 scope（与 VM 生命周期一致：close 时 cancel） */
    private val combineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 按当前排序模式整理后的展示列表 */
    val displayHistory: StateFlow<List<ShellHistoryItem>> = combine(_historyFlow, _sortMode) { items, mode ->
        when (mode) {
            ShellHistorySortMode.MostUsed -> items.sortedWith(
                compareByDescending<ShellHistoryItem> { it.useCount }.thenByDescending { it.lastUsedAt }
            )
            ShellHistorySortMode.Recent -> items.sortedWith(
                compareByDescending<ShellHistoryItem> { it.lastUsedAt }.thenByDescending { it.useCount }
            )
        }
    }.stateIn(combineScope, SharingStarted.Eagerly, emptyList())

    /** 宿主 scope（start 时注入，与 VM 生命周期一致） */
    private var hostScope: CoroutineScope? = null

    /**
     * 恢复持久化历史 + 启动防抖落盘。需传入 ViewModel 的 scope（与页面生命周期一致）。
     *
     * @return 历史恢复任务（加载协程会无条件覆盖当前列表）。需要与"恢复完成"同步的
     *         调用方可 join 等待后再 add，避免恢复落地晚于新增导致覆盖丢失；
     *         常规调用方（VM）可忽略返回值，加载在后台完成、界面经 StateFlow 自动更新。
     */
    fun start(scope: CoroutineScope): Job {
        hostScope = scope
        val restore = scope.launch(Dispatchers.IO) {
            val json = store.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY, "")
            _historyFlow.value = ShellHistorySerializer.deserialize(json)
        }
        scope.launch(Dispatchers.IO) {
            _sortMode.value = runCatching {
                ShellHistorySortMode.valueOf(store.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY_SORT, "MostUsed"))
            }.getOrDefault(ShellHistorySortMode.MostUsed)
        }
        // 防抖持久化
        scope.launch {
            _historyFlow
                .drop(1)
                .debounce(PERSIST_DEBOUNCE_MS.milliseconds)
                .collect { persist() }
        }
        return restore
    }

    /** 命令执行后记录历史（同 id 去重移位 + 计数合并） */
    fun add(item: ShellHistoryItem) {
        val now = System.currentTimeMillis()
        _historyFlow.update { current ->
            val existing = current.firstOrNull { it.id == item.id }
            val newUseCount = (existing?.useCount ?: 0) + 1
            val updatedItem = item.withCount(useCount = newUseCount, lastUsedAt = now)
            val withoutExisting = current.filter { it.id != item.id }
            val updated = listOf(updatedItem) + withoutExisting
            if (updated.size > MAX_HISTORY_SIZE) updated.take(MAX_HISTORY_SIZE) else updated
        }
    }

    /** 删除单条历史（仅移除该条，余项及顺序不变；经既有防抖链路持久化） */
    fun remove(id: String) {
        _historyFlow.update { current -> current.filterNot { it.id == id } }
    }

    /** 清空全部历史（经既有防抖链路持久化） */
    fun clear() {
        _historyFlow.value = emptyList()
    }

    fun setSortMode(mode: ShellHistorySortMode) {
        _sortMode.value = mode
        hostScope?.launch(Dispatchers.IO) {
            store.setValue(ShellToolsDataStore.KEY_SHELL_HISTORY_SORT, mode.name)
        }
    }

    private suspend fun persist() {
        val json = ShellHistorySerializer.serialize(_historyFlow.value)
        store.setValue(ShellToolsDataStore.KEY_SHELL_HISTORY, json)
    }

    /** 释放 combineScope（VM onCleared 时调用 flush 后调用） */
    fun close() {
        combineScope.cancel()
        hostScope = null
    }

    /**
     * 退出时落盘（onCleared 经 runBlocking + withTimeoutOrNull 限时调用：
     * VM scope 已取消，须独立完成写入；以挂起方式序列化并写 DataStore，不含内嵌 runBlocking）。
     */
    suspend fun flush() {
        val json = ShellHistorySerializer.serialize(_historyFlow.value)
        store.setValue(ShellToolsDataStore.KEY_SHELL_HISTORY, json)
    }
}
