# file-split-by-size Specification

## Purpose

定义按大小分割文本文件的核心行为契约：按 MB 阈值将大文件切分为多个子文件，覆盖分割规则、命名与边界处理。

## Requirements

### Requirement: 按 MB 大小分割 txt 文件

系统 SHALL 支持将 `.txt` 文件按用户指定的 MB 大小进行行级切割，生成多个带序号后缀的子文件。

**文件编码（本变更新增）**：输入读取与分片写出 SHALL 显式按 UTF-8 编解码（输入源为 xlog/alog 解密产物，UTF-8 文本；不依赖平台默认编码——JDK 17 打包态 Windows zh-CN 默认 GBK，按平台默认读写会把多字节 UTF-8 中文按字节边界错误切分或写成乱码）。

#### Scenario: 正常分割

- **WHEN** 用户指定 `--type split_by_size -f target.txt --size 10`
- **THEN** 系统按约 10MB 为上限逐行写入分片，输出 `target_000.txt`、`target_001.txt` 等文件

#### Scenario: 文件小于指定大小

- **WHEN** 目标文件总大小小于 `--size` 指定的 MB 数
- **THEN** 系统仅生成一个分片文件 `target_000.txt`，内容与原文件一致

#### Scenario: 单行超过指定大小

- **WHEN** 某一行本身的字节大小超过 `--size` 指定的 MB 数
- **THEN** 该行单独写入当前分片并关闭，分片大小超过目标值，程序继续处理后续行

#### Scenario: 中文内容跨平台读写一致（本变更新增）

- **WHEN** 输入文件含中文行，在任意平台执行分割
- **THEN** 分片内容与原文件逐行一致（UTF-8 编解码），不因平台默认编码产生乱码

### Requirement: 输出文件命名规则
输出文件 SHALL 以原文件名（不含扩展名）加下划线加三位零填充序号加 `.txt` 为文件名，序号从 `000` 起自增。

#### Scenario: 命名格式正确
- **WHEN** 原文件名为 `app.txt`，分割后产生 3 个分片
- **THEN** 输出文件名为 `app_000.txt`、`app_001.txt`、`app_002.txt`

### Requirement: 输出文件已存在时拒绝执行
系统 SHALL 在执行分割前检查第一个目标输出文件是否已存在，若已存在则返回失败并提示用户，不执行分割。

#### Scenario: 已存在输出文件
- **WHEN** `app_000.txt` 已存在于同目录
- **THEN** 系统返回 Failure，提示文件已存在，不执行任何写操作

### Requirement: 仅支持 txt 文件
系统 SHALL 仅接受扩展名为 `.txt` 的文件作为分割输入，其他扩展名应返回错误。

#### Scenario: 非 txt 文件输入
- **WHEN** 用户传入 `.xlog` 或其他非 `.txt` 文件
- **THEN** 系统提示扩展名不匹配，拒绝执行

### Requirement: size 参数必须为正整数
`--size` 参数 SHALL 要求为正整数（>= 1），单位为 MB，不接受 0 或负数。

#### Scenario: size 为 0 或负数
- **WHEN** 用户传入 `--size 0` 或 `--size -1`
- **THEN** 系统提示参数校验失败，拒绝执行
