package com.jiaoay.easy_dev_toolbox.widget.task

import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 任务卡产物「送飞书」单测（ReportDelivery 消费面）：
 *
 * - VM 语义（[FileTaskViewModel.sendProductToLark]）：成功产物经中立槽外发——
 *   通道返回 null = 已送出**成功**语义（反向断言：回执是成功 toast 而非被
 *   elvis 吞成的失败人话）；非 null = 失败人话原样回执；目录产物与非终态
 *   任务 no-op（不外发、零 toast）；
 * - 菜单装配源码锚（:ui 无 Compose 运行时直测缝，源码切片是一等守护形态——
 *   LogViewerSelectionGuardTest 同款配方）：「送飞书」条目在共享单源函数
 *   [buildTaskContextMenuEntries] 成功态（isFile 门控内），置灰判据 =
 *   [ReportDelivery.ready] 单源（enabled 形态置灰不隐藏），执行体经
 *   TaskListSectionActions 槽注入；VM 外发先收 reason 再判空分叉（无 elvis）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTaskViewModelLarkSendTest {

    private val dispatcher = StandardTestDispatcher()

    /** 通道回执脚本（var 捕获：null = 已送出；非 null = 失败人话） */
    private var deliverResult: String? = null
    private val deliveredFiles = mutableListOf<File>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        StatusSignals.clearForTest()
        ReportDelivery.clearForTest()
        deliveredFiles.clear()
        deliverResult = null
        ReportDelivery.wire { file ->
            deliveredFiles += file
            deliverResult
        }
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        StatusSignals.clearForTest()
        ReportDelivery.clearForTest()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private val createdVms = mutableListOf<FakeTaskViewModel>()
    private val tempDirs = mutableListOf<File>()

    /**
     * 产物 = 指定路径的单文件（或目录——目录产物用例），process 即时成功。
     * 产物文件须真实落盘（VM 的 isFile 守卫按在盘状态判——只报路径不落盘
     * 会被当成目录/缺失产物静默 no-op）。
     */
    private open class FakeTaskViewModel(private val product: File) : FileTaskViewModel() {
        override suspend fun process(file: File): ProcessResult {
            if (!product.isDirectory) product.writeText("product-of-${file.name}")
            return ProcessResult.Success(product.absolutePath)
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("edt-task-lark-send").toFile().also { tempDirs += it }

    private fun input(dir: File): File = Files.createTempFile(dir.toPath(), "in", ".txt").toFile()

    /** 等任务落终态（advanceUntilIdle 排 Main 队列 + 真实 IO 轮询，超时 fail） */
    private fun awaitSuccess(vm: FakeTaskViewModel): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            vm.tasks.value.singleOrNull()?.let { task ->
                when (val status = task.status) {
                    is TaskUiStatus.Success -> return status.outputPath
                    is TaskUiStatus.Error -> error("任务失败：${status.message}")
                    else -> Unit
                }
            }
            Thread.sleep(10)
        }
        error("任务未在时限内落终态：${vm.tasks.value}")
    }

    // ── VM 语义：null = 已送出（成功不被吞）────────────────────

    @Test
    fun `success product delivers file with success toast`() {
        val dir = tempDir()
        val product = File(dir, "out.txt")
        val vm = FakeTaskViewModel(product).also { createdVms += it }
        vm.addFiles(listOf(input(dir)))
        val outputPath = awaitSuccess(vm)

        vm.sendProductToLark(vm.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(product), deliveredFiles, "外发文件须是成功产物本身：$outputPath")
        // 反向断言：deliver 返回 null = 已送出成功——回执必须是成功 toast；
        // 若被 elvis 吞掉，这里会出现失败人话而非成功回执
        val toast = StatusSignals.toast.value
        assertTrue(
            toast.any { "已送出至飞书会话" in it.text && product.name in it.text },
            "null = 已送出，回执须为成功 toast（成功不得被吞成失败）：$toast",
        )
    }

    @Test
    fun `failure reason from channel is echoed as toast`() {
        val dir = tempDir()
        val product = File(dir, "out.txt")
        val vm = FakeTaskViewModel(product).also { createdVms += it }
        vm.addFiles(listOf(input(dir)))
        awaitSuccess(vm)

        deliverResult = "桥接未运行，请先在飞书桥接页启动"
        vm.sendProductToLark(vm.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(product), deliveredFiles, "失败前通道已收到外发请求")
        val toast = StatusSignals.toast.value
        assertTrue(
            toast.any { it.text == "桥接未运行，请先在飞书桥接页启动" },
            "失败人话须由通道归一后原样回执：$toast",
        )
    }

    // ── VM 守卫：目录产物与非 Success 态 no-op ─────────────────

    @Test
    fun `directory product is not delivered`() {
        val dir = tempDir()
        val productDir = File(dir, "out-dir").also { it.mkdirs() }
        val vm = FakeTaskViewModel(productDir).also { createdVms += it }
        vm.addFiles(listOf(input(dir)))
        awaitSuccess(vm)

        vm.sendProductToLark(vm.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(deliveredFiles.isEmpty(), "目录产物不可作文件消息外发：$deliveredFiles")
        assertTrue(StatusSignals.toast.value.isEmpty(), "no-op 不留回执")
    }

    @Test
    fun `running task is not delivered`() {
        val dir = tempDir()
        val product = File(dir, "out.txt")
        val vm = FakeTaskViewModel(product).also { createdVms += it }
        vm.addFiles(listOf(input(dir)))
        // 不推进调度器：任务恒 Running——非 Success 态 no-op
        vm.sendProductToLark(vm.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(deliveredFiles.isEmpty(), "Running 中途产物不外发（终态前产物不完整）")
        assertTrue(StatusSignals.toast.value.isEmpty(), "no-op 不留回执")
    }

    // ── 装配源码锚：条目单源 + 置灰判据 + no-elvis 分叉 ──────────

    @Test
    fun `menu entry lives in single source with ready as disabled predicate`() {
        val section = sourceAt(
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/TaskListSection.kt",
        )
        // 条目在共享单源函数（右键与 ⋯ 两入口同获），置灰判据 = ready 单源
        assertTrue(
            "ContextMenuEntry(\"送飞书\", enabled = ReportDelivery.ready.value)" in section,
            "「送飞书」条目须在共享单源函数内且以 ReportDelivery.ready 为置灰判据单源",
        )
        // isFile 门控内（目录产物条目缺席——与「查看」同口径）
        val menuBody = section.substringAfter("internal fun buildTaskContextMenuEntries(")
        val isFileGateIdx = menuBody.indexOf("if (File(success.outputPath).isFile)")
        val sendIdx = menuBody.indexOf("送飞书")
        assertTrue(
            isFileGateIdx in 0 until sendIdx,
            "「送飞书」条目须落在 isFile 门控内（目录产物不可作文件消息外发）",
        )
        // VM 动作经槽注入（TaskListSection 组合期单点）
        assertTrue(
            "TaskListSectionActions.sendToLark = viewModel::sendProductToLark" in section,
            "「送飞书」执行体须经 TaskListSectionActions.sendToLark 槽注入 VM 动作",
        )
    }

    @Test
    fun `vm deliver branches on reason without elvis swallow`() {
        val vm = sourceAt(
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/FileTaskViewModel.kt",
        )
        val sendBody = vm.substringAfter("fun sendProductToLark(")
        // null = 已送出成功语义——先收 reason 再判空分叉，不可 elvis 兜底
        assertTrue(
            "val reason = ReportDelivery.deliver(file)" in sendBody,
            "外发须先收 reason（不可 elvis 兜底——会把 null 成功吞成失败）",
        )
        assertTrue(
            "if (reason == null)" in sendBody,
            "须以 reason == null 分叉成功/失败回执",
        )
        assertTrue(
            !Regex("""ReportDelivery\.deliver\(file\)\s*\?:""").containsMatchIn(sendBody),
            "deliver 调用不得带 elvis 兜底（null = 已送出成功语义会被吞）",
        )
    }

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
