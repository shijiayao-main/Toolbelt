package com.jiaoay.easy_dev_toolbox.core.buffer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 级文件暂存区（调查工作台 S4——融合案 D 裁决 8：File Buffer 暂存区）。
 *
 * ## 定位
 * 「数据就位先于工具选择」的中转槽：命令面板执行/产物产出后先把文件路径暂存于此，
 * 再由用户拖给工作台/会话消费——palette「暂存到 Buffer」动作与围栏 register 双向打通。
 *
 * ## UI 消费面
 * - **工作台页 chips 行**（LogWorkbenchPage `FileBufferRow`）：Buffer 非空时在
 *   证据流区顶部渲染 chips——点击 chip = 载入工作台分析输入（addTargetFile）+
 *   出暂存（remove 对偶收口）；× = 显式移除；hover Tooltip 显全路径。空 Buffer
 *   零渲染；
 * - **会话围栏 register**（WorkbenchSession/QuestSession registerFiles 双挂
 *   removeAll）：文件进围栏即出暂存（「拖进会话才授权」）。
 *
 * ## 纯内存暂存（只存路径）
 * - **不做 SpaceStore.addFiles**：SpaceStore 是同步 SQLite、调用方自管线程
 *   （SpaceStore.kt 契约）——object 内自建 scope 会红 AppHostGuardTest；写空间
 *   成员属宿主/VM 侧职责（先例：WorkbenchViewModel.attachFilesToSpace）。
 * - **授权语义在围栏**（「拖进会话才授权」）：文件被会话 register 即从暂存区移除
 *   （WorkbenchSession/QuestSession registerFiles 双挂 FileBuffer.remove）——
 *   Buffer 只承载「待安置」，不承载访问授权。
 *
 * ## 纪律（对齐 LogWorkbenchDeepLink object 门面先例）
 * 纯数据槽：StateFlow 单例 + 无 IO + 无 scope——消费方（palette/页面）自行决定
 * 渲染与写时机。
 */
object FileBuffer {

    /** 暂存文件行（路径 + 进入时间——UI 按 addedAt 倒序呈现） */
    data class BufferedFile(
        val path: String,
        val addedAt: Long,
    )

    private val _files = MutableStateFlow<List<BufferedFile>>(emptyList())

    /** 当前暂存集（保序——先进先安置；StateFlow 供 UI 响应式消费） */
    val files: StateFlow<List<BufferedFile>> = _files.asStateFlow()

    /** 暂存一批文件（去重保序——已在暂存区的路径不重复、不刷新时间） */
    fun offer(paths: List<String>) {
        if (paths.isEmpty()) return
        val known = _files.value.map { it.path }.toSet()
        val now = System.currentTimeMillis()
        _files.value = _files.value + paths
            .filter { it.isNotBlank() && it !in known }
            .map { BufferedFile(path = it, addedAt = now) }
    }

    /** 单文件暂存（便捷面） */
    fun offer(path: String) = offer(listOf(path))

    /** 移除单件（围栏 register 消费即出暂存——「拖进会话才授权」对偶面） */
    fun remove(path: String) {
        _files.value = _files.value.filterNot { it.path == path }
    }

    /** 批量移除（会话 registerFiles 的配套消费面） */
    fun removeAll(paths: List<String>) {
        if (paths.isEmpty()) return
        val set = paths.toSet()
        _files.value = _files.value.filterNot { it.path in set }
    }

    /** 清空（用户显式丢弃/全部安置后） */
    fun clear() {
        _files.value = emptyList()
    }

    /** 测试缝：清空（单测隔离用） */
    fun clearForTest() {
        _files.value = emptyList()
    }
}
