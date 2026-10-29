# Delta: lark-bridge-page

## MODIFIED Requirements

### Requirement: 配置持久化

系统 SHALL 将页面配置（App ID、App Secret、测试消息目标 chat_id、dryRun、工作区域路径、工作区多目录白名单、发送者白名单、发送者黑名单、访问控制模式、拒绝回复模板、未匹配兑底回复、权限档位、禁删开关、安全提示注入开关、运行时确认开关/超时、技能列表）通过 EasyDataManager 持久化，遵循"持久化不直接用 DataStore"约定。新增配置 key SHALL 包括：`lark_bridge_workspace_whitelist`（多目录白名单，JSON 数组，与单目录取并集）、`lark_bridge_skills`（技能列表，`SkillConfig` JSON 整体存取）、`lark_bridge_agent_prohibit_delete`（禁删开关，默认 true）、`lark_bridge_agent_safety_prompt_enabled`（安全提示注入，默认 true）；权限档位 key `lark_bridge_agent_permission_preset` 默认为 `safe`。工作区域 SHALL 为必填项（无默认值，空串表示未配置）。访问控制模式 SHALL 支持白名单（默认）与黑名单两种；拒绝回复与未匹配兑底回复为可选模板（空 = 静默）。页面 SHALL 提供发送者统计卡：从统计文件聚合展示各发送者的对话量、被拒次数与用户名（用户名解析失败时以 open_id 兑底），支持手动刷新。

文本类配置项（App ID、App Secret、chat_id、白名单、触发前缀、重置指令、停止指令、工具限制、agent 启动目录、System Prompt、自定义模板）SHALL 采用失焦提交：输入过程仅更新内存态（不写持久化、不重建桥接实例），输入框失去焦点时值有变化才提交（持久化 + 重建实例）。开关类与单选类配置项 SHALL 保持点击即提交。桥接实例重建 SHALL 自动恢复运行态：重建前实例处于运行中时，重建后自动启动新实例。

工作区域卡 SHALL 保持显式保存流（保存按钮触发持久化与目录创建），不适用失焦提交。

**工作区域路径校验语义（本变更新增）**：工作区域路径的绝对性校验 SHALL 采用平台语义（`java.io.File.isAbsolute`）——Unix 下以 `/` 开头为绝对路径；Windows 下盘符路径（`C:\…`、`C:/…`）与 UNC 路径（`\\…`）均为绝对路径。`~` 前缀展开 SHALL 兼容两种分隔符（`~/` 与 `~\`），展开行为由单一共享实现提供，各调用点 MUST NOT 自行维护展开逻辑。

#### Scenario: 配置重启后保留

- **WHEN** 用户填写 App ID / App Secret / chat_id / 工作区域 / 白名单并重启应用
- **THEN** 配置项恢复为上次保存的值

#### Scenario: 工作区域未配置时功能受限

- **WHEN** 工作区域未配置且用户通过飞书触发 agent 执行
- **THEN** agent 执行器拒绝执行并回复「请先配置工作区域」类提示，启动预检给出警告

#### Scenario: 工作区域路径输入校验

- **WHEN** 用户输入的工作区域路径不是绝对路径或无法创建
- **THEN** 配置卡给出错误提示，不保存无效值

#### Scenario: Windows 盘符路径可保存（本变更新增）

- **WHEN** 应用运行于 Windows，用户输入工作区域路径 `C:\dev\ws` 或 `C:/dev/ws`
- **THEN** 校验通过，保存按钮出现，路径可保存并生效

#### Scenario: 波浪号展开兼容两种分隔符（本变更新增）

- **WHEN** 用户输入 `~/work`、`~\work` 或裸 `~`
- **THEN** 三种输入均展开为用户主目录（及其子目录），校验按展开后结果进行

#### Scenario: 白名单未命中发送者被拒绝并引导配置

- **WHEN** 白名单为空或消息发送者 open_id 不在白名单内，且消息命中 agent 触发前缀
- **THEN** agent 不执行，日志台记录该发送者 open_id 供用户复制配置

#### Scenario: 输入过程零副作用

- **WHEN** 桥接运行中，用户在白名单输入框连续输入多个字符（未失焦）
- **THEN** 桥接实例不重建、服务持续运行，持久化未写入

#### Scenario: 失焦提交且值有变化才重建

- **WHEN** 用户在配置输入框修改后点击输入框外（失焦）
- **THEN** 值有变化时持久化并重建桥接实例；值未变化时不触发持久化与重建

#### Scenario: 运行态自动恢复

- **WHEN** 桥接运行中，用户失焦提交任一文本类配置
- **THEN** 桥接实例重建后自动恢复运行状态（无需手动重拨开关），日志记录重建与恢复

#### Scenario: 未运行时提交不启动

- **WHEN** 桥接未运行，用户失焦提交配置
- **THEN** 配置持久化、实例重建，服务保持未启动（不意外拉起）
