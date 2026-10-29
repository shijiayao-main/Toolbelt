package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.page.ToolPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 工具页使用统计：为命令面板 frecency 排序 + 推荐首页「常用入口」供数。
 *
 * 计数口径：**使用 = 该页核心功能动作完成/发起**，
 *    非页面浏览——点击页面/切 tab 不再计数。计数真源 = `core/navigation/ToolUsageSignal`
 *    信号流（feature 动作点发信号 → AppViewModel init 订阅 → 本 tracker 计数）。
 *    [setCurrentPage] 切页只持久化 last_page，不碰计数。
 *
 * 计数真源在内存 map，启动时一次性从 DataStore 回填；信号到达时内存 +1 后把
 * 全量 JSON 快照写 `page_use_counts`（经 [EasyDataManager.setUsageCounts]——
 * 不搭切页便车：last_page 与计数是两个独立事件流，合并写配方已退役）。
 *
 * 落盘竞争安全：每次落盘的都是「当下内存快照」，DataStore edit 内部串行执行，
 * 多次信号到达后最终落盘的总是最新快照，计数不丢。
 *
 * 统计范围 = 排除宿主结构性页面（[NON_COUNTED]，设置/应用中心/报告/调试——结构性页面
 * 不是「被使用的工具」）。
 * AppCenter 页已退役恒不可达；枚举值保留（僵尸枚举），排除集保留作防未来信号误发兜底。
 */
class PageUsageTracker {

    private companion object {
        /** 结构性页面不计数（非「被使用的工具」） */
        private val NON_COUNTED = setOf(
            ToolPage.Setting,
            ToolPage.AppCenter,
            ToolPage.Report,
            ToolPage.Debug,
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
    }

    private val counts = linkedMapOf<String, Int>()

    private val _countsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val countsFlow: StateFlow<Map<String, Int>> = _countsFlow.asStateFlow()

    suspend fun load() {
        val persisted = EasyDataManager.getPageUseCounts()
        persisted.forEach { (key, count) ->
            counts[key] = maxOf(counts[key] ?: 0, count)
        }
        _countsFlow.value = counts.toMap()
    }

    fun incrementAndSerialize(page: ToolPage): String? {
        if (page in NON_COUNTED) {
            return null
        }
        val next = (counts[page.name] ?: 0) + 1
        counts[page.name] = next
        _countsFlow.value = counts.toMap()
        return json.encodeToString(mapSerializer, counts)
    }

    /** 当前计数（palette frecency 消费；无记录 = 0） */
    fun countOf(page: ToolPage): Int = counts[page.name] ?: 0

    /** 全量快照（测试与调试用） */
    fun snapshot(): Map<String, Int> = counts.toMap()
}

/** 计数 map 的 JSON 编解码（[EasyDataManager.getPageUseCounts] 消费；独立 object 便于测试直用） */
internal object PageUsageCountsSerializer {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())

    fun decode(raw: String): Map<String, Int> = json.decodeFromString(mapSerializer, raw)
}
