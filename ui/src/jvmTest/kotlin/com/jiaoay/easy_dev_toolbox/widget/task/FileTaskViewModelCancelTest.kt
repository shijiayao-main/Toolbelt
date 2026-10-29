package com.jiaoay.easy_dev_toolbox.widget.task

import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.TaskUi
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 文件任务基座取消语义单测（阻塞实现不被强停的收口契约 + 检查点停写 + 单飞去重）：
 *
 * - 检查点停写：带 [FileTaskViewModel.ensureTaskActive] 检查点的 process 循环在取消后
 *   停止写产物（产物分块数 < 总数），卡面 Cancelled 且部分产物路径回填；
 * - 阻塞实现收口：无检查点的 process 跑完后在 withContext 边界观察到取消，
 *   卡面 Cancelled 且产物如实回填（不谎报「未产生产物」）；
 * - 取消前置收口：任务启动前取消 → Cancelled（null）——无产物在盘；
 * - 单飞去重：同产物目标（含输入文件路径兜底键）的在途任务未结束时重复提交被跳过，
 *   且拒绝经 StatusSignals 明示原因（非静默丢弃）；
 * - hasRunningTasks 随在途任务翻转（拖放区 busy 门禁消费）。
 *
 * 测试 VM 直接继承基类（公开入口 addFiles/cancelTask 驱动，不放宽 protected 可见性）。
 * Main 替换为 StandardTestDispatcher：建卡/取消在测试线程同步执行（单写点无并发窗口），
 * process 在真实 IO 线程跑，真实 IO 续体落回 Main 队列由 advanceUntilIdle 排空。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTaskViewModelCancelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        // drain 纪律（真实 IO 续体落回 Main 队列晚于断言，直接 resetMain 撞并发窗口）
        Thread.sleep(100)
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        StatusSignals.clearForTest()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private val createdVms = mutableListOf<FakeTaskViewModel>()
    private val tempDirs = mutableListOf<java.io.File>()

    /** 可注入 process 行为的假 VM：产物 = 单文件，目标集 = 产物路径（去重键同一份） */
    private open class FakeTaskViewModel(
        private val totalChunks: Int,
        private val chunkDelayMs: Long,
        private val product: java.io.File,
    ) : FileTaskViewModel() {

        var checkpointed = true

        override suspend fun process(file: File): ProcessResult {
            for (i in 0 until totalChunks) {
                if (checkpointed) ensureTaskActive()
                product.appendText("chunk-$i\n")
                if (chunkDelayMs > 0) delay(chunkDelayMs)
            }
            return ProcessResult.Success(product.absolutePath)
        }

        override fun outputTargetsOf(file: File): Set<String> = setOf(product.absolutePath)

        override fun onTaskCancelled(file: File): String? =
            if (product.isFile) product.absolutePath else null
    }

    private fun tempDir(): java.io.File =
        Files.createTempDirectory("edt-task-cancel").toFile().also { tempDirs += it }

    private fun input(dir: java.io.File, name: String = "in"): java.io.File =
        Files.createTempFile(dir.toPath(), name, ".txt").toFile()

    /** 等任务落终态（advanceUntilIdle 排 Main 队列 + 真实 IO 轮询，超时 fail）；
     *  多卡并存（终态后重提交）用 [taskId] 定位，单卡用例省略即唯一卡 */
    private fun awaitTerminal(vm: FakeTaskViewModel, taskId: String? = null): TaskUi {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            val task = vm.tasks.value.single { taskId == null || it.id == taskId }
            if (task.status !is TaskUiStatus.Running) return task
            Thread.sleep(10)
        }
        error("任务未在时限内落终态：${vm.tasks.value}")
    }

    // ── 检查点停写 + partialOutput 回填 ─────────────────────────

    @Test
    fun `cancel with checkpoints stops writing and backfills partial output`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 32, chunkDelayMs = 40, product = product)
            .also { createdVms += it }
        val input = input(dir)

        vm.addFiles(listOf(input))
        // 推进调度器启动 process（真实 IO 线程），等首个分块落盘后取消——
        // 循环还有 31 块未写，取消必落在中途（延迟档 40ms 远大于轮询间隔）
        dispatcher.scheduler.advanceUntilIdle()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && product.length() == 0L) Thread.sleep(5)
        val taskId = vm.tasks.value.single().id
        vm.cancelTask(taskId)

        val task = awaitTerminal(vm)
        val status = task.status as? TaskUiStatus.Cancelled
        assertTrue(status != null, "取消应落 Cancelled：${task.status}")
        assertEquals(product.absolutePath, status.partialOutput, "在盘部分产物应回填卡面")
        val chunks = product.readLines().size
        assertTrue(chunks in 1 until 32, "取消后应停止写产物（部分分块而非全部）：chunks=$chunks")
    }

    @Test
    fun `cancel before start yields cancelled with no partial output`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 4, chunkDelayMs = 0, product = product)
            .also { createdVms += it }
        val input = input(dir)

        vm.addFiles(listOf(input))
        // 未推进调度器（process 未启动）即取消
        vm.cancelTask(vm.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()
        val task = awaitTerminal(vm)

        assertEquals(TaskUiStatus.Cancelled(null), task.status, "未产出产物取消应回填 null")
        assertTrue(!product.isFile, "未启动的 process 不应产出任何产物")
    }

    @Test
    fun `blocking process finishing after cancel still lands cancelled with receipt`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 4, chunkDelayMs = 0, product = product)
            .also { createdVms += it }
        // 无检查点模拟阻塞实现：整段写完（不可强停），取消只在挂起点被观察到
        vm.checkpointed = false
        val input = input(dir)

        vm.addFiles(listOf(input))
        // 推进调度器：process 在真实 IO 上即刻写完全部块，续体排队回 Main（未运行）
        dispatcher.scheduler.advanceUntilIdle()
        // 等首块落盘再取消：收口回执按取消时刻的在盘产物计算（确定性，不赌 IO 时序）
        val startDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < startDeadline && !product.isFile) Thread.sleep(2)
        vm.cancelTask(vm.tasks.value.single().id)
        // 阻塞实现不被强停：取消后剩余分块照写，等写满再断言
        val writeDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < writeDeadline && product.readLines().size < 4) Thread.sleep(2)
        val task = awaitTerminal(vm)

        val status = task.status as? TaskUiStatus.Cancelled
        assertTrue(status != null, "跑完才观察到的取消仍应落 Cancelled（withContext 边界）：${task.status}")
        assertEquals(product.absolutePath, status.partialOutput, "已写完的产物应如实回填而非谎报无产物")
        assertEquals(4, product.readLines().size, "阻塞实现不被强停：全部分块写完")
    }

    // ── 单飞去重 ─────────────────────────────────────────────

    @Test
    fun `duplicate submit while in flight is skipped with reason toast`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 4, chunkDelayMs = 0, product = product)
            .also { createdVms += it }

        // 不推进调度器：首卡恒在途（Running），第二次提交必撞去重（确定性，不依赖 process 时长）
        vm.addFiles(listOf(input(dir, "a")))
        vm.addFiles(listOf(input(dir, "b")))

        assertEquals(1, vm.tasks.value.size, "同产物目标的重复提交应被跳过：${vm.tasks.value.map { it.fileName }}")
        val toast = StatusSignals.toast.value
        assertTrue(toast.any { "已跳过" in it.text }, "拒绝必须带原因（StatusSignals 明示）：$toast")
    }

    @Test
    fun `resubmit after terminal state is accepted`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 1, chunkDelayMs = 0, product = product)
            .also { createdVms += it }

        vm.addFiles(listOf(input(dir, "a")))
        awaitTerminal(vm)
        // 终态后重提交：产物已在盘 → 挂覆盖裁决（重跑 affordance），确认后照常建卡
        // （在途去重只对 Running 生效——终态产物在盘属覆盖确认卡的管辖面）
        vm.addFiles(listOf(input(dir, "b")))
        kotlin.test.assertNotNull(vm.overwriteRequest.value, "产物在盘须挂覆盖裁决")
        vm.confirmOverwrite()
        awaitTerminal(vm, vm.tasks.value.last().id)

        assertEquals(2, vm.tasks.value.size, "确认覆盖后重提交正常建卡")
    }

    @Test
    fun `existing product routes to overwrite confirm instead of dead-end submit`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 1, chunkDelayMs = 0, product = product)
            .also { createdVms += it }

        product.writeText("旧产物")
        vm.addFiles(listOf(input(dir)))

        assertEquals(0, vm.tasks.value.size, "产物在盘不得直接建卡（库层硬拒是死端面）")
        val request = vm.overwriteRequest.value
        kotlin.test.assertNotNull(request, "产物在盘须挂覆盖裁决（重跑 affordance）")
        assertTrue(request!!.conflicts.contains("out.txt"), "确认卡须明示将被覆盖的产物")

        vm.dismissOverwrite()
        assertEquals(0, vm.tasks.value.size, "取消覆盖不建卡（旧产物原样保留）")
        assertEquals("旧产物", product.readText(), "取消覆盖不得动旧产物")
    }

    @Test
    fun `confirm overwrite clears stale product and reruns`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 1, chunkDelayMs = 0, product = product)
            .also { createdVms += it }

        product.writeText("旧产物")
        vm.addFiles(listOf(input(dir)))
        vm.confirmOverwrite()
        val task = awaitTerminal(vm)

        assertTrue(task.status is TaskUiStatus.Success, "确认覆盖后重跑成功：${task.status}")
        assertTrue("chunk-0" in product.readText(), "覆盖重跑产物已刷新（旧内容被替换）")
    }

    @Test
    fun `hasRunningTasks flips with in-flight work`() {
        val dir = tempDir()
        val product = java.io.File(dir, "out.txt")
        val vm = FakeTaskViewModel(totalChunks = 4, chunkDelayMs = 20, product = product)
            .also { createdVms += it }

        vm.addFiles(listOf(input(dir)))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.hasRunningTasks.value, "有在途任务时应为 true（拖放区 busy 门禁消费）")

        vm.cancelTask(vm.tasks.value.single().id)
        awaitTerminal(vm)
        assertTrue(!vm.hasRunningTasks.value, "全部终态后应回 false")
    }
}
