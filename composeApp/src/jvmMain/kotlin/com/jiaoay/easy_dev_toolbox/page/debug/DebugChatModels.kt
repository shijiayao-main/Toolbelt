package com.jiaoay.easy_dev_toolbox.page.debug

import com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource

enum class DebugMessageType {
    INPUT,
    OUTPUT,
}

data class DebugChatMessage(
    val type: DebugMessageType,
    val content: String,
    val source: ShellOutputSource? = null,
    // 稳定 id——LazyColumn key 用（drop(1) 滚动窗口下 hash 等位置键会让全列表重组，无 key 则追加整列重组）。
    val id: Long = nextId(),
) {
    companion object {
        private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextId(): Long = idCounter.incrementAndGet()
    }
}

enum class DebugShellMode {
    Simple,
    Interactive,
}
