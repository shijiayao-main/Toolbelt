# Tasks: add-flow-capabilities

## 1. 能力目录与 prompt 协作版

- [x] 1.1 `QuestAbilityPrompts`：LOG_ANALYSIS_SECTION / VOICE_MINUTES_SECTION
      协作版改写（去人设开头换段式过渡，纪律正文逐条保留；🔴 改写期实测抓到
      read_doc 失真——真名 knowledge.read，工作台真源 prompt 同有此文案失真
      未动——不属本 change 面）
- [x] 1.2 `QuestCapabilities` 两真实注册项（log-analysis / voice-minutes——
      toolNames 值同源自持字面量 + promptSection 接 D2 产物）
- [x] 1.3 `QuestCapabilitiesGuardTest`：toolNames ∈ AgentToolCatalog 可解析面 +
      id 唯一 + promptSection 非空 + 两真实 id 存在（5 用例全绿）

## 2. QuestSession 装配缝扩展

- [x] 2.1 构造参数追加 privateKeyProvider（null = 不装 analyze_log）+ memoryDir
      （null = 不装 memory.*）；ToolContextConfig 透传
- [x] 2.2 🔴 escapeGate 桥：context 挂 gateBridge（pendingGate StateFlow +
      CompletableDeferred 挂起等 UI + cancel 清理——WorkbenchSession 配方平移）+
      QuestSessionTest gate 三态用例（allow 登记围栏 / deny DENIED 软反馈 /
      cancel 清 pendingGate）——7→10 用例全绿

## 3. 页面与 VM 接线

- [x] 3.1 FlowViewModel：startFlow 装配缝对齐（勾日志分析 →
      DecryptTools.defaultPrivateKeyProvider；memoryDir 默认直连）+
      pendingGate 透传面 + approveGate/denyGate 动作
- [x] 3.2 FlowPage 路径确认卡（PathConfirmCard——AskCard 同区渲染：pendingGate
      非空时输入区上方，允许/拒绝两钮；全 token 化零白名单）

## 4. 收口

- [x] 4.1 全模块守护 `--rerun-tasks --no-build-cache` 全绿（UiGuard 零新增白名单）
- [x] 4.2 openspec validate 干净 + 端到端验收剧本待维护者真机（登记不阻塞归档——
      剧本在 design D7；真机后如需修补另开小批）
