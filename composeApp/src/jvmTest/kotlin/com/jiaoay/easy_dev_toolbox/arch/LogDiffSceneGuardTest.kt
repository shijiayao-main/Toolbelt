package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffPageContent
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffViewModel
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files

/**
 * 日志对比页（「工具」聚合页第 6 签）场景渲染像素带守护：
 * ImageComposeScene 零显示器配方（[SceneGuardSupport] 单源）扩到「预驱动 VM 状态注入」
 * 形态——[LogDiffPageContent] 经 viewModel{} 自建 VM、无状态注入口，测试侧把预驱动到
 * 目标态的 VM 经 ViewModelProvider 同 key 通道预置进独立 ViewModelStore，组合期
 * viewModel() 缓存命中即返回该 VM（不改生产面）。
 *
 * 验证面（该页后续布局改动的回归网）：
 * - 双槽横排：首带（双拖放槽）内存在跨水平中线的背景隔断、两侧均有内容——
 *   Row weight 均分 + spacedBy 布局断裂（双槽叠成单列/中缝消失）即红；
 * - 内容带互斥：各带间背景间隙 > 3px（叠放回归）；
 * - 缺一拒收横幅：仅一槽在位时发起对比，Error 横幅（error 着色图标）进入渲染面；
 * - Done 态结果卡：REMOVED 行 error 着色 / ADDED 行 primary 着色进入渲染面
 *   （着色锚经主题探针小场景取色后精确比对，与 idle 同尺寸渲染做差分基线，
 *   不硬编码色值、不被页内常驻 primary 按钮污染）；
 * - 分布对照模式（两向模式之一）：Done 态对照卡作为新增内容带进入渲染面
 *   （与分布空态同尺寸渲染对比带数）；归一化开关仅行级模式可见——分布空态
 *   带数须少于行级空态（开关带缺席即态区分锚，反向即开关泄漏）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogDiffSceneGuardTest {

    private val dispatcher = StandardTestDispatcher()
    private val createdVms = mutableListOf<LogDiffViewModel>()
    private val tempDirs = mutableListOf<java.io.File>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdVms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    // ── 场景渲染底座：[SceneGuardSupport] 单源（本包） ─────────────────────────

    /**
     * 双槽并排探测：带内存在一行，水平中线邻域（mid±4）整段为背景隔断（≥8px），
     * 且隔断两侧各有 ≥10px 内容（槽内容居中排版——图标+标题约 30px，阈值取其下限）——
     * 即左右两拖放槽各占一侧、中缝未被吞没（单列叠放或中缝消失即不满足）。
     */
    private fun dualSlotMidGapRow(pixels: Array<IntArray>, band: IntRange, width: Int): Boolean {
        val mid = width / 2
        for (y in band) {
            val row = pixels[y]
            val midGapClear = (mid - 4..mid + 4).none { isContentPx(row[it]) }
            if (!midGapClear) continue
            val leftContent = (0 until mid - 8).count { isContentPx(row[it]) } >= 10
            val rightContent = (mid + 8 until width).count { isContentPx(row[it]) } >= 10
            if (leftContent && rightContent) return true
        }
        return false
    }

    // ── VM 状态注入底座 ────────────────────────────────────────────────────

    /**
     * 预驱动 VM 注入独立 ViewModelStore：组合期 viewModel() 与本函数走同一
     * ViewModelProvider.get(key, KClass) 通道（同 key 即缓存命中），返回该 owner
     * 经 CompositionLocalProvider 提供给场景内容。
     */
    private fun seededOwner(vm: LogDiffViewModel, key: String? = null): ViewModelStoreOwner {
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm as T
        }
        val provider = ViewModelProvider.create(owner, factory, CreationExtras.Empty)
        if (key == null) provider.get(LogDiffViewModel::class) else provider.get(key, LogDiffViewModel::class)
        // VM 生命周期由测试自理：store 不 clear（clear 会触发 onCleared 并关闭
        // viewModelScope，注入后的状态驱动将失效），tearDown 统一 cancel 兜底
        return owner
    }

    /** 真实 IO 轮询：advanceUntilIdle 不等待 IO 线程续体，轮询推进直至谓词命中或超时 */
    private fun awaitState(maxMillis: Long = 4_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + maxMillis
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(20)
        }
        error("状态未在预算内落定（真实 IO 轮询超时）")
    }

    private fun tempFile(name: String, lines: List<String>): java.io.File {
        val dir = Files.createTempDirectory("logdiff-scene").toFile()
        tempDirs += dir
        return java.io.File(dir, name).apply { writeText(lines.joinToString("\n")) }
    }

    /** 宿主列包裹复刻：无头内容体多根直发，宿主（ToolsPage）Column spacedBy 即其带间真源 */
    @Composable
    private fun HostWrappedColumn(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space12),
        ) {
            content()
        }
    }

    // ── 用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `idle dual slots render side by side with mid background gap`() {
        val vm = LogDiffViewModel().also { createdVms += it }
        val pixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(vm)) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 3,
            "空态应渲染 ≥3 内容带（双槽/归一化开关/开始对比按钮），实际 ${bands.size}：$bands",
        )
        assertBandsSeparated(bands)
        // 拖放槽顶缘的 1-2px 描边带先于槽体带——按高度取首个实体带（≥24px）作双槽行
        val slotBand = bands.firstOrNull { it.last - it.first + 1 >= 24 }
            ?: fail("应存在 ≥24px 的双拖放槽实体带（细描边带之外），实际带表：$bands")
        assertTrue(
            dualSlotMidGapRow(pixels, slotBand, SCENE_W),
            "双槽应并排渲染：中缝存在背景隔断且两侧各有内容——双槽叠成单列或中缝消失即红",
        )
    }

    @Test
    fun `missing one slot rejection renders error banner into pixel plane`() {
        val vm = LogDiffViewModel().also { createdVms += it }
        vm.onFilesSelected(LogDiffViewModel.Slot.OLD, listOf(tempFile("old.txt", listOf("alpha"))))
        vm.startDiff()
        // 缺一拒收：inputError 同步置位（不进协程）——NEW 槽缺席时发起对比
        assertTrue(vm.inputError.value != null, "前置：缺一拒收须置 inputError（否则 VM 契约回归）")

        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }
        val idlePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(LogDiffViewModel().also { createdVms += it })) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val rejectPixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(vm)) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val bands = contentRows(rejectPixels, SCENE_W)
        assertTrue(
            bands.size >= 4,
            "拒收态应渲染 ≥4 内容带（双槽/开关/按钮/横幅），实际 ${bands.size}：$bands",
        )
        assertBandsSeparated(bands)
        assertTrue(
            countColor(rejectPixels, errorColor) >= countColor(idlePixels, errorColor) + 10,
            "缺一拒收横幅的 error 着色图标应进入渲染面（差分计数不增即横幅缺失）" +
                "——idle=${countColor(idlePixels, errorColor)} reject=${countColor(rejectPixels, errorColor)}",
        )
    }

    @Test
    fun `done state renders result card with removed and added line colors`() {
        val old = tempFile(
            "old.txt",
            listOf(
                "context line one",
                "beta payload line for removed color probe",
                "context line two",
            ),
        )
        val new = tempFile(
            "new.txt",
            listOf(
                "context line one",
                "BETA payload line for added color probe",
                "context line two",
                "delta tail line for added color probe too",
            ),
        )
        val vm = LogDiffViewModel().also { createdVms += it }
        vm.onFilesSelected(LogDiffViewModel.Slot.OLD, listOf(old))
        vm.onFilesSelected(LogDiffViewModel.Slot.NEW, listOf(new))
        vm.startDiff()
        awaitState { vm.diffState.value is LogDiffViewModel.DiffUiState.Done }

        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }
        val primaryColor = probeSchemeColor { MaterialTheme.colorScheme.primary }
        val idlePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(LogDiffViewModel().also { createdVms += it })) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val donePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(vm)) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val bands = contentRows(donePixels, SCENE_W)
        assertTrue(
            bands.size >= 4,
            "Done 态应渲染 ≥4 内容带（双槽/开关/按钮/结果卡），实际 ${bands.size}：$bands",
        )
        // 结果卡内部行块（hairline 描边 + 行级着色底 + 文本行）合法产生 ≤3px 间隙带，
        // 原始带互斥只断不倒挂；卡在场与行级着色由带数 ≥4 与色值差分断言兜底
        assertBandsNotOverlapping(bands)
        // 差分基线：页内「开始对比」按钮常驻 primary 着色、空态无 error 着色——
        // 与同尺寸 idle 渲染做差，增量只能来自结果卡的行级着色
        val errGain = countColor(donePixels, errorColor) - countColor(idlePixels, errorColor)
        val primaryGain = countColor(donePixels, primaryColor) - countColor(idlePixels, primaryColor)
        assertTrue(
            errGain >= 10,
            "REMOVED 行应着 error 色进入渲染面，增量 $errGain < 10——行级删除着色缺失",
        )
        assertTrue(
            primaryGain >= 30,
            "ADDED 行应着 primary 色进入渲染面，增量 $primaryGain < 30——行级新增着色缺失",
        )
    }

    @Test
    fun `distribution compare done renders compare card and hides normalize switch`() {
        // threadtime 头行形态（时间戳 + pid tid 级别 Tag : msg）：块识别与 Tag/级别
        // 提取的最小可解析面；对比侧多出两个新 Tag + E/F 各一块，保证对照卡非空
        val old = tempFile(
            "old.txt",
            listOf(
                "10-11 09:00:00.000  100  200 I Alpha : steady line one",
                "10-11 09:00:01.000  100  200 E Beta : error line one",
            ),
        )
        val new = tempFile(
            "new.txt",
            listOf(
                "10-11 09:00:00.000  100  200 I Alpha : steady line one",
                "10-11 09:00:01.000  100  200 E Beta : error line one",
                "10-11 09:00:02.000  100  200 F Gamma : fatal crash appeared",
                "10-11 09:00:03.000  100  200 E Delta : late error arrived",
            ),
        )
        val vm = LogDiffViewModel().also { createdVms += it }
        vm.setMode(LogDiffViewModel.DiffMode.DISTRIBUTION)
        vm.onFilesSelected(LogDiffViewModel.Slot.OLD, listOf(old))
        vm.onFilesSelected(LogDiffViewModel.Slot.NEW, listOf(new))
        vm.startDiff()
        awaitState { vm.compareState.value is LogDiffViewModel.CompareUiState.Done }
        val done = vm.compareState.value as LogDiffViewModel.CompareUiState.Done
        assertTrue(
            done.compare.newTags.isNotEmpty(),
            "前置：分布对照须产出新增 Tag 面（两份分布一致的输入会让对照卡差分锚空转）",
        )

        val lineIdlePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(LogDiffViewModel().also { createdVms += it })) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val distIdlePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides seededOwner(
                    LogDiffViewModel().also { createdVms += it }
                        .apply { setMode(LogDiffViewModel.DiffMode.DISTRIBUTION) },
                ),
            ) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val donePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(vm)) {
                HostWrappedColumn { LogDiffPageContent() }
            }
        }
        val lineIdleBands = contentRows(lineIdlePixels, SCENE_W)
        val distIdleBands = contentRows(distIdlePixels, SCENE_W)
        val doneBands = contentRows(donePixels, SCENE_W)
        assertTrue(
            doneBands.size >= 4,
            "分布 Done 态应渲染 ≥4 内容带（双槽/模式选择器/按钮/对照卡），实际 ${doneBands.size}：$doneBands",
        )
        assertBandsSeparated(doneBands)
        assertTrue(
            doneBands.size > distIdleBands.size,
            "分布对照卡应作为新增内容带进入渲染面（done=${doneBands.size} ≤ 分布 idle=${distIdleBands.size}）——对照卡缺席",
        )
        // 态区分锚：归一化开关是行级比对键开关，分布模式（块级统计不比行）下该带
        // 必须缺席——泄漏进分布模式即「开关在场语义自污」回归
        assertTrue(
            lineIdleBands.size > distIdleBands.size,
            "归一化开关仅行级模式可见：行级 idle 带数 ${lineIdleBands.size} 应多于分布 idle 带数 ${distIdleBands.size}" +
                "——两向模式的开关可见性区分断裂（分布模式泄漏开关或行级丢失开关均红）",
        )
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 902
    }
}
