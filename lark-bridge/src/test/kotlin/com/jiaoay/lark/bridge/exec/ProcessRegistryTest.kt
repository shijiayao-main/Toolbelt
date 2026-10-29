package com.jiaoay.lark.bridge.exec

import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [方案 D9/R10 · 并发、背压与资源生命周期] ProcessRegistry：有界集（上限 maxConcurrent*2）+ 登记/注销配对 + killAll 清空。
 */
class ProcessRegistryTest {

    /** 轻量假 Process（纯登记语义测试用，不启真实进程避免 flaky）。 */
    private class FakeProcess(private var alive: Boolean = true) : Process() {
        var forciblyKilled = false
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { alive = false; return 0 }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
        override fun destroyForcibly(): Process { forciblyKilled = true; alive = false; return this }
        override fun isAlive(): Boolean = alive
    }

    @Test
    fun `capacity 为 maxConcurrent 的两倍`() {
        assertEquals(6, ProcessRegistry(maxConcurrent = 3).capacity)
        assertEquals(2, ProcessRegistry(maxConcurrent = 1).capacity)
        assertEquals(20, ProcessRegistry(maxConcurrent = 10).capacity)
    }

    @Test
    fun `capacity 至少为 1（maxConcurrent 为 0 时不塌陷）`() {
        assertEquals(1, ProcessRegistry(maxConcurrent = 0).capacity)
    }

    @Test
    fun `register 与 unregister 配对 - liveCount 增减`() {
        val registry = ProcessRegistry(maxConcurrent = 2)
        try {
            val p1 = FakeProcess()
            val p2 = FakeProcess()
            assertEquals(0, registry.liveCount)
            assertTrue(registry.register(p1))
            assertEquals(1, registry.liveCount)
            assertTrue(registry.register(p2))
            assertEquals(2, registry.liveCount)
            registry.unregister(p1)
            assertEquals(1, registry.liveCount)
            registry.unregister(p2)
            assertEquals(0, registry.liveCount)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `达容量上限 register 返回 false（有界，防子进程无限堆积）`() {
        val registry = ProcessRegistry(maxConcurrent = 1) // capacity = 2
        try {
            val p1 = FakeProcess()
            val p2 = FakeProcess()
            val p3 = FakeProcess()
            assertTrue(registry.register(p1))
            assertTrue(registry.register(p2))
            assertTrue(registry.isFull)
            assertFalse(registry.register(p3), "超过 capacity 应拒绝登记")
            assertEquals(2, registry.liveCount)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `注销后腾出容量可再登记`() {
        val registry = ProcessRegistry(maxConcurrent = 1)
        try {
            val p1 = FakeProcess()
            val p2 = FakeProcess()
            assertTrue(registry.register(p1))
            assertTrue(registry.register(p2))
            assertFalse(registry.register(FakeProcess()))
            registry.unregister(p1)
            assertFalse(registry.isFull)
            assertTrue(registry.register(FakeProcess()))
        } finally {
            registry.close()
        }
    }

    @Test
    fun `killAll 强杀全部在途并清空 liveCount`() {
        val registry = ProcessRegistry(maxConcurrent = 5)
        try {
            val procs = List(4) { FakeProcess() }
            procs.forEach { assertTrue(registry.register(it)) }
            assertEquals(4, registry.liveCount)
            registry.killAll()
            assertEquals(0, registry.liveCount)
            assertTrue(procs.all { it.forciblyKilled }, "killAll 应对每个在途进程 destroyForcibly")
            assertTrue(procs.none { it.isAlive })
        } finally {
            registry.close()
        }
    }

    @Test
    fun `killAll 对空表安全`() {
        val registry = ProcessRegistry(maxConcurrent = 2)
        try {
            registry.killAll()
            assertEquals(0, registry.liveCount)
        } finally {
            registry.close()
        }
    }

    // ==================== 🔴 2026-09-17 审计修复回归（HOT 容量热更） ====================

    /**
     * 审计 §五③：maxConcurrentAgents 标 HOT 但 capacity 装配期固化——HOT 扩容后
     * PermitGate 放行、registry 按旧容量拒收新进程。修复 = reconfigureCapacity 热更口。
     */
    @Test
    fun `reconfigureCapacity 扩容后新进程可登记`() {
        val registry = ProcessRegistry(maxConcurrent = 1)   // capacity = 2
        try {
            val procs = List(2) { FakeProcess() }
            procs.forEach { assertTrue(registry.register(it)) }
            assertEquals(2, registry.liveCount)

            // 旧容量已满：再登记被拒
            assertFalse(registry.register(FakeProcess()), "容量 2 已满时 register 应拒绝")

            // HOT 扩容到 4（capacity = 8）：新进程可登记
            registry.reconfigureCapacity(4)
            assertEquals(8, registry.capacity)
            assertTrue(registry.register(FakeProcess()), "扩容后 register 应放行")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `reconfigureCapacity 缩容不影响在途进程`() {
        val registry = ProcessRegistry(maxConcurrent = 2)   // capacity = 4
        try {
            val procs = List(2) { FakeProcess() }
            procs.forEach { assertTrue(registry.register(it)) }

            // 缩容到 1（capacity = 2）：在途 2 个进程仍在表内（不杀、不清）
            registry.reconfigureCapacity(1)
            assertEquals(2, registry.liveCount, "缩容只影响后续 register 判定，在途不杀")
            assertTrue(procs.all { it.isAlive })
        } finally {
            registry.close()
        }
    }
}
