# Tasks: mcp-expose

## 1. 依赖引入与服务骨架（infra）

- [x] 1.1 SDK 依赖接线：`libs.versions.toml` 加 `mcp-sdk = "io.modelcontextprotocol:kotlin-sdk-server:0.15.0"` + `ktor-server-cio`/`ktor-client-cio`（版本对齐 SDK 传递需求）；`:feature-mcp` 建块（convention + settings include + composeApp 依赖行）；模块空骨架（McpContributions 占位）编译绿（D1）
- [x] 1.2 `McpServerManager`（object，AppLifecycleParticipant）：start(scope)（读开关配置决定起不起 server——embeddedServer CIO + mcpStatelessStreamableHttp，127.0.0.1 绑定）/ shutdown()（停 server + 取消在途 run）/ startNow/stopNow（页面开关即时启停门面）/ 状态面 StateFlow（运行态/端口/连接计数/错误）；端口占用失败明示不崩溃（spec 场景）；单测：状态机迁移/开关配置读序（零 Ktor——server 启停经接口缝 fake）
- [x] 1.3 配置 key：`LlmConfigStore` 新 key `mcp_server_enabled`/`mcp_server_port`（缺省 8765、校验域 1024..65535）+ AppHost 参与者注册（AppViewModel init 或 initializer 先例核对注册点）；单测：key 回环/默认值/校验域（D6）

## 2. 工具面映射（tools）

- [x] 2.1 `MCP_EXPOSE_NAMES` 名集（feature 内自持：fs.extended 七件 + knowledge 三件 + analyze_log；🔴 memory.*/write_todos 不外放）+ `McpToolRegistrar`：描述符 → MCP Tool（名称 = descriptor.name、描述 = uiHint.description ?: description、schema 从 Args @Serializable serializer().descriptor 派生——JSON Schema 映射纯函数）；调用分发经 buildRegistry 进程内单例；单测：名集精确集合/schema 派生（Args 类型→JSON Schema 四类映射）/描述符派生恒等（D3）
- [x] 2.2 围栏装配：ToolExecutionContext 授权根目录 = getSourceRoots() allowlist + 调用参数文件逐次 register（附件渐进）；escapeGate = deny-only（reason 带授权目录清单——fail-safe，McpPermissionServer 同哲学）；单测：围栏内允许/围栏外 deny+reason（fake gate）（D4）
- [x] 2.3 `McpToolRegistrar` 接线进 server（manager start 时注册——工具清单与描述符面单一真源）；`workbench_status` 查询工具（run 状态/进度/结果读取——stateless 两段式）补挂

## 3. agent 会话外放（agents）

- [x] 3.1 `McpRunRegistry`（object：ConcurrentHashMap<runId, RunHandle> + 容量上限 10 + 查询/登记/取消面）；RunHandle（StateFlow 进度 + cancel 钩 + runId）；单测：容量上限拒绝/登记查询回环/取消传播（D2）
- [x] 3.2 `workbench_analyze` 工具：参数（log_files/target_time/range_minutes/symptom/tag_regex?/follow_up_context?）→ 新 runId → 旁路 KoogToolLoop 会话（WorkbenchPrompts + 自持 WORKBENCH_NAMES 值同源 + getWorkbenchMaxIterations 预算）；progress 通知上报（SDK 0.15.0 API 施工时核对，不可用降级日志通知约定）；取消语义（MCP cancelled → RunHandle.cancel，部分结果保留——spec 场景）；未配端点明确报错（D5）
- [x] 3.3 `distill_run` 工具：参数（source_dir/module_desc/entry_hints[]）→ 蒸馏候选产出文本（审批前形态不写库——产出文本经 tools/call 返回）；getDistillMaxIterations 预算；单测：两 agent 工具的参数组装纯函数/未配端点错误面（fake LLM executor——koog fake executor 先例）

## 4. 服务页 UI 与导航（ui-nav）

- [x] 4.1 `McpExposePage`：PageScaffold 标准页——状态卡（运行态徽章/端点 URL/CopyIconButton/连接计数）+ 开关行 + 端口 CommitNumberField（校验域）+ 工具清单 LazyColumn（描述符直读 + 风险徽章三级色）+ 日志台（manager 事件流——LogConsolePanel 配方）；全 token 化零白名单（D7）
- [x] 4.2 `McpExposeViewModel`：manager 门面消费（零 Ktor/SDK 类型——D1 隔离）+ 开关/端口配置态 + 工具清单面 + 连接日志面；🔴 VM 构造零网络/零 server 触达
- [x] 4.3 `ToolPage.McpExpose` 枚举值（LarkBridge 后 Debug 前）+ `McpContributions`（navItem icon=SHARE、group=ENTRY、注册序 LarkBridge 后）+ NavTest 断言面同步（入口组 2 成员 [LarkBridge, McpExpose] + navItems 顺序表）

