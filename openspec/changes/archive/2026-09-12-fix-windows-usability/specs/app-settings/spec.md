# Delta: app-settings

## MODIFIED Requirements

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
