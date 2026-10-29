// :lark-gateway 测试支撑（M3-T3，B5）。无 @Test 方法 ⇒ 贡献 0 个测试，仅供 sink-based 测试复用。
// 🔴 零 SDK：CardStreamPort 是裁定 8（B-5）的 internal seam，fake 直接实现它，测试完全脱离真实 cardkit/网络。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * CardStreamPort 的计数替身（§11.5-§11.7）。记录 cardkit 四步的调用次数、生成的 cardId/messageId、
 * 每次 patch/settings 的 sequence 与内容。可注入 [createResponder] / [patchResponder] 让某次调用返回错误码，
 * 以驱动轮换/重建/退避分支（§4.9 的 check()）。
 *
 * 🔴 无 @Volatile、无 object 声明：sink 主循环与测试体都在同一 StandardTestDispatcher 单线程上交错，
 *    普通 var 计数即可见（F 组只扫 src/main/kotlin，测试源集本就不在其守护范围）。
 */
internal class FakeCardStreamPort : CardStreamPort {

    var createCount = 0; private set
    var sendRefCount = 0; private set
    var patchCount = 0; private set
    var settingsCount = 0; private set

    /** 每次建卡生成的 card_id（互不相同，R20：绝不复用）。 */
    val cardIds = mutableListOf<String>()

    /** 🔴 S6 回填：每次 createCardEntity 的整卡 cardJson（验证 schema/config/body 形状，特别是 config 不含 streaming_config）。 */
    val createCardJsons = mutableListOf<String>()

    /** 每次「按引用发送」生成的 messageId。 */
    val messageIds = mutableListOf<String>()

    /** 🔴 S6 回填：每次 sendCardByReference 的入参（验证 msgType 走 SDK 层，此处记录 seam 可见的 content/receiveId/receiveIdType）。 */
    val sendRefContents = mutableListOf<String>()
    val sendRefReceiveIds = mutableListOf<String>()
    val sendRefReceiveIdTypes = mutableListOf<String>()

    /** 每次 patch 的 sequence（验证 §5.2：每段从起始值单调递增；轮换后归零重来）。 */
    val patchSequences = mutableListOf<Int>()
    val settingsSequences = mutableListOf<Int>()

    /** 每次 patch 的 partialJson（验证不丢字：窗口内多次 append 拼接）。 */
    val patchContents = mutableListOf<String>()
    val lastPatchContent: String? get() = patchContents.lastOrNull()

    /** 🔴 S6 回填：每次 patch 的 elementId（验证恒为 stream_md）与 finishSettings 的 settingsJson（验证 streaming_mode=false）。 */
    val patchElementIds = mutableListOf<String>()
    val settingsContents = mutableListOf<String>()

    /** 注入点：按 create 序号（1-based）返回自定义结果；返回 null 用默认成功。 */
    var createResponder: ((Int) -> CardCallOutcome?)? = null

    /** 注入点：按 patch 序号（1-based）返回自定义结果；返回 null 用默认成功。用于 200860/200750/300317 触发轮换。 */
    var patchResponder: ((Int) -> CardCallOutcome?)? = null

    override suspend fun createCardEntity(cardJson: String): CardCallOutcome {
        createCount++
        createCardJsons.add(cardJson)
        createResponder?.invoke(createCount)?.let { return it }
        val id = "card_$createCount"
        cardIds.add(id)
        return CardCallOutcome(success = true, code = 0, cardId = id)
    }

    override suspend fun sendCardByReference(contentJson: String, receiveId: String, receiveIdType: String): CardCallOutcome {
        sendRefCount++
        sendRefContents.add(contentJson)
        sendRefReceiveIds.add(receiveId)
        sendRefReceiveIdTypes.add(receiveIdType)
        val mid = "msg_$sendRefCount"
        messageIds.add(mid)
        return CardCallOutcome(success = true, code = 0, messageId = mid)
    }

    override suspend fun patchElement(cardId: String, elementId: String, partialJson: String, sequence: Int): CardCallOutcome {
        patchCount++
        patchSequences.add(sequence)
        patchContents.add(partialJson)
        patchElementIds.add(elementId)
        patchResponder?.invoke(patchCount)?.let { return it }
        return CardCallOutcome(success = true, code = 0)
    }

    override suspend fun finishSettings(cardId: String, settingsJson: String, sequence: Int): CardCallOutcome {
        settingsCount++
        settingsSequences.add(sequence)
        settingsContents.add(settingsJson)
        return CardCallOutcome(success = true, code = 0)
    }
}

/**
 * 构造一个受虚拟时间驱动的 [LarkCardStreamSink]（§11.0：runTest + StandardTestDispatcher + 虚拟时间；
 * 长驻主循环由调用方传入 backgroundScope 承载）。
 * 🔴 clock = scheduler.currentTime、sleeper = 默认 delay（跑在 StandardTestDispatcher(scheduler) 上 ⇒ 虚拟时间），
 *    故 advanceTimeBy/runCurrent 可精确驱动节流窗口与 9min 期限，全程不连真机（§11.7 ⑥）。
 */
internal fun newTestSink(
    port: CardStreamPort,
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler,
    initial: CardSpec = CardSpec(title = "进度", markdown = ""),
    onSignal: (GatewaySignal) -> Unit = {},
): LarkCardStreamSink = LarkCardStreamSink(
    port = port,
    target = LarkTarget.Chat("oc_test"),
    initial = initial,
    netDispatcher = StandardTestDispatcher(scheduler),
    scope = scope,
    onSignal = onSignal,
    clock = { scheduler.currentTime },
)

/** 从模块工作目录向上定位仓库根（兼容不同 Gradle 调用 cwd），供 grep 型断言读取生产源。 */
internal fun testRepoRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
    return dir
}

/**
 * 剥离 Kotlin 源里的注释（行 // 与嵌套块 /* */）与字符串字面量（"..." 与 """..."""）后再返回。
 * 🔴 grep 型「禁止出现 X」断言必须用归一化源，否则会命中**注释/日志字符串里对 X 的提及**（如抬头注释写「本文件零 Dispatchers.IO」、
 *    log.error 文案写「依赖宿主 exitProcess 兜底」）造成假红。做法对齐 LarkSdkIsolationTest.normalizeKotlinSource。
 * 🔴「必须出现 X」的存在性断言仍用**原始源**（X 常是含字符串字面量的代码，如 writeFile(tmp.toString())）。
 */
internal fun stripCommentsAndStrings(src: String): String {
    val sb = StringBuilder(src.length)
    var i = 0
    val n = src.length
    var blockDepth = 0
    while (i < n) {
        val c = src[i]
        val next = if (i + 1 < n) src[i + 1] else '\u0000'
        when {
            blockDepth > 0 -> {
                if (c == '/' && next == '*') { blockDepth++; i += 2 } else if (c == '*' && next == '/') { blockDepth--; i += 2 } else i++
            }
            c == '/' && next == '/' -> { while (i < n && src[i] != '\n') i++ }
            c == '/' && next == '*' -> { blockDepth++; i += 2 }
            c == '"' && next == '"' && i + 2 < n && src[i + 2] == '"' -> {
                i += 3
                while (i < n && !(src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"')) i++
                i = minOf(i + 3, n)
            }
            c == '"' -> {
                i++
                while (i < n && src[i] != '"') { if (src[i] == '\\') i++; i++ }
                i = minOf(i + 1, n)
            }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}