## 5. 守护与收口（guard-close）

- [x] 5.1 `McpSdkIsolationTest`（LarkSdkIsolation 模式）：`io.modelcontextprotocol.*` 只许出现在 McpServerManager/McpToolRegistrar/McpAgentTools 三文件（归一化扫描）+ Ktor 引用单点（build.gradle.kts 白名单）+ 白名单锚防腐化三断言
- [x] 5.2 全模块守护终跑 `--rerun-tasks --no-build-cache`（core/feature-mcp/feature-logkit/feature-logworkbench/feature-larkbridge/feature-voice/composeApp/log-tools）+ UiGuard 零白名单新条目核对
- [ ] 5.3 真机验收：MCP Inspector 连通（`npx -y @modelcontextprotocol/inspector` → http://127.0.0.1:8765/mcp）+ 工具清单核对 + analyze_log 合成样本调用 + workbench_analyze 研判一次（维护者 LLM 端点）+ 取消保全 + 端口冲突明示 + jpackage 打包后启动验证（Ktor jlink 模块面——D9）
  - 🔴 **未执行登记（2026-10-09 全项目修复批）**：本项勾选状态为「未勾即归档」——归档时未执行、无执行凭据，验收欠账保留不销账。**复检触发器** = P3 批「真机验收独立验收位」补跑本清单（与语音转写 4.2 冻结记载矛盾裁决同批）。
  - **补跑结果（2026-10-09 真机验收独立验收位，部分执行）**：
    - ✅ Inspector 连通 + 工具清单核对：`npx -y @modelcontextprotocol/inspector --cli http://127.0.0.1:9000/mcp --transport http --method tools/list` 返回 19 件，与 `MCP_EXPOSE_NAMES` 精确一致（fs.extended 七件 + knowledge 三件 + log.analyze_log + log.* 五件 + workbench_analyze/workbench_status/distill_run；memory.*/write_todos 确认未外放）。
    - ✅ 工具调用链：`fs.extended.convert_timestamp(1726032000000)` → `1726032000000 → 2024-09-11 13:20:00.000`（schema 解码 → Koog 分发 → 结果回传，换算值正确）。
    - ✅ 围栏 fail-safe：围栏外路径调 `log.analyze_log` 返回拒绝文案「路径不在本任务允许范围内…」（deny-only 契约实测成立）。
    - ✅ workbench_analyze 研判一次（真实 LLM 端点）：run 启动→agent 走 knowledge.search→analyze_log→read_file→完成，`workbench_status` 可查 COMPLETED 与结果文本；围栏内日志文件逐次登记生效。
    - ✅ 并发面观测：3 个并发 run 全部完成，页面在途计数即时回落（终态 bump 版本号行为可见）。
    - ❌ 取消保全未执行：单次研判 ~2-3s 即完成，UI「中止」点击未命中在途窗口（取消语义有 `McpRunRegistryTest` 单测在位，端到端取消未取证）。
    - ❌ 端口冲突明示未执行：服务页开关对合成指针点击未响应，未能构造「占用端口后重启」场景；应用未崩溃面已间接观测（服务启停多次无崩溃）。
    - ❌ jpackage 打包后启动验证未执行（Ktor jlink 模块面仍未取证）。
    - **另登记（本批实测发现）**：launch 期 `mcp_server_enabled=true` 自动启动连续两次未生效（页面开关手动重启后立即生效），根因未查——复检触发器见下。
    - **复检触发器**：①研判耗时显著变长（多轮工具循环）时补取消保全端到端；②服务页开关可被自动化驱动时补端口冲突明示；③下次打包发版时补 jpackage 启动验证；④下次复检一并查 launch 期自动启动未生效问题。
- [x] 5.4 openspec validate + git commit 收口 + docs-sync 提醒（新模块/新页面/入口组二成员——确认后执行）

## 验证方式（apply 阶段执行）

- 单测全绿（零 Ktor/零 SDK——manager/registrar 经接口缝 fake；schema 派生/名集/登记表/围栏纯函数直测）
- 隔离守护（SDK 三文件白名单 + Ktor 单点）+ NavTest 入口组断言
- MCP Inspector 真机连通全链（工具面 + agent 会话 + 取消）——验收清单 5.3
- jpackage 打包启动验证（Ktor jlink 模块面——审计报告两条阻断的教训）
