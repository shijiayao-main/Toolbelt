# :log-tools — XLog 解码 / 日志过滤与分割 / 独立 CLI

> 纯 JVM 库模块（`JvmCliConventionPlugin`：Java 11 + jvmTarget 11 + shadowJar fat-jar），
> 源码根 `log-tools/src/main/kotlin/com/jiaoay/`。三大能力面：**XLog/alog 加密日志解码**
> （`com/jiaoay/xlog/`）、**明文日志 Tag/时间过滤与按大小分割**（`com/jiaoay/log/`）、
> **独立 CLI**（`com.jiaoay.log.MainKt`，`log-tools-all.jar`）。
>
> 库纪律（模块级硬约束）：
> - **零日志门面依赖**——库内失败上报统一走 `ToolsLog` 的 System.err 单行结构化输出
>   （`[tools-fail] <where>: <类型>: <消息> (caused by …)`，截断 500 字符），不引
>   kotlin-logging/slf4j（会拖 provider 进 fat jar 并改变 CLI 输出流）；桌面宿主经
>   stderr 重定向/logback 捕获。
> - **lib 层异常消息保持英文**（零依赖纪律）；中文映射在宿主消费面
>   （`:ui` `describeFileTaskError`，见 [pages/logkit-tools.md](pages/logkit-tools.md)）。
> - 模块内实现细节（stream/parser/codec/postprocess/LogTimestamp/ToolsLog）为
>   `internal`，public 面只有 `LogDecode` / `LogFormatDetector` / `LogProbe` /
>   `LogTagFilter` / `LogTimeFilter` / `LogSplitter` / `LogAnalysisPipeline` /
>   `LogDiff` / `LogViewer`（含 `LogViewSession`）/ `LogStatsAnalyzer` /
>   `ToolsResult` / `LogExts` / `utils/SafeExtract` / `utils/ZstdUtils`。

消费方（依赖方向，均合法单向）：

| 模块 | 消费面 |
|------|--------|
| `:core` | `api(project(":log-tools"))`——agent 工具 `LogProbe`（文件嗅探）/ `LogFormatDetector`（判型路由）/ `LogDecode` / `SafeExtract`（`core/llm/tools/`） |
| `:feature-logkit` | XLog 解密 / Tag 过滤 / 文件分割三工具页的底层管线 |
| `:feature-archive` | 解压页 `SafeExtract`（纯 JDK 解包 + zip-slip 防护） |
| `:lark-bridge` | 飞书引用文件预处理（`.xlog`/`.alog` 解密，`LogDecode` 门面） |

---

## 文件总览

| 文件 | 可见性 | 职责 |
|------|--------|------|
| `xlog/LogDecode.kt` | public | 解码门面（唯一全量解密入口） |
| `xlog/LogFormatDetector.kt` | public | 内容判型（XLOG_SEGMENTS / PLAINTEXT / UNKNOWN） |
| `xlog/LogProbe.kt` | public | 头部窗口探测（零解码、零密钥、零输出；文件级探测唯一 public 入口） |
| `xlog/stream/XLogChunkReader.kt` | internal | 滑动窗口流式读取器（8MB chunk / 64MB 单段上限） |
| `xlog/parser/LogSpaceParser.kt` | internal | 段格式无状态纯函数解析器 |
| `xlog/parser/LogSpaceSlice.kt` | internal | 单段解析结果（零拷贝切片宿主） |
| `xlog/parser/MagicStartMark.kt` | internal | 段头 magic 标记 → 加密/压缩能力元数据 |
| `xlog/codec/LogCodecRegistry.kt` | internal | 标记 → 解密+解压组合注册中心 |
| `xlog/codec/TeaKeyResolver.kt` | internal | ECDH 密钥协商 + session 级缓存 |
| `xlog/codec/TeaCipher.kt` | internal | TEA(XTEA 变体) 解密（8B 整块，零 per-block 分配） |
| `xlog/codec/EcdhKeyAgreement.kt` | internal | secp256k1 ECDH 协商（BouncyCastle） |
| `xlog/codec/Decompressors.kt` | internal | zlib(raw) / zstd 解压 |
| `xlog/postprocess/PostProcessorPipeline.kt` | internal | 解码后处理管道（行级变换） |
| `xlog/postprocess/TimestampNormalizer.kt` | internal | 行首时区毫秒时间戳归一化 |
| `xlog/postprocess/LogPostProcessor.kt` | internal | 处理器接口 + SegmentContext / DecodeSummary / LineSink |
| `log/LogTagFilter.kt` | public | Tag 块过滤 + `sanitizeTagName` 产物命名单源 |
| `log/LogTimeFilter.kt` | public | 时间窗口过滤 |
| `log/LogSplitter.kt` | public | 按大小分割 |
| `log/LogTimestamp.kt` | internal | 行首时间戳契约单源（判定/解析共用同一正则） |
| `log/LogExts.kt` | public | `String?.isTimeLine()` 判定 + `String.parseLogTimestampTarget()` 目标时间解析（均委托 LogTimestamp 单源） |
| `log/LogViewer.kt` | public | 大文件查看引擎（`LogViewer.open` 行偏移索引 + `LogViewSession` 窗口 seek / 级别导航 / 文本搜索 / 时间戳导航，契约见「③ 查看引擎」节） |
| `log/LogStatsAnalyzer.kt` | public | 日志统计概览（Tag 频次/级别计数/时间跨度——块识别经 `scanBlocks` 单源复用，级别对齐 threadtime/brief 双形态，流式单遍内存有界） |
| `log/ToolsResult.kt` | public | `Success(outputFile)` / `Failure(reason)` sealed 结果 |
| `log/ToolsLog.kt` | internal | stderr 单行结构化失败上报 |
| `log/pipeline/LogAnalysisPipeline.kt` | public | 日志分析确定性管线（解密→时间过滤→环境扫描→Tag 过滤） |
| `log/Main.kt` + `log/cli/LogUtilsCommand.kt` | — | CLI 入口（clikt） |
| `utils/SafeExtract.kt` | public | zip/tar/tar.gz/gz 解包（zip-slip 防护 + 解压炸弹三闸） |
| `utils/ZstdUtils.kt` | public | 单文件 zstd 压缩/解压（当前仓库内无生产消费面，测试与工具保留） |

