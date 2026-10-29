package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.toolbelt.adb.AdbEndpoint
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.clickAt
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodes
import com.jiaoay.toolbelt.device.DeviceKeys
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * 独立窗会话操作面内容组合单测（场景渲染底座单源消费，fake 引擎流 + fake ops——
 * 不触真窗真引擎）：满幅帧渲染锚（heightCap=null 契约面）+ 停止与关闭语义分离链
 * （正常停止持窗 + 主钮翻「开始投屏」；失败停止驻留后自动关；非会话态首发即关窗——
 * 内容 collect → 控制器 recall → visible 回写）+ 顶栏操作钮/设备键排/画质弹层渲染锚 +
 * 画质档位起点生效置灰（running 态点击不达改档回调）+ 关窗请求停投链（onCloseRequest
 * 收口 → recall 汇点 → 注册外呼）+ 回执行 Saved/Failed 双形态 + 起录立即失败/送飞书
 * 结果瞬态文本 + 窄窗画面区权重兜底 + 双 Saved 回执与瞬态文本堆叠下帧区保底 + 开窗
 * 初始几何分数锚 + 几何记忆尺寸健全闸（巨/碎记忆判无效）。流值在渲染帧间由测试线程
 * 写入，场景 Unconfined 收集链同步送达——无需单线程钉扎（渲染期无后台持续写流协程）。
 */
class ScreencastDetachedWindowContentTest {

    /** 注入帧的纯色锚（品红 0xFF00FF——主题任何槽位不含此色，帧区检测零歧义） */
    private val frameColor = intArrayOf(255, 0, 255)

    private val handles = mutableListOf<SceneGuardHandle>()

    @AfterTest
    fun tearDown() {
        handles.forEach { it.close() }
        // 先清窗关即停投外呼再 recall（复位路径不得驱动上一用例注册的 fake 外呼）
        ScreencastDetachedWindowController.onSessionEnd = null
        ScreencastDetachedWindowController.recall()
        ScreencastDetachedWindowController.windowHandle = null
        // ReportDelivery 是进程级单例槽——ready 发布不跨用例残留
        ReportDelivery.clearForTest()
        // 首开手势引导一次性标记同纪律——复位保断言确定性（帧在场用例恒见引导行）
        resetScreencastGestureIntroForTest()
    }

    /**
     * fake 操作面（记录调用 + 可编程同步驱动回调——Content 的 ops 隔离面，零真引擎
     * 触达；引擎落流面由 ScreencastSnapshotTest 的引擎用例覆盖，本类只验窗呈现）。
     */
    private class FakeOps : ScreencastDetachedWindowOps {
        val toggleRecordingCalls = mutableListOf<Boolean>()
        val toggleShowTouchesCalls = mutableListOf<Boolean>()
        val injectKeyCalls = mutableListOf<Int>()
        var stopCalls = 0
            private set
        var snapshotCalls = 0
            private set
        var sendLarkCalls = 0
            private set

        /** 可编程回调（null = 静默——同步在 onClick 调用栈内驱动） */
        var immediateFailure: String? = null
        var larkResult: String? = null

        /** 显示触点立即失败回调（null = 静默成功） */
        var showTouchesFailure: String? = null

        override fun stop() {
            stopCalls++
        }

        /** 可编程快照回执（null = 静默——busy 收口由回调驱动用例单独覆盖） */
        var snapshotOutcome: ScreencastSnapshotOutcome? = null

        override fun snapshot(onResult: (ScreencastSnapshotOutcome) -> Unit) {
            snapshotCalls++
            snapshotOutcome?.let(onResult)
        }

        override fun toggleRecording(active: Boolean, onImmediateFailure: (String) -> Unit) {
            toggleRecordingCalls += active
            immediateFailure?.let(onImmediateFailure)
        }

        override fun toggleShowTouches(active: Boolean, onImmediateFailure: (String) -> Unit) {
            toggleShowTouchesCalls += active
            showTouchesFailure?.let(onImmediateFailure)
        }

        override fun sendSnapshotToLark(onResult: (String) -> Unit) {
            sendLarkCalls++
            larkResult?.let(onResult)
        }

        override fun injectKey(keycode: Int): Boolean {
            injectKeyCalls += keycode
            return true
        }

        val restartCalls = mutableListOf<ScreencastOptions?>()

        /** 重试/重开结果回调值（null = 不驱动回调——编排分支由专门用例驱动） */
        var restartResult: Boolean? = null
        var restartCallsCount = 0
            private set

        override fun restart(options: ScreencastOptions?, onResult: (Boolean) -> Unit) {
            restartCallsCount++
            restartCalls += options
            restartResult?.let(onResult)
        }
    }

    private fun streamingStatus() = ScreencastStatus.Streaming("设备终端", "S1", 1920, 1080)

    /** 品红宽帧（窄窗用例：设备横幅纵横比贴近真机画面——Fit 后铺满帧区宽度而非内接方形） */
    private fun wideFrame(width: Int, height: Int): ScreencastDecodedFrame {
        val image = androidx.compose.ui.graphics.ImageBitmap(width, height)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val paint = androidx.compose.ui.graphics.Paint().apply {
            // color 须 OR 不透明 alpha（6 位色值 = 全透明帧，solidFrame 同教训）
            this.color = androidx.compose.ui.graphics.Color((0xFF shl 24) or 0xFF00FF)
        }
        canvas.drawRect(
            androidx.compose.ui.geometry.Rect(0f, 0f, width.toFloat(), height.toFloat()),
            paint,
        )
        return ScreencastDecodedFrame(image = image, width = width, height = height)
    }

