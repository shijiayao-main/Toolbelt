package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 🔴 2026-09-17 复核修复回归（#12 后半·二次）：排干协程 join 的**上限必须真正生效**。
 *
 * ## 背景（这是「修了但无效」的典型形态，用例存在的意义就是钉死它）
 * 首轮修复给第 7 步的 `drainOut.join()/drainErr.join()` 套了
 * `withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MILLIS)`，看似有了 1.5s 上限。但 drain 协程当时是
 * 本函数 `coroutineScope { ... }` 的**子协程**——`withTimeoutOrNull` 只能「放弃 join」，
 * `coroutineScope` 在块返回前**仍会等全部子协程完成**，而 drain 阻塞在**不可取消**的
 * `reader.readLine()` / `lines.send()`（背压，无消费者）上 ⇒ `launch()` 永不返回，
 * 上限被彻底架空（复核实测：设 1500ms 上限，9s 仍未返回；孙进程持管道场景 20005ms）。
 *
 * 修法 = drain 移入独立 `streamScope`（同型先例 `core/.../StreamingShell.kt`）。
 *
 * ## 判据
 * 用**容量 1 且无消费者**的管道制造必然阻塞的 `lines.send`（比依赖进程行为更确定），
 * 外层给 8s 上限断言 `launch()` 返回。**修复前此用例必然超时失败**（launch 挂死），
 * 修复后约 1.5s 返回。
 */
class DrainJoinTimeoutTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("drain-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    @Test
    fun `排干阻塞时 launch 仍在上限内返回（join 超时不被 coroutineScope 架空）`() = runBlocking {
        val root = tempDir("blocked")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        // 容量 1 + 全程无消费者 ⇒ 第 2 行起 drain 永久阻塞在 lines.send（背压语义本身是对的）
        val lines = Channel<AgentLine>(1)
        try {
            val result = withTimeoutOrNull(8_000) {
                launcher.launch(
                    ProgramSpec(command = listOf("/bin/sh", "-c", "echo A; echo B; echo C"), workingDir = root),
                    lines,
                    totalTimeoutMillis = 3_000,
                    idleTimeoutMillis = 60_000,
                )
            }
            assertTrue(
                result != null,
                "🔴 launch 未在 8s 内返回——排干协程的 join 上限被 coroutineScope 架空" +
                    "（drain 必须是独立 scope 的子协程，见 ProgramLauncher 第 4 步注释）",
            )
        } finally {
            // 进程已退出、管道无消费者：取消管道让残留 drain 收敛
            lines.cancel()
            delay(200) // 给独立 scope 的读协程一点退出时间，避免污染后续用例
            registry.close()
        }
        Unit
    }
}
