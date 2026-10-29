package com.jiaoay.easy_dev_toolbox.page.archive

import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 解压任务取消收口单测（公开入口 addFiles/cancelTask 驱动）：
 *
 * - 重跑覆盖裁决：产物目录已在盘的重拖不直接建卡（挂覆盖确认，logkit 同口径），
 *   驳回不破坏历史内容；确认后清空旧目录重新解压（新旧混杂根治）；
 * - 未启动取消：全新产物目录 → Cancelled（null），零产物；
 * - 检查点停写：zip 切分卷解压循环逐条目带 [FileTaskViewModel.ensureTaskActive]
 *   检查点，取消后解压即停（解出条目数 < 总条目数），已解出部分保留并回填。
 *
 * 调度纪律：Main 替换 StandardTestDispatcher——建卡/取消在测试线程同步执行
 * （首卡恒在途的确定性去重/取消时序），解压在真实 IO 线程跑，续体落回 Main
 * 队列由 advanceUntilIdle 排空（LogTagFilterViewModelTest 同源纪律）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelCancelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Thread.sleep(100)
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private val createdVms = mutableListOf<ArchiveViewModel>()
    private val tempDirs = mutableListOf<File>()

    private fun vm(): ArchiveViewModel = ArchiveViewModel().also { createdVms += it }

    private fun tempDir(): File = Files.createTempDirectory("edt-archive-cancel").toFile().also { tempDirs += it }

    private fun zipFile(dir: File, name: String, entries: Map<String, String>): File =
        File(dir, name).apply {
            ZipOutputStream(outputStream()).use { zos ->
                entries.forEach { (entryName, content) ->
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
        }

    /** 等指定任务落终态（真实 IO 续体落回 Main 队列，advanceUntilIdle 排空） */
    private fun awaitTerminal(vm: ArchiveViewModel, taskId: String): TaskUiStatus {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            val task = vm.tasks.value.firstOrNull { it.id == taskId } ?: continue
            if (task.status !is TaskUiStatus.Running) return task.status
            Thread.sleep(10)
        }
        fail("任务未在时限内落终态：${vm.tasks.value}")
    }

    @Test
    fun `re-drag with existing output hangs on overwrite confirm and keeps history`() {
        val dir = tempDir()
        val archive = zipFile(dir, "data.zip", mapOf("inner/first.txt" to "one"))
        val destDir = File(dir, "data.zip_extracted")
        val viewModel = vm()

        // 首次解压跑完，产物目录落定（历史内容）
        viewModel.addFiles(listOf(archive))
        val firstId = viewModel.tasks.value.single().id
        val first = awaitTerminal(viewModel, firstId)
        assertTrue(first is TaskUiStatus.Success, "首次解压应成功：$first")

        // 重拖同一压缩包：产物目录在盘 → 挂覆盖裁决不直接建卡（与 logkit 重跑
        // affordance 同口径——重解压合并语义会把旧目录残留混进新产物）；
        // 挂起裁决期历史内容原样保留
        viewModel.addFiles(listOf(archive))
        assertTrue(viewModel.overwriteRequest.value != null, "产物在盘须挂覆盖裁决")
        assertEquals(1, viewModel.tasks.value.size, "裁决前不建第二张卡：${viewModel.tasks.value}")
        assertTrue(File(destDir, "inner/first.txt").isFile, "裁决期历史解压内容不破坏")

        // 确认覆盖：旧目录先清空再重新解压（新旧混杂根治）
        viewModel.confirmOverwrite()
        val secondId = viewModel.tasks.value.single { it.id != firstId }.id
        val second = awaitTerminal(viewModel, secondId)
        assertTrue(second is TaskUiStatus.Success, "确认覆盖后重跑应成功：$second")
        assertTrue(File(destDir, "inner/first.txt").isFile, "重跑后产物再生")
    }

    @Test
    fun `dismiss overwrite keeps history and creates no card`() {
        val dir = tempDir()
        val archive = zipFile(dir, "keep.zip", mapOf("inner/first.txt" to "one"))
        val destDir = File(dir, "keep.zip_extracted")
        val viewModel = vm()

        viewModel.addFiles(listOf(archive))
        val firstId = viewModel.tasks.value.single().id
        assertTrue(awaitTerminal(viewModel, firstId) is TaskUiStatus.Success, "首次解压应成功")

        viewModel.addFiles(listOf(archive))
        assertTrue(viewModel.overwriteRequest.value != null, "产物在盘须挂覆盖裁决")
        viewModel.dismissOverwrite()

        assertEquals(1, viewModel.tasks.value.size, "驳回后不建卡：${viewModel.tasks.value}")
        assertTrue(File(destDir, "inner/first.txt").isFile, "驳回覆盖不破坏历史解压内容")
    }

    @Test
    fun `cancel before start on fresh name yields cancelled with no receipt`() {
        val dir = tempDir()
        val archive = zipFile(dir, "fresh.zip", mapOf("a.txt" to "x"))
        val viewModel = vm()

        viewModel.addFiles(listOf(archive))
        viewModel.cancelTask(viewModel.tasks.value.single().id)
        val status = awaitTerminal(viewModel, viewModel.tasks.value.single().id)

        assertEquals(TaskUiStatus.Cancelled(null), status, "未启动即取消不应有产物回执")
        assertTrue(!File(dir, "fresh.zip_extracted").exists(), "未启动的解压不应产出目录")
    }

    @Test
    fun `zip split extraction stops at checkpoint on cancel`() {
        val dir = tempDir()
        // 单卷 zip 切分（.zip.001）——processZipSplits → extractZipStream 逐条目检查点路径。
        // 400 条目 × 150KB（零字节压得极小，解压侧写满 60MB）——解压时长远大于取消时延
        val entryCount = 400
        val payload = ByteArray(150_000)
        val split = File(dir, "big.zip.001").apply {
            ZipOutputStream(outputStream()).use { zos ->
                repeat(entryCount) { i ->
                    zos.putNextEntry(ZipEntry("e%03d.txt".format(i)))
                    zos.write(payload)
                    zos.closeEntry()
                }
            }
        }
        val destDir = File(dir, "big.zip_extracted")
        val viewModel = vm()

        viewModel.addFiles(listOf(split))
        dispatcher.scheduler.advanceUntilIdle() // 启动解压（真实 IO 线程）
        // 等首个条目落盘即取消——其余条目尚未解出（60MB 余量远大于轮询间隔）
        val firstEntry = File(destDir, "e000.txt")
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !firstEntry.isFile) Thread.sleep(2)
        val taskId = viewModel.tasks.value.single().id
        viewModel.cancelTask(taskId)

        val status = awaitTerminal(viewModel, taskId)
        val cancelled = status as? TaskUiStatus.Cancelled
        assertTrue(cancelled != null, "取消应落 Cancelled：$status")
        assertEquals(destDir.absolutePath, cancelled.partialOutput, "已解出部分应保留并回填")
        // 排空 + 余量后计数：解压在检查点停止（条目数 < 总数）
        Thread.sleep(200)
        dispatcher.scheduler.advanceUntilIdle()
        val extracted = destDir.listFiles()?.size ?: 0
        assertTrue(extracted < entryCount, "取消后应停止写产物（解出 $extracted < $entryCount）")
    }
}