    /** 品红帧像素计数（±8/通道——Image 双线性缩放边缘仍命中核心区） */
    private fun countFramePixels(pixels: Array<IntArray>): Int {
        var cnt = 0
        for (row in pixels) {
            for (p in row) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - frameColor[0]) <= 8 && abs(g - frameColor[1]) <= 8 && abs(b - frameColor[2]) <= 8) cnt++
            }
        }
        return cnt
    }

    private fun nodeText(node: SemanticsNode): String = with(node.config) {
        getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    private fun nodesContaining(handle: SceneGuardHandle, snippet: String): List<SemanticsNode> =
        handle.semanticsNodes { node -> nodeText(node).contains(snippet) }

    /** 语义树定位 + 场景左键点击（点击链全真——指针命中按钮 onClick，不旁路状态） */
    private fun clickNode(handle: SceneGuardHandle, snippet: String) {
        val node = nodesContaining(handle, snippet).firstOrNull()
            ?: error("语义树未命中「$snippet」节点——组合未完成或文案漂移")
        handle.clickAt(
            node.positionInRoot.x + node.size.width / 2f,
            node.positionInRoot.y + node.size.height / 2f,
        )
    }

    /**
     * 单行回执结构锚：[rowSnippet] 的路径行节点与 [actionSnippet] 的动作钮节点
     * 垂直中心同带（Row 内同行）——动作钮独立成行或贴缘错位即红。
     */
    private fun assertActionInlineWithRow(
        handle: SceneGuardHandle,
        rowSnippet: String,
        actionSnippet: String,
    ) {
        val rowNode = nodesContaining(handle, rowSnippet).firstOrNull()
            ?: error("语义树未命中「$rowSnippet」回执行")
        val rowCenter = rowNode.positionInRoot.y + rowNode.size.height / 2f
        assertTrue(
            nodesContaining(handle, actionSnippet).any { action ->
                val actionCenter = action.positionInRoot.y + action.size.height / 2f
                abs(rowCenter - actionCenter) < minOf(rowNode.size.height, action.size.height) / 2f
            },
            "「$actionSnippet」动作钮须与「$rowSnippet」路径行同行（Saved 回执单行承载）",
        )
    }

    /** 全 fake 流场景（Idle 用例之外恒 Streaming；显式传 ops 免真引擎单例触达；
     *  画质档位流/回调同 fake 注入——不触 App 级档位档存单例） */
    private fun setContent(
        handle: SceneGuardHandle,
        status: MutableStateFlow<ScreencastStatus> = MutableStateFlow(streamingStatus()),
        frame: MutableStateFlow<ScreencastFrame?> = MutableStateFlow(null),
        fps: MutableStateFlow<Int> = MutableStateFlow(30),
        recordingState: MutableStateFlow<ScreencastRecordingState> =
            MutableStateFlow(ScreencastRecordingState.Idle),
        recordingResult: MutableStateFlow<ScreencastRecordingOutcome?> = MutableStateFlow(null),
        snapshotResult: MutableStateFlow<ScreencastSnapshotOutcome?> = MutableStateFlow(null),
        showTouchesEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false),
        bitrateTierBits: MutableStateFlow<Long?> = MutableStateFlow(null),
        fpsTier: MutableStateFlow<Float> = MutableStateFlow(ScreencastEngine.DEFAULT_MAX_FPS),
        maxSizeTier: MutableStateFlow<Int> = MutableStateFlow(1080),
        onBitrateTierChange: (Long?) -> Unit = {},
        onFpsTierChange: (Float) -> Unit = {},
        onMaxSizeTierChange: (Int) -> Unit = {},
        ops: ScreencastDetachedWindowOps,
        dropPushRouter: ScreencastDropPushRouter? = null,
        connectionTimedOut: Boolean = false,
        onClearConnectionTimeout: () -> Unit = {},
        onFrameTouch: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit =
            { _, _, _, _, _, _ -> },
        content: @Composable () -> Unit = {
            ScreencastDetachedWindowContent(
                status = status,
                frame = frame,
                fps = fps,
                recordingState = recordingState,
                recordingResult = recordingResult,
                snapshotResult = snapshotResult,
                showTouchesEnabled = showTouchesEnabled,
                bitrateTierBits = bitrateTierBits,
                fpsTier = fpsTier,
                maxSizeTier = maxSizeTier,
                onBitrateTierChange = onBitrateTierChange,
                onFpsTierChange = onFpsTierChange,
                onMaxSizeTierChange = onMaxSizeTierChange,
                onFrameTouch = onFrameTouch,
                ops = ops,
                dropPushRouter = dropPushRouter,
                connectionTimedOut = connectionTimedOut,
                onClearConnectionTimeout = onClearConnectionTimeout,
            )
        },
    ) {
        handle.setContent { content() }
    }

    @Test
    fun `content renders status line and full bleed frame from fake flows`() {
        val status = MutableStateFlow<ScreencastStatus>(streamingStatus())
        val frame = MutableStateFlow<ScreencastFrame?>(ScreencastFrame(0L, solidFrame(color = 0xFF00FF).image))
        val fps = MutableStateFlow(30)
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, status = status, frame = frame, fps = fps, ops = FakeOps())
        val pixels = handle.renderPixels()
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(bands.isNotEmpty(), "操作面内容（顶栏/键排/帧面）须进入渲染面——组合断裂即红")
        assertTrue(
            countFramePixels(pixels) >= 5_000,
            "满幅帧（heightCap=null）须渲染（品红锚像素 ${countFramePixels(pixels)} < 5000）",
        )
    }

    @Test
    fun `connecting placeholder shows budget hint without retry entry`() {
        // 受理即开窗的连接段占位：连接中态带预算读数文案；无超时回执不得渲染重试入口
        val status = MutableStateFlow<ScreencastStatus>(ScreencastStatus.Connecting)
        val frame = MutableStateFlow<ScreencastFrame?>(null)
        val fps = MutableStateFlow(0)
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, status = status, frame = frame, fps = fps, ops = FakeOps())
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "连接中…").isNotEmpty(),
            "连接段占位须带连接中文案（窗内承接连接反馈，不静默）",
        )
        // 两级分层：主行只承载「窗内此刻发生什么」，预算注记拆次行降档
        assertTrue(
            nodesContaining(handle, "连接中…").all { "最长等待" !in nodeText(it) },
            "预算注记须拆次行（主行不得混排预算读数）",
        )
        assertTrue(
            nodesContaining(handle, "最长等待约 ${SCREENCAST_CONNECT_BUDGET_MS / 1_000} 秒").isNotEmpty(),
            "预算注记次行须在场（预算读数不缺位——不静默等终态）",
        )
        assertTrue(
            nodesContaining(handle, "重试").isEmpty(),
            "无超时回执不得渲染重试入口（超时态专属动作）",
        )
    }

    @Test
    fun `connect timeout placeholder renders retry cancel and retry restarts with receipt cleared`() {
        // 连接超时态：占位翻超时文案 + 重试/取消双动作；重试经 ops.restart 真重试
        // （停会话→等终态→按上次受理参数重启的编排面），超时回执随重试起点清
        val status = MutableStateFlow<ScreencastStatus>(ScreencastStatus.Connecting)
        val frame = MutableStateFlow<ScreencastFrame?>(null)
        val fps = MutableStateFlow(0)
        val ops = FakeOps()
        var clearCalls = 0
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(
            handle,
            status = status,
            frame = frame,
            fps = fps,
            ops = ops,
            connectionTimedOut = true,
            onClearConnectionTimeout = { clearCalls++ },
        )
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "连接超时").isNotEmpty(),
            "连接预算走完的占位须翻超时文案（不静默等终态）",
        )
        assertTrue(
            nodesContaining(handle, "取消").isNotEmpty(),
            "超时态须渲染「取消」入口（停等待——Stopped 持窗后可「开始投屏」重开）",
        )
        clickNode(handle, "重试")
        assertEquals(1, ops.restartCallsCount, "重试须经 ops.restart 真重试（编排面收口）")
        assertTrue(ops.restartCalls.single() == null, "连接超时重试按原参数重启（不携带改档）")
        assertEquals(1, clearCalls, "超时回执须随重试起点清（旧回执不吞新会话）")
    }

    @Test
    fun `failed stop lingers with reason instead of immediate close`() {
        // 失败一瞥（仅失败停止）：窗不立即关——失败原因驻留行 + 立即关闭可点；正常停止
        // 即时关窗节奏不在此用例（auto close 用例覆盖）
        val status = MutableStateFlow<ScreencastStatus>(ScreencastStatus.Connecting)
        val frame = MutableStateFlow<ScreencastFrame?>(null)
        val fps = MutableStateFlow(0)
        ScreencastDetachedWindowController.visible = true
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, status = status, frame = frame, fps = fps, ops = FakeOps())
        handle.renderPixels()
        status.value = ScreencastStatus.Stopped("连接被拒", failed = true)
        handle.renderPixels()
        assertTrue(
            ScreencastDetachedWindowController.visible,
            "失败终态不得立即关窗（失败原因窗内一瞥——驻留期承载原因呈现）",
        )
        assertTrue(
            nodesContaining(handle, "投屏失败").isNotEmpty(),
            "失败驻留行须呈现失败原因（不静默闪关）",
        )
        assertTrue(
            nodesContaining(handle, "立即关闭").isNotEmpty(),
            "失败驻留行须带「立即关闭」入口（不强迫等驻留结束）",
        )
        assertTrue(
            nodesContaining(handle, "投屏已停止（失败）").isNotEmpty(),
            "失败驻留期占位须组合出 Stopped 态（引擎终态清帧——占位与驻留行不双信号矛盾）",
        )
        assertTrue(
            nodesContaining(handle, "已连接").isEmpty(),
            "失败驻留期占位不得含「已连接」（驻留期是用户唯一读失败原因的窗口期）",
        )
    }

    @Test
    fun `normal stop keeps window open and top bar flips to start`() {
        // 停止与关闭语义分离：正常停止（failed=false）窗持住——设置面不随停止消失，
        // 主钮翻「开始投屏」；退出只走窗关闭钮（窗 X = 关窗即停投）
        val status = MutableStateFlow<ScreencastStatus>(streamingStatus())
        val frame = MutableStateFlow<ScreencastFrame?>(null)
        val fps = MutableStateFlow(30)
        val ops = FakeOps()
        ScreencastDetachedWindowController.visible = true
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, status = status, frame = frame, fps = fps, ops = ops)
        handle.renderPixels()
        assertTrue(ScreencastDetachedWindowController.visible, "Streaming 态窗口保持开启")
        status.value = ScreencastStatus.Stopped("已停止", failed = false)
        handle.renderPixels()
        assertTrue(
            ScreencastDetachedWindowController.visible,
            "正常停止持窗（设置面不随停止消失——退出走窗关闭钮）",
        )
        assertTrue(
            nodesContaining(handle, "开始投屏").isNotEmpty(),
            "Stopped 持窗态顶栏主钮须翻「开始投屏」",
        )
        assertTrue(
            nodesContaining(handle, "已停止——「开始投屏」重开，关窗即退出").isNotEmpty(),
            "帧区占位须给出持窗态的下一步指引",
        )
        // Stopped 持窗态主钮重开 = 按当前档位快照经 ops.restart（与弹层「按新档开始
        // 投屏」同源同动作）
        ops.restartResult = true
        clickNode(handle, "开始投屏")
        handle.renderPixels()
        assertEquals(1, ops.restartCallsCount, "Stopped 持窗态主钮须经 ops.restart 重开")
    }

    @Test
    fun `window opened while not casting closes on first collection`() {
        val status = MutableStateFlow<ScreencastStatus>(ScreencastStatus.Idle)
        val frame = MutableStateFlow<ScreencastFrame?>(null)
        val fps = MutableStateFlow(0)
        ScreencastDetachedWindowController.visible = true
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, status = status, frame = frame, fps = fps, ops = FakeOps())
        handle.renderPixels()
        assertFalse(
            ScreencastDetachedWindowController.visible,
            "非会话态首发即 recall（StateFlow.collect 首发当前值）——窗口语义=会话操作面",
        )
    }

    @Test
    fun `top bar actions and device key grid render in content`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val ops = FakeOps()
        setContent(handle, ops = ops)
        val pixels = handle.renderPixels()
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 3,
            "顶栏/设备键排/文本条内容带须渲染（操作面组合断裂即红），实际 ${bands.size} 带",
        )
        listOf("停止投屏", "保存快照", "开始录像", "画质", "输入文本").forEach { label ->
            assertTrue(nodesContaining(handle, label).isNotEmpty(), "顶栏/文本条须渲染「$label」")
        }
        // 设备键排 = COMMON_KEYS 全量 13 键（BACK 鼠标主通道一键返回，不筛选目录）
        DeviceKeys.COMMON_KEYS.forEach { key ->
            assertTrue(nodesContaining(handle, key.label).isNotEmpty(), "键排须渲染 ${key.label}")
        }

        // 点击链全真：键排 BACK → fake ops 记录 keycode 4（键值语义真源 = DeviceKeys）
        clickNode(handle, "返回")
        assertEquals(listOf(DeviceKeys.BACK.keyCode), ops.injectKeyCalls, "键排点击须透传 keycode 到 ops")
        clickNode(handle, "停止投屏")
        assertEquals(1, ops.stopCalls, "顶栏停止钮点击须达 ops.stop")
        clickNode(handle, "保存快照")
        assertEquals(1, ops.snapshotCalls, "顶栏快照钮点击须达 ops.snapshot")
    }

    @Test
    fun `show touches toggle renders label flip and click reaches ops with active flag`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val ops = FakeOps()
        val showTouchesEnabled = MutableStateFlow(false)
        setContent(handle, showTouchesEnabled = showTouchesEnabled, ops = ops)
        handle.renderPixels()
        // 图标钮语义锚走 ContentDescription（字形钮无 Text 载荷，手势图标钮同款配方）
        assertTrue(
            contentDescriptionNodes(handle, "开启触点显示").isNotEmpty(),
            "顶栏须渲染「开启触点显示」开关钮（tooltip 契约文案在场性由源码锚用例守护）",
        )
        contentDescriptionNodes(handle, "开启触点显示").first().let { node ->
            handle.clickAt(
                node.positionInRoot.x + node.size.width / 2f,
                node.positionInRoot.y + node.size.height / 2f,
            )
        }
        assertEquals(listOf(false), ops.toggleShowTouchesCalls, "关态点击须以 active=false 达 ops")

        // 开态形态：引擎流翻面驱动语义翻面（开态可辨识）+ 图标随 primary 翻面
        showTouchesEnabled.value = true
        handle.renderPixels()
        assertTrue(
            contentDescriptionNodes(handle, "关闭触点显示").isNotEmpty(),
            "开态须翻面渲染「关闭触点显示」语义",
        )
        contentDescriptionNodes(handle, "关闭触点显示").first().let { node ->
            handle.clickAt(
                node.positionInRoot.x + node.size.width / 2f,
                node.positionInRoot.y + node.size.height / 2f,
            )
        }
        assertEquals(listOf(false, true), ops.toggleShowTouchesCalls, "开态点击须以 active=true 达 ops")

        // 立即失败回执（窗无 toast 通道——瞬态文本行是唯一呈现面，静默即违显式回执纪律）
        ops.showTouchesFailure = "仅投屏会话中可切换显示触点——先开始投屏"
        contentDescriptionNodes(handle, "关闭触点显示").first().let { node ->
            handle.clickAt(
                node.positionInRoot.x + node.size.width / 2f,
                node.positionInRoot.y + node.size.height / 2f,
            )
        }
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "无法切换显示触点：仅投屏会话中可切换显示触点").isNotEmpty(),
            "切换立即失败须落窗内瞬态文本行",
        )
    }

    /** ContentDescription 语义树定位（字形图标钮的定位面——手势图标钮用例同配方） */
    private fun contentDescriptionNodes(
        handle: SceneGuardHandle,
        snippet: String,
    ): List<SemanticsNode> = handle.semanticsNodes { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.contains(snippet) } == true
    }

    @Test
    fun `saved receipts render path lines with action buttons`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val ops = FakeOps()
        setContent(
            handle,
            recordingResult = MutableStateFlow(
                ScreencastRecordingOutcome.Saved(File("/tmp/scene/rec.mp4"), 42L, null),
            ),
            snapshotResult = MutableStateFlow(ScreencastSnapshotOutcome.Saved(File("/tmp/scene/shot.png"))),
            ops = ops,
        )
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "快照已保存：/tmp/scene/shot.png").isNotEmpty(),
            "快照 Saved 回执须渲染路径持态行",
        )
        assertTrue(
            nodesContaining(handle, "录像已保存：/tmp/scene/rec.mp4").isNotEmpty(),
            "录像 Saved 回执须渲染路径持态行",
        )
        assertEquals(
            2,
            nodesContaining(handle, "打开所在文件夹").size,
            "Saved 双回执行各渲染一个「打开所在文件夹」动作",
        )
        assertTrue(
            nodesContaining(handle, "送飞书").isNotEmpty(),
            "快照 Saved 回执行须渲染「送飞书」动作",
        )
        // 单行承载结构锚：路径行与动作钮同行（动作钮不得独立成行或贴缘错位）
        assertActionInlineWithRow(handle, "快照已保存：/tmp/scene/shot.png", "打开所在文件夹")
        assertActionInlineWithRow(handle, "快照已保存：/tmp/scene/shot.png", "送飞书")
        assertActionInlineWithRow(handle, "录像已保存：/tmp/scene/rec.mp4", "打开所在文件夹")
        // 桥接未就绪（ready=false 缺省）时「送飞书」置灰——disabled 钮不发点击（行为锚，
        // 跨语义树版本稳定；置灰原因文案由 HoverTooltipBox 承载）
        clickNode(handle, "送飞书")
        assertEquals(0, ops.sendLarkCalls, "ready=false 时送飞书须置灰（ReportDelivery.ready 单源判据）")
    }

    @Test
    fun `failed receipts render read-only error lines without actions`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(
            handle,
            recordingResult = MutableStateFlow(
                ScreencastRecordingOutcome.Failed("未捕获到任何画面帧——录像期间投屏画面无变化或过早停止"),
            ),
            snapshotResult = MutableStateFlow(
                ScreencastSnapshotOutcome.Failed("当前无画面帧——投屏尚未出图或已停止"),
            ),
            ops = FakeOps(),
        )
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "画面快照未保存：当前无画面帧").isNotEmpty(),
            "快照 Failed 须渲染只读错误行（失败不静默）",
        )
        assertTrue(
            nodesContaining(handle, "录像未保存：未捕获到任何画面帧").isNotEmpty(),
            "录像 Failed 须渲染只读错误行（与快照回执行同构）",
        )
        assertTrue(
            nodesContaining(handle, "打开所在文件夹").isEmpty() && nodesContaining(handle, "送飞书").isEmpty(),
            "Failed 形态零动作（只读错误行，不渲染任何回执动作钮）",
        )
    }

    @Test
    fun `recording immediate failure surfaces as transient notice`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val ops = FakeOps().apply { immediateFailure = "仅投屏进行中可录像——先开始投屏" }
        setContent(handle, ops = ops)
        handle.renderPixels()
        clickNode(handle, "开始录像")
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "无法开始录像：仅投屏进行中可录像——先开始投屏").isNotEmpty(),
            "起录立即失败须落窗内瞬态文本（引擎不落 recordingResult、窗无 toast——静默即违显式回执纪律）",
        )
        assertEquals(listOf(false), ops.toggleRecordingCalls, "点击「开始录像」须以 active=false 达 ops")
    }

    @Test
    fun `send snapshot to lark result surfaces as transient notice`() {
        // ready=true 驱动送飞书可点形态（置灰形态断言在 Saved 形态用例）
        ReportDelivery.publishReady(true)
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val ops = FakeOps().apply { larkResult = "已送出至飞书会话：shot.png" }
        setContent(
            handle,
            snapshotResult = MutableStateFlow(ScreencastSnapshotOutcome.Saved(File("/tmp/scene/shot.png"))),
            ops = ops,
        )
        handle.renderPixels()
        clickNode(handle, "送飞书")
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "已送出至飞书会话：shot.png").isNotEmpty(),
            "送飞书结果人话须落窗内瞬态文本（deliver 结果经 onResult 回调，窗无 toast 通道）",
        )
        assertEquals(1, ops.sendLarkCalls, "点击「送飞书」须达 ops.sendSnapshotToLark")
    }

    @Test
    fun `narrow window switches key grid to single row scroll and keeps frame alive`() {
        // 320×240 = Dimens 最小窗 token：高度饥饿（maxHeight < 键排 FlowRow 形态下限
        // token）→ 键排切单行横向滚动，画面区权重段保住——「看不到图像」的最小窗防线
        val handle = SceneGuardHandle(NARROW_W, NARROW_H).also { handles += it }
        setContent(
            handle,
            frame = MutableStateFlow(ScreencastFrame(0L, wideFrame(width = 320, height = 64).image)),
            ops = FakeOps(),
        )
        val pixels = handle.renderPixels()
        assertTrue(
            countFramePixels(pixels) >= 5_000,
            "窄窗 320×240 下画面区仍须承载帧（键排滚动形态让位），品红锚像素 ${countFramePixels(pixels)} < 5000",
        )
        // 操作面各段完整渲染：滚动形态不丢键、顶栏操作钮与文本条照常
        DeviceKeys.COMMON_KEYS.forEach { key ->
            assertTrue(nodesContaining(handle, key.label).isNotEmpty(), "键排滚动形态须仍渲染 ${key.label}")
        }
        assertTrue(nodesContaining(handle, "停止投屏").isNotEmpty(), "顶栏操作钮须渲染")
        assertTrue(nodesContaining(handle, "输入文本").isNotEmpty(), "文本注入条须渲染")
    }

    @Test
    fun `double saved receipts with transient notice keep frame visible in minimal window`() {
        // 320×240 最小窗 + 双 Saved 回执 + 瞬态文本堆叠：回执行区恒高无上限，帧区
        // weight 段无保底即被挤到 0 高（品红锚 0 像素）——帧区保底下限生效后仍须有
        // 可见品红（总高超窗由底部键排/文本条被裁承担，帧带部分可见即可）
        val handle = SceneGuardHandle(NARROW_W, NARROW_H).also { handles += it }
        val ops = FakeOps().apply { immediateFailure = "仅投屏进行中可录像——先开始投屏" }
        setContent(
            handle,
            frame = MutableStateFlow(ScreencastFrame(0L, wideFrame(width = 320, height = 64).image)),
            recordingResult = MutableStateFlow(
                ScreencastRecordingOutcome.Saved(File("/tmp/scene/rec.mp4"), 42L, null),
            ),
            snapshotResult = MutableStateFlow(ScreencastSnapshotOutcome.Saved(File("/tmp/scene/shot.png"))),
            ops = ops,
        )
        handle.renderPixels()
        clickNode(handle, "开始录像")
        val pixels = handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "快照已保存：/tmp/scene/shot.png").isNotEmpty() &&
                nodesContaining(handle, "录像已保存：/tmp/scene/rec.mp4").isNotEmpty(),
            "双 Saved 回执须同时在渲染面（堆叠形态前置）",
        )
        // 双 Saved 单行形态锚：最小窗下路径与动作钮仍同行（按钮让位不破行结构）
        assertActionInlineWithRow(handle, "快照已保存：/tmp/scene/shot.png", "打开所在文件夹")
        assertActionInlineWithRow(handle, "录像已保存：/tmp/scene/rec.mp4", "打开所在文件夹")
        assertTrue(
            nodesContaining(handle, "无法开始录像：仅投屏进行中可录像——先开始投屏").isNotEmpty(),
            "堆叠形态第三件瞬态文本须在场",
        )
        assertTrue(
            countFramePixels(pixels) >= 1_000,
            "双 Saved 回执 + 瞬态文本堆叠下帧区保底须生效（品红锚像素 ${countFramePixels(pixels)} < 1000）",
        )
    }

    @Test
    fun `initial geometry follows aspect ratio and workarea fraction`() {
        // 竖屏设备 1080x1920：高 = 1000×0.55 = 550（与主窗并存的紧凑档），宽 = 550×(1080/1920) = 309（窄高窗）
        assertEquals(
            DpSize(309.dp, 550.dp),
            computeDetachedInitialSize(ScreencastStatus.Streaming("d", "s", 1080, 1920), 1000, Density(1f)),
        )
        // 非 Streaming 回退 16:9（开窗门禁 Streaming-only 的防御兜底）：550×16/9 = 977.8 → 978
        assertEquals(
            DpSize(978.dp, 550.dp),
            computeDetachedInitialSize(null, 1000, Density(1f)),
        )
    }

    @Test
    fun `saved geometry soundness gate rejects oversized and undersized memory`() {
        val minW = 320
        val minH = 240
        fun geometry(width: Int, height: Int) =
            ScreencastDetachedInitialGeometry(x = 100, y = 100, width = width, height = height)

        // 健全记忆（工作区内、不低于最小窗 token）→ 采纳
        assertTrue(
            isSoundDetachedSavedGeometry(geometry(800, 500), 1_440, 900, minW, minH),
            "工作区内且高于最小窗的记忆须判健全",
        )
        // 巨记忆（超工作区 0.85——陈旧分辨率/误存的铺满几何）→ 判无效回退计算位
        assertFalse(
            isSoundDetachedSavedGeometry(geometry(1_400, 500), 1_440, 900, minW, minH),
            "宽超工作区 0.85 的巨记忆须判无效",
        )
        assertFalse(
            isSoundDetachedSavedGeometry(geometry(800, 800), 1_440, 900, minW, minH),
            "高超工作区 0.85 的巨记忆须判无效",
        )
        // 碎记忆（低于最小窗 token——退化几何开成碎窗）→ 判无效
        assertFalse(
            isSoundDetachedSavedGeometry(geometry(300, 500), 1_440, 900, minW, minH),
            "宽低于最小窗 token 的碎记忆须判无效",
        )
        assertFalse(
            isSoundDetachedSavedGeometry(geometry(800, 200), 1_440, 900, minW, minH),
            "高低于最小窗 token 的碎记忆须判无效",
        )
        // 边界恰在阈值内（0.85×1440=1224、0.85×900=765）→ 采纳；退化工作区/零尺寸 → 无效
        assertTrue(
            isSoundDetachedSavedGeometry(geometry(1_224, 765), 1_440, 900, minW, minH),
            "恰在 0.85 阈值上的记忆须判健全（开区间上界）",
        )
        assertFalse(
            isSoundDetachedSavedGeometry(geometry(800, 500), 0, 900, minW, minH),
            "工作区不可读（0）时记忆不可裁——判无效回退计算位",
        )
        assertFalse(
            isSoundDetachedSavedGeometry(geometry(0, 500), 1_440, 900, minW, minH),
            "零尺寸记忆须判无效",
        )
    }

    // ── 泊指集成面（右键泊指 → 状态行计数 + 帧区胶囊 + 注入通道记录） ──────────────

    @Test
    fun `right click in frame parks finger and surfaces count in status line`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val touches = mutableListOf<Pair<Int, Long>>()
        setContent(
            handle,
            frame = MutableStateFlow(ScreencastFrame(7L, solidFrame(color = 0xFF00FF).image)),
            ops = FakeOps(),
            onFrameTouch = { action, _, _, _, _, pointerId -> touches += action to pointerId },
        )
        handle.renderPixels()
        // 帧区定位（contentDescription「设备画面」）+ 右键单击注入（全真指针链，不旁路状态）
        val frameNode = handle.semanticsNodes { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "设备画面" } == true
        }.firstOrNull() ?: error("帧区未渲染（contentDescription「设备画面」未命中）")
        val center = Offset(
            frameNode.positionInRoot.x + frameNode.size.width / 2f,
            frameNode.positionInRoot.y + frameNode.size.height / 2f,
        )
        handle.scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = center,
            buttons = PointerButtons(isSecondaryPressed = true),
            button = PointerButton.Secondary,
        )
        handle.scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = center,
            buttons = PointerButtons(),
            button = PointerButton.Secondary,
        )
        handle.renderPixels()
        assertEquals(
            listOf(ScrcpyControlProtocol.MOTION_ACTION_DOWN to ScrcpyControlProtocol.POINTER_ID_PARKED_FIRST),
            touches,
            "右键单击泊入须经注入通道发一次 DOWN（-11，fake 记录面——不触真引擎单例）",
        )
        assertTrue(
            nodesContaining(handle, "已泊 1 指").isNotEmpty(),
            "顶栏状态行须带「已泊 1 指」计数（泊指在场的可见状态面）",
        )
        assertTrue(
            nodesContaining(handle, "泊 1/3").isNotEmpty(),
            "帧区左下须渲染「泊 1/3」计数胶囊",
        )
    }

    // ── 手势可发现性面（顶栏「手势」钮 + 首开一次性引导） ─────────────────────

    @Test
    fun `gesture button toggles legend card in frame region`() {
        // 顶栏固定钮全量宽 ≈540px（含置顶钮）：点击行为用例须以全钮就位的场景宽驱动；
        // SCENE_W=480 下尾部钮进入压挤档（手势钮 13px 残宽），点击语义不成立——压挤
        // 档是窄窗既有降级形态（窗口最小宽 320 下顶栏本就超宽），非本用例守护面
        val handle = SceneGuardHandle(560, SCENE_H).also { handles += it }
        setContent(
            handle,
            frame = MutableStateFlow(ScreencastFrame(0L, solidFrame(color = 0xFF00FF).image)),
            ops = FakeOps(),
        )
        handle.renderPixels()
        // 图标钮语义锚走 ContentDescription（字形钮无 Text 载荷）——语义树定位 + 全真点击
        val gestureButton = handle.semanticsNodes { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it.contains("展开手势映射") } == true
        }.firstOrNull() ?: error("顶栏未渲染「手势」图标钮（ContentDescription 未命中）")
        handle.clickAt(
            gestureButton.positionInRoot.x + gestureButton.size.width / 2f,
            gestureButton.positionInRoot.y + gestureButton.size.height / 2f,
        )
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "双指捏合 / 旋转").isNotEmpty(),
            "点开「手势」须在帧区弹映射图例卡（人话键位/动作对照）",
        )
        // 泊指三行同卡（右键通道手势的人话可发现性面）
        assertTrue(
            nodesContaining(handle, "泊一根手指（最多 3 根）").isNotEmpty(),
            "图例卡须含右键单击泊指行",
        )
        assertTrue(
            nodesContaining(handle, "取消本次泊入").isNotEmpty(),
            "图例卡须含右键拖动取消行",
        )
        assertTrue(
            nodesContaining(handle, "清空全部泊指").isNotEmpty(),
            "图例卡须含双击右键清空行",
        )
        assertTrue(
            handle.semanticsNodes { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it.contains("收起手势映射") } == true
            }.isNotEmpty(),
            "开态图标钮语义翻面",
        )
        val collapseButton = handle.semanticsNodes { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it.contains("收起手势映射") } == true
        }.first()
        handle.clickAt(
            collapseButton.positionInRoot.x + collapseButton.size.width / 2f,
            collapseButton.positionInRoot.y + collapseButton.size.height / 2f,
        )
        handle.renderPixels()
        assertTrue(nodesContaining(handle, "双指捏合 / 旋转").isEmpty(), "再点「手势」须收起图例卡")
    }

    @Test
    fun `first frame shows one shot gesture intro notice`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(
            handle,
            frame = MutableStateFlow(ScreencastFrame(0L, solidFrame(color = 0xFF00FF).image)),
            ops = FakeOps(),
        )
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "多指手势：Ctrl+左键拖").isNotEmpty(),
            "首帧到达须示一次性手势引导（transientNotice 行承载——可发现性不靠翻文档）",
        )
        // 一次性：再开一窗（新组合）不再示——进程级标记；可跳过性 = transientNotice 行可被下一动作覆盖
        val second = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(
            second,
            frame = MutableStateFlow(ScreencastFrame(0L, solidFrame(color = 0xFF00FF).image)),
            ops = FakeOps(),
        )
        second.renderPixels()
        assertTrue(
            nodesContaining(second, "多指手势：Ctrl+左键拖").isEmpty(),
            "引导仅首开一次（进程级标记，重开窗不重示）",
        )
    }

    // ── 画质档位弹层面（顶栏「画质」钮 + 起点生效置灰） ─────────────────────────

    @Test
    fun `quality menu renders tier groups and parks tiers while running`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val bitrateChanges = mutableListOf<Long?>()
        setContent(
            handle,
            ops = FakeOps(),
            onBitrateTierChange = { bitrateChanges += it },
        )
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "画质").isNotEmpty(),
            "顶栏须渲染「画质」钮（档位控制随会话操作面在窗内——窗是投屏唯一使用场景）",
        )
        clickNode(handle, "画质")
        handle.renderPixels()
        listOf("码率", "帧率上限", "画面上限").forEach { label ->
            assertTrue(nodesContaining(handle, label).isNotEmpty(), "画质弹层须渲染「$label」档组")
        }
        assertTrue(
            nodesContaining(handle, "投屏中不可改档——停投后解锁（窗口保持），重开时生效").isNotEmpty(),
            "running 态弹层须注记解锁引导（起点生效语义不静默——停投不带走设置面）",
        )
        // running（Streaming）档位置灰：点击不达改档回调（起点生效——server 无中途改档通道）
        clickNode(handle, "2M")
        assertTrue(bitrateChanges.isEmpty(), "Streaming 态码率档须置灰（点击不得达改档回调）")
        assertTrue(
            nodesContaining(handle, "停投并按新档重开").isNotEmpty(),
            "running 态弹层须渲染「停投并按新档重开」（改档通道唯一 UI 路径）",
        )
    }

    @Test
    fun `quality tier clicks land in change callbacks when session idle`() {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val bitrateChanges = mutableListOf<Long?>()
        val fpsChanges = mutableListOf<Float>()
        val sizeChanges = mutableListOf<Int>()
        setContent(
            handle,
            status = MutableStateFlow(ScreencastStatus.Stopped("已停止", failed = false)),
            ops = FakeOps(),
            onBitrateTierChange = { bitrateChanges += it },
            onFpsTierChange = { fpsChanges += it },
            onMaxSizeTierChange = { sizeChanges += it },
        )
        handle.renderPixels()
        clickNode(handle, "画质")
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "投屏中不可改档").isEmpty(),
            "非 running 态弹层不渲染解锁引导注记（当前可改即当前选）",
        )
        assertTrue(
            nodesContaining(handle, "停投并按新档重开").isEmpty(),
            "非 running 态弹层不渲染改档重开钮（无会话在跑——重开无从谈起）",
        )
        clickNode(handle, "40M")
        clickNode(handle, "60 fps")
        clickNode(handle, "720p")
        assertEquals(listOf<Long?>(40_000_000L), bitrateChanges, "非 running 态码率档可改（起点生效——下次 start 快照）")
        assertEquals(listOf(60f), fpsChanges, "非 running 态帧率档可改")
        assertEquals(listOf(720), sizeChanges, "非 running 态画面上限档可改")
    }

    @Test
    fun `tier restart flow keeps window open unlocks chips and starts with snapshot`() {
        // 改档重开全链（停止与关闭语义分离形态）：running 态弹层一键「停投并按新档重开」
        // = 直停（Stopped 恒持窗，无需编排标志）→ Stopped 落定 chips 解锁 + 弹层翻
        // 「按新档开始投屏」（无取消钮——退出走窗 X）→ 选档 → 按新档开始 = 档位快照经
        // ops.restart（归一化单源 screencastOptionsFromTiers）
        val status = MutableStateFlow<ScreencastStatus>(streamingStatus())
        val bitrateTier = MutableStateFlow<Long?>(null)
        val ops = FakeOps()
        ScreencastDetachedWindowController.visible = true
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(
            handle,
            status = status,
            bitrateTierBits = bitrateTier,
            ops = ops,
        )
        handle.renderPixels()
        clickNode(handle, "画质")
        handle.renderPixels()
        clickNode(handle, "停投并按新档重开")
        handle.renderPixels()
        assertEquals(1, ops.stopCalls, "running 态一键钮须直停（stop 经 ops——窗恒持住）")
        assertTrue(ScreencastDetachedWindowController.visible, "停投落 Stopped 恒持窗（语义分离）")

        // 引擎侧 Stopped 落定（fake 流驱动）→ 弹层翻待选态：chips 解锁 + 开始钮
        status.value = ScreencastStatus.Stopped("已停止", failed = false)
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "按新档开始投屏").isNotEmpty(),
            "Stopped 持窗态弹层须翻「按新档开始投屏」",
        )
        assertTrue(
            nodesContaining(handle, "取消").isEmpty(),
            "弹层不得回潮「取消」编排钮（旧改档编排专属态已随语义分离退役——退出走窗 X）",
        )
        // 解锁验锚：档位流现值直达改档快照（chips 解锁后选择即流值更新）
        bitrateTier.value = 40_000_000L
        handle.renderPixels()

        ops.restartResult = true
        clickNode(handle, "按新档开始投屏")
        handle.renderPixels()
        assertEquals(1, ops.restartCallsCount, "按新档开始须经 ops.restart（改档快照通道）")
        assertEquals(
            ScreencastOptions(videoBitRate = 40_000_000L, maxFps = ScreencastEngine.DEFAULT_MAX_FPS, maxSize = 1080),
            ops.restartCalls.single(),
            "改档快照 = 档位流现值经归一化单源（40M + 默认帧率 + 1080 预览档）",
        )
    }

    @Test
    fun `tier restart start failure closes window with receipt`() {
        // Stopped 持窗态开始但引擎未受理（restart 回执 false）：显式回执 + 窗关——
        // 引擎无历史参数时窗内 start 通道恒死，持窗无义（用户回主窗重新受理）
        val status = MutableStateFlow<ScreencastStatus>(streamingStatus())
        val ops = FakeOps()
        ScreencastDetachedWindowController.visible = true
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(
            handle,
            status = status,
            ops = ops,
        )
        handle.renderPixels()
        clickNode(handle, "画质")
        handle.renderPixels()
        clickNode(handle, "停投并按新档重开")
        status.value = ScreencastStatus.Stopped("已停止", failed = false)
        handle.renderPixels()
        ops.restartResult = false
        clickNode(handle, "按新档开始投屏")
        handle.renderPixels()
        assertFalse(
            ScreencastDetachedWindowController.visible,
            "引擎未受理时窗须关（窗内 start 通道恒死——visible 回写）",
        )
        assertTrue(
            nodesContaining(handle, "重新开始投屏失败").isNotEmpty(),
            "重启失败须显式回执（不静默）",
        )
    }

    // ── 窗关即停投链（onCloseRequest 收口 → recall 汇点 → 注册外呼） ────────────

    @Test
    fun `window close request ends session via registered stop route`() {
        var sessionEndCalls = 0
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.onSessionEnd = { sessionEndCalls++ }
        // frame=null = headless 测试形态（java.awt.Frame headless 不可构造——几何写回跳过）
        onDetachedWindowCloseRequested(frame = null, onPersistGeometry = { _, _, _, _ -> })
        assertEquals(1, sessionEndCalls, "关窗请求须经注册外呼停投（窗关即停投——recall 汇点统一调用）")
        assertFalse(ScreencastDetachedWindowController.visible, "关窗请求须收窗（visible=false）")
    }

    // ── 拖拽推送回执行（窗内渲染面——路由层单测覆盖管线语义，本面只验逐件持态行呈现）──

    @Test
    fun `drop receipts render per file rows and apk install flows through confirm`() {
        // drop 事件不可注入（场景渲染底座无平台拖放通道）——路由层单测 + drop 面源码锚
        // 兜底；本用例直驱 router.offer 验回执行的窗内呈现形态。确认挂起期不渲染
        // （渲染底座不验对话窗——确认对话的在场/裁决语义由路由层单测背书），确认
        // 放行后渲染验安装完成行的呈现。
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val dir = java.nio.file.Files.createTempDirectory("scene-drop").toFile()
        val apk = File(dir, "evil.APK").apply { writeBytes(ByteArray(8)) }
        val ok = File(dir, "ok.log").apply { writeBytes(ByteArray(512)) }
        val gateway = FakePushGateway { _, onLine ->
            onLine(
                com.jiaoay.toolbelt.device.ShellOutput(
                    "1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)",
                    com.jiaoay.toolbelt.device.ShellOutputSource.STDOUT,
                ),
            )
            0
        }
        val stat = ScriptedStat(listOf(dropPushAbsentStat(), dropPushStatOf(512)))
        val installGateway = FakeInstallGateway { _, onLine ->
            onLine(
                com.jiaoay.toolbelt.device.ShellOutput(
                    "Performing Streamed Install",
                    com.jiaoay.toolbelt.device.ShellOutputSource.STDOUT,
                ),
            )
            onLine(
                com.jiaoay.toolbelt.device.ShellOutput(
                    "Success",
                    com.jiaoay.toolbelt.device.ShellOutputSource.STDOUT,
                ),
            )
            0
        }
        // 前后列举同集 = 升级覆盖形态（差集空，回执行如实注明）
        val pkgGateway = FakePkgGateway(
            allLists = listOf(pkgListOf("com.a"), pkgListOf("com.a")),
            systemLists = listOf(pkgListOf("com.a"), pkgListOf("com.a")),
        )
        val dropPush = ScreencastDropPushRouter(
            scope = scope,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = com.jiaoay.toolbelt.device.DeviceFilePusher(
                runner = NoopRunner,
                gateway = gateway,
                statEntry = stat.statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            installer = com.jiaoay.toolbelt.device.DeviceApkInstaller(
                runner = NoopRunner,
                gateway = installGateway,
                packageBrowser = com.jiaoay.toolbelt.device.DevicePackageBrowser(
                    runner = pkgGateway,
                    endpointReader = { AdbEndpoint("") },
                ),
                endpointReader = { AdbEndpoint("") },
            ),
        )
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, ops = FakeOps(), dropPushRouter = dropPush)
        dropPush.offer(listOf(apk, ok), "S1")
        assertEquals(
            ScreencastApkInstallConfirm("evil.APK", 8),
            dropPush.installConfirm.value,
            "安装确认在场数据携带文件名 + 大小（确认前零 spawn——安装不可逆）",
        )
        assertEquals(0, installGateway.commands.size, "确认前零 adb install spawn")

        dropPush.confirmInstall()
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "已安装：evil.APK（8 B）——升级覆盖，包名未识别").isNotEmpty(),
            "安装完成回执须落窗内持态行（基线档文件名 + 大小 + 升档缺席注明原文）",
        )
        assertTrue(
            nodesContaining(handle, "adb：Performing Streamed Install").isNotEmpty(),
            "安装回执行须携带 adb 回显原文（追溯底线——装了什么可回查）",
        )
        assertTrue(
            nodesContaining(handle, "已推送：ok.log → /sdcard/ok.log（512 B）").isNotEmpty(),
            "完成回执须携带设备目标全路径与校验通过的大小（安装不断后续文件）",
        )
        assertEquals(1, installGateway.commands.size, "确认后安装件 spawn 一次")
        assertEquals(1, gateway.commands.size, "完成件推送一次")
        scope.cancel()
    }

    @Test
    fun `pushing line renders while transfer in flight then flips to pushed row`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val dir = java.nio.file.Files.createTempDirectory("scene-drop").toFile()
        val local = File(dir, "build.log").apply { writeBytes(ByteArray(512)) }
        val gate = CompletableDeferred<Unit>()
        val gateway = FakePushGateway { _, onLine ->
            gate.await()
            onLine(
                com.jiaoay.toolbelt.device.ShellOutput(
                    "1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)",
                    com.jiaoay.toolbelt.device.ShellOutputSource.STDOUT,
                ),
            )
            0
        }
        val stat = ScriptedStat(listOf(dropPushAbsentStat(), dropPushStatOf(512)))
        val dropPush = ScreencastDropPushRouter(
            scope = scope,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = com.jiaoay.toolbelt.device.DeviceFilePusher(
                runner = NoopRunner,
                gateway = gateway,
                statEntry = stat.statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
        )
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        setContent(handle, ops = FakeOps(), dropPushRouter = dropPush)
        dropPush.offer(listOf(local), "S1")
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "推送中：build.log → /sdcard/").isNotEmpty(),
            "在途行须落窗内持态行（预检/传输/校验全程可见）",
        )
        gate.complete(Unit)
        handle.renderPixels()
        assertTrue(
            nodesContaining(handle, "已推送：build.log → /sdcard/build.log（512 B）").isNotEmpty(),
            "完成后在途行须原位翻面为完成行",
        )
        scope.cancel()
    }

    private companion object {
        const val SCENE_W = 480
        const val SCENE_H = 360
        const val NARROW_W = 320
        const val NARROW_H = 240
    }
}
