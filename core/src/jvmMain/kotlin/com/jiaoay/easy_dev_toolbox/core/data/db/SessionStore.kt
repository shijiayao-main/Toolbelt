package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry

/**
 * 会话存储契约（agent 会话持久化的抽象面）。
 *
 * 既有 JSONL 账本（[SessionLedger]）与 SQLite 实现（SqliteSessionStore）的共同
 * 契约——会话 reducer 经本接口消费存储，装配点决定实现（生产 = SQLite 单源、
 * 历史测试 = JSONL 或 null 纯内存）。
 *
 * 语义契约（与 JSONL 账本逐字对齐，行为零迁移）：
 * - [append] 幂等 upsert：同会话同 id 覆写（工具卡 RUNNING→终态回填路径）；
 * - [tombstone] 会话废弃：reset（新建会话）语义——[replay] 不再返回该会话条目；
 * - [replay] 重建会话条目（装配序 = 落盘序）；未知会话返回空表；
 * - [latestSessionId] FIFO 最新会话 id（重启恢复用；无会话返回 null）。
 */
interface SessionStore {
    /** 追加一条（同会话同 id 覆写 = 幂等 upsert） */
    fun append(sessionId: String, entry: ChatEntry)

    /** 会话废弃（reset/新建会话）：此后 replay 不再返回该会话条目 */
    fun tombstone(sessionId: String)

    /** 重建会话条目（装配顺序即落盘顺序）；未知会话返回空表 */
    fun replay(sessionId: String): List<ChatEntry>

    /** 最新会话 id（重启恢复用；无会话返回 null） */
    fun latestSessionId(): String?

    /** 装载存量数据（实现自决：JSONL 读文件重建 / SQLite 恒 no-op——存量行落库即生效） */
    fun load()
}