---

## ① XLog 编解码管线（com/jiaoay/xlog/）

### 段格式与流式读取

xlog/alog 是**段序列**容器，每段布局（`LogSpaceParser`）：

```
magic start(1B) | seq(2B LE) | beginHour(1B) | endHour(1B) |
length(4B LE) | crypt key(N B) | log(length B) | magic end(1B, 0x00)
```

- 段头固定 9B；`crypt key` 长度由 magic 标记声明（4B 或 64B）；单段载荷上限
  64MB（xlog 原生 length 为 uint32，此处收紧）。
- 解析协议：`XLogChunkReader` 以 8MB chunk 窗口流式喂字节——窗口末尾半条段
  压实到窗口头部续读；单段超窗时窗口扩容（封顶 64MB 载荷 + 头部余量）；垃圾字节
  （无 magic 前缀）逐字节跳过；候选段所需字节超过「窗口内剩余 + 文件未读」时判为
  无效垃圾继续扫，不误作「数据未到齐」挂到 EOF。
- 🔴 消费游标推进必须用 parse 返回的**绝对偏移** `startIndex + totalSize`——
  段头可能远在本次扫描起点之后，按相对偏移推进会重复消费垃圾（垃圾前缀场景死循环）。
- EOF 处不完整尾段**静默丢弃**（仅当残留以段头 magic 起始才计为半条段），字节数
  记入 `DecodeSummary.droppedTailBytes`；`LogSpaceSlice.window` 零拷贝切片仅在
  回调返回前有效。

### 段头 magic 标记 → 解码组合（MagicStartMark / LogCodecRegistry）

| 标记 | mark | cryptKeyByteSize | 加密 | 压缩 |
|------|------|------------------|------|------|
| NoCompressStart | 0x03 | 4 | NONE | NONE |
| CompressStart | 0x04 | 4 | NONE | ZLIB |
| CompressStart1 | 0x05 | 4 | NONE | NONE（名实不符，原样输出——解码兼容约定） |
| NoCompressStart1 | 0x06 | 64 | NONE | NONE |
| CompressStart2 | 0x07 | 64 | ECDH_TEA | ZLIB |
| NoCompressNoCryptStart | 0x08 | 64 | NONE | NONE |
| CompressNoCryptStart | 0x09 | 64 | NONE | ZLIB |
| SyncZstdStart | 0x0A | 64 | NONE | NONE（名实不符，原样输出） |
| SyncNoCryptZstdStart | 0x0B | 64 | NONE | NONE（名实不符，原样输出） |
| AsyncZstdStart | 0x0C | 64 | ECDH_TEA | ZSTD |
| AsyncNoCryptZstdStart | 0x0D | 64 | NONE | ZSTD |

`LogCodecRegistry.codecFor(mark)` 按 mark 元数据推导「解密（可选）+ 解压（可选）」组合：
解密仅 `ECDH_TEA` 一种策略（`TeaCipher`），解压有 `ZLIB`（raw Inflater）与 `ZSTD`
（zstd-jni 流式）两种；NONE 跳过对应步骤。

### 密钥语义（TeaKeyResolver / EcdhKeyAgreement / TeaCipher）

- 段头 crypt key（64B）= 对方公钥的 `X || Y`；与调用方传入的**私钥 hex** 做
  secp256k1 ECDH 协商，协商结果整体作为 TEA 密钥（`EcdhKeyAgreement.agree`，
  BouncyCastle provider）。
- `TeaKeyResolver` 以公钥 hex 为键做 **session 级缓存**——同一文件的数百段通常
  来自同一设备（公钥相同），缓存避免重复协商；生命周期 = 单次 `decodeFile`
  （私钥可能被用户中途更换，不跨调用缓存）。
- `TeaCipher.decrypt`：XTEA 变体，16 轮，delta `0x9E3779B9`，小端；8 字节整块
  解密、尾部不足 8B 的部分**原样保留**；密钥至少 16 字节（require）。
- 私钥为空且文件含加密段时：`TeaKeyResolver.resolve` 抛
  `IllegalArgumentException("the log is encrypted and the private key needs to be entered")`
  ——该英文消息是**私钥缺失哨兵**，宿主错误映射按此识别。

### 解码门面契约（LogDecode）

`LogDecode().decodeFile(privateKey: String, logFile: File, outFile: File)`：

