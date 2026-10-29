package com.jiaoay.easy_dev_toolbox.page.deviceobs

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceLayoutBounds
import com.jiaoay.toolbelt.device.DeviceLayoutDump
import com.jiaoay.toolbelt.device.DeviceLayoutNode
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.DeviceWindowViewsDump
import com.jiaoay.toolbelt.device.DeviceWindowViewsEntry
import com.jiaoay.toolbelt.device.WindowViewNode

/**
 * 布局面板 VM 回归网（fake 设备源 + fake 双检查内核注入，零真子进程）：
 * - 装载语义：显式抓取装载树、失败人话回执且不覆盖旧快照、loading 单飞双点拒、
 *   在途抓取被目标变更作废（迟到快照不上屏）、设备流启动期首拍不误清已抓快照；
 * - 降级编排：主源（全窗口视图）成功零降级 spawn、主源失败显式降级 uiautomator
 *   （人话原因透传 + 能力差标注）、双源都失败组合回执；
 * - 窗口选择：切窗换树清展开/选中（过滤词保留）、越界/降级源无动作；
 * - 懒展开语义：默认展开深度（根下两层）、点开/收起记忆覆盖默认、重抓清空展开
 *   与选中态（过滤词保留）；
 * - 搜索过滤矩阵：text/resource-id/class 三字段包含匹配（大小写不敏感）、命中
 *   祖先路径自动展开直达、全树零命中投影为空、查询态用户收起不被命中重新顶开；
 * - 目标门控：无设备/多设备未选/检测失败三态同步拒绝零 spawn（人话回执）、
 *   目标从有到无清快照回执失效原因、串号实变清快照不自动重抓；
 * - 选中语义：路径选中解析节点、再点同节点收起；预览命中揭示（revealNode）展开
 *   祖先路径 + 选中不取反、路径不可解析无动作；展开箭头附带选中（不取反）；
 * - 子树视图栈（双击进子树状态机）：进子树重投影（行 id 恒窗口绝对路径）/ 同点
 *   连击不长栈 / 子树内再进另一层（栈式历史）/ 退出一次退一层 / 重抓成功、切窗、
 *   换设备复位 / refresh 失败随旧快照保留 / reveal 命中子树外自动退出、命中内保留；
 * - text 按需补抓：单飞双点拒、快照来源缺席/抓取在途人话拒绝零 spawn、join
 *   成功/未 join 到/失败三态回执、refresh 进入作废在途补抓且 busy 位随作废方
 *   复位（迟到回执零状态写入）、目标变更作废、取消静默归零、选中变化不残留；
 * - joinTextByBounds 纯函数：bounds 逐值相等第一优先、重叠 ≥0.5 兜底、双空 null；
 * - 纯函数边缘：路径解析越界/非数字段拒绝、class/resource-id 简名（NO_ID 归缺）、
 *   bounds/标记/可见性/窗口偏移文本。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLayoutViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** fake 设备三流直驱（无轮询环无 DataStore——VM 只吃三流读值） */
    private class FakeDeviceSource : DeviceStateSource {
        val deviceStateFlow = MutableStateFlow<DeviceState>(DeviceState.None)
        override val deviceState: StateFlow<DeviceState> = deviceStateFlow.asStateFlow()

        val detectErrorFlow = MutableStateFlow<String?>(null)
        override val detectError: StateFlow<String?> = detectErrorFlow.asStateFlow()

        val selectedSerialFlow = MutableStateFlow<String?>(null)
        override val selectedSerial: StateFlow<String?> = selectedSerialFlow.asStateFlow()
    }

    /** fake 双检查内核：记录调用序（断言锚），脚本路由回执（在途用例可挂起） */
    private class FakeKernel(
        private val windowViewsScript: suspend (serial: String, ordinal: Int) -> Result<DeviceWindowViewsDump> =
            { _, _ -> Result.failure(IllegalStateException("全窗口视图未脚本化")) },
        private val legacyScript: suspend (serial: String, ordinal: Int) -> Result<DeviceLayoutDump> =
            { _, _ -> Result.failure(IllegalStateException("uiautomator 未脚本化")) },
    ) {
        val windowViewsCalls = mutableListOf<String>()
        val legacyCalls = mutableListOf<String>()

        val dumpWindowViews: suspend (String) -> Result<DeviceWindowViewsDump> = { serial ->
            windowViewsCalls += serial
            windowViewsScript(serial, windowViewsCalls.size)
        }

        val dumpHierarchy: suspend (String) -> Result<DeviceLayoutDump> = { serial ->
            legacyCalls += serial
            legacyScript(serial, legacyCalls.size)
        }
    }

    // ── 夹具：全窗口视图源节点 ──

    private fun wnode(
        className: String = "android.widget.FrameLayout",
        viewId: String = "NO_ID",
        clickable: Boolean = false,
        windowLeft: Int? = null,
        windowTop: Int? = null,
        children: List<WindowViewNode> = emptyList(),
    ) = WindowViewNode(
        className = className,
        viewHash = "",
        viewId = viewId,
        bounds = DeviceLayoutBounds(0, 0, 100, 100),
        windowLeft = windowLeft,
        windowTop = windowTop,
        visibility = 0,
        clickable = clickable,
        enabled = true,
        selected = false,
        activated = false,
        padding = null,
        childCount = children.size,
        children = children,
        properties = emptyMap(),
    )

    private fun wdumpOf(vararg windows: WindowViewNode) = DeviceWindowViewsDump(
        windows.mapIndexed { index, root ->
            DeviceWindowViewsEntry(
                windowHash = "h$index",
                title = "win$index",
                packageName = null,
                windowLeft = root.windowLeft,
                windowTop = root.windowTop,
                root = root,
            )
        },
    )

    /** 四层树（懒展开断言面，id 作识别锚）：R(0) → A(0/0) → A1(0/0/0) → A1a(0/0/0/0)，另有 B(0/1) */
    private fun deepWindowTree() = wnode(
        viewId = "id/r",
        children = listOf(
            wnode(viewId = "id/a", children = listOf(wnode(viewId = "id/a1", children = listOf(wnode(viewId = "id/a1a"))))),
            wnode(viewId = "id/b"),
        ),
    )

    // ── 夹具：uiautomator 降级源节点 ──

    private fun node(
        text: String = "",
        resourceId: String = "",
        className: String = "android.widget.FrameLayout",
        clickable: Boolean = false,
        bounds: DeviceLayoutBounds = DeviceLayoutBounds(0, 0, 100, 100),
        children: List<DeviceLayoutNode> = emptyList(),
    ) = DeviceLayoutNode(
        index = 0,
        text = text,
        resourceId = resourceId,
        className = className,
        packageName = "com.demo",
        contentDesc = "",
        checkable = false,
        checked = false,
        clickable = clickable,
        enabled = true,
        focusable = false,
        focused = false,
        scrollable = false,
        longClickable = false,
        password = false,
        selected = false,
        bounds = bounds,
        children = children,
    )

    private fun dumpOf(root: DeviceLayoutNode) = DeviceLayoutDump(rotation = 0, root = root)

    /** 四层降级树（文本面） */
    private fun deepLegacyTree(): DeviceLayoutNode = node(
        text = "R",
        children = listOf(
            node(text = "A", children = listOf(node(text = "A1", children = listOf(node(text = "A1a"))))),
            node(text = "B"),
        ),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 推进虚拟时钟到收敛（fake 内核同步回执，一轮推进即收敛） */
    private fun DeviceLayoutViewModel.settle() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun rowIds(state: DeviceLayoutPanelState): List<String> = state.rows.map { it.id }

    private fun vmOf(
        devices: FakeDeviceSource,
        kernel: FakeKernel,
    ) = DeviceLayoutViewModel(devices, kernel.dumpWindowViews, kernel.dumpHierarchy)

    /** 主源成功装载的默认编排（多数用例的前置态） */
    private fun loadedWindowViewsVm(
        kernel: FakeKernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
        ),
    ): Pair<FakeDeviceSource, DeviceLayoutViewModel> {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()
        return devices to vm
    }

    // ── 装载语义（主源） ──

    @Test
    fun `refresh loads tree with default lazy expand depth`() {
        val (_, vm) = loadedWindowViewsVm()

        val state = vm.state.value
        assertNull(state.error)
        assertFalse(state.loading)
        assertEquals(LayoutSnapshotSource.WINDOW_VIEWS, state.source)
        assertNull(state.degradedNotice)
        assertEquals("S1", state.dumpSerial, "refresh 成功落快照来源串号（投屏点亮门控比对锚）")
        assertEquals(listOf("0", "0/0", "0/0/0", "0/1"), rowIds(state), "默认展开根下两层，深层收起（深度优先展开序）")
        assertTrue(state.rows.first { it.id == "0" }.expanded, "根默认展开")
        assertTrue(state.rows.first { it.id == "0/0" }.expanded, "深度 1 默认展开")
        val a1 = state.rows.first { it.id == "0/0/0" }
        assertTrue(a1.hasChildren)
        assertFalse(a1.expanded, "深度 2 起默认收起（懒展开——设备层级上千节点不全量铺开）")
        assertEquals(5, state.nodeCount)
    }

    @Test
    fun `window views success skips legacy kernel`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
        )
        loadedWindowViewsVm(kernel)
        assertEquals(1, kernel.windowViewsCalls.size)
        assertEquals(emptyList(), kernel.legacyCalls, "主源成功零降级 spawn")
    }

    @Test
    fun `toggle expands collapsed node and second toggle collapses it`() {
        val (_, vm) = loadedWindowViewsVm()

        vm.toggleNode("0/0/0")
        assertTrue(rowIds(vm.state.value).contains("0/0/0/0"), "点开收起层后深层节点进投影")
        vm.toggleNode("0/0/0")
        assertFalse(
            rowIds(vm.state.value).contains("0/0/0/0"),
            "再点收起（展开记忆写 false 覆盖默认收起后的显式展开）",
        )
    }

    @Test
    fun `collapse of default expanded root hides children`() {
        val (_, vm) = loadedWindowViewsVm()

        vm.toggleNode("0")
        assertEquals(listOf("0"), rowIds(vm.state.value), "收起默认展开的根（记忆 false 覆盖默认展开）")
    }

    @Test
    fun `expand all reveals deep nodes and collapse all leaves root only`() {
        val (_, vm) = loadedWindowViewsVm()

        vm.expandAllNodes()
        assertEquals(
            vm.state.value.root?.descendantCount ?: 0,
            rowIds(vm.state.value).size,
            "整树展开后全部节点进投影（深树 fragment 元素一键可见）",
        )
        vm.collapseAllNodes()
        assertEquals(listOf("0"), rowIds(vm.state.value), "整树收起回到只剩根行")
    }

    // ── 降级编排 ──

    @Test
    fun `window views failure falls back to uiautomator with explicit notice`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.failure(IllegalStateException("未取回窗口视图 ZIP（设备侧退出码 1）")) },
            legacyScript = { _, _ -> Result.success(dumpOf(deepLegacyTree())) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)

        val state = vm.state.value
        assertEquals(LayoutSnapshotSource.UI_AUTOMATOR, state.source)
        assertTrue(
            state.degradedNotice?.contains("未取回窗口视图 ZIP") == true,
            "降级人话透传主源失败原因：${state.degradedNotice}",
        )
        assertTrue(state.degradedNotice?.contains("uiautomator") == true, "降级人话标注降级源")
        assertTrue(state.rows.isNotEmpty(), "降级快照树进投影")
        assertEquals(1, kernel.windowViewsCalls.size)
        assertEquals(1, kernel.legacyCalls.size, "主源失败触发一次降级 spawn")
    }

    @Test
    fun `both kernels failing combine receipts`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.failure(IllegalStateException("主源原因甲")) },
            legacyScript = { _, _ -> Result.failure(IllegalStateException("降级原因乙")) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()

        val state = vm.state.value
        assertTrue(state.error?.contains("主源原因甲") == true, "组合回执含主源原因：${state.error}")
        assertTrue(state.error?.contains("降级原因乙") == true, "组合回执含降级原因：${state.error}")
        assertNull(state.root)
        assertNull(state.dumpSerial, "全失败不落快照来源串号")
    }

    // ── 装载语义（失败/竞态） ──

    @Test
    fun `refresh failure keeps old snapshot and lands human receipt`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, ordinal ->
                if (ordinal == 1) {
                    Result.success(wdumpOf(deepWindowTree()))
                } else {
                    Result.failure(IllegalStateException("adb 执行失败：boom"))
                }
            },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()
        vm.refresh()
        vm.settle()

        val state = vm.state.value
        assertEquals(2, kernel.windowViewsCalls.size)
        assertTrue(state.error?.contains("adb 执行失败：boom") == true, "失败回执人话原样：${state.error}")
        assertTrue(state.root != null, "失败不覆盖旧快照（重试语义，旧树留屏）")
        assertEquals("S1", state.dumpSerial, "失败不覆盖旧快照的来源串号（旧快照与其来源绑定不清）")
        assertFalse(state.loading)
    }

    @Test
    fun `refresh failure on fresh vm lands receipt without snapshot`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.failure(IllegalStateException("未取回窗口层级 XML")) },
            legacyScript = { _, _ -> Result.failure(IllegalStateException("uiautomator 也失败")) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()

        val state = vm.state.value
        assertNull(state.root)
        assertTrue(state.error?.contains("未取回窗口层级 XML") == true)
    }

    @Test
    fun `loading single flight rejects second refresh`() {
        val gate = CompletableDeferred<DeviceWindowViewsDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(gate.await()) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()
        assertTrue(vm.state.value.loading, "在途抓取 busy 位上屏")

        vm.refresh()
        vm.settle()
        assertEquals(1, kernel.windowViewsCalls.size, "在途抓取中再点被拒（单飞防双发）")

        gate.complete(wdumpOf(deepWindowTree()))
        vm.settle()
        assertFalse(vm.state.value.loading, "完成后 busy 落位")
        assertTrue(vm.state.value.root != null)
        assertEquals(1, kernel.windowViewsCalls.size)
    }

    @Test
    fun `stale dump is dropped after target switch`() {
        val gate = CompletableDeferred<DeviceWindowViewsDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(gate.await()) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()
        assertTrue(vm.state.value.loading)

        devices.deviceStateFlow.value = DeviceState.Single("S2", "Demo-1")
        vm.settle()
        assertFalse(vm.state.value.loading, "目标变更中止在途态")
        assertNull(vm.state.value.root, "不同设备的层级不可互推——快照清空")

        gate.complete(wdumpOf(deepWindowTree()))
        vm.settle()
        assertNull(vm.state.value.root, "迟到快照不上屏（请求序号守卫）")
        assertEquals(1, kernel.windowViewsCalls.size, "换目标不自动重抓（显式动作契约）")
    }

    @Test
    fun `first device flow emission keeps user refreshed snapshot`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        // 不先 settle：设备流首拍未跑，用户先显式抓取
        vm.refresh()
        vm.settle()

        val state = vm.state.value
        assertTrue(state.root != null, "启动期首拍（串号与抓取串号一致）不误清已抓快照")
        assertNull(state.error)
    }

    // ── 窗口选择 ──

    @Test
    fun `select window switches tree and resets expansion keeps query`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ ->
                Result.success(
                    wdumpOf(
                        wnode(viewId = "id/win0", children = listOf(wnode(viewId = "id/w0child"))),
                        wnode(viewId = "id/win1", children = listOf(wnode(viewId = "id/w1child"))),
                    ),
                )
            },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)

        vm.setQuery("w1")
        vm.selectWindow(1)
        val state = vm.state.value
        assertEquals(1, state.selectedWindowIndex)
        assertEquals("id/win1", state.root?.resourceId, "切窗换树")
        assertEquals(emptyMap(), state.expanded, "切窗清展开记忆（对另一棵树无意义）")
        assertNull(state.selectedNodeId, "切窗复位选中态")
        assertEquals("w1", state.query, "切窗保留过滤词（显示偏好）")
        assertTrue(rowIds(state).contains("0/0"), "新窗口树进投影")

        vm.selectWindow(99)
        assertEquals(1, vm.state.value.selectedWindowIndex, "越界下标无动作（查询面不猜不修正）")
    }

    @Test
    fun `select window is no action on legacy source`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.failure(IllegalStateException("主源不可用")) },
            legacyScript = { _, _ -> Result.success(dumpOf(deepLegacyTree())) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)

        vm.selectWindow(0)
        assertEquals(0, vm.state.value.selectedWindowIndex)
        assertEquals(LayoutSnapshotSource.UI_AUTOMATOR, vm.state.value.source, "降级源无窗可选（无动作）")
    }

    // ── 搜索过滤矩阵（uiautomator 降级源，含 text 面） ──

    /** 过滤矩阵树：R(0) → N1(0/0, class=ViewGroup) → N1a(0/0/0, text/resource-id 命中面)；N2(0/1) */
    private fun filterTree(): DeviceLayoutNode = node(
        children = listOf(
            node(
                className = "android.view.ViewGroup",
                children = listOf(
                    node(
                        text = "SettingsEntry",
                        resourceId = "com.demo:id/settings",
                        className = "android.widget.Button",
                        clickable = true,
                    ),
                ),
            ),
            node(text = "按钮", className = "android.widget.Button"),
        ),
    )

    private fun loadedFilterVm(): Pair<FakeDeviceSource, DeviceLayoutViewModel> {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.failure(IllegalStateException("主源不可用")) },
            legacyScript = { _, _ -> Result.success(dumpOf(filterTree())) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()
        return devices to vm
    }

    @Test
    fun `query matches text case insensitively and auto expands ancestors`() {
        val (_, vm) = loadedFilterVm()
        vm.setQuery("settings")
        val state = vm.state.value
        assertEquals(listOf("0", "0/0", "0/0/0"), rowIds(state), "命中节点祖先路径自动展开直达（未命中枝不进投影）")
        assertEquals(listOf(false, false, true), state.rows.map { it.matchesQuery }, "命中标记只落命中行")
        assertTrue(state.rows.first { it.id == "0/0" }.expanded, "命中祖先自动展开")
    }

    @Test
    fun `query matches resource id`() {
        val (_, vm) = loadedFilterVm()
        vm.setQuery("id/settings")
        assertEquals(listOf("0", "0/0", "0/0/0"), rowIds(vm.state.value), "resource-id 包含匹配")
    }

    @Test
    fun `query matches class`() {
        val (_, vm) = loadedFilterVm()
        vm.setQuery("viewgroup")
        assertEquals(listOf("0", "0/0"), rowIds(vm.state.value), "class 包含匹配（大小写不敏感）；命中节点的未命中子层不进投影")
        assertTrue(vm.state.value.rows.first { it.id == "0/0" }.matchesQuery)
    }

    @Test
    fun `window views source query matches id and class`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ ->
                Result.success(
                    wdumpOf(
                        wnode(
                            className = "android.view.ViewGroup",
                            children = listOf(
                                wnode(viewId = "id/settings", className = "android.widget.Button", clickable = true),
                            ),
                        ),
                        wnode(viewId = "id/other"),
                    ),
                )
            },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)

        vm.setQuery("SETTINGS")
        val state = vm.state.value
        assertEquals(listOf("0", "0/0"), rowIds(state), "全窗口视图源 id 命中（大小写不敏感；窗口根即树根，命中节点的祖先路径在投影）")
        assertTrue(state.rows.first { it.id == "0/0" }.matchesQuery, "命中标记落命中行")
        assertFalse(state.rows.first { it.id == "0" }.matchesQuery, "根是路径节点非命中节点")

        vm.setQuery("text不存在词")
        assertEquals(emptyList(), rowIds(vm.state.value), "全树零命中投影为空（含根）")
    }

    @Test
    fun `query with no hit projects empty`() {
        val (_, vm) = loadedFilterVm()
        vm.setQuery("不存在的词xyz")
        assertEquals(emptyList(), rowIds(vm.state.value), "全树零命中投影为空（含根）")
    }

    @Test
    fun `user collapse in query mode overrides hit auto expand`() {
        val (_, vm) = loadedFilterVm()
        vm.setQuery("settings")
        vm.toggleNode("0/0")
        val state = vm.state.value
        assertEquals(
            listOf("0", "0/0"),
            rowIds(state),
            "查询态收起的子树不被命中重新顶开（记忆优先于自动展开）",
        )
        val ancestor = state.rows.first { it.id == "0/0" }
        assertFalse(ancestor.expanded, "祖先被用户收起")
        assertFalse(ancestor.matchesQuery, "祖先是路径节点非命中节点")
    }

    // ── 重抓与选中语义 ──

    @Test
    fun `re dump clears expansion and selection but keeps query`() {
        val (_, vm) = loadedFilterVm()
        vm.toggleNode("0/0/0")
        vm.selectNode("0/0")
        vm.setQuery("按钮")
        vm.refresh()
        vm.settle()

        val state = vm.state.value
        assertEquals(emptyMap(), state.expanded, "重抓清空展开记忆（新树对旧树无意义）")
        assertNull(state.selectedNodeId, "重抓复位选中态")
        assertEquals("按钮", state.query, "重抓保留过滤词（显示偏好，与文件/应用子页同口径）")
        assertTrue(state.rows.isNotEmpty(), "rows 投影随新快照重算")
    }

    @Test
    fun `select node resolves node and reselect collapses`() {
        val (_, vm) = loadedFilterVm()
        vm.selectNode("0/0")
        val state = vm.state.value
        assertEquals("0/0", state.selectedNodeId)
        assertEquals("android.view.ViewGroup", state.selectedNode?.className, "路径选中解析到节点")

        vm.selectNode("0/0")
        assertNull(vm.state.value.selectedNodeId, "再点同节点收起")
        assertNull(vm.state.value.selectedNode)
    }

    @Test
    fun `reveal node expands ancestor path and selects without toggle`() {
        val (_, vm) = loadedWindowViewsVm()
        vm.collapseAllNodes()
        assertEquals(listOf("0"), rowIds(vm.state.value), "前置：整树收起")

        vm.revealNode("0/0/0/0")
        val state = vm.state.value
        assertEquals("0/0/0/0", state.selectedNodeId, "预览命中直接选中（收起态命中的揭示意图）")
        assertEquals("id/a1a", state.selectedNode?.resourceId, "路径选中解析到节点")
        assertEquals(
            listOf("0", "0/0", "0/0/0", "0/0/0/0", "0/1"),
            rowIds(state),
            "祖先路径全展开，深层命中节点进树投影（深度优先展开序）",
        )

        vm.revealNode("0/0/0/0")
        assertEquals("0/0/0/0", vm.state.value.selectedNodeId, "再点同节点保持选中（预览命中不取反——收起是树行的显式 affordance）")
    }

    @Test
    fun `reveal node rejects unresolvable path without polluting expansion`() {
        val (_, vm) = loadedWindowViewsVm()
        vm.collapseAllNodes()
        vm.revealNode("0/9/9")
        val state = vm.state.value
        assertNull(state.selectedNodeId, "路径不可解析无动作（查询面不猜不修正）")
        assertEquals(listOf("0"), rowIds(state), "展开记忆不被污染")
    }

    // ── 子树视图栈（双击进子树状态机） ──

    @Test
    fun `enter subtree re-projects rows with absolute ids and back pops one level`() {
        val (_, vm) = loadedWindowViewsVm()

        vm.enterSubtree("0/0")
        var state = vm.state.value
        assertEquals(listOf("0/0"), state.subtreeStack, "进子树入栈（尾元素 = 当前投影根）")
        assertEquals(
            listOf("0/0", "0/0/0", "0/0/0/0"),
            rowIds(state),
            "投影重定到子树根：行 id 恒窗口绝对路径零换制；深度相对 base 重计，" +
                "默认展开深度随 base 重定（base 与其下层展开）",
        )
        assertEquals(0, state.rows.first().depth, "base 自身行深度 0（缩进随子树重定）")
        assertFalse(state.rows.first { it.id == "0/0/0/0" }.expanded, "孙层照默认深度收起")

        vm.enterSubtree("0/0")
        assertEquals(listOf("0/0"), vm.state.value.subtreeStack, "同点连击不长栈（栈顶已是该节点无动作）")

        vm.enterSubtree("0/0/0")
        state = vm.state.value
        assertEquals(listOf("0/0", "0/0/0"), state.subtreeStack, "子树内再进另一层：栈式视图历史")
        assertEquals(listOf("0/0/0", "0/0/0/0"), rowIds(state), "嵌套子树投影")

        vm.exitSubtree()
        assertEquals(listOf("0/0"), vm.state.value.subtreeStack, "返回一次退一层")
        vm.exitSubtree()
        state = vm.state.value
        assertEquals(emptyList(), state.subtreeStack, "退到空栈 = 全树态")
        assertEquals(listOf("0", "0/0", "0/0/0", "0/1"), rowIds(state), "全树投影复原")

        vm.exitSubtree()
        assertEquals(emptyList(), vm.state.value.subtreeStack, "空栈再退无动作")
    }

    @Test
    fun `enter subtree from full window diagram pushes sibling subtree onto stack`() {
        val (_, vm) = loadedWindowViewsVm()
        vm.enterSubtree("0/0")

        // 左图数据面恒全窗：子树态下双击当前子树外的节点照常入栈（视图历史语义）
        vm.enterSubtree("0/1")
        val state = vm.state.value
        assertEquals(listOf("0/0", "0/1"), state.subtreeStack, "子树外命中入栈不替换历史")
        assertEquals(listOf("0/1"), rowIds(state), "投影重定到兄弟子树")

        vm.exitSubtree()
        assertEquals(listOf("0/0"), vm.state.value.subtreeStack, "退一层回上一视图")
        assertEquals(listOf("0/0", "0/0/0", "0/0/0/0"), rowIds(vm.state.value))
    }

    @Test
    fun `subtree stack resets on re dump, window switch and device change`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ ->
                Result.success(
                    wdumpOf(
                        wnode(viewId = "id/win0", children = listOf(wnode(viewId = "id/w0child"))),
                        wnode(viewId = "id/win1", children = listOf(wnode(viewId = "id/w1child"))),
                    ),
                )
            },
        )
        val (devices, vm) = loadedWindowViewsVm(kernel)

        vm.enterSubtree("0/0")
        assertTrue(vm.state.value.subtreeStack.isNotEmpty(), "前置：已进子树")
        vm.refresh()
        vm.settle()
        assertEquals(emptyList(), vm.state.value.subtreeStack, "重抓成功复位子树栈（新树对旧栈无意义）")
        assertEquals(listOf("0", "0/0"), rowIds(vm.state.value), "重抓复位回全树投影")

        vm.enterSubtree("0/0")
        vm.selectWindow(1)
        val switched = vm.state.value
        assertEquals(emptyList(), switched.subtreeStack, "切窗复位子树栈（另一棵树对旧栈无意义）")
        assertEquals("id/win1", switched.root?.resourceId, "切窗换树")
        assertEquals(listOf("0", "0/0"), rowIds(switched), "新窗口全树投影")

        vm.enterSubtree("0/0")
        devices.deviceStateFlow.value = DeviceState.Single("S2", "Demo-1")
        vm.settle()
        val cleared = vm.state.value
        assertEquals(emptyList(), cleared.subtreeStack, "换设备随快照清空复位子树栈")
        assertEquals(emptyList(), cleared.rows, "快照清空投影随之清空")
        assertNull(cleared.root)
    }

    @Test
    fun `refresh failure keeps subtree stack with old snapshot`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, ordinal ->
                if (ordinal == 1) {
                    Result.success(wdumpOf(deepWindowTree()))
                } else {
                    Result.failure(IllegalStateException("adb 执行失败：boom"))
                }
            },
            legacyScript = { _, _ -> Result.failure(IllegalStateException("uiautomator 也挂了")) },
        )
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = vmOf(devices, kernel)
        vm.settle()
        vm.refresh()
        vm.settle()

        vm.enterSubtree("0/0")
        assertEquals(listOf("0/0", "0/0/0", "0/0/0/0"), rowIds(vm.state.value), "前置：子树投影在位")

        vm.refresh()
        vm.settle()
        val state = vm.state.value
        assertTrue(state.error?.contains("adb 执行失败：boom") == true, "失败回执人话原样")
        assertEquals(listOf("0/0"), state.subtreeStack, "失败不覆盖旧快照——子树栈随旧树保留（重试语义）")
        assertEquals(listOf("0/0", "0/0/0", "0/0/0/0"), rowIds(state), "旧子树投影留屏可继续查")
    }

    @Test
    fun `reveal outside current subtree exits subtree and inside keeps it`() {
        val (_, vm) = loadedWindowViewsVm()
        vm.enterSubtree("0/0")

        vm.revealNode("0/0/0/0")
        var state = vm.state.value
        assertEquals(listOf("0/0"), state.subtreeStack, "命中当前子树内不退栈")
        assertEquals("0/0/0/0", state.selectedNodeId)
        assertTrue(rowIds(state).contains("0/0/0/0"), "命中行进当前子树投影")

        vm.revealNode("0/1")
        state = vm.state.value
        assertEquals(emptyList(), state.subtreeStack, "命中当前子树外自动清栈回全树（不退出则展开的祖先不进投影）")
        assertEquals("0/1", state.selectedNodeId)
        assertTrue(rowIds(state).contains("0/1"), "全树投影纳入命中行——「图点击 → 树内可见定位」链路保通")
    }

    @Test
    fun `toggle node arrow also selects the row`() {
        val (_, vm) = loadedWindowViewsVm()

        vm.toggleNode("0/0/0")
        var state = vm.state.value
        assertEquals("0/0/0", state.selectedNodeId, "点展开箭头同时选中该行（选中→详情/图定位管道）")
        assertTrue(rowIds(state).contains("0/0/0/0"), "展开态照常取反")

        vm.toggleNode("0/0/0")
        state = vm.state.value
        assertEquals("0/0/0", state.selectedNodeId, "再点箭头保持选中（箭头是展开 affordance 非选中开关）")
        assertFalse(rowIds(state).contains("0/0/0/0"), "再点收起子层")
    }

    // ── 目标门控 ──

    @Test
    fun `target unavailable refresh lands receipt without spawn`() {
        // 无设备
        val none = FakeDeviceSource()
        val noneKernel = FakeKernel()
        val noneVm = DeviceLayoutViewModel(none, noneKernel.dumpWindowViews, noneKernel.dumpHierarchy)
        noneVm.settle()
        noneVm.refresh()
        assertTrue(noneVm.state.value.error?.contains("未检测到设备") == true)
        assertEquals(0, noneKernel.windowViewsCalls.size, "零 spawn")
        assertEquals(0, noneKernel.legacyCalls.size, "零 spawn（降级源同门控）")

        // 多设备未选
        val multi = FakeDeviceSource()
        multi.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("A", "Demo-1"), DeviceInfo("B", "Demo-1")))
        val multiKernel = FakeKernel()
        val multiVm = DeviceLayoutViewModel(multi, multiKernel.dumpWindowViews, multiKernel.dumpHierarchy)
        multiVm.settle()
        multiVm.refresh()
        assertTrue(multiVm.state.value.error?.contains("多设备连接") == true)
        assertEquals(0, multiKernel.windowViewsCalls.size + multiKernel.legacyCalls.size, "零 spawn")

        // 检测失败优先分支
        val detectFailed = FakeDeviceSource()
        detectFailed.detectErrorFlow.value = "adb not found"
        val detectKernel = FakeKernel()
        val detectVm = DeviceLayoutViewModel(detectFailed, detectKernel.dumpWindowViews, detectKernel.dumpHierarchy)
        detectVm.settle()
        detectVm.refresh()
        assertTrue(detectVm.state.value.error?.contains("设备检测失败") == true)
        assertEquals(0, detectKernel.windowViewsCalls.size + detectKernel.legacyCalls.size, "零 spawn")
    }

    @Test
    fun `target loss clears snapshot with invalidation receipt`() {
        val (devices, vm) = loadedFilterVm()
        assertTrue(vm.state.value.root != null, "前置：快照已装载")

        devices.deviceStateFlow.value = DeviceState.None
        vm.settle()

        val state = vm.state.value
        assertNull(state.root, "目标失效清快照（陈旧层级不冒充可读数）")
        assertNull(state.dumpSerial, "快照来源串号随快照清空")
        assertTrue(state.error?.contains("已不可用") == true, "回执失效原因：${state.error}")
        assertEquals(emptyList(), state.rows)
        assertEquals(emptyList(), state.windows, "窗口清单随快照清空")
    }

    @Test
    fun `serial switch clears snapshot without auto re dump`() {
        val (devices, vm) = loadedWindowViewsVm()
        assertTrue(vm.state.value.root != null)

        devices.deviceStateFlow.value = DeviceState.Single("S2", "Demo-1")
        vm.settle()

        val state = vm.state.value
        assertNull(state.root, "换设备清快照")
        assertNull(state.dumpSerial, "换设备清快照来源串号（不同设备的层级不可互推——门控比对锚随之复位）")
        assertNull(state.error, "显式动作契约——不自动重抓也不落回执")
        assertNull(state.source, "快照清空后数据源标注随之清空")
        assertEquals(emptyList(), state.rows, "快照清空后投影随之清空")
        assertEquals(emptyList(), state.windows, "窗口清单随快照清空")
    }

    // ── text 按需补抓（fetchNodeText 状态机） ──

    @Test
    fun `fetch text joins a11y node text on success`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ ->
                Result.success(dumpOf(node(text = "设置", bounds = DeviceLayoutBounds(0, 0, 100, 100))))
            },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)
        val target = vm.state.value.root!!

        vm.fetchNodeText(target)
        vm.settle()
        val state = vm.state.value
        assertFalse(state.textLoading, "补抓完成后 busy 位落位")
        assertEquals("text  设置", state.textResult, "join 成功回执 = text 前缀 + 节点原文")
        assertEquals(1, kernel.legacyCalls.size, "复用降级源注入缝，一次 spawn")
    }

    @Test
    fun `fetch text rejects without snapshot with human receipt and zero spawn`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakeKernel()
        val vm = vmOf(devices, kernel)
        vm.settle()

        vm.fetchNodeText(wnode())
        val state = vm.state.value
        assertTrue(
            state.textResult?.contains("快照来源不明") == true,
            "快照来源缺席人话拒绝不静默：${state.textResult}",
        )
        assertFalse(state.textLoading)
        assertEquals(0, kernel.legacyCalls.size, "零 spawn")
    }

    @Test
    fun `fetch text rejects while snapshot loading is in flight`() {
        // 已装载快照（dumpSerial 保留）后重抓在途：loading 分支人话；无快照在途则
        // dumpSerial 缺席分支先行（拒绝顺序 = dumpSerial 先于 loading）
        val gate = CompletableDeferred<DeviceWindowViewsDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, ordinal ->
                if (ordinal == 1) Result.success(wdumpOf(deepWindowTree())) else Result.success(gate.await())
            },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)
        vm.refresh()
        vm.settle()
        assertTrue(vm.state.value.loading, "前置：重抓在途（旧快照留屏、dumpSerial 保留）")
        assertEquals("S1", vm.state.value.dumpSerial)

        vm.fetchNodeText(wnode())
        assertTrue(
            vm.state.value.textResult?.contains("层级抓取进行中") == true,
            "抓取在途人话拒绝：${vm.state.value.textResult}",
        )
        assertFalse(vm.state.value.textLoading)
        assertEquals(0, kernel.legacyCalls.size, "零 spawn")
        gate.complete(wdumpOf(wnode()))
        vm.settle()
    }

    @Test
    fun `fetch text single flight rejects second fetch while in flight`() {
        val gate = CompletableDeferred<DeviceLayoutDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ -> Result.success(gate.await()) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)

        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertTrue(vm.state.value.textLoading, "补抓 busy 位上屏")
        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertEquals(1, kernel.legacyCalls.size, "busy 期间再点被拒（单飞防双发）")

        gate.complete(dumpOf(node(text = "文本", bounds = DeviceLayoutBounds(0, 0, 100, 100))))
        vm.settle()
        assertFalse(vm.state.value.textLoading)
    }

    @Test
    fun `refresh entering invalidates in-flight text and busy resets`() {
        val gate = CompletableDeferred<DeviceLayoutDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ -> Result.success(gate.await()) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)
        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertTrue(vm.state.value.textLoading)

        // refresh 照常放行（loading 守卫不拦 textLoading），进入即作废在途补抓
        vm.refresh()
        vm.settle()
        assertFalse(vm.state.value.loading, "refresh 成功完成（独立序号不搅共享序号）")
        assertFalse(vm.state.value.textLoading, "在途补抓 busy 位随作废方复位（防卡死）")
        assertNull(vm.state.value.textResult)

        // 迟到补抓回执：序号失配静默丢弃零状态写入
        gate.complete(dumpOf(node(text = "迟到文本", bounds = DeviceLayoutBounds(0, 0, 100, 100))))
        vm.settle()
        assertFalse(vm.state.value.textLoading)
        assertNull(vm.state.value.textResult, "迟到回执零状态写入")
    }

    @Test
    fun `target change invalidates in-flight text`() {
        val gate = CompletableDeferred<DeviceLayoutDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ -> Result.success(gate.await()) },
        )
        val (devices, vm) = loadedWindowViewsVm(kernel)
        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertTrue(vm.state.value.textLoading)

        devices.deviceStateFlow.value = DeviceState.Single("S2", "Demo-1")
        vm.settle()
        assertFalse(vm.state.value.textLoading, "目标变更作废在途补抓")

        gate.complete(dumpOf(node(text = "旧设备文本")))
        vm.settle()
        assertNull(vm.state.value.textResult, "迟到回执不上屏")
    }

    @Test
    fun `cancel fetch resets state silently`() {
        val gate = CompletableDeferred<DeviceLayoutDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ -> Result.success(gate.await()) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)
        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertTrue(vm.state.value.textLoading)

        vm.cancelFetchText()
        vm.settle()
        assertFalse(vm.state.value.textLoading, "取消即无回执，busy 静默归零")
        assertNull(vm.state.value.textResult)

        gate.complete(dumpOf(node(text = "迟到")))
        vm.settle()
        assertNull(vm.state.value.textResult, "取消后的迟到回执不上屏")
    }

    @Test
    fun `selection change clears text result and invalidates in-flight fetch`() {
        val gate = CompletableDeferred<DeviceLayoutDump>()
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ -> Result.success(gate.await()) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)
        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertTrue(vm.state.value.textLoading)

        vm.selectNode("0/0")
        assertFalse(vm.state.value.textLoading, "选中节点变化作废在途补抓")
        assertNull(vm.state.value.textResult, "不跨节点残留")

        gate.complete(dumpOf(node(text = "前节点文本")))
        vm.settle()
        assertNull(vm.state.value.textResult, "迟到回执不落在新选中的详情卡上")
    }

    @Test
    fun `fetch text failure lands human receipt`() {
        val kernel = FakeKernel(
            windowViewsScript = { _, _ -> Result.success(wdumpOf(deepWindowTree())) },
            legacyScript = { _, _ -> Result.failure(IllegalStateException("uiautomator 挂了")) },
        )
        val (_, vm) = loadedWindowViewsVm(kernel)
        vm.fetchNodeText(vm.state.value.root!!)
        vm.settle()
        assertTrue(
            vm.state.value.textResult?.contains("uiautomator 挂了") == true,
            "失败人话原文回执：${vm.state.value.textResult}",
        )
        assertFalse(vm.state.value.textLoading)
    }

    // ── joinTextByBounds 纯函数 ──

    @Test
    fun `join text prefers exact bounds match then overlap fallback`() {
        val tree = node(
            children = listOf(
                node(text = "背景", bounds = DeviceLayoutBounds(0, 0, 100, 100)),
                node(text = "设置", bounds = DeviceLayoutBounds(10, 20, 200, 80)),
            ),
        )
        assertEquals("设置", joinTextByBounds(tree, DeviceLayoutBounds(10, 20, 200, 80)), "逐值相等第一优先")

        // 无逐值相等：目标全含于节点（重叠比 1.0 ≥ 0.5）兜底
        assertEquals(
            "背景",
            joinTextByBounds(tree, DeviceLayoutBounds(5, 5, 100, 100)),
            "无逐值相等时取重叠面积比最大且 ≥ 0.5 的 text 节点",
        )
    }

    @Test
    fun `join text returns null when no exact and overlap below threshold`() {
        val tree = node(text = "小条", bounds = DeviceLayoutBounds(0, 0, 10, 10))
        assertNull(
            joinTextByBounds(tree, DeviceLayoutBounds(0, 0, 100, 100)),
            "重叠比 0.01 < 0.5 不兜底",
        )
        assertNull(
            joinTextByBounds(node(), DeviceLayoutBounds(0, 0, 100, 100)),
            "树内无非空 text 节点返回 null",
        )
        assertNull(
            joinTextByBounds(node(text = "x"), DeviceLayoutBounds(50, 50, 50, 50)),
            "退化目标（零面积）无可比对面返回 null",
        )
    }

    // ── 纯函数边缘 ──

    @Test
    fun `find layout node resolves paths and rejects invalid`() {
        val root = deepLegacyTree()
        assertEquals("A1a", findLayoutNode(root, "0/0/0/0")?.text)
        assertEquals("R", findLayoutNode(root, LAYOUT_ROOT_ID)?.text)
        assertNull(findLayoutNode(root, "0/9"), "越界拒绝")
        assertNull(findLayoutNode(root, "0/x"), "非数字段拒绝")
    }

    @Test
    fun `engine surface class name detection is closed suffix set`() {
        assertTrue(isEngineSurfaceClassName("android.view.SurfaceView"))
        assertTrue(isEngineSurfaceClassName("android.view.TextureView"))
        assertTrue(isEngineSurfaceClassName("android.opengl.GLSurfaceView"))
        assertTrue(
            isEngineSurfaceClassName("com.demo.oem.map.ui.view.OemMapSurfaceView"),
            "OEM 定制引擎面（地图主窗口 SurfaceView 形态）",
        )
        assertFalse(isEngineSurfaceClassName("DecorView"))
        assertFalse(
            isEngineSurfaceClassName("com.demo.launcher.fullscreen.view.surface.SurfaceFullScreenLayout"),
            "含 Surface 字样但非 SurfaceView 后缀——非实锤不虚标",
        )
        assertFalse(isEngineSurfaceClassName(""))
    }

    @Test
    fun `layout row text helpers project fields`() {
        assertEquals("Button", layoutClassShortName("android.widget.Button"))
        assertEquals("title", layoutResourceIdShort("com.demo:id/title"))
        assertNull(layoutResourceIdShort(""))
        assertNull(layoutResourceIdShort("NO_ID"), "View 体系无 id 标记按缺席面呈现")
        assertEquals("weird", layoutResourceIdShort("weird"), "无 id/ 分隔回退原串")
        assertEquals("clock", layoutResourceIdShort("id/clock"), "全窗口视图源 id 形态取条目简名")
        assertEquals("[0,0][100,100]", layoutBoundsText(DeviceLayoutBounds(0, 0, 100, 100)))
        assertEquals(
            "可点 可长按 已选中",
            layoutFlagsText(
                node(clickable = true).copy(longClickable = true, selected = true),
            ),
        )
        assertEquals("", layoutFlagsText(node()), "零标记回空串")

        assertEquals("", windowViewVisibilityText(0), "可见正常态不列")
        assertEquals("不可见（占位）", windowViewVisibilityText(4))
        assertEquals("不占位（GONE）", windowViewVisibilityText(8))
        assertEquals("visibility=2", windowViewVisibilityText(2), "ROM 自定义读数原样呈现")
        assertEquals(
            "已禁用 可点 已激活",
            windowViewFlagsText(wnode(clickable = true).copy(enabled = false, activated = true)),
        )
        assertEquals("", windowViewFlagsText(wnode()), "零标记回空串")
        assertEquals("(1932, 102)", windowOffsetText(1932, 102))
        assertEquals("(0, 102)", windowOffsetText(null, 102), "单边偏移缺席按 0 记")
        assertNull(windowOffsetText(null, null), "偏移全缺席不出该行")
    }
}
