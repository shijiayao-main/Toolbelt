# Toolbelt

**Toolbelt** 是一个 Kotlin Compose Desktop（JVM）开发者工具箱桌面应用，主打移动与嵌入式设备日志分析，并集成设备观测（投屏 / logcat / 性能采样）与飞书桥接能力。

<p>
  <em>拖入加密日志 → 解密、过滤、截取、对比 → AI 研判产出报告 → 一键送飞书。
  连上 Android 设备 → 投屏操控、抓 logcat、性能采样、布局检查，一站完成。</em>
</p>

## 功能特性

### 日志分析

- **日志工作台**：AI 研判日志（分析输入 → 证据流 → 分析报告），报告可导出为可分享 Markdown、一键发送飞书
- **XLog 解密**：`.xlog` / `.alog` 加密日志一键解密，支持目录递归批量
- **Tag 过滤**：正则预演 + 分布统计，按 Tag 块过滤日志
- **时间截取**：按起止时刻切出问题区间日志段
- **日志查看器**：行号 / 时间戳 / 级别跳转、文本搜索、大文件流畅浏览
- **其他**：大文件分割、双日志时间戳归一化 diff 对比
- **管线衔接**：各工具产物右键直送下一工具 / AI 研判 / 飞书

### 设备观测

- **投屏**：Android 实时投屏独立窗，多指手势（捏合 / 旋转 / 平移）、录屏、快照、设备键遥控、文本注入、画质档位切换
- **logcat**：实时抓取落盘，tag / 级别过滤抓取中可改，复现点标记，停止后带锚点直达
- **性能采样**：CPU / 内存实时曲线
- **布局检查器**：全窗口 View 层级树 + 预览图，类 Android Studio Layout Inspector 形态
- **文件 / 应用面板**：设备文件浏览拉取、应用列表 / 详情 / 快照对比

### 工具与集成

- **Shell 工具**：adb / shell 快捷指令一键执行，自定义命令与执行历史回放
- **压缩包解压**：zip / tar / tar.gz / 7z 及多种分卷格式拖入即解
- **飞书桥接**：官方 SDK 长连接接入，单一 JSON 配置文档 + Compose 编辑器，消息直达飞书群
- **小工具**：时间戳转换、知识库蒸馏、语音转写（sherpa-onnx 离线转写）、MCP 服务外放

## 下载

前往 [Releases](https://github.com/shijiayao-main/Toolbelt/releases) 获取最新版本：

| 平台 | 安装包 |
|------|--------|
| Linux (deb) | `Toolbelt-<版本>.deb` |
| Windows (msi) | `Toolbelt-<版本>.msi` |
| macOS (dmg) | `Toolbelt-<版本>-<arch>.dmg` |

> 仓库名若已从 EasyDevToolbox 重命名，旧链接会自动跳转。

## 系统要求

- JDK 25（Gradle 工具链经 `gradle/gradle-daemon-jvm.properties` 自动下载，无需手动安装）
- Linux / macOS / Windows
- 可选外部工具：`adb`（设备功能）、`ffmpeg`（非 wav 音频转写）、已配置 PATH 的 shell

## 构建与运行

```bash
./gradlew :composeApp:run                        # 运行桌面应用
./gradlew build                                  # 全模块构建（含测试）
./gradlew :composeApp:packageDistributionForCurrentOS   # 打包本机安装包
```

## 文档

- **用户手册**：[docs/manual/](docs/manual/) ——按功能模块划分、照着即会的使用说明
- **知识库**：[docs/index.md](docs/index.md) ——架构 / 约定 / 各专题文档总索引
- **变更规格归档**：[openspec/changes/archive/](openspec/changes/archive/)

## 贡献

欢迎 issue 与 PR。提交前请阅读 [AGENTS.md](AGENTS.md)——本仓库有一组**架构守护测试**（UI 令牌 / 卡片配方 / SDK 隔离 / 注释纪律 / 工具围栏等），改动会触发对应守护测试校验：

```bash
./gradlew :composeApp:guardTests :core:guardTests --rerun-tasks --no-build-cache   # 守护面快速通道
./gradlew build                                                                    # 全量构建（含测试）
```

UI 改动遵循 [docs/ui-style-guide.md](docs/ui-style-guide.md) 的令牌体系；改动了用户可感知的功能后，知识库（docs/）与用户手册（docs/manual/）需同步更新。安全漏洞请勿开公开 issue 报告，见 [SECURITY.md](SECURITY.md)。

## 技术栈

- [Kotlin](https://kotlinlang.org) + [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) Desktop
- Gradle 多模块（`composeApp` / `core` / `ui` / 各 `feature-*` / `lark-bridge` / `lark-gateway` / `lark-ws` / `log-tools`）
- kotlinx.serialization / kotlinx.coroutines / SQLDelight / 官方飞书 OpenAPI SDK
- 投屏解码栈：JavaCV（FFmpeg LGPL 构建）；语音转写：sherpa-onnx

## 许可证

本项目以 [Apache License 2.0](LICENSE) 发布，随包分发信息见 [NOTICE](NOTICE)。

随包分发的 `scrcpy-server` 来自 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)（Apache License 2.0），在本应用投屏功能启动时自动释放到用户数据目录。
