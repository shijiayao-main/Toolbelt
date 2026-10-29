package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * palette 空态智能数据面（「空态即首页」——推荐面板真实可达）。
 *
 * 三路只读拉取（Mixed 空查询的推荐首页渲染）：
 * - **最近空间**：SpaceStore.listSpaces 倒序前 N 条（空间栏同款单例直连——
 *   SpaceBarState 是 SpaceBar 私有实例不可复用，本类自建直连读面）；
 * - **常用入口**：宿主传入 usageCounts 排序（命令级计数不存在——PageUsageTracker
 *   只收 ToolPage；侦察风险记录，第一版用页面计数）；
 * - **未读产物**：RunStore.listRuns 取 reportPath 非 null 最近 N 条。
 *
 * 🔴 线程纪律（AppHostGuardTest 合规——无自建 scope）：SQLDelight 同步执行，
 *    拉取经 [LaunchedEffect] + [Dispatchers.IO]（SpaceBarState.refresh 同款；主线程直接查库即冻结 UI）。
 *    🔴 推荐面板不挂 filtered.isEmpty() 条件下（与候选集
 *    恒含设置项互斥→不可达死代码，refresh 拉取随之全空转）；
 *    Mixed 空查询即渲染（面板打开即见），拉取链路保持实效。
 */
internal class PaletteEmptyState {
    var recentSpaces by mutableStateOf<List<SpaceStore.SpaceSummary>>(emptyList())
        private set
    var recentReports by mutableStateOf<List<RunStore.RunSummary>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    /** 全量拉取（挂起面——palette 打开瞬间 LaunchedEffect 承接） */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            runCatching {
                val db = Database.database()
                recentSpaces = SpaceStore(db).listSpaces().take(RECENT_LIMIT)
                recentReports = RunStore(db).listRuns()
                    .filter { it.reportPath != null }
                    .take(RECENT_LIMIT)
            }
            loaded = true
        }
    }

    private companion object {
        const val RECENT_LIMIT = 3
    }
}

/** palette 空态数据装配（打开瞬间拉取一次；空态数据只读零写） */
@Composable
internal fun rememberPaletteEmptyState(visible: Boolean): PaletteEmptyState {
    val state = remember { PaletteEmptyState() }
    LaunchedEffect(visible) {
        if (visible) state.refresh()
    }
    return state
}
