package com.jiaoay.easy_dev_toolbox.core.extensions

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ProcessTreeKiller] 契约守护。
 *
 * 病灶背景：`Process.destroy()` 只作用于直接子进程。shell 包装形态下直接子进程是
 * 解释器（`bash -l -c`），真正的命令是孙进程——解释器一死，孙进程被 init 收养后
 * 继续持有管道/端口，成为孤儿（`adb logcat &` 一类命令的典型表现）。
 */
class ProcessTreeKillerTest {

    private fun spawnTree(): Pair<Process, ProcessHandle> {
        // 父 shell 再起一个子 shell，再在其中跑 sleep —— 构成 父 → 子 → 孙 三层
        val parent = ProcessBuilder("bash", "-c", "bash -c 'sleep 120' & wait").start()
        // 等子孙真正起来（避免快照早于 fork）
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (parent.descendants().findAny().isPresent) break
            Thread.sleep(20)
        }
        val descendant = parent.descendants().findFirst().orElse(null)
            ?: error("测试前置失败：子进程未在 5s 内派生")
        return parent to descendant
    }

    @Test
    fun `destroyTree terminates descendants not just the direct child`() {
        val (parent, descendant) = spawnTree()
        try {
            assertTrue(descendant.isAlive, "前置：孙进程须在运行")

            val signaled = ProcessTreeKiller.destroyTree(parent, force = true)
            assertTrue(signaled >= 1, "至少主进程须收到终止信号（实际 $signaled）")

            assertTrue(
                parent.waitFor(5, TimeUnit.SECONDS),
                "主进程须在 5s 内退出",
            )
            assertTrue(
                descendant.onExit().orTimeout(5, TimeUnit.SECONDS).let { true },
                "🔴 孙进程必须一并终止——只杀直接子进程会留下孤儿",
            )
        } finally {
            if (parent.isAlive) runCatching { parent.destroyForcibly() }
            if (descendant.isAlive) runCatching { descendant.destroyForcibly() }
        }
    }

    @Test
    fun `destroyTree on already-exited process is a no-op`() {
        val process = ProcessBuilder("bash", "-c", "exit 0").start()
        process.waitFor(5, TimeUnit.SECONDS)
        assertEquals(0, ProcessTreeKiller.destroyTree(process, force = true), "已退出进程不应发出信号")
    }
}