- 管线：流式读取 → 段解析 → 解码组合 → 后处理管道
  （`PostProcessorPipeline.default()`，默认仅 `TimestampNormalizer`：
  行首 `[Asia/Shanghai]-1787812910686 ` 归一化为 `08-27 14:41:50.686 `，
  输出格式与 `:log` 侧行首时间戳契约一致；时区标签解析失败回退系统默认时区）。
- **密钥预校验**：打开输出前，头部窗口探测（`LogProbe.probe`）发现含加密段
  （`needsPrivateKey`）且私钥为空即抛哨兵消息——把失败时点从「首个加密段回调内」
  提前到「打开输出前」，非加密文件不误伤。
- 🔴 **原子替换写**：temp 全量解码成功后 `Files.move(ATOMIC_MOVE)` 覆盖产物
  （不支持原子移动的文件系统退化为 REPLACE_EXISTING）；失败/取消路径删除 temp
  且**保留在盘旧产物**（半途密钥错误不 truncate 已有产物）；temp 命名
  `<outFile.name>.tmp`，须删净——`.tmp` 残留会被按前缀扫产物的消费面误当产物。
- 实例无共享状态，可并发调用；每次调用内部持有独立的密钥缓存与后处理管道。

### 内容判型与探测（LogFormatDetector / LogProbe）

后缀不可信（已解密文件仍挂 `.alog` 后缀、明文 mmap 被当加密文件送解密等），
入口统一走内容嗅探：

`LogFormatDetector.detect(file, windowBytes = 4MB)` 判型优先级：

1. xlog/alog 段结构探测（`LogProbe.probe`，magic+段头+尾标自洽，误报率极低）
   → `Format.XLOG_SEGMENTS`；
2. 明文形态：首行含 `begin of mmap` 标记，或头部窗口前 200 行内任一行命中
   行首时间戳契约（复用 `:log` 侧 `LogTimestamp.isTimeLine`）→ `Format.PLAINTEXT`；
3. 兜底 `Format.UNKNOWN`（空文件/随机二进制/损坏数据）。

`routeFor(file) = detect(file)`——解密入口的统一前置路由：PLAINTEXT 跳过解密
（源文件即产物语义）；UNKNOWN 且后缀为 `.alog`/`.xlog` 时按用户意图照常尝试
解密、失败如实报错。

`LogProbe.probe(file, maxBytes = 8MB, maxSegments = 16)` 返回
`XLogProbe(isXLog, segmentsFound, needsPrivateKey, compressions)`：
- `isXLog` = 窗口内解析出 ≥1 条完整段且段头 magic 命中；
- `needsPrivateKey` = 命中任一 ECDH_TEA 加密段（无钥不可解密，调用方提示用户配置）；
- `compressions` = 命中段的压缩方式名集合（NONE/ZLIB/ZSTD），供判型摘要展示。

两者均为阻塞 IO，调用方自行包 `Dispatchers.IO`。

---

## ② 过滤与分割（com/jiaoay/log/）

### 行首时间戳契约单源（LogTimestamp / LogExts）

判定与解析共用同一正则（防 `isTimeLine` 判定与 `LogTimeFilter` 解析漂移）：

```
^(\d{4}-\d{2}-\d{2}|\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3})
```

兼容两种行首格式：裸格式 `MM-dd HH:mm:ss.SSS`（xlog 自产，年份由调用方以
目标时间锚定）与年份格式 `yyyy-MM-dd HH:mm:ss.SSS`（外部解码器产物）。

- `isTimeLine(line)`：仅判形态；
- `parseLine(line, fallbackYear)`：形态不命中或数值非法（如 99 月）返回 null；
- `parseTarget(target)`：规范格式 `yyyy-MM-dd_HH:mm:ss.SSS`，容忍空格分隔与
  省略毫秒（自动补 `.000`）；亦容忍裸 `MM-dd HH:mm:ss.SSS`（xlog 自产明文行首
  同款，从日志直接复制即可）。裸形态仅对裸格式日志保证正确（年份按目标年统一
  重锚，锚定值不影响结果）；跨年段裸文件相对先后在边界处倒置（区间可能被拒
  end<start 或头部多收）；年格式文件裸目标恒不命中（转零输出守护拒绝）；
  年份格式日志（外部解码器产物）与跨年边界的日志请补全年份。

### LogTagFilter — Tag 块过滤

`filterByTag(file, tagRegex, tagName): ToolsResult`：

- **块语义**：行首时间戳行开新块；块切分由**时间行**触发，空行原样入块
  （数据保真）；块内**任意一行**命中 `tagRegex`（`Pattern.compile` + `find`）
  即整块按原行序输出。
- **段落降级**（无标准时间戳行首的文件，mmap 转储/tombstone 等 crash 内容）：
  头部窗口（`LogTagFilter.HEADER_WINDOW_LINES` = 500 行）零时间行即激活降级，
  空行兼作块切分触发（保真归块尾——命中块顺序写出自带空行分隔不粘连）；
  时间行触发不替换，降级期间照常切分。降级激活时 `ToolsResult.Success.warning`
  携带 `LogTagFilter.PARAGRAPH_FALLBACK_WARNING`，CLI `filter_by_tag` 在成功锚前
  echo 同款 warning 行——块口径不同必须自报形态，不静默。
- 产物：同目录 `<文件名去扩展名>.filter-tag_<sanitizeTagName(tagName)>.txt`；
  产物已存在 → `Failure("<路径> 已存在, 不需要再次进行处理")`（exists 幂等
  契约——重跑死端由宿主 feature 层覆盖确认卡解，见 [pages/logkit-tools.md](pages/logkit-tools.md)）。
