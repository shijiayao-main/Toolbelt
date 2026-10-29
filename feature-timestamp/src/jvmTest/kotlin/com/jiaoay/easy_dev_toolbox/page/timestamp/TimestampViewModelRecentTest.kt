package com.jiaoay.easy_dev_toolbox.page.timestamp

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TL-12 · 最近转换记录 chips 验收（重设计批 4 · 2026-10-01）：VM [TimestampViewModel]
 * `_recent` 流行为直测——转换成功去重插首、cap 8 挤最旧、Empty/Invalid 不记录。
 *
 * 卡面 acceptance：
 * - 连续转换 3 个不同值 → recent 3 条（点击 chip 回填 = onTimestampChange + convert
 *   直调——回填语义经 VM 公开面钉住，页面侧 RecentChipsRow 只做接线）；
 * - 第 9 个新值挤掉最旧（cap 8）；
 * - 去重：同值再转不重复占位（插首移顶）。
 *
 * 🔴 心跳协程纪律（侦察警示）：VM 构造起 500ms 心跳（viewModelScope 常驻）——
 * 每用例收尾必须 viewModelScope.cancel()（onCleared 等价收尾），防滞留协程跨用例
 * 漂移（DataStore 双 flaky 同款根因）；验收 3 连跑判非回归。
 *
 * 🔴 DataStore 隔离纪律：VM init 读 TimestampConfigStore（AppDataStore 真单例落
 * 数据根）——user.home 重定向 + 隔离 store 实例整体替换（TimestampConfigStore.store
 * 测试接缝），restoreOnly 不删目录（在途写 flaky 先例）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimestampViewModelRecentTest {

    companion object {
        init {
            // 与 TimestampConverterTest 同款时区锚：固定防环境漂（转换成败判定与时区无关）
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"))
        }
    }

    /** user.home 隔离（DataStore 真单例数据根须落临时目录） */
    private val homeIsolation = UserHomeIsolation("edt-tl12-test")

    private val dispatcher = StandardTestDispatcher()
    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        // 隔离 DataStore：VM init 的格式恢复读落隔离 store（不触真机配置文件）
        originalStore = TimestampConfigStore.store
        TimestampConfigStore.store = LocalDataManager(dataStoreName = "test_tl12_${java.util.UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        TimestampConfigStore.store = originalStore
        homeIsolation.restoreOnly() // 🔴 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp 由系统清理）
        Dispatchers.resetMain()
    }

    private fun vm() = TimestampViewModel()

    /**
     * 值合同：直调 [TimestampViewModel.convert] 后须已产出有效结果——
     * 防用例值写坏（如位数错）把「记录了但没转成」误判绿（recent 只认成功转换）。
     */
    private fun convertValid(viewModel: TimestampViewModel, value: String) {
        viewModel.convert(value)
        val output = viewModel.output.value
        assertTrue(
            output !is ConversionResult.Empty && output !is ConversionResult.Invalid,
            "convert(\"$value\") 须产出有效结果才该记 recent（本用例值合同被打破：output=$output）",
        )
    }

    @Test
    fun `cap constant pinned to 8 in companion`() {
        assertEquals(
            8,
            TimestampViewModel.RECENT_CAP,
            "TL-12 卡面钉死 cap 8——常量须入 VM companion（internal 供本断言锚）",
        )
    }

    @Test
    fun `three distinct conversions produce three chips`() {
        val viewModel = vm()
        try {
            convertValid(viewModel, "1756790400")
            convertValid(viewModel, "1756790400123")
            convertValid(viewModel, "2025-09-02 13:20:00")

            assertEquals(
                listOf("2025-09-02 13:20:00", "1756790400123", "1756790400"),
                viewModel.recent.value,
                "连续转换 3 个不同值 → 3 条记录，新值插首（最近在前）",
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `empty and invalid conversions are not recorded`() {
        val viewModel = vm()
        try {
            convertValid(viewModel, "1756790400")

            // Empty / Invalid 不记录（与使用计数同口径——只认成功转换）
            viewModel.convert("")
            viewModel.convert("abc")

            assertEquals(
                listOf("1756790400"),
                viewModel.recent.value,
                "Empty/Invalid 转换不得污染 recent（同 usage 计数口径）",
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `ninth value evicts oldest at cap 8`() {
        val viewModel = vm()
        try {
            // 9 个互异 10 位秒值（2001 年区间合法时间戳）
            val distinct = (0..8).map { "100000000$it" }
            distinct.forEach { convertValid(viewModel, it) }

            assertEquals(
                8,
                viewModel.recent.value.size,
                "cap 8：第 9 个新值后仍 8 条（挤掉最旧）",
            )
            assertEquals(
                distinct.drop(1).reversed(),
                viewModel.recent.value,
                "挤掉最旧（首转的 ${distinct.first()} 被逐），且新值插首序不变",
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `duplicate value moves to front without double entry`() {
        val viewModel = vm()
        try {
            convertValid(viewModel, "1756790400")
            convertValid(viewModel, "1756790400123")
            // 同值（含空白变体）再转：去重插首，不重复占位
            viewModel.convert("  1756790400  ")

            assertEquals(
                listOf("1756790400", "1756790400123"),
                viewModel.recent.value,
                "同值再转 = 插首移顶不占新位（trim 净值去重——空白变体同值）",
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `chip refill path rounds back through onTimestampChange and convert`() {
        val viewModel = vm()
        try {
            convertValid(viewModel, "1756790400123")
            val chipValue = viewModel.recent.value.single()

            // 页面 onChipClick 接线的回填语义：先回填输入框、再立即 convert
            viewModel.onTimestampChange(chipValue)
            viewModel.convert(chipValue)

            assertEquals(chipValue, viewModel.timestamp.value, "点 chip 回填：输入框值 = chip 值")
            assertIs<ConversionResult.FromTimestamp>(
                viewModel.output.value,
                "回填后立即 convert 出结果（点 chip 即出结果，不等防抖）",
            )
            assertEquals(
                listOf(chipValue),
                viewModel.recent.value,
                "回填转换是同值去重——recent 不重复占位",
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
