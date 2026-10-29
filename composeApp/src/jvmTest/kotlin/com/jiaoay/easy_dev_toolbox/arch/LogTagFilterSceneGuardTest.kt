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
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitConfigStore
import com.jiaoay.easy_dev_toolbox.page.logtag.LogTagFilterPage
import com.jiaoay.easy_dev_toolbox.page.logtag.LogTagFilterViewModel
import com.jiaoay.easy_dev_toolbox.page.logtag.LogTagStatsState
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import java.util.UUID

/**
 * Tag 过滤页「正则预演区 + 分布统计面板」场景渲染像素带守护：
 * ImageComposeScene 零显示器配方（[SceneGuardSupport] 单源）扩到「预驱动 VM 状态注入」
 * 形态——[LogTagFilterPage] 经 viewModel{} 自建 VM、无状态注入口，测试侧把预驱动到
 * 目标态的 VM 经 ViewModelProvider 同 key 通道预置进独立 ViewModelStore，组合期
 * viewModel() 缓存命中即返回该 VM（不改生产面）。
 *
 * 验证面（两块新 UI 后续布局改动的回归网）：
 * - 内容带互斥：参数区两输入框 / 预演卡 / 「看分布」按钮 / 统计面板卡各带间
 *   背景间隙 > 3px（叠放/粘连回归）；
 * - 预演卡在场（增量差分锚）：空正则渲染基线 → 输入合法正则再渲染，内容带数与
 *   内容纵向占位必须增长——预演卡缺席（门控回归/状态链断）即红；
 * - 统计面板 Tag 频次行的 primary 着色（可点回填 affordance 的视觉锚——该着色
 *   被误改则「可点行」退化为不可点行文，用户无从发现可点）。
 *
 * 状态驱动走既有 logtag VM 测试同款配方（setMain(StandardTestDispatcher) + 真实 IO
 * 轮询）。拖批刻意走「正则/标记留空被 validate 拒」路径——拒批也照常捕获预演锚点
 * （VM KDoc 契约），且任务卡不自动进入执行态，免在途动画与真实过滤 IO 给场景引入
 * 不可控噪带；拒收横幅与预演卡在输入正则时耦合换位（清横幅/出预演卡），故预演卡
 * 在场锚取增量差分而非带数下限。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogTagFilterSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-logtag-scene")
    private val dispatcher = StandardTestDispatcher()
    private val createdVms = mutableListOf<LogTagFilterViewModel>()
    private val tempDirs = mutableListOf<java.io.File>()

    /**
     * DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：Unconfined
     * 让 DataStore 文件 IO 在调用线程同步落定——drain 即全静默，无真实 IO 线程续体
     * 与 resetMain 的竞态。🔴 不得改与 Main 共享 StandardTestDispatcher 的 scope：
     * DataStore 挂起读写经 withContext 跳到注入 scope，本 VM 的持久化链路若改挂
     * 测试调度器会因无人推进而死锁。
     */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        // 经 LogKitConfigStore 测试缝换入受控 store（redirect 窗口内 eager 钉死目录）
        originalStore = LogKitConfigStore.store
        LogKitConfigStore.store = LocalDataManager(
            dataStoreName = "test_logtag_scene_${UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
    }

    @AfterTest
    fun tearDown() {
        // DataStore IO 已随 Unconfined scope 同步落定——drain 即静默；VM 的文件统计
        // IO（真实 ioDispatcher）由用例内 awaitState 等待落定后再走到这里
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        LogKitConfigStore.store = originalStore
        Dispatchers.resetMain()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        homeIsolation.restoreOnly()
    }

    // ── 场景渲染底座：[SceneGuardSupport] 单源（本包） ─────────────────────────

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

    // ── VM 状态注入底座 ────────────────────────────────────────────────────

    /**
     * 预驱动 VM 注入独立 ViewModelStore：组合期 viewModel() 与本函数走同一
     * ViewModelProvider.get(key, KClass) 通道（同 key 即缓存命中），返回该 owner
     * 经 CompositionLocalProvider 提供给场景内容。store 不 clear（clear 会触发
     * onCleared 并关闭 viewModelScope），tearDown 统一 cancel 兜底。
     */
    private fun seededOwner(vm: LogTagFilterViewModel): ViewModelStoreOwner {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm as T
        }
        ViewModelProvider.create(owner, factory, CreationExtras.Empty).get(LogTagFilterViewModel::class)
        return owner
    }

    /** drain init 恢复协程后再交互：init 的 DataStore 读经注入 Unconfined scope 同步落定，排空 init 协程防其覆盖已输入正则 */
    private fun drainInit() {
        repeat(10) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
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

    /** 统计 fixture：头行块须可被既有块规则识别（brief 形态 E/Tag），含 Alpha 多块 */
    private fun statsFixtureFile(): java.io.File {
        val content = buildString {
            appendLine("2026-10-11 08:00:00.000 E/Alpha: block-2 head")
            appendLine("continuation block-2")
            appendLine("2026-10-11 08:00:01.000 W/Beta(1234): block-3 head")
            appendLine("2026-10-11 08:00:02.000 E/Alpha: block-4 head")
            appendLine("2026-10-11 08:00:05.000 I/Gamma: block-5 head")
        }
        val dir = Files.createTempDirectory("edt-logtag-scene").toFile()
        tempDirs += dir
        return java.io.File(dir, "stats-fixture.txt").apply { writeText(content) }
    }

    // ── 用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `preview card and stats panel render as separated bands with primary tag rows`() {
        val vm = LogTagFilterViewModel().also { createdVms += it }
        drainInit()
        // 基线态：正则/标记留空拖入 → 整批被拒（预演锚点照常捕获——VM KDoc 契约），
        // 任务卡不自动进入执行态；「看分布」对锚点文件全文件统计
        vm.addFiles(listOf(statsFixtureFile()))
        vm.requestStats()
        awaitState { vm.statsState.value is LogTagStatsState.Ready }
        assertTrue(vm.regexPreview.value == null, "前置：空正则不触发预演（否则差分基线失效）")

        val primaryColor = probeSchemeColor { MaterialTheme.colorScheme.primary }
        val baselinePixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(vm)) {
                HostWrappedColumn { LogTagFilterPage(embedded = true) }
            }
        }
        val baselineBands = contentRows(baselinePixels, SCENE_W)
        assertTrue(
            baselineBands.size >= 4,
            "统计就绪基线应渲染 ≥4 内容带（正则框/标记框/看分布按钮+统计卡/拖放区），" +
                "实际 ${baselineBands.size}：$baselineBands",
        )
        assertBandsSeparated(baselineBands)

        // 预演态：输入合法正则 → 预演卡出现（拒收横幅同拍被清——耦合换位，在场锚取差分）
        vm.onTagRegexChange("Alpha")
        awaitState { vm.regexPreview.value?.matchedBlocks?.let { it > 0 } == true }

        val previewPixels = renderPixels(SCENE_W, SCENE_H) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides seededOwner(vm)) {
                HostWrappedColumn { LogTagFilterPage(embedded = true) }
            }
        }
        val previewBands = contentRows(previewPixels, SCENE_W)
        assertBandsSeparated(previewBands)
        assertTrue(
            previewBands.size >= baselineBands.size + 1,
            "预演卡出现后内容带数应至少 +1（基线 ${baselineBands.size} → 实际 ${previewBands.size}）" +
                "——预演卡缺席（门控回归或预演状态链断）",
        )
        assertTrue(
            previewBands.last().last >= baselineBands.last().last + 20,
            "预演卡应把页面内容纵向占位推升 ≥20px（基线末带底 ${baselineBands.last().last} → " +
                "实际 ${previewBands.last().last}）——预演卡以零占位缺席",
        )
        assertTrue(
            countColor(previewPixels, primaryColor) >= 15,
            "统计面板 Tag 频次行应以 primary 着色渲染（可点回填 affordance 视觉锚）——" +
                "计数 ${countColor(previewPixels, primaryColor)} < 15：Ready 面板缺失或频次行着色退化",
        )
    }

    companion object {
        private const val SCENE_W = 1096

        /**
         * 场景视口须容纳统计面板（Ready 态）+ 预演卡 + 拖放区全量内容：
         * 统计面板加「送飞书」钮后页面变高，902px 视口下预演卡把拖放区推出
         * 视口（末带差分锚退化为卡内带，+20px 增量断言失真）——视口加高到
         * 全量可见，差分锚才恒指拖放区。
         */
        private const val SCENE_H = 1150
    }
}