- `sanitizeTagName(raw)`（public，宿主 feature 层预判产物名与库层命名**逐字一致**
  的单源）：文件系统非法字符（`< > : " | ? *` 与路径分隔符）替换 `_`；命中
  Windows 保留设备名（CON/PRN/AUX/NUL/COM1-9/LPT1-9，不分大小写）追加 `_`；
  尾部空白与点号循环清除（Windows 忽略尾部点号与空格）。

### LogTimeFilter — 时间窗口过滤

`filterByTime(file, target, rangeMinute): ToolsResult`：

- `target` 经 `LogTimestamp.parseTarget` 解析，失败即 `Failure`（消息含期望格式
  与裸形态容忍口径——裸 `MM-dd HH:mm:ss.SSS` 仅对裸格式日志保证正确，
  年份格式/跨年日志请补全年份）；
- **对称窗口** `[target - rangeMinute, target + rangeMinute]`；
- 产物：同目录 `<文件名去扩展名>.filter-time_HHmm-HHmm.txt`（分钟补零）；exists 幂等同上。
- 行为：时间行**先判定后写入**——窗口内时间行当行即写、超窗时间行触发
  `break` 提前结束；空行原样透传不参与判定；裸 `MM-dd` 格式的年份锚定
  `currentYear = targetTime.year`。
- 🔴 **空时间锚点防呆**：全文件未识别到任何时间戳行（`timeLines == 0`）时
  删除 0 行产物并报 `Failure`——「输出 0 行的静默失效」会误导下游拿空文件
  得出「日志不存在」的假结论。
- 🔴 **零命中防呆**：时间行存在但均在区间外（`writtenLines == 0 && timeLines > 0`，
  含全格式区间整体越界与年格式文件裸目标场景）同样删产物报 `Failure`
  （「区间未命中任何时间行（时间戳行 N 行均不在区间内）」）——空产物
  假成功同样是「日志不存在」的假结论源头。
- 底层 `filterByTime(inputFile, outputFile, startTime, endTime, currentYear): Stats`
  返回 `Stats(timeLines, writtenLines)` 供管线消费。

### LogSplitter — 按大小分割

`splitBySize(file, sizeMb): ToolsResult`：

- 产物：同目录 `<文件名去扩展名>_<序号 padStart(3,'0')>.txt`，如 `foo_000.txt`；
  首片已存在 → `Failure`（exists 幂等）。
- 字节数按「字符数 + 1（换行）」**估算**（性能热路径不逐行 toByteArray）；
  UTF-8 下估算恒 ≥ 实际的一半、≤ 实际全值，作为「何时切下一片」触发量足够准。
- 恰在阈值处读完（或输入为空）时最后开启的分片可能 0 字节——该空片被删除，
  防宿主按 `_<idx>` 前缀枚举兄弟卷时数出 phantom 卷。

### 结果类型与失败上报（ToolsResult / ToolsLog）

`ToolsResult` sealed：`Success(outputFile)` / `Failure(reason)`。三个过滤/分割
入口的 catch 统一走 `ToolsLog.reportFailure("<组件名>", e)`（stderr 单行结构化、
异常链逐层展开、截断 500 字符），再返回 `Failure("执行失败, message: …")`。

---

## ③ 查看引擎（LogViewer / LogViewSession，log/LogViewer.kt）

大文件日志查看引擎：按行号窗口 seek 读取，任意行号跳转直达（行偏移索引 +
随机访问回读），文件内容不整文件驻留。**非线程安全**（单调用方串行使用）；
全部为阻塞 IO，调用方自行包 `Dispatchers.IO`。

### 打开与格式路由（LogViewer.open）

`LogViewer.open(file): OpenResult`（sealed：`Ok(session)` / `Failure(reason)`）——
读取/判型异常均折叠为 `Failure`，不抛。格式路由复用 `LogFormatDetector` 单源：

- `XLOG_SEGMENTS`：**拒绝**（加密段结构需先解密产出 txt 再查看——失败文案自带引导），
  `Failure.encrypted = true`（typed 档位，构造点唯一）——查看器 Failed 态以该档位
  渲染「送 XLog 解密」衔接入口（判型单源，不做失败文案字符串匹配）；
- `PLAINTEXT` / `UNKNOWN`：按 UTF-8 文本查看（UNKNOWN 形态的不可解码字节按替换符
  呈现，无害；查看只读不改写）。

打开 = 单遍流式字节扫描建行偏移索引（百 MB 量级亚秒级）；打开后**无常驻文件句柄**
（`readWindow` 每次独立开 `RandomAccessFile`）。会话经 `open` 构造，
`LogViewSession` 构造函数 private，不直接实例化。

### 内存上界（会话驻留只有两块）

- **行起始偏移索引：8 字节/行**（`LongArray`，`session.indexMemoryBytes` 自证口径），
  与行长、文件字节总量无关；
- **单次窗口行集：≤ `LogViewer.MAX_WINDOW_LINES`（5000）行**原文——请求行数超限被
  钳制（实际返回 `min(maxLines, 5000, 剩余行数)`），窗口外行永不驻留。

两个硬上界常量均带值依据 KDoc（真门禁：超出即钳制/截断，非软提示）。

