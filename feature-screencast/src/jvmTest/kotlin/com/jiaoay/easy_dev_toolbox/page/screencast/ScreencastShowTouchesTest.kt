package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 显示触点会话控制器单测（fake adb 网关 + 隔离标记 store，零真 adb/零真 DataStore 目录）：
 * 恢复语义三件逐面验收——原值捕获（先 get 后 put 1）、原值已是 1 的直通语义、会话收口
 * 恢复原值 + 清标记、强杀残留兜底（标记保留 → App 次启动核对恢复 + 清标记）；
 * 外加源码锚用例（引擎恢复挂点单点性 + 开关文案契约在场性——行为面不可观察的结构性质
 * 只能源码锚钉死，ScreencastEngineTest 源码锚用例同配方）。
 */
class ScreencastShowTouchesTest {

    private lateinit var originalStore: LocalDataManager
    private lateinit var storeDir: File

    @BeforeTest
    fun setUp() {
        originalStore = ScreencastShowTouchesStore.store
        // datastoreDir 构造期钉死（LocalDataManager 契约：落盘路径在首读写时才解析，
        // 隔离必须在构造期注入）——不触真实数据目录，无须 user.home 重定向
        storeDir = java.nio.file.Files.createTempDirectory("showtouch-test").toFile()
        ScreencastShowTouchesStore.store = LocalDataManager(
            dataStoreName = "test_showtouches_${UUID.randomUUID()}",
            datastoreDir = storeDir,
        )
    }

    @AfterTest
    fun tearDown() {
        ScreencastShowTouchesStore.store = originalStore
        storeDir.deleteRecursively()
    }

