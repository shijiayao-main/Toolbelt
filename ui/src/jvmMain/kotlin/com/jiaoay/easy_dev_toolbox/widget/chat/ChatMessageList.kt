package com.jiaoay.easy_dev_toolbox.widget.chat

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.widget.BottomFollowScrollEffect
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView

/**
 * 会话消息列表（消息流容器）：
 *
 * - LazyColumn + 稳定 id key（ChatEntry.id）；
 * - 空态引导：entries 为空时渲染 [EmptyStateView] 撑满可视高
 *   （EvidenceTimeline:308 item 内先例——fillParentMaxHeight 是 LazyItemScope 扩展，
 *   仅 item 块内合法；首条消息到达后 isEmpty 门控自然退出）；
 * - 贴底跟随：复用 [BottomFollowScrollEffect]（contentVersion = 条目数 + 尾条目 id 哈希，
 *   封顶后也能持续触发跟随——contentVersion 注释先例）；
 * - 输出面选择：整面 SelectionContainer（视口内拖选复制——X2 输出面配方，
 *   EvidenceTimeline 行流同款）；
 * - 零动画铁律：消息出现不加 animateItem/AnimatedVisibility（流式内容动画禁令）；
 * - 条目渲染全权委托 [ChatEntryRow]。
 *
 * @param entries 会话条目（有序）
 * @param contentVersion 「有新内容」信号（单调变化值——用 ChatEntry.nextId 计数即可）；
 *        null 时回退 itemCount 触发
 * @param running 会话运行中（运行中强制贴底跟随，用户上翻即停——enabled 语义留给调用方）
 */
@Composable
fun ChatMessageList(
    entries: List<ChatEntry>,
    modifier: Modifier = Modifier,
    contentVersion: Long? = null,
    onOpenArtifact: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // 会话消息 = 输出面（X2 第二批——「结论/回复带走」产出动线；EvidenceTimeline
    // 行流同款配方）。LazyColumn 滚动回收下跨屏选区有瑕疵，验收判据 = 视口内拖选复制
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
        ) {
            if (entries.isEmpty()) {
                // 新会话引导空态（EmptyStateView 四要素——icon 走默认 INBOX）。
                // BottomFollowScrollEffect
                // 兼容：空态时 itemCount=0 不触发（effect 内 last=-1）。
                item(key = "chat-empty") {
                    EmptyStateView(
                        text = "会话已就绪",
                        learningHint = "在下方输入目标或补充信息，agent 会主动向你确认细节",
                        fillParent = false,
                        modifier = Modifier.fillParentMaxHeight(),
                    )
                }
            }
            items(entries, key = { it.id }) { entry ->
                ChatEntryRow(entry = entry, onOpenArtifact = onOpenArtifact)
            }
        }
    }

    BottomFollowScrollEffect(
        listState = listState,
        itemCount = entries.size,
        contentVersion = contentVersion,
    )
}
