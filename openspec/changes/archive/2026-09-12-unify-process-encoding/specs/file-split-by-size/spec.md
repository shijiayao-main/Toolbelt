# Delta: file-split-by-size

## MODIFIED Requirements

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