    /** 可编程 settings 命令 fake：get 回显可设、put 可失败（模拟设备拔线/命令被拒） */
    private class FakeSettingsGateway(
        var getValue: String = ScreencastShowTouches.VALUE_OFF,
    ) : ScrcpyAdbGateway {
        val commands = mutableListOf<String>()
        var failPut = false

        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return when {
                "settings get system show_touches" in command ->
                    Result.success(ShellResult(0, getValue, ""))

                "settings put system show_touches" in command && failPut ->
                    Result.success(ShellResult(1, "", "settings unavailable"))

                else -> Result.success(ShellResult(0, "", ""))
            }
        }
    }

    private fun newController(gateway: FakeSettingsGateway, serial: String? = "CAR-1") =
        ScreencastShowTouches(adb = gateway, serial = serial)

    @Test
    fun `enable captures original via get then puts on and marks residue`() = runBlocking {
        val gateway = FakeSettingsGateway(getValue = "0")
        val controller = newController(gateway)
        assertNull(controller.enable(), "原值 0 → 开启须成功")
        assertEquals(
            listOf(
                ScreencastShowTouches.buildGetCommand(),
                ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_ON),
            ),
            gateway.commands,
            "开启必须先 get 读回原值、后 put 1——原值是恢复语义的素材，未读即写即违契约",
        )
        assertTrue(ScreencastShowTouchesStore.isActive(), "设备被改写后残留标记须在档")
        assertEquals("0", ScreencastShowTouchesStore.readOriginal(), "原值须入标记")
        assertEquals("CAR-1", ScreencastShowTouchesStore.readSerial(), "串号须入标记（次启动恢复命令的目标设备）")
        assertTrue(controller.enabled.value, "开启成功后开关态为开")
    }

    @Test
    fun `enable passes through when original already on without writing`() = runBlocking {
        val gateway = FakeSettingsGateway(getValue = "1")
        val controller = newController(gateway)
        assertNull(controller.enable(), "原值已是 1 → 直通开启须成功")
        assertEquals(
            listOf(ScreencastShowTouches.buildGetCommand()),
            gateway.commands,
            "原值已是 1 时不写 1（设备未被改写，无恢复面）",
        )
        assertFalse(ScreencastShowTouchesStore.isActive(), "未改写设备不得落残留标记")
        assertTrue(controller.enabled.value, "直通形态开关态为开")
    }

    @Test
    fun `session end restore puts original back and clears marker`() = runBlocking {
        val gateway = FakeSettingsGateway(getValue = "0")
        val controller = newController(gateway)
        controller.enable()
        controller.restoreOnSessionEnd()
        assertEquals(
            listOf(
                ScreencastShowTouches.buildGetCommand(),
                ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_ON),
                ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_OFF),
            ),
            gateway.commands,
            "会话收口须写回原值（0）",
        )
        assertFalse(ScreencastShowTouchesStore.isActive(), "恢复成功后标记须清档")
        assertFalse(controller.enabled.value, "会话收口后开关态恒回落 false")
    }

    @Test
    fun `session end restore failure keeps marker then next startup check heals`() = runBlocking {
        val gateway = FakeSettingsGateway(getValue = "0")
        val controller = newController(gateway, serial = "CAR-9")
        controller.enable()
        gateway.failPut = true
        controller.restoreOnSessionEnd()
        assertTrue(
            ScreencastShowTouchesStore.isActive(),
            "恢复失败（设备拔线等）须保留标记——次启动核对续试",
        )
        assertFalse(controller.enabled.value, "收口后开关态恒回落 false（恢复失败也不例外）")

        // App 次启动核对：标记活跃 → 按标记原值 + 串号恢复 + 清标记
        val startupGateway = FakeSettingsGateway()
        val capturedSerials = mutableListOf<String?>()
        val restored = ScreencastShowTouches.restoreResidueAtStartup(
            gatewayFactory = { serial ->
                capturedSerials += serial
                startupGateway
            },
        )
        assertTrue(restored, "标记在档时次启动核对须执行恢复")
        assertEquals(
            listOf(ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_OFF)),
            startupGateway.commands,
            "次启动核对按标记原值恢复（不重读设备值——残留开态的回显不可信）",
        )
        assertEquals(listOf<String?>("CAR-9"), capturedSerials, "恢复命令目标设备 = 标记串号")
        assertFalse(ScreencastShowTouchesStore.isActive(), "次启动恢复成功后标记须清档")
    }

    @Test
    fun `startup residue check without marker is no-op`() = runBlocking {
        val gateway = FakeSettingsGateway()
        val restored = ScreencastShowTouches.restoreResidueAtStartup(gatewayFactory = { gateway })
        assertFalse(restored, "无标记即无残留，核对为 no-op")
        assertTrue(gateway.commands.isEmpty(), "无标记不得发任何恢复命令")
    }

    @Test
    fun `startup residue check keeps marker when device unreachable`() = runBlocking {
        ScreencastShowTouchesStore.writeMarker("0", "CAR-1")
        val gateway = FakeSettingsGateway().apply { failPut = true }
        val restored = ScreencastShowTouches.restoreResidueAtStartup(gatewayFactory = { gateway })
        assertFalse(restored, "恢复失败返回 false（无残留同形——调用方无须区分）")
        assertTrue(ScreencastShowTouchesStore.isActive(), "恢复失败保留标记，下次启动再试")
    }

    @Test
    fun `enable heals stale residue marker before capturing original`() = runBlocking {
        // 上一进程强杀残留：标记在档、设备端回显残留开态——若直接捕获会把 1 误当原值
        ScreencastShowTouchesStore.writeMarker("0", "CAR-1")
        val gateway = FakeSettingsGateway(getValue = "1")
        val controller = newController(gateway)
        assertNull(controller.enable(), "残留自愈后开启须成功")
        assertEquals(
            listOf(
                ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_OFF),
                ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_ON),
            ),
            gateway.commands,
            "标记在档时开启须先按标记恢复原值、再写 1——自愈写入即确立设备值信念（=标记原值），" +
                "无须重读设备（重读会把残留回显误当真值）",
        )
        assertEquals("0", ScreencastShowTouchesStore.readOriginal(), "原值 = 标记原值（残留开态不固化）")
        assertTrue(ScreencastShowTouchesStore.isActive(), "写 1 后标记按不变量重新在档")
    }

    @Test
    fun `disable with original on keeps marker for session end restore to on`() = runBlocking {
        // 原值 1 → 关闭是显式偏离（写 0），标记保持活跃（original=1）——会话收口恢复回开态
        val gateway = FakeSettingsGateway(getValue = "1")
        val controller = newController(gateway)
        controller.enable()
        gateway.commands.clear()
        assertNull(controller.disable(), "关闭须成功")
        assertEquals(
            listOf(ScreencastShowTouches.buildPutCommand(ScreencastShowTouches.VALUE_OFF)),
            gateway.commands,
            "关闭 = 写 0（用户显式语义，非恢复原值）",
        )
        assertTrue(ScreencastShowTouchesStore.isActive(), "偏离原值（0 ≠ 1）标记须在档")
        assertEquals("1", ScreencastShowTouchesStore.readOriginal(), "原值 1 不被关闭动作覆写")
        controller.restoreOnSessionEnd()
        assertTrue(
            gateway.commands.any { it == ScreencastShowTouches.buildPutCommand("1") },
            "会话收口按原值恢复回 1（恢复语义 = 回到会话前状态）",
        )
        assertFalse(ScreencastShowTouchesStore.isActive(), "恢复成功后标记清档")
    }

    @Test
    fun `source anchors - engine restore hangs once in session finally before terminal status`() {
        val source = sourceOf("ScreencastEngine.kt")
        val gateIdx = source.indexOf("if (showTouchesSession === showTouches) showTouchesSession = null")
        val restoreIdx = source.indexOf("showTouches.restoreOnSessionEnd()")
        val nonCancellableIdx = source.indexOf("withContext(NonCancellable) {")
        val terminalIdx = source.indexOf("_status.value = ScreencastStatus.Stopped(message, failed)")
        assertTrue(gateIdx >= 0, "会话引用摘除语句缺失（API 门禁收口面）")
        assertTrue(restoreIdx >= 0, "会话收口恢复调用缺失")
        assertEquals(
            1,
            source.split("showTouches.restoreOnSessionEnd()").size - 1,
            "恢复动作只允许一个调用点（会话 finally 单点，散点即红）",
        )
        assertTrue(
            gateIdx < restoreIdx,
            "须先摘会话引用再恢复——摘引用后迟来的切换请求在入口恒拒，收口与切换的竞态在 API 门禁关闭",
        )
        assertTrue(
            nonCancellableIdx in 0..restoreIdx,
            "恢复必须在会话 finally 的 NonCancellable 段——取消态下清理会被协程取消短路",
        )
        assertTrue(
            restoreIdx < terminalIdx,
            "恢复必须先于终态落定——Runtime.shutdown 的终态等待窗才能覆盖恢复",
        )
    }

    @Test
    fun `source anchors - detached window carries hover contract note and toggle labels`() {
        val source = sourceOf("ScreencastDetachedWindow.kt")
        val noteIdx = source.indexOf("internal const val SHOW_TOUCHES_HOVER_NOTE")
        assertTrue(noteIdx >= 0, "hover 契约注记常量缺失")
        val note = source.substring(noteIdx).substringBefore("\n\n")
        assertTrue(
            "仅物理触摸可见" in note && "注入输入不显示" in note,
            "hover 注记必须写明「仅物理触摸可见、注入输入不显示」——AI 注入输入看不到触点是 scrcpy 系统语义，缺注记即被误判开关失效",
        )
        assertTrue(
            "SHOW_TOUCHES_HOVER_NOTE" in source.substringAfter("HoverTooltipBox("),
            "契约注记必须挂进开关钮的 tooltip（挂在常量上不消费 = 死接线）",
        )
        assertTrue(
            "if (showTouchesActive) \"关闭触点显示\" else \"开启触点显示\"" in source,
            "开关语义翻面缺失（开态可辨识的语义树锚——图标钮 ContentDescription 翻面）",
        )
        assertTrue(
            "tint = if (showTouchesActive)" in source,
            "开态图标须随 primary 翻面（手势图标钮同款开态高亮配方）",
        )
        assertTrue("toggleShowTouches(showTouchesActive)" in source, "开关钮点击须以当前态达 ops")
    }

    /** 源码锚用例的取材口：测试工作目录是模块目录，向上找 settings.gradle.kts 定位项目根 */
    private fun sourceOf(fileName: String): String {
        var dir = File(".").absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                return File(
                    dir,
                    "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/$fileName",
                ).readText()
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("项目根未定位（settings.gradle.kts 未在祖先目录找到）")
    }
}
