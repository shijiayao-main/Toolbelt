# copy-rewording Specification

## Purpose

产品文案、LLM 提示词与示例语料面向通用设备/嵌入式场景叙事——公开面不出现车载域
词汇（用户决策：车载语境属不宜公开面）。本规格是该文案约束的唯一书面依据：守护测试
（`VehicleTermGuardTest`）依据它解释「为什么源码里没有车载字样」，防止后续贡献者把
场景限定词与车载域示例词自然写回。

## Requirements

### Requirement: 公开面零车载域词

用户可见文案（UI 文本、手册、README）、LLM 提示词（prompt 人设、工具 UiHint/描述）、
用户输入示例（placeholder 示例词）与测试语料 SHALL 不使用车载域词
（`车载|车机|AVM|泊车` 及同域业务缩写如 APA/R 档）。生产源码注释同档适用。

**残留允许面**（历史记录按当时名称保留）：openspec 归档区；守护测试自身的正则与
白名单声明；工具英文 description 的 vehicle 字样（非用户可见）。

替换语义分流：日志语境→「设备日志」、设备连接语境→「目标设备」、叙事语境→
「移动/嵌入式设备」、示例语料语境→「设置/相机/桌面」等通用功能域。

#### Scenario: prompt 与工具描述零车载域词

- **GIVEN** 主源码（非测试、非归档）的 LLM prompt 与工具 UiHint/描述
- **WHEN** 守护 grep 运行（`车载|车机|AVM|泊车`，扫描面 = 生产 jvmMain、docs/manual、README）
- **THEN** 除允许面外零命中

#### Scenario: 手册真源与资源副本同步

- **GIVEN** docs/manual 与 feature-larkbridge resources/manual 两份手册
- **WHEN** 文案替换
- **THEN** 两份对应文件逐字节一致（既有 user-manual 同步纪律）

#### Scenario: 测试语料通用化

- **GIVEN** 测试内以车载域词作 fixture/断言锚
- **WHEN** 文案替换
- **THEN** fixture 串与断言串成对同步更新为通用功能域叙事，测试全绿