### 行语义（与 `LogDiff` 同一套 readLine 口径，单源不漂移）

`\n`、`\r\n`、孤立 `\r` 均为行终止；文末无终止符的尾行计一行；空文件零行。
`totalLines` 即该口径的行数；`ViewerLine.number` 为 1 基展示行号，
`readWindow`/导航/搜索的入参 `startLine` 为 0 基行下标。

### LogViewSession API（逐项契约）

```kotlin
fun readWindow(startLine: Int, maxLines: Int): List<ViewerLine>
fun nextLevelLine(startLine: Int, levels: Set<LogStatsAnalyzer.Level>): Int?
fun prevLevelLine(startLine: Int, levels: Set<LogStatsAnalyzer.Level>): Int?
fun searchLines(startLine: Int, keyword: String): LineSearchResult
fun nextTimestampLine(startLine: Int, target: LocalDateTime): Int?
```

- **readWindow**：按行号窗口读取（任意合法 `startLine` 直达，无从头顺序读）。
  `startLine < 0` 或 `maxLines ≤ 0` 抛 `IllegalArgumentException`（调用方编程错误）；
  `startLine ≥ totalLines` 返回空表。返回行集的 `level` 单源复用
  `LogStatsAnalyzer.parseHead` 块头解析（不造第二套解析器）；续行/形态不命中为
  `null`——消费方按中性色渲染，不得视为级别缺失的错误。
- **nextLevelLine**：自 `startLine`（0 基，**含**）起向后找首个级别在 `levels` 中的行，
  返回其 0 基下标；无命中或 `levels` 为空返回 null。单次查询最坏走到文件末行。
- **prevLevelLine**：自 `startLine`（0 基，**不含**）起向前找最近命中行。与 next 的
  含起点成对——起点行本身不参与，连点回退不重复命中当前行；`startLine` 大于
  `totalLines` 按末行计。单次查询最坏走到文件首行。
- **searchLines**：自 `startLine`（含）起收集含 `keyword` 的行下标（升序）。
  匹配口径 = **大小写不敏感子串**（非正则——日志关键词大小写混杂，敏感匹配易
  假性无命中）。结果集硬上界 `LogViewer.MAX_SEARCH_RESULTS`（1000）：达到即
  停止扫描并置 `LineSearchResult.truncated = true`——文件中可能还有更多匹配，
  消费方须明示截断，不得把上界当匹配总数。keyword 为空抛
  `IllegalArgumentException`。
- 级别导航/搜索/时间戳导航的扫描形态均为**逐窗 seek**（步长 `MAX_WINDOW_LINES`，经行偏移索引
  索引回读），不预建级别偏移表/全文索引——把全文本解析成本推迟到实际导航/搜索点击时
  按需支付，单窗驻留与窗口读取同档有界。
- **nextTimestampLine**：自 `startLine`（0 基，含）起向后找首个行首时间戳 ≥ `target` 的行，
  返回其 0 基下标。行首解析单源 `LogTimestamp.parseLine`（与时间过滤同一契约）；裸格式
  `MM-dd` 行年份锚定取 `target` 年份（LogTimeFilter 同口径）。无时间行/目标超尾返回
  null 不猜落点。

### 漂移失效语义

查看期间文件长度与索引建立时不一致 → `readWindow` 及各导航/搜索 API 抛
`IllegalStateException`（漂移失效），调用方重新打开（查看器 UI 折叠为 Drifted 态
给重开入口）。**同长度的原地改写不可探测**——索引只锚定行起始偏移与文件长度。
读流中 EOF 早于索引区间同样按失效处理（文件被截断）。

### 消费面

- `:ui` `widget/LogViewerDialog.kt`（`LogViewerStateHolder`：打开 → 首窗载入 →
  触边续读/行号跳转/时间戳跳转/级别导航/文本搜索；驻留行集恒 ≤ 5000，续读超界裁远端；
  打开/回读经 IO 调度，漂移异常折叠为 Drifted 态重开。**行文本可选中复制**
  （「找到一行 → 带走」产出动线）：行列表整容器粒度包 `SelectionContainer`
  ——行 `Text` 零 clickable（行点击跳转属日志对比页，不在查看器），拖选与
  导航控件零交互冲突；LazyColumn 滚动回收下跨屏选区有瑕疵，验收判据 =
  视口内拖选复制）。底部动作区带**「深度分析」研判入口**：当前文件经
  `:core` `DeepAnalyzeHook` 中立槽直投 AI 研判链（宿主 `AppViewModel` 单点
  wire → 跳日志工作台并预填症状备注；offer 第三参 `prefillTargetTime` 承载
  问题时间点预填——查看器带 `initialTimestamp` 定位打开时原样透传该第三参
  （工作台「问题时间点」框同步预填），行号定位档 `initialTimestamp` 为
  null 不透传（工作台不触碰时间域，用户手填值原样保留）；与任务卡
  「深度分析」钮同槽同链）。
  仅 Ready 态渲染（Failed 态不投——加密档由「送 XLog 解密」衔接承接，Drifted
  态索引失效不投），宿主未接线时按钮缺席。守护：`LogViewerDeepAnalyzeGuardTest`；
