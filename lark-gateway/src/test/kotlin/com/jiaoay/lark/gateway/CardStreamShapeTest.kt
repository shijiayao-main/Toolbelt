// :lark-gateway 测试（§16 S6 真机实测固化）。类名 CardStreamShapeTest = 卡片流式四步「调用形状」防回归网。
// 🔴 零 SDK / 零真机 / 零网络：FakeCardStreamPort seam（裁定 8/B-5）+ 虚拟时间 + 对生产源 SdkCardStreamPort.kt 的 grep 断言。
// 🔴 四条防回归网中的三条落在本文件：① msgType=="interactive"（真机 230001）② partial_element 不含 tag（真机 300312）
//    ③ config 不含 streaming_config（真机 11311/10002）；第四条「分段循环必然终止」落在 SegmentRotationTest。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * §16：把 S6 真机铁证（build/sdk-probe/probe-card.log、probe-patch.log、build/s6p-*.log）变成精确调用形状断言。
 * 卡片 JSON 结构定稿（实测 code=0）：
 *   schema="2.0" / config={streaming_mode,update_multi,summary}（🔴 无 streaming_config）/ body.elements[0]={tag:markdown,element_id:stream_md,content}
 *   发送 im.message.create(receiveIdType="chat_id", msgType="interactive", content={"type":"card","data":{"card_id":…}})
 *   更新 cardkit.cardElement.patch(cardId, elementId="stream_md", partial_element={element_id,content}（🔴 无 tag）, sequence++)
 *   结束 cardkit.card.settings(cardId, config.streaming_mode=false, sequence++)
 */
class CardStreamShapeTest {

    // ① 发送卡片实体：receiveIdType=="chat_id"、content 反序列化 == {"type":"card","data":{"card_id":<实际值>}}
    @Test
    fun `sendCardByReference uses chat_id and card reference content`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()                      // 建第 1 段：createCardEntity + sendCardByReference

