# app-settings Specification

## Purpose

定义应用设置页的持久化偏好配置契约。本能力覆盖设置页配置项的行为规范，当前包含 ADB 可执行文件路径配置：支持自定义 adb 安装位置，涵盖路径编辑、浏览选择、可用性检测、持久化，以及命令执行时的 adb 路径解析与回退顺序。

## Requirements

### Requirement: ADB 可执行文件路径配置项
设置页 SHALL 提供 ADB 可执行文件路径配置项，包含：路径文本输入框（支持直接输入路径）、"浏览"按钮（打开系统文件选择器，选择后回填路径到输入框）与"检测可用性"按钮（使用当前配置路径执行 adb 版本查询并展示结果）。配置值 SHALL 持久化，应用重启后保留。

#### Scenario: 浏览选择 ADB 路径
- **WHEN** 用户点击"浏览"并在文件选择器中选中某个可执行文件
- **THEN** 路径输入框回显所选文件的绝对路径，配置被持久化

#### Scenario: 手动输入路径
- **WHEN** 用户在路径输入框中直接输入路径并确认
- **THEN** 配置按输入值持久化

#### Scenario: 检测成功
- **GIVEN** 当前配置路径指向可用的 adb 可执行文件
- **WHEN** 用户点击"检测可用性"
- **THEN** 展示检测成功提示及 adb 版本信息

#### Scenario: 检测失败
- **GIVEN** 当前配置路径为空、指向不存在的文件或指向非 adb 可执行文件
- **WHEN** 用户点击"检测可用性"
- **THEN** 展示检测失败的明确提示

#### Scenario: 重启后保留
- **GIVEN** 用户已配置 ADB 路径为 "/opt/android-sdk/platform-tools/adb"
- **WHEN** 重启应用后打开设置页
- **THEN** 路径配置项显示 "/opt/android-sdk/platform-tools/adb"

#### Scenario: 默认为空
- **WHEN** 用户从未配置过 ADB 路径
- **THEN** 路径配置项为空，命令执行使用系统 PATH 中的 adb

### Requirement: ADB 路径解析顺序

应用内所有 adb 命令调用（含设备检测）SHALL 按以下顺序解析 adb 可执行文件：自定义路径非空时优先使用自定义路径；为空时使用系统 PATH 中的 `adb`。自定义路径无效时 SHALL 按所配路径如实执行并失败，不静默回退 PATH。

**含空白路径的引号语义（本变更新增）**：自定义 adb 路径含空白字符时 SHALL 按实际执行 shell 的引用语义包裹——Unix（经登录 shell 执行）使用单引号包裹；Windows（经 `cmd.exe /c` 执行）使用双引号包裹（`cmd.exe` 不解析单引号，单引号包裹会使命令按字面断裂导致命令失败）。不含空白时原样使用。

#### Scenario: 自定义路径优先

- **GIVEN** 自定义路径配置为 "/opt/adb"，且系统 PATH 中也存在 adb
- **WHEN** 任意页面执行 adb 命令
- **THEN** 使用 "/opt/adb" 作为 adb 可执行文件执行

#### Scenario: 空路径回退 PATH

- **GIVEN** 自定义路径为空
- **WHEN** 任意页面执行 adb 命令
- **THEN** 使用系统 PATH 中的 adb 执行

#### Scenario: 无效路径不静默回退

- **GIVEN** 自定义路径指向不存在的文件
- **WHEN** 执行 adb 命令
- **THEN** 命令启动失败并展示错误信息，不回退 PATH 中的 adb

#### Scenario: 设备检测使用同一路径

- **GIVEN** 自定义路径配置为 "/opt/adb"
- **WHEN** Shell 工具页刷新设备状态
- **THEN** 设备检测命令使用 "/opt/adb" 执行

#### Scenario: Windows 含空格路径可执行（本变更新增）

- **WHEN** 应用运行于 Windows，自定义 adb 路径为 `C:\Program Files\platform-tools\adb.exe`（含空格）
- **THEN** 重写出的命令以双引号包裹该路径，经 `cmd.exe /c` 执行成功

#### Scenario: Unix 单引号语义保持（本变更新增）

- **WHEN** 应用运行于 macOS / Linux，自定义 adb 路径为 `/opt/my tools/adb`（含空格）
- **THEN** 重写出的命令以单引号包裹该路径（现网行为不变），经登录 shell 执行成功