- 底部动作区另带**「送往 Tag 过滤」管线衔接入口**：当前文件经 `:core`
  `TaskPipelineHandoff` 通用投递面直调 `offer`（查看器不自持映射——来源 key =
  `SourcePageId.LOG_VIEWER` 专属常量，目标查投递面单张映射表，契约见
  [pages/navigation.md](pages/navigation.md)「TaskPipelineHandoff」节）——目标
  命令 + 文件路径预填进命令面板参数直填态，Tag 正则与输出标记由用户补齐；
  offer 成功即关闭 Dialog（显式关闭防浮层残留）。门控同深度分析：仅 Ready 态
  渲染（Failed 态不投——加密档由「送 XLog 解密」衔接承接），宿主未接线
  （`TaskPipelineHandoff.isWired`）时按钮缺席。守护：
  `LogViewerHandoffGuardTest`（`:ui`）；
- 底部动作区另带**「送时间截取」横向直投入口**：当前文件经同一
  `TaskPipelineHandoff` 投递面 offer **扩参**（`extraArgs`——HandoffSeed.args =
  单路径 map + extraArgs）直投表外常量 `VIEWER_TIME_SLICE`（`tools.slice-time`：
  切出物是本文件的时间子集，非下一级管线段，故不进映射表——语义判据见
  [pages/navigation.md](pages/navigation.md)「TaskPipelineHandoff → 横向直投
  （非管线）」节）。预填集：文件路径恒预填；查看器带 `initialTimestamp` 定位
  打开时预填 `start_time`（原样透传），行号档同无定位只预填路径；
  `end_time` 恒不预填（留白由用户补齐，缺填走命令既有「未填写结束时刻」回执）。
  门控同「送往 Tag 过滤」：仅 Ready 态渲染，宿主未接线时按钮缺席；
- 底部动作区另带**「送 XLog 解密」Failed 态衔接入口**（加密件专属）：打开被判型为
  加密段结构拒绝时（引擎 `Failure.encrypted` typed 档位直传），当前文件经同一
  `TaskPipelineHandoff` 投递面直调 `offer`——来源 key =
  `SourcePageId.LOG_VIEWER_ENCRYPTED` 专属常量，目标 = `logkit.xlog-decrypt`
  （`file_path` 落点），offer 成功即关闭 Dialog。门控：仅加密档 Failed 态渲染
  （非加密失败不渲染，维持失败回执引导现状），宿主未接线或映射表缺行时按钮缺席；
  私钥缺失零新处理（命令自身回执已引导到「工具」页配置）。守护：
  `LogViewerHandoffGuardTest`（`:ui`）；
- 命令面板 `tools.view-log` 带参命令与 HUD 产物 chip 分流（文本日志形态进查看器），
  经 `composeApp/.../page/home/ParamCommandCatalog.kt` 的 `LogViewerRequest` 写槽、
  宿主根叠层挂载 Dialog。带初始定位的请求现网消费方两档：**初始定位行**（1 基）=
  日志对比页差异行跳转（见 [pages/logkit-tools.md](pages/logkit-tools.md)「日志对比页」节）；
  **初始定位时间戳** = 日志工作台报告依据跳转的超窗分支（目标行不在预览前窗且产物
  已截断时带 `initialTimestamp` 直达，见 [pages/log-workbench.md](pages/log-workbench.md)
  「页面布局」节）——两档并存时 `initialLine` 优先（行号是零歧义定位，时间戳是
  行号缺位时的语义定位降级档，裁决单源在宿主消费面）；palette 单文件打开与
  工作台截断动作不带定位。另：logcat 抓取面板「标记」锚点的直达路径走
  `LogcatPanel` 直接挂载查看器并透传 `initialTimestamp`（不经 `LogViewerRequest`
  槽），锚点契约见 [pages/logcat.md](pages/logcat.md)「标记」节
  （面板归属 `:feature-logcat`，挂载于设备观测空间页）。

---

## ④ 独立 CLI（com.jiaoay.log.MainKt）

构建：`./gradlew :log-tools:shadowJar` → fat jar `log-tools-all.jar`
（`Main-Class: com.jiaoay.log.MainKt`），参数解析 clikt。

### 用法

```
java -jar log-tools-all.jar --type <xlog|filter_by_tag|filter_by_time|filter_by_range|split_by_size|pipeline|diff|stats> -f <文件> [旗标]
```

平铺参数单命令：`--type` 选择子功能，全部旗标挂同一命令；`--type` 决定哪组旗标必填。

| --type | 必填旗标 | 可选旗标 | 文件后缀白名单 |
|--------|----------|----------|----------------|
| `xlog` | `-k/--key`（私钥 hex） | — | `.xlog` / `.alog` |
| `filter_by_tag` | `--tagRegex`、`--tagName` | — | `.txt` / `.log` |
| `filter_by_time` | `--targetTime`（yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS——xlog 明文行首同款，仅裸格式日志正确）、`--range`（分钟，Long） | — | `.txt` / `.log` |
| `filter_by_range` | `--startTime`、`--endTime`（均 yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS——xlog 明文行首同款，仅裸格式日志正确） | — | `.txt` / `.log` |
| `split_by_size` | `--size`（MB，Int） | — | `.txt` / `.log` |
| `pipeline` | `--targetTime`、`--range` | `-k/--key`（.alog/.xlog 必填、.txt 明文可省）、`--tagRegex`+`--tagName`（省略 = 跳过 Tag 级）、`--envPattern`（省略 = 跳过环境扫描级） | `.xlog` / `.alog` / `.txt` / `.log` |
| `diff` | `--other <对比文件>`（`-f` 为基准） | `--normalizeTimestamps`（行首时间戳归一化对齐——仅时间戳不同的行视为同一行） | `.txt` / `.log` |
| `stats` | —（仅需 `-f`） | — | `.txt` / `.log` |

