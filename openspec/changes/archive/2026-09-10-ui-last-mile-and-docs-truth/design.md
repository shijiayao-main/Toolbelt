# Design — ui-last-mile-and-docs-truth

## 核心取舍

### 1. AppSwitchRow 增强：补参数而非将就替换（用户拍板）

PageSettings 私有版与 AppSwitchRow 有 4 处差异（hover 背景渐入 / label bodyLarge / 行 padding+clip / rowClickable 语义）。用户拍板"增强组件后替换"，即：

- **加参不加行为**：三个新参默认值严格 = 现渲染路径。`hoverable=false` 时不进 HoverableRow 组合（连 interactionSource 都不建），保证 RouteRuleEditor×6 / ConfigFieldRenderer:125 零改动零视觉变化，且不威胁 feature-larkbridge 的"零白名单条目"态势（守护测试必须 `--no-build-cache` 验证）。
- **hover 实现**：`hoverable=true` 时 Row 换成 `HoverableRow`（`ui/.../widget/interactions/HoverActions.kt:40`）。已核实其背景绘制挂 modifier 链**外层**（调用方 clip/padding 之前）、带 `shape` 参数、走 `AppMotion.fast()` + `AppAlpha.bgSubtle`——与 PageSettings 私有版渲染次序逐点一致，hover 背景覆盖整行含 padding 区。
- **labelStyle 用非空 `TextStyle`**（默认参在组合期求值 `MaterialTheme.typography.bodyMedium` 合法），不用 nullable 避免歧义。
- **不加 labelColor**：两形态实际颜色同为 onBackground（私有版显式色是冗余）。
- **padding/clip 不加参**：调用方 modifier 补（已拍板）。
- **rowClickable 语义差异接受**：AppSwitchRow 行点击无 ripple（indication=null）、Switch 本体 onCheckedChange=null；私有版相反。净效果都是切换一次。主反馈是 hover 背景，与 ThemeOptionItem 交互语言统一。

目标签名：

```kotlin
@Composable
fun AppSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    rowClickable: Boolean = true,
    hoverable: Boolean = false,
    hoverShape: Shape = RectangleShape,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
)
```

PageSettings 调用点形态：`AppSwitchRow(label, checked, onCheckedChange, modifier = Modifier.fillMaxWidth().clip(AppShapes.medium).padding(horizontal = Dimens.space16, vertical = Dimens.space10), hoverable = true, hoverShape = AppShapes.medium, labelStyle = MaterialTheme.typography.bodyLarge)`。

已知接受微差：点击热区上下各缩 10dp（padding 从 clickable 外侧移到内侧）；无 ripple。

### 2. 确认框/空态替换 = 纯删码

AppConfirmDialog(isDangerous/confirmLabel) 与 EmptyStateView(icon/fillParent) 对 4 处手写现场逐行可表达（已验证）。次要视觉差仅 title 字体级别（组件标准 titleMedium vs 手写默认），无参数可关，接受。删除后随手清理未用 import（AppAlpha/AlertDialog/Switch 等，逐个甄别他处是否仍用）。

### 3. P0-5 修正以源码为真源

- 配置归属逐 key 核实过（EasyDataManager 6 key / LogKitConfigStore 4 / ShellToolsDataStore 5 / TimestampConfigStore 1 / LegacyConfigImporter 标记），不是猜的。
- 分档表修正依据链：BuiltinHandlers.kt:377 `weight = HandlerWeight.LIGHT` → :360 KDoc "设计裁定 URGENT 为笔误" → HandlerKinds.kt:48/Responder.kt:80 已是 LIGHT。守护测试（IntakeGuardTest/ParityTest/Isolation/Guard）全部剥注释后扫描，改注释零影响（已验证）。
- ObserveStatusTest 头注释：BC-341..342=ProgressAccumulator、343..346=LogBus、347..352=SkillRegistry（behavior-contract.md:480-500），无一行属状态面——删 BC 引用，保留 D18/D19/D20。
- draining 字段：唯一读取方 drainAwareConnection 已删，全仓零读取（含测试）；Draining 对外发布走 connectionRef.set(Draining(...))（Bridge.kt:307）不受影响——纯死写清理。

## 施工约束

- 波次 1/2 不依赖在途改动落地；波次 3（lark-bridge 4 文件）**硬前置 W0**（用户先提交 remove-bridge-dead-code 在途 13 文件），避免 git status 混淆。
- 修正注释时以实际文件内容定位（Admission.kt 行号已因在途改动漂移，grep URGENT 定位）。
- 全程不跑 git 命令。

## 验证设计

- 波次 1：`./gradlew :composeApp:jvmTest :feature-shelltools:jvmTest :feature-larkbridge:jvmTest --rerun-tasks --no-build-cache`（三模块守护全扫；重点 UiArchitectureGuardTest 零新违规、桥接页零白名单不破）
- 波次 3：`./gradlew :lark-bridge:test --rerun-tasks --no-build-cache`
- grep 清零：手写现场符号（AlertDialog 确认框块/私有 SwitchRow）在对应文件零残留
- PageSettings 视觉：`./gradlew :composeApp:run` 人工过设置页（hover 渐入、行高、字号）
