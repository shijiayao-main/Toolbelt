// :lark-gateway 测试（M3-T3，§11.5 CardThrottleTest = BC NF-02，裁定 6/B-17 归属本轮）。
// 🔴 零 SDK / 零真机：sink 主循环经 FakeCardStreamPort + 虚拟时间驱动（§11.0）。
// 🔴 §11.5 ⑦：长驻主循环一律挂 backgroundScope（否则 runTest 因未结束子协程挂死）。
// 🔴 滚动窗口语义（2026-09-12）：tryAppend→tryReplace 全量替换；字符增量判定（charsHit）随追加模式退役，
//    本文件 ② 的断言改钉「替换为更短快照同样必须 patch」（旧 pendingChars>0 守卫的缺陷反例）。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * §11.5：时间窗节流 + 终态立即 flush + 1 槽 conflate 不丢帧 + 🔴 替换语义下「变短也必须刷」。
 *
 * 🔴 与规格的差异（施工实测，记入报告）：§11.5 ① 的字面脚本 `repeat(1000){ append; advanceTimeBy(1) }` **再** `advanceTimeBy(1000)`
 *    总虚拟时长 = 2000ms，按其自身阈值「4 = 1000ms/250ms」会产出约 8 次 patch，与 `≤4` 自相矛盾。定稿：只保留「1000 次 append
 *    跨越 1000ms 虚拟时间」这一段（正是「1000 append/秒」的语义），断言 patch ≤ 4。已登记 §15.2 M-11。
 */
class CardThrottleTest {

    // ① 虚拟时间下 1000 次替换/秒 ⇒ ≤ 4 次 update（阈值 4 = 1000ms / streamThrottleMs(250)）
    @Test
    fun `one thousand replaces per second yield at most four patches`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()   // 初始 rotateSegment：建第 1 段，无 patch
        assertEquals(1, port.createCount)
        assertEquals(0, port.patchCount)

        repeat(1000) {
            sink.tryReplace("x$it")
            testScheduler.advanceTimeBy(1)   // 1000 次 × 1ms = 1000ms 虚拟时间
        }
        testScheduler.runCurrent()

