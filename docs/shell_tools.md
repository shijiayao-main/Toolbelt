# shell_tools.json 配置规范

ADB 快捷指令的配置文件，用于定义工具列表中显示的命令分组和命令按钮。

## 文件位置与三源管道

```
~/.toolbelt/shell_tools.json          # 用户主配置（只读）
~/.toolbelt/shell_tools_custom.json   # 自定义配置（应用内 CRUD 写入）
composeResources/files/shell_tools_default.json # 内置默认配置（随应用打包，仅通用示例命令）
```

> **内置默认配置只允许放通用公开命令**（无个人路径、无内部包名/action/registry）。
> 个人命令合集请写入主配置 `shell_tools.json`（位于用户目录，不进仓库），避免随仓库与安装包泄露。

工具列表按三源管道合并（`page/shelltools/ShellToolsConfigRepository`）：

1. **内置默认配置**作为展示基线（新用户开箱即见 3 分组 / 12 工具的通用示例，不再是空页）；
2. **主配置存在且解析成功 → 完整取代**内置默认；不存在 → 静默使用默认；读失败/空白/解析失败 →
   回退默认并在列表顶部展示错误提示（可关闭）；
3. **自定义配置**（分组树 + 未分组工具）整体追加在列表尾部。

主配置对应用为**只读**（应用内所有写操作只落 shell_tools_custom.json）；
修改主配置后点击页面标题栏「刷新」按钮生效，无需重启。

---

## 顶层结构

配置文件是一个 **JSON 数组**，每个元素为一个节点（Node）。

```json
[ <Node>, <Node>, ... ]
```

节点分为两种类型，通过顶层 `type` 字段区分：

| `type` 值 | 类型      | 说明               |
|----------|---------|------------------|
| `0`      | **父节点** | 可折叠的分组容器，内部包含子节点 |
| `1`（非 0） | **子节点** | 可执行的命令按钮         |

---

## 公共字段

所有节点（父节点和子节点）都包含以下字段：

| 字段           | 类型             | 必填 | 说明                    |
|--------------|----------------|----|-----------------------|
| `type`       | `int`          | ✅  | `0` = 父节点，非 0 = 子节点   |
| `name`       | `string`       | ✅  | 节点显示名称                |
| `body`       | `object/array` | ✅  | 父节点为子节点数组；子节点为命令配置对象  |
| `showOutput` | `boolean`      | ❌  | 执行后是否跳转输出页，默认 `false` |
| `id`         | `string`       | ❌  | 节点稳定标识（见下文「id 字段说明」）  |

### showOutput 字段说明

| 值           | 行为                                |
|-------------|-----------------------------------|
| `false`（默认） | 命令在后台静默执行，按钮显示 loading 动画，完成后恢复正常 |
| `true`      | 执行后跳转到输出页面，实时查看命令输出               |

> **Chain 类型特殊规则**：即使 `showOutput: false`，链式命令中某步骤执行失败时，会自动跳转输出页面以展示失败详情。

---

## 父节点

`type: 0`，`body` 为子节点数组，支持点击展开/折叠。

```json
{
  "type": 0,
  "name": "分组名称",
  "body": [
    <子节点>,
    <子节点>
  ]
}
```

---

## 子节点

`type: 1`，`body` 为命令配置对象，通过 body 内的 `type` 字段决定命令类型。

```json
{
  "type": 1,
  "name": "按钮名称",
  "showOutput": false,
  "body": { <命令配置> }
}
```

### body.type 命令类型总览

| `body.type` | 命令类型            | 按钮样式              | 说明         |
|-------------|-----------------|-------------------|------------|
| `0`         | Normal（普通）      | 填充主色 + ▶ 图标       | 单条命令，一键执行  |
| `1`         | MultiSelect（多选） | 卡片内多个 Outlined 按钮 | 同组多个命令选一执行 |
| `2`         | Input（输入）       | 次色调填充 + ✏ 图标      | 执行前弹窗填写参数  |
| `3`         | Chain（链式）       | Tertiary 色 + ⏭ 图标 | 串行执行多条命令   |

---

### Normal — 普通命令

点击直接执行一条 shell 命令。

**body 结构：**

| 字段      | 类型       | 必填 | 说明              |
|---------|----------|----|-----------------|
| `type`  | `int`    | ✅  | 固定为 `0`         |
| `name`  | `string` | ❌  | 保留字段（可不填）       |
| `value` | `string` | ✅  | 要执行的完整 shell 命令 |
| `desc`  | `string` | ❌  | 按钮副标题，显示在名称下方   |

