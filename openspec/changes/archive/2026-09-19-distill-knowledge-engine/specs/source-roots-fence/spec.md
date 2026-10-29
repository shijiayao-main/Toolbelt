# Spec Delta

## Purpose

定义 agent 路径围栏的「允许根目录集合」语义与多源码目录配置契约：让蒸馏 agent 能整目录读源码而不触发逐文件 HITL 弹窗，同时把授权范围严格限定在桌面链——桥接链的出站文件面与源码目录永久隔离。

## ADDED Requirements

### Requirement: 围栏目录前缀放行

agent 路径围栏 SHALL 支持「允许根目录集合」语义：路径 canonical 化后落在任一允许根目录内（前缀比对，含分隔符边界）即放行读类工具访问，不再逐文件触发 HITL 确认。前缀比对 SHALL 平移既有 workspace 围栏的大小写归一（Windows）与用户目录展开（~）语义；符号链接 canonical 后落在根外时拒绝（fail-safe）。

#### Scenario: 目录内文件免逐文件确认

- **GIVEN** 允许根目录含 /home/user/proj，用户启动蒸馏读取 /home/user/proj/src/A.kt
- **WHEN** agent 发起读该文件
- **THEN** 路径 canonical 化后前缀命中根目录，直接放行，无 HITL 弹窗

#### Scenario: 根外路径仍走原语义

- **GIVEN** 允许根目录含 /home/user/proj，agent 请求 /home/user/.ssh/config
- **WHEN** 路径校验
- **THEN** 不命中任何允许根，围栏按既有语义处理（HITL 确认或拒绝），源码目录授权不外溢

#### Scenario: 符号链接逃逸被拒

- **GIVEN** 源码树内存在指向 /home/user/.ssh 的符号链接
- **WHEN** agent 经该链接读文件
- **THEN** canonical 解析后真实路径落在允许根外，拒绝访问（fail-safe）

### Requirement: 多源码目录配置轨道

应用 SHALL 提供多源码目录配置（有序列表，支持多项目根），配置持久化且改动对桌面链热生效（免重启）；AI 设置页 SHALL 提供该配置的管理界面（目录列表 + 逐项删除 + 系统目录选择器）。配置面 SHALL 明示授权语义：哪些 agent 链消费此配置、授权范围是整目录读取、授权后目录内路径不再逐文件确认。

#### Scenario: 配置改动热生效

- **GIVEN** 桌面链已配置 1 个源码目录且 agent 会话空闲
- **WHEN** 用户在 AI 设置页新增第 2 个源码目录
- **THEN** 下一次蒸馏/agent 任务无需重启即可读取新目录（配置经单一访问器热读）

#### Scenario: 授权范围文案可见

- **GIVEN** 用户打开 AI 设置页「源码目录」卡片
- **WHEN** 渲染配置界面
- **THEN** 界面明示：该目录列表授权桌面链 agent 整目录读取、授权后目录内文件不再逐个弹窗确认

### Requirement: 桥接链与源码目录永久隔离

飞书桥接链 SHALL NOT 消费源码目录配置：源码根目录 SHALL NOT 进入桥接链 agent 的路径允许集，桥接链的出站文件工具（send_file 面）与源码目录授权永久隔离。此为安全边界钉死（防出站旁路：源码目录进桥接允许集 = 用户整个源码项目可被发到飞书群）。

#### Scenario: 桥接链读不到源码目录

- **GIVEN** 用户配置了源码目录 /home/user/proj，桥接 agent 会话收到含该路径的消息
- **WHEN** 桥接链装配工具围栏
- **THEN** /home/user/proj 不在桥接链允许集，桥接 agent 的读工具访问该目录按既有桥接围栏语义处理（不因源码目录配置而放行）

#### Scenario: send_file 面不扩权

- **GIVEN** 用户已配置源码目录
- **WHEN** 桥接链执行出站文件交付
- **THEN** 源码目录内的文件不因该配置获得任何出站放行（出站判据与源码目录配置完全无关）

### Requirement: 蒸馏产物落点不污染源码工作区

蒸馏链的任何产物（候选知识/中间报告）SHALL NOT 写入源码目录内；产物统一落应用数据目录。源码目录对 agent 是只读面。

#### Scenario: 产物落应用数据目录

- **GIVEN** 蒸馏执行完成产出候选知识
- **WHEN** 产物落盘
- **THEN** 产物位于应用数据目录（非源码目录），源码目录 git status 无新增脏文件
