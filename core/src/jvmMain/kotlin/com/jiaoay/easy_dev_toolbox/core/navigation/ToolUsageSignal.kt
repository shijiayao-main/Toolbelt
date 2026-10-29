package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 工具页「真实使用」信号契约（真机反馈）。
 *
 * ## 口径（本契约的唯一语义）
 * 「使用」= 该页**核心功能动作完成/发起**，**非页面浏览**——点击页面、切 tab、
 * 深入模式打开页面都不计数；只有 XLog 解密完成 / 过滤执行 / 分割执行 / 转换执行 /
 * 解压完成 / Shell 命令启动 / run 研判发起 / 蒸馏发起 / 转写发起 / AI 配置保存 /
 * Flow 消息发送 / 桥接启动 / MCP 服务启动等**动作**才 +1（报告查看不计数——
 * PageUsageTracker.NON_COUNTED 显式排除）。
 *
 * ## 交点纪律（对齐 ToolAction/ParamCommand 契约）
 * - feature 侧只 import 本 object（:core，双方都可见），在核心动作完成/发起点
 *   一行 `ToolUsageSignal.report(...)` 发信号；不感知宿主、不写 DataStore；
 * - 宿主 `AppViewModel` init 订阅信号流（loadEnum 映射回 `ToolPage` 枚举）→
 *   `PageUsageTracker` 内存 +1 → DataStore 落盘；
 * - [PageId] 常量承载字符串 id（= `ToolPage` 枚举 `name` 拼写）——防 feature
 *   侧手写字符串漂移；宿主侧对未知 id 忽略 + JLog 兜底。
 *
 * ## 丢流无害
 * [extraBufferCapacity] 有界 + `tryEmit` 非挂起：无订阅者/缓冲满时静默丢弃——
 * 计数是 UX 排序数据（palette frecency），非关键账目，丢一条无碍。
 */
object ToolUsageSignal {

    /** 各工具页字符串 id（与宿主 ToolPage 枚举 name 逐字一致——拼写漂移防护） */
    object PageId {
        const val XLOG_DECODER = "XLogDecoder"
        const val LOG_TAG_FILTER = "LogTagFilter"
        const val FILE_SPLITTER = "FileSplitter"
        const val SHELL_TOOLS = "ShellTools"
        const val TIMESTAMP = "Timestamp"
        const val ARCHIVE = "Archive"
        const val LARK_BRIDGE = "LarkBridge"
        const val MCP_EXPOSE = "McpExpose"
        const val AI_SETTINGS = "AiSettings"
        const val DISTILL = "Distill"
        const val LOG_WORKBENCH = "LogWorkbench"
        const val VOICE_TRANSCRIBE = "VoiceTranscribe"
        const val FLOW = "Flow"
    }

    private val _signals = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        replay = 0,
    )

    /** 使用信号流（宿主 AppViewModel init 订阅） */
    val signals: SharedFlow<String> = _signals.asSharedFlow()

    /**
     * 发信号（动作完成/发起点调用——一行接线）。
     * @param pageId [PageId] 常量（= ToolPage 枚举 name）；tryEmit 失败静默丢流
     */
    fun report(pageId: String) {
        _signals.tryEmit(pageId)
    }
}