**示例：**

```json
{
  "type": 1,
  "name": "重启设备",
  "body": {
    "type": 0,
    "value": "adb reboot",
    "desc": "冷重启到系统"
  }
}
```

---

### MultiSelect — 多选命令

在同一行展示多个按钮，每个按钮对应一条独立命令，选一执行。

**body 结构：**

| 字段     | 类型       | 必填 | 说明        |
|--------|----------|----|-----------|
| `type` | `int`    | ✅  | 固定为 `1`   |
| `name` | `string` | ❌  | 保留字段（可不填） |
| `list` | `array`  | ✅  | 按钮列表      |

**list 子项结构：**

| 字段      | 类型       | 必填 | 说明             |
|---------|----------|----|----------------|
| `name`  | `string` | ✅  | 按钮显示文本（同组内应唯一） |
| `value` | `string` | ✅  | 要执行的 shell 命令  |
| `desc`  | `string` | ❌  | 保留字段           |

**示例：**

```json
{
  "type": 1,
  "name": "重启模式",
  "body": {
    "type": 1,
    "list": [
      { "name": "Recovery", "value": "adb reboot recovery" },
      { "name": "Fastboot",  "value": "adb reboot bootloader" },
      { "name": "System",    "value": "adb reboot" }
    ]
  }
}
```

> **注意**：同组内 `list[].name` 应保持唯一，否则 loading 状态追踪可能出现冲突。

---

### Input — 输入参数命令

点击后弹出参数填写对话框，参数填充到命令模板后执行。

**body 结构：**

| 字段      | 类型       | 必填 | 说明                  |
|---------|----------|----|---------------------|
| `type`  | `int`    | ✅  | 固定为 `2`             |
| `name`  | `string` | ❌  | 保留字段（可不填）           |
| `value` | `string` | ✅  | 命令模板，用 `{参数名}` 作占位符 |
| `list`  | `array`  | ✅  | 参数定义列表              |

**list 子项结构：**

| 字段     | 类型       | 必填 | 说明                   |
|--------|----------|----|----------------------|
| `name` | `string` | ✅  | 参数名，需与模板中 `{参数名}` 一致 |
| `desc` | `string` | ❌  | 参数说明，显示在输入框 label 中  |

**占位符规则：** 命令模板中 `{name}` 会被替换为用户输入的对应值。

**示例：**

```json
{
  "type": 1,
  "name": "设置代理",
  "showOutput": true,
  "body": {
    "type": 2,
    "value": "adb shell settings put global http_proxy {host}:{port}",
    "list": [
      { "name": "host", "desc": "代理服务器地址" },
      { "name": "port", "desc": "端口号" }
    ]
  }
}
```

---

### Chain — 链式命令

依次串行执行多条 adb shell 命令，任意一步失败即停止并标记后续步骤为已跳过。

**body 结构：**

| 字段     | 类型              | 必填 | 说明                |
|--------|-----------------|----|-------------------|
| `type` | `int`           | ✅  | 固定为 `3`           |
| `name` | `string`        | ❌  | 保留字段（可不填）         |
| `list` | `array<string>` | ✅  | 按顺序执行的 shell 命令列表 |

> **注意**：`list` 中的命令会在 `adb shell` 交互会话中执行，不需要 `adb shell` 前缀。

**示例：**

```json
{
  "type": 1,
  "name": "完整部署",
  "showOutput": true,
  "body": {
    "type": 3,
    "list": [
      "am force-stop com.example.app",
      "pm clear com.example.app",
      "pm install -r /sdcard/app.apk",
      "am start -n com.example.app/.MainActivity"
    ]
  }
}
```

---

## 完整示例