语义要点：

- **退出码契约**：`ToolsResult.Failure` → 抛 CliktError，进程**退出码非零**
  （脚本化消费/降级协议判定依据；clikt 自身的参数校验错误亦非零退出）。
- **`xlog` 的内容判型路由**（`decodeAndEcho`）：`LogFormatDetector` 判为
  PLAINTEXT 时跳过解密，按产物语义输出明文副本并打印 `skip-decrypt` 说明；
  其余（段结构 / UNKNOWN 按后缀意图）走 `LogDecode().decodeFile`。产物 =
  源文件同目录 `<源文件名>.txt`（如 `foo.alog` → `foo.alog.txt`）。
- **0 字节产物预警**：解密产物 0 字节时打印 warning——可能是明文 mmap 形态
  （源文件直接可用）或密钥/数据不匹配，给出判别指引防下游拿空文件继续过滤。
- **`pipeline`**：确定性分析管线（`LogAnalysisPipeline.run`）——Stage
  DECRYPT → TIME_FILTER（±range 对称窗口；**空结果守护**：产物 < 5 行即
  Abort，提示先做定位校验回捞再决定修正文件或中止）→ ENV_SCAN（命中只置
  「环境污染模式」附注，**永不中止**）→ TAG_FILTER（tagRegex 空 = 跳过；
  产物 0 行 Abort）。成功逐级打印 `stage=… outputFile=… note=…`；中止打印
  `aborted at stage=…: <原因>` 并非零退出。逐级 API（`runDecrypt` /
  `runTimeFilter` / `runEnvScan` / `runTagFilter`）供 agent 工具分步调用。
- `--targetTime` 同容忍空格分隔、省略毫秒与裸 `MM-dd HH:mm:ss.SSS`
  （`LogTimestamp.parseTarget` 语义——裸形态仅对裸格式日志保证正确，
  年份格式/跨年日志请补全年份）。
- **`filter_by_range`**：起止区间截取（引擎 = `LogTimeFilter.filterByRange`）——
  与 `filter_by_time` 的时间点 ±N 分钟对称窗口**互不替代**：奇数分钟区间
  （如 13:00~13:35 共 35 分钟）只有本型能精确表达。时间参数口径与 UI 侧
  palette 命令 `tools.slice-time` 逐字一致：容忍空格分隔、省略毫秒与裸
  `MM-dd HH:mm:ss.SSS`（xlog 自产明文行首同款，从日志直接复制即可——
  裸形态仅对裸格式日志保证正确，年份格式/跨年日志请补全年份），
  缺参、格式错、起止倒挂均拒绝（宁拒不猜）。产物 = 原文件旁
  `<文件名>.filter-time_HHmm-HHmm.txt`（与对称窗口同款命名），已存在拒绝、
  零时间锚点与零命中（时间行均在区间外）静默失效删产物报失败（镜像对称窗口语义）。
- **`diff`**：两份日志对比（引擎 = `LogDiff`：Myers O(ND) 对齐 + 行哈希流式扫描
  内存有界 + 归一化模式复用 `TimestampNormalizer`，契约见 `LogDiff` KDoc）。产物 =
  基准同目录 `<基准>.diff_<对比>.txt`（unified 格式，覆盖旧次输出）；编辑距离超
  `MAX_ALIGN_EDITS` 时退化整体替换并打 warning（建议 `--normalizeTimestamps`）。
  桌面消费面 = 「工具」页第 6 签（`composeApp/.../page/tools/logdiff/`——hunks
  结构化渲染、degraded 明示、导出复用 `writeUnified`）。
- **`stats`**：分布统计**纯输出型**（引擎 = `LogStatsAnalyzer.analyze`，零产物文件）——
  扫描行数 / 日志块总数 / 时间跨度（呈现格式 yyyy-MM-dd_HH:mm:ss.SSS，与
  `--targetTime` 入参口径一致；全文无时间行时如实呈现「无可识别时间行」不猜零值）/
  级别计数（V/D/I/W/E/F 全档呈现、缺席档显零 + 未识别级别块数）/ Tag 频次 Top 截断
  （默认前 30 项，余量恒明示；未识别 Tag 块数按引擎 KDoc 口径 = totalBlocks 减
  各项合计）。呈现语义与桌面统计面板、agent 工具 `log.stats` 同核同口径——写过滤
  正则前先看分布，免盲试。

### 与宿主页面的关系

CLI 与桌面 App 工具页（`:feature-logkit` 四胞胎 + `:composeApp` 日志对比签）消费
同一套库函数——行为契约以本篇为准，页面装配/覆盖确认卡/取消语义见
[pages/logkit-tools.md](pages/logkit-tools.md)。

---

## 附加能力面（utils/）

### SafeExtract — 压缩包解包（public，多域共享）

zip / tar / tar.gz / tgz / 单文件 .gz 解到目标目录。**纯 JDK 实现**
（ZipInputStream / 手写 ustar 512B header 解析 / GZIPInputStream，零第三方压缩库）。

