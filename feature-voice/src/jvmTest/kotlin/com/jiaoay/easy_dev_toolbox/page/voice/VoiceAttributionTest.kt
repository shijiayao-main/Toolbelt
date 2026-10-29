package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * AG-04 语音产物自动归属单测（重设计批 1 · knowledge:// 轻归属拍板）：
 * - 挂点 3（exportNote）：Main 同步非 suspend → launch(Dispatchers.IO) 包裹的归属链
 *   round-trip（导出文件 absolutePath 落当前空间 filePaths）；
 * - 挂点 1/2（process/summarizeRecording 直调点）：归属语义的 provider null/空间缺失/
 *   异常路径由 :core SpaceAttributionTest 既有覆盖，本测补 VM 侧挂点语义——无选中空间
 *   （provider=null）导出不回归、不炸（静默跳过非错误）。
 *
 * ## 测试缝
 * - 🔴 SpaceAttribution.databaseProvider 是 internal——跨模块（:core → :feature-voice）
 *   不可见（K2 internal 跨模块拒绝），不能像 :core 的 SpaceAttributionTest 那样注入临时库；
 *   改走 user.home 隔离：Database.database() 真单例落临时 home，SpaceAttribution 默认
 *   写点（Database.database()）与断言面（本测直读）即同一隔离库闭环
 *   （UserHomeIsolation 共享件三件套同款样板——feature-voice 无 :test-fixtures 依赖，
 *   手写三行：重定向 user.home 即重定向 FileUtils.getAppDataDir 数据根）；
 * - [VoiceViewModel.setAcceptedNoteForTest]：acceptedNote 直灌（生产唯一写点 acceptNote
 *   需 session 产 Produced 态——真实 LLM，单测不可驱动）；
 * - 🔴 Main dispatcher 替换（viewModelScope = Main.immediate）+ VM 级 IO 调度器注入
 *   （exportNote 归属协程与 Main 共享同一 TestScheduler——advanceUntilIdle 即确定性
 *   排空归属链，无需真实 IO 轮询；Database 直连在归属协程内同步执行，同源推进）。
 *
 * VM 构造触达 ModelStore.refresh/refreshVad（隔离 home 内模型目录读——无模型即
 * Unknown 态，零 JNI 加载，ModelStoreTest 同款安全面）；init 的 LlmConfigStore 读走
 * 隔离 home 的 DataStore（首次建库只读无写）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAttributionTest {

    private val dispatcher = StandardTestDispatcher()
    private val originalHome: String? = System.getProperty("user.home")
    private lateinit var tempHome: File

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempHome = File(System.getProperty("java.io.tmpdir"), "edt-voice-attr-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }
        System.setProperty("user.home", tempHome.absolutePath) // Database.database() 真单例落隔离库
        // 🔴 Database.instance 是 JVM 级缓存——跨用例串库（上一用例的临时库已随
        //    tearDown 删除，缓存连接指向已删文件 → SQLITE_CANTOPEN）：重定向后必须
        //    resetForTest 清缓存，db() 才会针对新 home 懒重建
        Database.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        SpaceAttribution.currentSpaceIdProvider = null
        dispatcher.scheduler.advanceUntilIdle() // 排空在途协程再 resetMain（drain 纪律）
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        originalHome?.let { System.setProperty("user.home", it) }
        Database.resetForTest() // 清指向临时库的缓存连接（先还原 user.home 再清）
        runCatching { tempHome.deleteRecursively() } // 删失败静默（DataStore 在途写防 flaky）
    }

    /** Database.database() 真单例（隔离 home 内）——SpaceAttribution 默认写点同源 */
    private fun db() = Database.database()

    /**
     * VM 级 IO 与 Main 同 TestScheduler 后归属链确定性排空；轮询环保留作推进兜底
     * （首拍 advanceUntilIdle 即完成，victim 不满足才继续轮——防御未来新增在途段）。
     */
    private fun settle(victim: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!victim() && System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (victim()) break
            Thread.sleep(50)
        }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `export note attributes exported file into current space`() {
        val spaceStore = SpaceStore(db())
        spaceStore.create(id = "space-voice", title = "语音归属测试")
        SpaceAttribution.currentSpaceIdProvider = { "space-voice" }

        val vm = VoiceViewModel(transcriber = FakeOkTranscriber, ioDispatcher = dispatcher)
        vm.setAcceptedNoteForTest("# 纪要\n要点一")

        val out = vm.exportNote()
        assertNotNull(out, "导出返回文件")
        assertTrue(out.isFile, "纪要文件已落盘")

        settle { spaceStore.spaceById("space-voice")?.filePaths?.contains(out.absolutePath) == true }
        assertEquals(
            listOf(out.absolutePath),
            spaceStore.spaceById("space-voice")?.filePaths,
            "挂点 3：导出纪要文件 absolutePath 归属当前调查空间",
        )
    }

    @Test
    fun `export note with no current space silently skips attribution`() {
        val spaceStore = SpaceStore(db())
        spaceStore.create(id = "space-voice-2", title = "无空间导出测试")
        SpaceAttribution.currentSpaceIdProvider = { null } // 无选中空间——窗口期语义

        val vm = VoiceViewModel(transcriber = FakeOkTranscriber, ioDispatcher = dispatcher)
        vm.setAcceptedNoteForTest("无空间纪要")

        val out = vm.exportNote()
        assertNotNull(out, "无选中空间导出照常完成（静默跳过非错误）")
        assertTrue(out.isFile, "导出主流程不受归属影响")

        // 归属协程与 Main 同 TestScheduler——排空即跑完（静默跳过分支）证伪不落库
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            emptyList(),
            spaceStore.spaceById("space-voice-2")?.filePaths,
            "无选中空间静默跳过——不写成员（不回归）",
        )
    }
}

/** fake 转写器：不触 JNI 模型（SherpaTranscriber 惰性加载由本替身短路——构造零 JNI） */
private object FakeOkTranscriber : Transcriber {
    override fun transcribe(audioFile: File): Result<Transcription> = Result.success(Transcription("转写文本"))
}
