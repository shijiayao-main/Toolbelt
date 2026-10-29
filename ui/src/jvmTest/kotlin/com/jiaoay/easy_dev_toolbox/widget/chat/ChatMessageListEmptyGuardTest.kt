package com.jiaoay.easy_dev_toolbox.widget.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话消息流空态守护：ChatMessageList entries 空时消息区须有「会话已就绪」
 * 引导（对照证据流 EvidenceTimeline 有空态 item 先例）——EmptyStateView
 * （icon 默认 INBOX）+ fillParentMaxHeight 撑满 LazyColumn 可视高。
 *
 * 渲染件无 Compose 运行时直测缝（:ui 无 skiko 运行时），源码切片是一等守护形态：
 *
 * - **空态分支在位**：`if (entries.isEmpty())` + `item(key = "chat-empty")`；
 * - **引导文案**：「会话已就绪」+ learningHint「agent 会主动向你确认细节」；
 * - **撑满语义**：fillParentMaxHeight 走 LazyItemScope 扩展（🔴 勿误加为
 *   EmptyStateView 组件参数——组件无 fillParentMaxHeight 参）；
 * - **BottomFollowScrollEffect 零冲突**：空态时 itemCount=0 不触发（effect 内 last=-1）。
 */
class ChatMessageListEmptyGuardTest {

    @Test
    fun `xa06 chat message list empty state item wired`() {
        val source = chatMessageListSource()
        listOf(
            "if (entries.isEmpty())" to "空态门控在位（首条消息到达后 isEmpty 分支自然退出）",
            "item(key = \"chat-empty\")" to "空态 item 稳定 key 在位（对照 EvidenceTimeline ev-empty 先例）",
            "会话已就绪" to "空态主文案（新开流程「会话已就绪」引导语义）",
            "agent 会主动向你确认细节" to "learningHint 引导文案（输入目标/补充信息的下一步指引）",
            "Modifier.fillParentMaxHeight()" to "空态撑满 LazyColumn 可视高（LazyItemScope 扩展在 item 块内合法——EvidenceTimeline 同款）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "ChatMessageList 丢失空态要素「$needle」——$why")
        }
    }

    @Test
    fun `xa06 empty state reuses empty state view with default inbox icon`() {
        val source = chatMessageListSource()
        assertTrue(
            "EmptyStateView(" in source,
            "空态渲染收口 EmptyStateView（空态唯一语言组件）",
        )
        // 未指定 icon = 默认 INBOX（组件签名默认值），零显式 icon 参——防误对齐
        // 侧栏的 FORUM（不同面不同 icon）
        assertFalse(
            "icon =" in source.substringAfter("item(key = \"chat-empty\")").substringBefore("}\n") .let { fragment ->
                // 只检查空态 item 块内是否有显式 icon 参（文件其他处 EmptyStateView 唯一）
                fragment
            },
            "空态 EmptyStateView 零显式 icon 参（icon = 默认 INBOX——组件默认值承担）",
        )
    }

    private fun chatMessageListSource(): String = sourceAt(
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/chat/ChatMessageList.kt",
    )

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
