// 任务 15：UserNameResolver 的 LRU/TTL/peek/无锁 专项测试。
// 🔴 全部用假 fetch seam，绝不发真实网络请求、绝不创建凭据文件。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.gateway.SenderProfile
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UserNameResolverTest {

    // ==================== 冷路径 / 热路径 / 负向缓存 ====================

    @Test
    fun `冷路径命中 API 恰好一次`() = runBlocking {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(fetch = { calls.incrementAndGet(); "张三" })

        assertEquals("张三", resolver.nameOf("ou_a"))
        assertEquals(1, calls.get(), "首次调用应命中 API 恰好一次")
    }

    @Test
    fun `热路径零 API 调用（缓存命中）`() = runBlocking {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(fetch = { calls.incrementAndGet(); "张三" })

        resolver.nameOf("ou_a")
        assertEquals(1, calls.get())
        // 后续 100 次调用全部命中缓存
        repeat(100) { assertEquals("张三", resolver.nameOf("ou_a")) }
        assertEquals(1, calls.get(), "热路径应零 API 调用")
        assertEquals(100L, resolver.cacheHits)
    }

    @Test
    fun `负向缓存生效（连续多条未授权消息只打一次 API）`() = runBlocking {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(fetch = { calls.incrementAndGet(); null })

        // 模拟 10 条来自同一 openId 的消息（全部未授权）
        repeat(10) { assertNull(resolver.nameOf("ou_no_perm")) }
        assertEquals(1, calls.get(), "负向缓存必须生效：10 条消息只打一次 API")
        assertEquals(1L, resolver.fetchFailures)
    }

    @Test
    fun `API 抛异常时返回 null 不冒泡`() = runBlocking {
        val resolver = UserNameResolver(fetch = { throw RuntimeException("网络炸了") })

        assertNull(resolver.nameOf("ou_a"), "异常不应冒泡，应返回 null")
        assertEquals(1L, resolver.fetchFailures)
        // 后续调用命中负向缓存，不再抛
        assertNull(resolver.nameOf("ou_a"))
        assertEquals(1L, resolver.fetchTotal, "负向缓存后不再发起 fetch")
    }

    // ==================== peek() ====================

    @Test
    fun `peek 绝不触发 API（冷缓存返回 null）`() {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(fetch = { calls.incrementAndGet(); "张三" })

        // peek 在冷缓存上应返回 null 且不调 API
        assertNull(resolver.peek("ou_a"))
        assertEquals(0, calls.get(), "peek 绝不触发 API")
    }

    @Test
    fun `peek 命中热缓存返回名字`() = runBlocking {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(fetch = { calls.incrementAndGet(); "张三" })

        // 先通过 nameOf 填充缓存
        resolver.nameOf("ou_a")
        assertEquals(1, calls.get())
        // peek 应命中缓存
        assertEquals("张三", resolver.peek("ou_a"))
        assertEquals(1, calls.get(), "peek 不应额外触发 API")
    }

    @Test
    fun `peek 对负向缓存返回 null`() = runBlocking {
        val resolver = UserNameResolver(fetch = { null })

        resolver.nameOf("ou_x")  // 写入负向缓存
        assertNull(resolver.peek("ou_x"), "负向缓存的 peek 应返回 null（空串 ifBlank→null）")
    }

    // ==================== TTL ====================

    @Test
    fun `正向 TTL 12h 过期后重新 fetch`() = runBlocking {
        val calls = AtomicInteger(0)
        val time = AtomicLong(0L)
        val resolver = UserNameResolver(fetch = { calls.incrementAndGet(); "名字${calls.get()}" }, clock = { time.get() })

        assertEquals("名字1", resolver.nameOf("ou_a"))
        assertEquals(1, calls.get())

        // 11h 后仍命中缓存
        time.set(11L * 60 * 60 * 1000)
        assertEquals("名字1", resolver.nameOf("ou_a"))
        assertEquals(1, calls.get(), "11h 内应命中正向缓存")

        // 12h 后过期，重新 fetch
        time.set(12L * 60 * 60 * 1000)
        assertEquals("名字2", resolver.nameOf("ou_a"))
        assertEquals(2, calls.get(), "正向 TTL 12h 过期后应重新 fetch")
    }

    @Test
    fun `负向 TTL 5min 过期后重新 fetch`() = runBlocking {
        val calls = AtomicInteger(0)
        val time = AtomicLong(0L)
        val resolver = UserNameResolver(
            fetch = { calls.incrementAndGet(); if (calls.get() <= 1) null else "恢复名字" },
            clock = { time.get() },
        )

        assertNull(resolver.nameOf("ou_a"))
        assertEquals(1, calls.get())

        // 4min 后仍命中负向缓存
        time.set(4L * 60 * 1000)
        assertNull(resolver.nameOf("ou_a"))
        assertEquals(1, calls.get(), "4min 内应命中负向缓存")

        // 5min 后过期，重新 fetch（此时网络恢复）
        time.set(5L * 60 * 1000)
        assertEquals("恢复名字", resolver.nameOf("ou_a"))
        assertEquals(2, calls.get(), "负向 TTL 5min 过期后应重新 fetch")
    }

    // ==================== LRU 淘汰 ====================

    @Test
    fun `LRU 超过 1024 条后淘汰旧条目`() = runBlocking {
        val time = AtomicLong(0L)
        val resolver = UserNameResolver(fetch = { "name_$it" }, clock = { time.get() })

        // 填充 1025 条（超过 LRU_CAPACITY=1024）
        for (i in 0..1024) {
            time.set(i.toLong())  // 每条时间戳递增
            resolver.nameOf("ou_$i")
        }
        // 淘汰后缓存大小应 <= LRU_CAPACITY
        assertTrue(resolver.cacheSize <= UserNameResolver.LRU_CAPACITY,
            "缓存大小 ${resolver.cacheSize} 应 <= ${UserNameResolver.LRU_CAPACITY}")
    }

    // ==================== 无锁源码扫描断言 ====================

    @Test
    fun `UserNameResolver 源码归一化后零 synchronized 零 Mutex 零 runBlocking`() {
        val file = File(com.jiaoay.lark.bridge.pipeline.testRepoRoot(), "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/UserNameResolver.kt")
        assertTrue(file.isFile, "UserNameResolver.kt 必须存在")
        val normalized = com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings(file.readText(Charsets.UTF_8))
        val forbidden = listOf("synchronized", "Mutex", "runBlocking", "ReentrantLock", "withLock")
        val hits = forbidden.filter { normalized.contains(it) }
        assertEquals(emptyList(), hits, "🔴 UserNameResolver 必须零锁零阻塞原语（全链路无锁纪律）")
    }

    // ==================== profile 双产出（2026-09-14 用户身份链批）====================

    @Test
    fun `profileFetch 命中时名字与头像双产出且回填缓存`() = runBlocking {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(
            profileFetch = { calls.incrementAndGet(); SenderProfile("张三", "https://a.example/240.png") },
        )

        val profile = resolver.profileOf("ou_a")
        assertEquals("张三", profile?.name)
        assertEquals("https://a.example/240.png", profile?.avatarUrl)
        assertEquals(1, calls.get(), "首次冷读命中 profileFetch 恰好一次")

        // 🔴 R6：nameOf 与 profileOf 共享同一缓存条目 ⇒ nameOf 不再打 API
        assertEquals("张三", resolver.nameOf("ou_a"))
        assertEquals(1, calls.get(), "nameOf 应命中 profileOf 已回填的缓存，零额外网络")
        assertEquals("https://a.example/240.png", resolver.peekProfile("ou_a")?.avatarUrl, "peekProfile 只读命中")
    }

    @Test
    fun `profileFetch 为 null 时回落旧 fetch 名字-only（头像空串）`() = runBlocking {
        val profileCalls = AtomicInteger(0)
        val fetchCalls = AtomicInteger(0)
        val resolver = UserNameResolver(
            fetch = { fetchCalls.incrementAndGet(); "李四" },
            profileFetch = { profileCalls.incrementAndGet(); null },
        )

        assertEquals("李四", resolver.nameOf("ou_a"), "profileFetch miss 后应回落旧 fetch 取名")
        assertEquals(1, profileCalls.get())
        assertEquals(1, fetchCalls.get())
        // 回落路产生的缓存条目头像为空串——peekProfile 仍非 null（名字-only 条目）
        assertEquals("李四", resolver.peekProfile("ou_a")?.name)
        assertEquals("", resolver.peekProfile("ou_a")?.avatarUrl)
    }

    @Test
    fun `profileFetch 抛异常时静默回落旧 fetch`() = runBlocking {
        val fetchCalls = AtomicInteger(0)
        val resolver = UserNameResolver(
            fetch = { fetchCalls.incrementAndGet(); "王五" },
            profileFetch = { throw IllegalStateException("API 炸了") },
        )

        assertEquals("王五", resolver.nameOf("ou_a"), "profileFetch 抛异常必须静默回落 fetch，绝不影响主链路")
        assertEquals(1, fetchCalls.get())
        // 抛异常不计 fetch 失败（fetch 本身成功了）；后续调用走缓存
        assertEquals("王五", resolver.nameOf("ou_a"))
        assertEquals(1, fetchCalls.get(), "第二次应命中缓存")
    }

    @Test
    fun `profileOf 对负向占位与未命中返回 null`() = runBlocking {
        val resolver = UserNameResolver(profileFetch = { null }, fetch = { null })

        assertNull(resolver.profileOf("ou_x"), "两路全 miss ⇒ 负向占位 ⇒ profileOf 返回 null")
        // 负向占位已回填 ⇒ 热路径直接 null（不再打 API）
        assertEquals(1L, resolver.fetchTotal)
        assertNull(resolver.profileOf("ou_x"))
        assertEquals(1L, resolver.fetchTotal, "负向占位期内（5min TTL）不重复打 API")
        assertNull(resolver.peekProfile("ou_x"), "peekProfile 对负向占位返回 null")
        assertNull(resolver.peekProfile("ou_unknown"), "peekProfile 未命中返回 null")
    }

    @Test
    fun `profileOf 热路径命中缓存零 API`() = runBlocking {
        val calls = AtomicInteger(0)
        val resolver = UserNameResolver(
            profileFetch = { calls.incrementAndGet(); SenderProfile("名字$it", "https://a.example/$it.png") },
        )

        // 首次冷读回填
        resolver.nameOf("ou_a")
        assertEquals(1, calls.get())
        // 热路径：profileOf 命中 nameOf 已回填的条目 ⇒ 零 API
        val profile = resolver.profileOf("ou_a")
        assertEquals("名字ou_a", profile?.name)
        assertEquals("https://a.example/ou_a.png", profile?.avatarUrl)
        assertEquals(1, calls.get(), "profileOf 热路径命中缓存，零额外网络")
    }

    // ==================== 常量断言 ====================

    @Test
    fun `LRU 和 TTL 常量正确`() {
        assertEquals(1024, UserNameResolver.LRU_CAPACITY)
        assertEquals(12L * 60 * 60 * 1000, UserNameResolver.POSITIVE_TTL_MILLIS)
        assertEquals(5L * 60 * 1000, UserNameResolver.NEGATIVE_TTL_MILLIS)
    }

    // ==================== 辅助 ====================


}
