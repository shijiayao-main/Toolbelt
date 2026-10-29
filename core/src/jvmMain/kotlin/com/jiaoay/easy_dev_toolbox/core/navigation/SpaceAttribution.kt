package com.jiaoay.easy_dev_toolbox.core.navigation

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import com.jiaoay.easy_dev_toolbox.core.data.db.ToolbeltDb
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlinx.coroutines.CancellationException

/**
 * 调查空间自动归属槽（空间归属接线）。
 *
 * ## 语义
 * **当前空间存在时把成员记入**——调查 = 当前选中空间的自动归属面；无选中空间时
 * **静默跳过（非错误）**。
 *
 * ## 中立槽配方（[ToolUsageSignal] 信号流同包先例 +
 * [com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook] 槽先例）
 * - feature 侧只 import 本 object（:core，feature 与宿主双方可见——feature 不得
 *   import 宿主 AppViewModel 铁律），在成员创建成功点一行 `attribute*` 调用；
 * - 宿主 `AppViewModel` init 顶层 wire [currentSpaceIdProvider]（读
 *   `_currentSpaceId` 现值的 lambda——DeepAnalyzeHook wire 同款形态）；
 * - **null 槽 / null 返回 = 无选中空间**——全部 no-op（空间栏未选中时的窗口期、
 *   纯 CLI 语境均安全）。
 *
 * ## 线程纪律
 * 挂起函数（调用方 VM 的 IO 协程语境承接），无自建 scope（AppHostGuard 合规）。
 * [com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore] 同步 SQLite（SpaceStore.kt
 * 线程契约：调用方自管线程）——本槽不做调度决策，线程语境由调用方的挂起语境保证。
 *
 * ## 失败面（🔴 不静默丢失纪律——WorkbenchViewModel.attachFilesToSpace 配方同款）
 * 归属是**增强面**非关键路径：写库失败不拖垮调用方（会话/run 创建照常完成），
 * 但失败必须 JLog 落盘；CE 不吞（取消传播）。
 */
object SpaceAttribution {

    private const val TAG = "SpaceAttribution"

    /**
     * 当前调查空间 id provider（宿主 wire；null = 宿主未接线，全部 no-op）。
     * 生产代码不得在本槽外重新赋值（仅宿主 AppViewModel init 与 jvmTest 注入）。
     */
    @Volatile
    var currentSpaceIdProvider: (suspend () -> String?)? = null

    /** 会话创建成功 → 记入当前空间成员集（flowIds）。 */
    suspend fun attributeFlow(flowId: String) {
        recordIntoCurrentSpace { store, spaceId ->
            store.addFlows(spaceId, listOf(flowId))
        }
    }

    /** run 创建成功 → 记入当前空间成员集（runIds）。 */
    suspend fun attributeRun(runId: String) {
        recordIntoCurrentSpace { store, spaceId ->
            store.addRuns(spaceId, listOf(runId))
        }
    }

    /**
     * 产物文件（研判报告等）落盘 → 记入当前空间成员集（filePaths）。
     * [com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore.addFiles] 去重保序幂等
     * ——followUp 增量研判多次落盘同一文件不重复记。
     */
    suspend fun attributeFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        recordIntoCurrentSpace { store, spaceId ->
            store.addFiles(spaceId, paths)
        }
    }

    // ── 内部 ──

    /**
     * 成员写入收敛点：provider 读当前空间 id（null 静默跳过）→ add 成员 + touch
     * （活跃触达刷新列表排序）。写库失败 JLog 落盘不拖垮调用方；CE 不吞。
     */
    private suspend fun recordIntoCurrentSpace(write: (SpaceStore, String) -> Unit) {
        // 无选中空间：静默跳过（非错误——调查归属面语义）
        val spaceId = currentSpaceIdProvider?.invoke() ?: return
        runCatching {
            val store = SpaceStore(databaseProvider())
            write(store, spaceId)
            store.touch(spaceId)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            JLog.e(TAG, e) { "空间成员归属失败：spaceId=$spaceId（调查空间自动归属槽）" }
        }
    }

    /**
     * 数据库取用点（生产 = [Database.database()] :core 现行用法；internal 供
     * jvmTest 注入临时库——JInitializerManager.initializersProvider 先例：归属测试
     * 不得污染 `~/.easy_dev_toolbox/db/toolbelt.db` 真库）。
     * 生产代码不得重新赋值。
     */
    internal var databaseProvider: () -> ToolbeltDb = { Database.database() }
}
