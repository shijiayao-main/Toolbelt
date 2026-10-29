# Debug 页

文件：`page/debug/`（composeApp 模块内宿主页）

> Shell 调试页：设置页「开发者选项 → 显示调试入口」开启后，dock 系统区出现"调试"图标。

---

## 入口（SYS-15 · 重设计批 6 现行口径）

`PageSettings` 的 Switch → `AppViewModel.setDebugPageEnabled(enabled)`（持久化 `debug_page_enabled`）
`PageHomeMenu` dock 系统区：`debugEnabled` 为 true 时渲染 Debug `DockIconButton`（沉底系统区，与设置/AI/MCP 同区）
点击 dock 图标 → `AppViewModel.setCurrentPage(ToolPage.Debug)` → `PageHomeContent` 渲染 `ShellDebugPage`
命令面板（Ctrl+K）同样按 `debugEnabled` 门控 Debug 候选（`CommandPalette` debugEnabled 参数）

🔴 **返回链已退役**（SYS-15 · 重设计批 6）：`backFromDebug()` / `previousPage` 已随调试页
返回钮一并删除——dock/命令面板切走即离开，无返回链（AppViewModel.kt 内有退役注记）。

---

## 文件

| 文件 | 职责 |
|------|------|
| `ShellDebugPage.kt` | Composable，聊天气泡风格的命令输入/输出界面 |
| `ShellDebugViewModel.kt` | 命令执行逻辑，使用 `SimpleShell.execute(cmd = parts)` |
| `DebugChatModels.kt` | 聊天消息模型 |

---

## ShellDebugViewModel

调用 `SimpleShell.execute(cmd = parts)` 执行用户输入的命令，以聊天气泡形式展示输入命令和执行结果（stdout / stderr / exitCode）。