        assertTrue(port.patchCount <= 4, "1000ms 内 patch 应 ≤4（250ms 节流），实际 ${port.patchCount}")
        assertTrue(port.patchCount >= 1, "至少应 flush 一次，实际 ${port.patchCount}")
        // 关键收益：1000 次替换被节流到个位数 SDK 调用（D1/D3/R7②）
        assertTrue(port.patchCount < 1000)
        sink.close(); testScheduler.runCurrent()
    }

    // ② 🔴 滚动窗口钉死反例：替换为**更短**快照（删行）同样必须在时间窗内 patch——
    //    旧追加模式的 `pendingChars > 0` 守卫在替换语义下会静默吞掉「变短」的变化（chars 只减不增 ⇒ 永不 patch）。
    @Test
    fun `shrinking snapshot still fires a patch within the time window`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()

        sink.tryReplace("很长很长很长很长很长很长很长很长很长很长")   // 先给一个长快照
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent() // patch 一次（长快照上卡）
        val patchesBeforeShrink = port.patchCount

        sink.tryReplace("短")                                       // 🔴 替换为更短快照（净删字符）
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()

        assertTrue(
            port.patchCount > patchesBeforeShrink,
            "替换为更短快照同样必须 patch（变短也是内容变化），实际 patch 数 ${port.patchCount}，此前 $patchesBeforeShrink",
        )
        val content = port.lastPatchContent
        assertTrue(content != null && "\"content\":\"短\"" in content, "最后一次 patch 应反映最新（更短）快照，实际 $content")
        sink.close(); testScheduler.runCurrent()
    }

    // ③ 终态更新不受节流窗口限制（立即 flush，NF-02）
    @Test
    fun `flushTerminal fires immediately regardless of the throttle window`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()
        sink.tryReplace("hello")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // 一次常规 patch
        val patchBefore = port.patchCount
        val settingsBefore = port.settingsCount

        // 🔴 刚 patch 完（0ms 前）立即 flushTerminal ⇒ 终态调用立刻发出，不等 250ms
        sink.flushTerminal(CardSpec(title = "完成", markdown = "最终文本"))
        testScheduler.runCurrent()

        assertTrue(port.patchCount > patchBefore, "flushTerminal 应立即发出终态 patch（不受节流窗口限制）")
        assertEquals(settingsBefore + 1, port.settingsCount, "flushTerminal 应立即 settings 结束流式")
        assertFalse(sink.isOpen, "flushTerminal 后 isOpen == false")
    }

    // ④ 1 槽 conflate + 单一不可变快照（D3）：结构性 grep 断言（滚动窗口：一次 set 覆盖）
    @Test
    fun `sink uses a single atomic snapshot and no unbounded channel`() {
        val src = readSinkSource()
        // 🔴 零 Channel(Channel.UNLIMITED)（D3：无界缓冲 = 背压缺失）
        assertFalse("Channel.UNLIMITED" in src, "LarkCardStreamSink 禁止使用 Channel.UNLIMITED（D3）")
        assertFalse("Channel(Channel.UNLIMITED)" in src)
        // 🔴 单一快照持有者：恰一个 AtomicReference 字段（不是 4 个独立 var 组合）
        assertEquals(1, Regex("""AtomicReference\(""").findAll(src).count(), "应恰有 1 个 AtomicReference 快照槽")
        // 🔴 tryReplace 内用 slot.set 一次覆盖为最新快照（滚动窗口：不拼接，覆盖语义）
        val tryReplaceBody = src.substringAfter("fun tryReplace(").substringBefore("override suspend fun flushTerminal")
        assertEquals(1, Regex("""slot\.set""").findAll(tryReplaceBody).count(),
            "tryReplace 内应恰有一次 slot.set（全量覆盖最新快照，滚动窗口）")
        assertFalse(Regex("""slot\.updateAndGet""").containsMatchIn(tryReplaceBody),
            "tryReplace 不得再拼接旧快照（append 已退役）")
    }

    // ⑤ 滚动窗口：最后一次替换即卡上内容（覆盖语义，不再拼接）
    @Test
    fun `latest replace wins - card content equals the newest snapshot`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()

        sink.tryReplace("旧快照 a")
        sink.tryReplace("新快照 b")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // 触发 patch

        assertTrue(port.patchCount >= 1)
        val content = port.lastPatchContent
        assertTrue(content != null && "\"content\":\"新快照 b\"" in content,
            "卡上内容应恒等于最新快照（覆盖语义），实际 patch JSON=$content")
        assertFalse("\"旧快照 a\"" in content ?: "", "旧快照不得残留在卡上（不累积历史）：$content")
        sink.close(); testScheduler.runCurrent()
    }

    // ⑥ tryReplace 返回值语义：开放恒 true；close 后恒 false
    @Test
    fun `tryReplace return value tracks open state`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()

        assertTrue(sink.isOpen)
        assertTrue(sink.tryReplace("x"), "开放 ⇒ true")
        assertTrue(sink.tryReplace(""), "开放 + 空快照 ⇒ true（窗口清空也是合法帧）")

        sink.close(); testScheduler.runCurrent()
        assertFalse(sink.isOpen)
        assertFalse(sink.tryReplace("y"), "close 后 ⇒ false")
        assertFalse(sink.tryReplace(""), "close 后空串 ⇒ false")
    }

    /** 读取被测生产源文件（从模块工作目录向上定位仓库根，兼容不同 Gradle 调用 cwd）。 */
    private fun readSinkSource(): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val f = File(dir, "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkCardStreamSink.kt")
        assertTrue(f.exists(), "定位不到 LarkCardStreamSink.kt：${f.absolutePath}")
        return f.readText()
    }
}
