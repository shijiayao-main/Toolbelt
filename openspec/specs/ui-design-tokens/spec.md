# ui-design-tokens Specification

## Purpose

为应用提供统一的 UI 尺寸设计 token（间距、组件尺寸、圆角），消除散落在各页面的魔法值，保证界面视觉一致性，并为新页面开发提供强制使用的规范依据。

## Requirements

### Requirement: 间距设计 token
系统 SHALL 在主题模块提供集中定义的间距 token object（`Dimens`），基于 4dp 设计网格覆盖常用档位，至少包含 2、4、6、8、10、12、16、24 dp 八档，并 SHALL 为高频场景提供语义别名（页面内边距、列表项间距、元素内间距）。

#### Scenario: 间距 token 取值等价
- **WHEN** 开发者用间距 token 替换既有代码中的魔法值（如 `12.dp`、`8.dp`、`4.dp`、`2.dp`）
- **THEN** token 的实际取值与被替换的魔法值完全相等，界面视觉无任何变化

#### Scenario: 语义别名映射
- **WHEN** 开发者在页面级布局中使用页面内边距语义 token
- **THEN** 该 token 取值等于现有各页面统一的 24 dp 页面内边距

### Requirement: 组件尺寸与圆角 token
系统 SHALL 提供组件尺寸 token（常用图标尺寸档位：小/中/大）与圆角规范 token（`AppShapes`），圆角档位 SHALL 与 Material 3 默认形状档位值对齐（8 / 12 / 16 dp 等），替代 `RoundedCornerShape(dp)` 字面量与 `MaterialTheme.shapes.*` 混用的现状。

#### Scenario: 圆角统一引用
- **WHEN** 通用组件（如文件拖放区）与面板类组件（如输出面板）分别引用圆角 token
- **THEN** 二者取值与替换前的 `RoundedCornerShape(12.dp)`、`MaterialTheme.shapes.medium` 等既有取值相等，视觉无变化

#### Scenario: 图标尺寸档位
- **WHEN** 开发者为状态图标或按钮内图标选择尺寸
- **THEN** 可引用小（16-18 dp）、中（20 dp）、大（40 dp）档位 token，取值与现有组件一致

### Requirement: 高频魔法值替换范围
系统 SHALL 将以下范围内的间距/圆角/尺寸魔法值替换为 token：`widget/` 全部通用组件（FileDropZone、TaskCard、PageHeader、StatusView）、shelltools 页面（含输出面板、工具列表）、文件类页面（XLog 解析、文件分割、Log Tag 过滤）。替换 MUST 遵循值等价原则，不引入任何视觉变化。

#### Scenario: 通用组件替换
- **WHEN** 检查 `widget/` 下通用组件源码
- **THEN** 间距、圆角、图标尺寸均已引用 token，不存在裸露的间距/圆角魔法值

#### Scenario: 页面替换
- **WHEN** 检查 shelltools 页面与文件类页面源码
- **THEN** 高频魔法值（页面内边距、元素间距、卡片圆角）已引用 token；个别非阶梯值（如进度条描边 2 dp）可有据保留

### Requirement: token 使用规范文档
系统文档 SHALL 记载 token 体系的取值定义与使用规范：新页面与新组件 MUST 使用 token 而非硬编码尺寸；需要新档位时 MUST 先扩充 token 定义再使用。

#### Scenario: 文档可查
- **WHEN** 开发者查阅主题系统文档
- **THEN** 可获得间距阶梯、组件尺寸档位、圆角档位的完整取值表与使用示例

#### Scenario: 新页面遵循规范
- **WHEN** 开发新页面时需要设置间距或圆角
- **THEN** 依照文档规范从 token object 取值，不在页面代码中新写 `xx.dp` 间距/圆角字面量
