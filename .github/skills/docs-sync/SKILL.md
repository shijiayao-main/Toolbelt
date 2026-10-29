---
name: docs-sync
description: 触发知识库（docs/）的生成或更新。当用户明确要求"更新知识库/更新文档/同步 docs/同步架构文档/补文档"时使用；检测到本次会话完成了功能开发、或页面/模块/核心约定发生变更时，先向用户建议运行，经同意后再执行，不要未经确认自动修改 docs/。docs/ 知识库与代码保持同步，供 AI 后续按文档读代码、减少重复遍历源码。
---

# docs-sync：知识库生成与更新

`docs/` 是本项目的 AI 知识库——供 AI 在动手前按文档定位代码，避免重复遍历源码。它的价值完全依赖"与代码同步"：一篇过时的文档比没有文档更糟（AI 会被误导）。本 skill 负责在代码变更后把知识库拉回一致。

---

## 执行流程

### 第 1 步：确定变更范围

收集本次要同步的代码变更，按优先级取其一：

1. 用户明确指定的范围（文件/模块/功能）
2. 未提交的 git 变更：`git status --short` + `git diff --stat`（未 staged 与 staged 都算；若已 commit，可向用户要 commit 范围，或直接以源码现状对照文档现状，第 3 步本就以源码为准）
3. 若两者皆无：向用户询问"要同步哪个功能/模块的变更到知识库？"

### 第 2 步：读取知识库地图

读 `docs/index.md` 拿到文档地图。然后对每个受影响的代码区域，判断对应的文档：

| 变更落在 | 对应文档 |
|---------|---------|
| `page/**`、`widget/**`、`core/theme/**` | `docs/ui-style-guide.md`（规范真源是 `docs/core/theme.md`） |
| `page/home/**`（导航）、`page/ToolPage.kt` | `docs/pages/navigation.md` |
| `page/settings/**` | `docs/pages/settings.md` |
| `page/shelltools/**`（feature-shelltools）、`core/shelltools/**`、`core/extensions/`（Shell 层） | `docs/pages/shell-tools.md` + `docs/core/shell.md` |
| `page/debug/**` | `docs/pages/debug.md` |
| `page/larkbridge/**`（feature-larkbridge 内） | `docs/pages/lark-bridge.md` |
| `lark-bridge/**`（桥接内核 lib） | `docs/pages/lark-bridge.md` |
| `lark-gateway/**`（SDK 隔离适配层） | `docs/pages/lark-bridge.md` |
| `feature-larkbridge/**`（桥接页 feature 模块） | `docs/pages/lark-bridge.md` |
| `core/data/**`（:core）、`core/AppViewModel.kt`（composeApp）、`host/**`（composeApp 宿主包，EasyDataManager 所在） | `docs/core/data.md` + `docs/core/viewmodel.md` |
| `core/log/**` | `docs/core/log.md` |
| `main.kt`、`App.kt`、Gradle 模块结构 | `docs/architecture.md` |
| `gradle/libs.versions.toml`、`composeApp/build.gradle.kts`（依赖/打包/工具链） | `docs/architecture.md`「模块依赖关系」一节（仅当影响模块结构/依赖方向/构建方式；纯加依赖项可跳过，并在汇报中说明） |
| `composeResources/**/shell_tools*.json` | `docs/shell_tools.md` |
| `log-tools/**` | 属「已知欠账」（见第 4 步），按欠账条款新建专篇 `docs/log-tools.md` |
| 表中未列出的路径（如 `entity/**`、`page/xlog`、`page/logtag`、`page/timestamp`、`page/filesplit`、`core/initializer/**`） | 先对照 `docs/index.md` 的「已知欠账」小节：在欠账清单里的，按第 4 步补成专篇；不在的，检查是否被某篇文档的章节覆盖（用 index.md 定位），都没有再新建 |

### 第 3 步：读取目标文档 + 逐条核对

对每个需要更新的文档：

1. **完整读一遍目标文档**
2. **用 diff 对照**：把变更 diff 与文档逐节对照，列出三类差异：
   - 文档描述的行为已不存在（漂移）→ 修正或删除
   - 新增的行为/文件/约定文档未覆盖 → 补充
   - 文档正确但表述过时 → 顺手修订
