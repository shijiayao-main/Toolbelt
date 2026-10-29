# flow-store Specification

## ADDED Requirements

### Requirement: flows 表与迁移

SQLite schema SHALL 从 v3 迁移到 v4：新增 flows 表（id 主键 / title /
capabilities JSON 数组串 / created_at / updated_at）。v3 存量数据（runs 等）SHALL
在迁移后完整保留；`user_version` SHALL 落 4；重复打开应用 SHALL 不重复迁移。
flows 表 SHALL **不含 status 列**（流程是对话永可续，运行态是会话运行时非持久
元数据）。

#### Scenario: 存量升级不丢数据

- **GIVEN** schema v3 的既有数据库（含 runs 行）
- **WHEN** 应用启动触发迁移
- **THEN** flows 表创建成功，runs 行全量保留，user_version = 4

### Requirement: QuestStore 门面

系统 SHALL 提供 QuestStore 门面：create（幂等 upsert）/ listFlows（updated_at
倒序）/ flowById / rename / delete。title SHALL 可由首条用户消息截 80 字派生。
flows 行的 id SHALL 与 messages.session_id、chat_history.conversation_id 同 key
（三层同 key 续跑语义）。

#### Scenario: 创建与倒序列表

- **GIVEN** QuestStore 空表
- **WHEN** 依次创建两个流程（第二个 updated_at 更晚）
- **THEN** listFlows 返回两条且第二个在前；同 id 重复 create 为幂等覆写不新增行

#### Scenario: title 派生

- **GIVEN** 一条超过 80 字的首条用户消息
- **WHEN** 以其派生流程 title
- **THEN** title 截断至 80 字内

### Requirement: 删除三店级联

删除流程 SHALL 级联清理三层存储：flows 行 + messages 行 + sessions 行在同一
SQLDelight 事务内删除（🔴 拆单语句——多语句命名查询只执行首条）；chat_history
行经 SqliteChatHistoryProvider.deleteHistory 清理（JDBC 侧，失败不回滚
SQLDelight 面——宽松语义，孤儿 blob 无害）。

#### Scenario: 删除三店级联

- **GIVEN** 一个流程及其历史（messages 行 + sessions 行 + chat_history 行）
- **WHEN** 用户删除该流程
- **THEN** flows/messages/sessions 在同一事务内删除；chat_history 行随后清理
  （失败不阻塞 SQLDelight 面）
