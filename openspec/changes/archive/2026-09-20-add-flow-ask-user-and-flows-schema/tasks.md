# Tasks: add-flow-ask-user-and-flows-schema

## 1. ask_user 工具域（:core llm/tools）

- [x] 1.1 AskRequest/AskField/AskAnswer 模型 + UserQuestionGate fun interface
      （`core/src/jvmMain/.../llm/tools/AskUserModels.kt`——sealed AskField 四型 +
      resolveAskCapMillis 换算函数）
- [x] 1.2 AskUserTool（StructuredSimpleTool 子类：Args/FieldArg schema + type 校验
      BAD_ARGS + gate.onAsk 挂起 + 软反馈文案 + AskRequestPayload sink 旁路）
- [x] 1.3 AgentToolCatalog 接线：描述符注册（name="user.ask_user" + uiHint）+
      ToolContextConfig.userQuestionGate 槽位（null 不注册——distillBuffer 先例）+
      ToolPayload 新子类 AskRequestPayload
- [x] 1.4 AskUserTool 单测（fake gate resolve/skip/timeout 三态 + BAD_ARGS +
      取消穿透）+ catalog 门控测试（gate null 不注册 / 点名注册）

## 2. flows 表与 QuestStore（:core data/db）

- [x] 2.1 Flows.sq（flows 表 + insertFlow upsert/allFlows 倒序/flowById/renameFlow/
      deleteFlowMessages+deleteFlow 级联拆单语句）+ 3.sqm 迁移（v3→v4）+
      Database schema 版本号同步
- [x] 2.2 QuestStore 门面（RunStore 配方：create/listFlows/flowById/rename/delete；
      title 派生截 80 字；三店级联——事务内 flows+messages+sessions，chat_history
      经 deleteHistory 宽松清）
- [x] 2.3 FlowsMigrationTest（v3 存量升级不丢 runs + user_version=4 + 二次打开
      零重复迁移——RunsMigrationTest 配方）+ QuestStoreTest（CRUD/级联/倒序——
      RunStoreTest 配方，inMemory db）

## 3. 预算 key（:core llm）

- [x] 3.1 LlmConfigStore 加 getFlowMaxIterations()（缺省 40 节点）+ KEY 常量 +
      单测（缺省回落/写入读回——既有 key 测试配方）

## 4. 收口

- [x] 4.1 全模块守护 `--rerun-tasks --no-build-cache` 全绿（含既有 LarkSdk/SherpaOnnx/
      McpSdk 隔离守护 + UiGuard 零新增白名单）