3. 核对时**以源码为准**。对拿不准的点，读实际源码确认，不要照着 diff 猜。

### 第 4 步：判断是否需要新建文档

满足任一条件时新建专篇（而不是往已有文档里塞）：

- 一个新 Gradle 模块
- 一个新功能页面子包（对照：`page/settings` 有专篇、`page/shelltools` 有专篇）
- 一个新 core 层子系统（对照：`core/data`、`core/theme` 各有专篇）
- 触及「已知欠账」区域时（清单唯一真源见 `docs/index.md`「已知欠账」小节），优先把它补成专篇而不是继续欠账，补齐后从欠账清单中移除该条

新文档命名与归位：

```
docs/pages/<功能名>.md     ← 功能页面（kebab-case）
docs/core/<子系统>.md      ← core 层子系统
docs/<模块>.md             ← 新 Gradle 模块
```

### 第 5 步：遵循知识库写作规范

**语言与风格**：全部用中文（API/类名/key 等英文术语保留）。强调"结构 + 约定 + 可照抄代码"，不写教程式叙事。

**路径约定**：
- 默认根：`composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/`
- 新文档首行用 `文件：\`<相对默认根的路径>\`` 或文件总览表（`文件|职责`）声明覆盖范围（参照 `docs/core/theme.md`、`docs/pages/settings.md` 的现状）；默认根无需在文档中重复声明
- 仅当内容使用非默认根时，开头显式注明该根（如 `docs/shell_tools.md` 用 `~/.toolbelt/`）

**文档骨架**（沿用现有专篇的通用结构，参照 `docs/core/theme.md`、`docs/core/data.md`、`docs/pages/settings.md`）：
1. 首行声明覆盖的文件/路径（`文件：\`路径\`` 或 文件总览表 `文件|职责`）
2. API 签名 / 结构定义（Kotlin 代码块）
3. 行为要点（列表或表格）
4. 调用示例（可照抄）
5. 约定 / FAQ / 设计决策（复杂模块用 D1/D2… 编号追踪变更来源）

**警示信息**用 `>` 引用块；key/token/字段清单用表格。

### 第 6 步：同步 index.md（必做）

无论更新还是新建，最后都要维护 `docs/index.md`：

1. **文档目录树**：新文档在树中加行，格式 `├── xxx.md ← 一句职责注释`
2. **快速定位指南**：按"我想做的事 → 看哪里"加一行任务导向映射，`看哪里`用 Markdown 相对链接，可附锚点类名
3. 新文档在 `docs/pages/` 或 `docs/core/` 下时，树里也要补对应目录行
4. **已知欠账**：若补齐或新增了欠账条目，同步更新 index.md 的「已知欠账」小节（勾掉补齐项或追加新项）
5. 一次性报告（如 `docs/compatibility-audit-*.html`）不入目录树

### 第 7 步：校验

- 新建/修改的文档中提到的类名、文件路径、方法签名，抽查 3-5 处回源码验证存在且一致
- 文档间相对链接可点（相对路径正确）
- 若更新涉及 UI 令牌/守护规则，确认与 `docs/core/theme.md`（规范真源）一致，冲突时以 theme.md 为准
- 欠账清单有变化的，确认 `docs/index.md`「已知欠账」已同步
- 向用户汇报：更新了哪些文档、各自改了什么、是否新建了文档、index.md 是否同步

---

## 边界与注意

- **只写 `docs/` 下的知识库文档**，不改用户手册（那是 user-manual skill 的职责）、不改源码、不改 openspec 规格
- 知识库的已知欠账清单唯一真源在 `docs/index.md`「已知欠账」小节（含 log-tools 专篇、四个无专篇页面等），本 skill 只引用不另维护副本
- `docs/pages/navigation.md` 已同步：源码导航真源为 `page/home/ToolPageRegistry.kt`（feature Contributions 注册 + 宿主自有页拼接），设置/调试入口 `navItem = null` 不进工具分组
- 大规模重写一篇文档前，向用户确认；小步修正可直接做