- **zip-slip 防护**（`safeChild`）：条目名规范化——绝对路径、`..` 穿越、
  符号链接/硬链接条目一律拒绝（跳过该条目，不中止解包）。
  `:feature-archive` 的 7z/分卷路径与本规则逐字对齐单侧重放（SafeExtract
  是禁止重写块）。
- 🔴 **解压炸弹三闸**（超限抛 `ExtractLimitExceededException`，**不静默跳过**）：
  累计解出字节 2GiB、单条目压缩比 200:1、条目数 100,000；测试可经 `Limits`
  注入小阈值。
- 判定是否压缩包依据扩展名（`isArchive`），启用时机由调用方决定
  （宿主 agent 工具 `fs.extended.extract_archive`、桥接域 autoExtract、
  解压页命令转发三处消费）。

### ZstdUtils — 单文件 zstd 压缩/解压

`compressFile(targetFilePath, outDir, compressionLevel)`（分块 DirectBuffer +
`Zstd.compressDirectByteBuffer`，产物 `<name>.zs`）与 `decompressFile(in, out)`
（流式）。当前仓库内无生产消费面，作为 zstd-jni 直用工具保留。

---

## 测试面

`./gradlew :log-tools:test`；fat jar `./gradlew :log-tools:shadowJar`。

| 测试 | 守护面 |
|------|--------|
| `LogDecodeFileTest` / `LogDecodeCharacterizationTest` | 原子替换写（失败保旧产物）、.tmp 删净、密钥预校验先于输出打开、解码字节级行为 |
| `LogCodecRegistryTest` / `TeaCipherTest` / `TeaKeyResolverTest` / `EcdhKeyAgreementTest` | 标记→组合推导、TEA 算法语义、session 缓存、ECDH 协商往返 |
| `XLogChunkReaderTest` / `LogSpaceParserTest` | 流式窗口协议（半段压实/扩容/垃圾跳过/绝对偏移推进/尾段丢弃） |
| `LogFormatDetectorTest` / `LogProbeTest` | 内容判型三态、探测快照字段 |
| `PostProcessorPipelineTest` / `TimestampNormalizerTest` | 行级管道语义、时间戳归一化 |
| `LogTimestampContractTest` / `LogToolsEncodingTest` | 行首时间戳正则契约、库输出编码 |
| `LogAnalysisPipelineTest` | 五级管线/空结果守护/环境扫描不中止/Tag 可跳 |
| `SafeExtractTest` / `ZstdDecompressorTest` | zip-slip 与三闸、zstd 解压 |
| `LogDiffTest` / `LogViewerTest` / `LogStatsAnalyzerTest` / `LogTagFilterPreviewTest` | diff 引擎语义与 Myers/DP oracle 等价、查看器行偏移索引与窗口回读、分布统计、Tag 预演截断语义 |
| `LogPerfBaselineTest` | 大文件管线性能基线 harness：确定性 20 万行 fixture + 五引擎分段计时 + 数量级粗上界退化护栏（基线数字见下「性能基线」节） |

扫盘型/行为守护验收一律 `--rerun-tasks --no-build-cache`；本模块在桥接依赖链上
（引用文件解密），改桥接内核时的回归须连跑 `:log-tools:test`（见
[AGENTS.md](../AGENTS.md)「测试」节）。

## 性能基线（大文件管线，可复现 harness）

测量 harness = `LogPerfBaselineTest`（测试面表格末行）：确定性生成 **20 万行**
fixture（固定 seed 42，同机同内容；块 = 时间戳头行 + 0..3 行无时间戳续行，
ERROR 块带异常堆栈形态；约 8 万块），五个管线引擎分段计时，耗时数字输出到
测试 stdout（测试报告 `system-out` 亦可取数）。

**本节是粗上界护栏的推导依据，非精确基准**：下表数字为开发机实测，随机器与
负载漂移；测试断言口径 = **数量级粗上界**（阈值 = 实测基线 ×30 向上取整），
只拦算法级退化（如 O(n²) 改动），不断言精确毫秒（断精确值必 flaky）。失败
信息附实测值，红了先复测归因，不是改阈值。

| 引擎段 | 实测基线 | 粗上界断言（×30 取整） |
|--------|---------|----------------------|
| fixture 生成（20 万行写盘 ×2 份） | ~50ms | < 2s |
| `LogViewer.open`（20 万行偏移索引构建） | ~100ms | < 3s |
| `LogViewer.readWindow`（任意行号窗口回读 ×3） | ~40ms | < 1s |
| `LogTagFilter.filterByTagBlock`（全量块扫描 + 命中块写盘） | ~150ms | < 5s |
| `LogDiff.diff`（20 万行 ×2 份，3 处编辑，非归一化） | ~200ms | < 6s |
| `LogStatsAnalyzer.analyze`（全量单遍聚合） | ~470ms | < 15s |
| `LogTimeFilter.filterByTime`（中段 ±3 分钟窗口，提前结束路径） | ~80ms | < 3s |

实测口径：本仓开发机（Linux 台式机）冷跑轮次（含页缓存未热），Gradle `:log-tools:test`
单 JVM 内计时；fixture 经 companion 单例只生成一次。改引擎后复测：

```bash
./gradlew :log-tools:test --tests "com.jiaoay.log.LogPerfBaselineTest" --rerun-tasks --no-build-cache
```

阈值常量在该测试 companion object 内；改阈值须同步本表，本表数字以复测为准。