```json
[
  {
    "type": 0,
    "name": "设备管理",
    "body": [
      {
        "type": 1,
        "name": "重启设备",
        "body": {
          "type": 0,
          "value": "adb reboot",
          "desc": "冷重启到系统"
        }
      },
      {
        "type": 1,
        "name": "重启模式",
        "body": {
          "type": 1,
          "list": [
            { "name": "Recovery",  "value": "adb reboot recovery" },
            { "name": "Fastboot",  "value": "adb reboot bootloader" },
            { "name": "System",    "value": "adb reboot" }
          ]
        }
      }
    ]
  },
  {
    "type": 0,
    "name": "应用调试",
    "body": [
      {
        "type": 1,
        "name": "清除数据并启动",
        "showOutput": true,
        "body": {
          "type": 3,
          "list": [
            "am force-stop com.example.app",
            "pm clear com.example.app",
            "am start -n com.example.app/.MainActivity"
          ]
        }
      },
      {
        "type": 1,
        "name": "启动 Activity",
        "showOutput": true,
        "body": {
          "type": 2,
          "value": "adb shell am start -n {package}/{activity}",
          "list": [
            { "name": "package",  "desc": "包名，如 com.example.app" },
            { "name": "activity", "desc": "Activity 类名，如 .MainActivity" }
          ]
        }
      }
    ]
  }
]
```

---

## 自定义配置文件 shell_tools_custom.json

应用内「新增自定义命令 / 分组管理」的落盘文件，由 `ShellToolsConfigRepository` 全权读写
（单实例 Mutex 覆盖读-改-写全临界区，`.tmp` + rename 原子写）。

### 结构

数组元素两种形态，可混合（显式分组在前、未分组工具在后）：

```json
[
  {
    "type": 0,
    "name": "我的分组",
    "id": "custom-group:6c109584-4443-47ab-a871-b52ccf581b24",
    "body": [
      {
        "type": 1,
        "name": "我的命令",
        "id": "9f0d5d7a-1111-2222-3333-444455556666",
        "showOutput": true,
        "body": { "type": 0, "value": "adb devices", "desc": "列出设备" }
      }
    ]
  },
  {
    "type": 1,
    "name": "未分组命令",
    "id": "a1b2c3d4-...",
    "body": { "type": 0, "value": "echo hi", "desc": "" }
  }
]
```

- **未分组工具**（顶层 `type: 1`）在 UI 归入「我的自定义」默认分组（id 固定 `"custom"`），
  落盘保持扁平形态；对该默认分组重命名时才提升为显式分组（`id` 保持 `"custom"`）
- **分组 id** 前缀 `custom-group:` + UUID；**工具 id** 为 UUID 原文
- 工具类型（body.type 0/1/2/3）与主配置完全一致，四类型均可创建、编辑、执行、导入导出

### id 字段说明

| 来源 | id 规则 |
|------|--------|
| 主配置 / 内置默认配置节点 | 缺省（解析时按树路径确定性生成，同一文件重复加载不变） |
| 自定义工具 | UUID（读入缺失时自动补齐并立即回写） |
| 自定义分组 | `custom-group:` + UUID（读入缺失时自动补齐并立即回写） |
| 默认自定义分组 | 固定 `"custom"`（兼容展开状态记忆） |

手编/合并产生重复 id 时读入自动归一化：撞 id 项按落盘顺序保留首个（固定 `"custom"` 的默认分组恒不重生成），
其余自动重生成 id 并立即回写（幂等，二次读入不变），避免改名/删除命中错误对象。

id 用于：编辑原地替换（改名不重建）、跨组移动（数据不变仅归属变化）、执行 loading 追踪与历史关联。

### 旧数据迁移与回滚

- **旧扁平结构**（整个文件为顶层 `type: 1` 工具数组）读入时归入默认分组展示，落盘保持扁平
- **缺 id 节点**读入时自动补 UUID 并**立即原子回写**（幂等：二次读入 id 不变）
- 首次迁移前自动生成 `shell_tools_custom.json.bak`（保留最早一次迁移前内容，不覆盖）；
  回滚 = 用 `.bak` 内容替换原文件后重启应用（或点刷新）
- 迁移/回写异常时保留原文件并降级为空自定义列表（主配置不受影响）
- **分享 / 导入**：导出剥离 `id`（与既有分享物格式互通）；导入一律生成新 id（复制语义），
  兼容旧扁平片段与新分组结构（可混合）

---

## 常见问题

**Q：Chain 命令里需要加 `adb shell` 前缀吗？**
不需要。Chain 类型在内部启动了一个 `adb shell` 交互会话，`list` 中的命令直接在该会话内执行。

**Q：`showOutput` 不填会怎样？**
默认为 `false`，即静默执行、按钮显示 loading。如果需要查看命令输出，设置为 `true`。

**Q：Normal 类型的命令需要 `adb` 前缀吗？**
需要。Normal / MultiSelect / Input 类型通过系统 shell 直接执行 `value` 字段的内容，需要写完整命令，例如
`adb shell am force-stop com.example`。