        assertEquals(1, port.sendRefCount)
        assertEquals("chat_id", port.sendRefReceiveIdTypes[0], "receiveIdType 必须是 chat_id（S6 靶子为 p2p 会话，实测 code=0）")
        assertEquals("oc_test", port.sendRefReceiveIds[0])
        // content 精确等于 {"type":"card","data":{"card_id":"card_1"}}（card_1 = fake 生成的实际 cardId）
        assertEquals("""{"type":"card","data":{"card_id":"card_1"}}""", port.sendRefContents[0])
        assertEquals("card_1", port.cardIds[0])
        sink.close(); testScheduler.runCurrent()
    }

    // ① grep 防回归网：msgType 必须是 "interactive"，绝不能是 "card"（真机 230001 invalid msg_type: card）
    @Test
    fun `SdkCardStreamPort sendCardByReference uses msgType interactive not card`() {
        val src = readGatewaySource()
        val body = src.substringAfter("override suspend fun sendCardByReference")
            .substringBefore("override suspend fun patchElement")
        // 🔴 用原始源（保留字符串字面量）；真机实测：msgType 必须 "interactive"，"card" 会被飞书拒绝 230001 invalid msg_type: card（见 gateway-contract.md §16）
        assertTrue(".msgType(\"interactive\")" in body, "sendCardByReference 必须用 msgType(\"interactive\")（真机 code=0）")
        assertFalse(".msgType(\"card\")" in body, "msgType(\"card\") 会被飞书拒绝：230001 invalid msg_type: card（§16 防回归网）")
    }

    // ② patch：partial_element 键集合精确 == {element_id, content}，🔴 绝不含 tag（真机 300312 tag cannot be updated，P1/P4 全灭）
    @Test
    fun `patchElement partial never contains tag key`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sink.tryReplace("hello")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // timeHit → firePatch

        assertEquals(1, port.patchCount)
        val partial = port.patchContents[0]
        // 🔴 防回归网（300312）：partial JSON 里绝不能出现 "tag" 键
        assertFalse("\"tag\"" in partial, "partial_element 绝不能含 tag 键（真机 300312 tag cannot be updated，§16）：$partial")
        assertTrue("\"element_id\":\"stream_md\"" in partial, partial)
        assertTrue("\"content\":\"hello\"" in partial, partial)
        // 键集合精确等于 {element_id, content}：P2 形态，恰两个顶层键
        assertEquals(2, countTopLevelKeys(partial), "partial_element 恰 2 个键 {element_id, content}：$partial")
        assertEquals("stream_md", port.patchElementIds[0], "patch 的 elementId 恒为 stream_md")
        sink.close(); testScheduler.runCurrent()
    }

    // ③ 卡片 JSON：schema==2.0 / streaming_mode==true / update_multi==true / 🔴 config 不含 streaming_config / body.elements[0] 形状
    @Test
    fun `created card json has schema config body and no streaming_config`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler, initial = CardSpec(title = "进度", markdown = "init"))
        testScheduler.runCurrent()

        assertEquals(1, port.createCardJsons.size)
        val card = port.createCardJsons[0]
        assertTrue("\"schema\":\"2.0\"" in card, card)
        assertTrue("\"streaming_mode\":true" in card, card)
        assertTrue("\"update_multi\":true" in card, "update_multi 必设（多次 patch 同一元素的前提）：$card")
        // 🔴 防回归网（11311 printStep is nil / 10002 failed to unmarshal PrintStepConfig）：config 不含 streaming_config
        assertFalse("streaming_config" in card, "卡片 config 不得含 streaming_config（真机 11311/10002，§16）：$card")
        // body.elements[0] = {tag:markdown, element_id:stream_md, content:init}
        assertTrue("\"tag\":\"markdown\"" in card, card)
        assertTrue("\"element_id\":\"stream_md\"" in card, card)
        assertTrue("\"content\":\"init\"" in card, card)
        sink.close(); testScheduler.runCurrent()
    }

    // ④ sequence：从 1 严格递增、🔴 per-stream 独立（两个 sink 互不干扰，per-sink Int 无全局 map）
    @Test
    fun `sequence starts at one and is per stream independent`() = runTest {
        val portA = FakeCardStreamPort()
        val sinkA = newTestSink(portA, backgroundScope, testScheduler)
        val portB = FakeCardStreamPort()
        val sinkB = newTestSink(portB, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        repeat(3) {
            sinkA.tryReplace("a"); sinkB.tryReplace("b")
            testScheduler.advanceTimeBy(250); testScheduler.runCurrent()
        }
        // 两个 sink 各自 sequence 从 1 严格递增，互不干扰
        assertEquals(listOf(1, 2, 3), portA.patchSequences, "sink A 的 sequence 从 1 严格递增")
        assertEquals(listOf(1, 2, 3), portB.patchSequences, "per-stream 独立：sink B 的 sequence 不受 sink A 影响")
        sinkA.close(); sinkB.close(); testScheduler.runCurrent()
    }

    // ⑤ 结束流式：flushTerminal 调 finishSettings 且 settings.streaming_mode==false、sequence 紧接递增
    @Test
    fun `flushTerminal finishes settings with streaming mode false and increments sequence`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sink.tryReplace("body")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // 一次节流 patch (seq=1)

        sink.flushTerminal(CardSpec(title = "进度", markdown = "done"))
        testScheduler.runCurrent()

        assertEquals(1, port.settingsCount)
        val settings = port.settingsContents[0]
        assertTrue("\"streaming_mode\":false" in settings, "结束流式必须置 streaming_mode=false：$settings")
        // flushTerminal 内先 patch(seq+1) 再 settings(seq+1)：settings seq 严格紧接最后一次 patch 递增
        val lastPatchSeq = port.patchSequences.last()
        assertEquals(lastPatchSeq + 1, port.settingsSequences[0], "settings sequence 紧接最后一次 patch 递增")
        assertFalse(sink.isOpen, "flushTerminal 后 sink 关闭")
    }

    // 极简顶层键计数（partial_element 是扁平对象、value 均为标量字符串，无嵌套 {} / []，数逗号即可）
    private fun countTopLevelKeys(json: String): Int {
        val inner = json.removePrefix("{").removeSuffix("}")
        return inner.split(",").count { it.isNotBlank() }
    }

    private fun readGatewaySource(): String {
        val f = File(testRepoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkCardStreamPort.kt")
        assertTrue(f.exists(), "定位不到 SdkCardStreamPort.kt（移动须同步更新本测试路径锚）：${f.absolutePath}")
        return f.readText()
    }
}
