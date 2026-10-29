# 设备域与内置 adb

设备功能（Shell 工具页的 adb 命令、设备观测的 logcat / 投屏 / 文件 / 应用 / 布局 / 性能 / 按键）
原先依赖用户本机自装的 adb。本域把 adb 随包分发，并让设备能力脱离宿主可独立使用。

## 模块

| 模块 | 包名 | 定位 |
|------|------|------|
| `:adb-core` | `com.jiaoay.toolbelt.adb` | 平台判定 / 端点解析 / 二进制释放 / 命令重写 |
| `:device-core` | `com.jiaoay.toolbelt.device` | 设备检测与全部 adb 内核（文件/包/布局/性能/按键/无线） |

两者都是 jvmTarget 11 纯 JVM lib，**零宿主依赖**：命令执行、日志、协程作用域、配置读取、
产物目录全部由调用方注入。宿主的适配件在 `:core`：
`core/device/SystemAdbProcessRunner.kt`（执行通道实现）与 `core/device/DeviceHub.kt`（单源装配）。

🔴 包名必须是 `com.jiaoay.toolbelt.*` 而不是宿主的 `com.jiaoay.easy_dev_toolbox.*`：
有 `src/main/kotlin` 源集的模块会被 `ScanSurfaceSelfCheckTest` 自动判定为 jvm11 lib，
而 `LarkSdkIsolationTest` 的 D 组禁止 lib 引用宿主包前缀（宿主 jvmTarget 25，反向依赖编译不过）。

## 内置 adb 的分发链路

```
构建期：prepareBundledAdb
  └── 下载 platform-tools_r<版本>-{win,linux,darwin}.zip（三源依次尝试，sha256 校验）
      └── 只解出 adb（Windows 加 AdbWinApi.dll / AdbWinUsbApi.dll）+ NOTICE.txt
          └── 落 adb-core/src/main/resources/adb/<平台>/ + 生成 manifest.properties
              （版本、文件清单、逐文件 sha256——运行期释放器据此校验）

运行期：AdbEndpointResolver.resolve()
  ├── 内置就绪 → AdbEndpoint(释放产物路径, 5038)   ← AdbBinaryReleaser 释放到 ~/.toolbelt/adb/v<版本>/
  └── 内置不可用 → null + lastIssue 人话原因（由状态栏/设置页呈现）
```

**只有一支**：不提供系统 PATH 回退，也不提供自定义路径配置。桌面应用以用户身份运行、
写的是自己的数据目录，释放失败面只剩磁盘写满一类极端情况——为它保留一条隐式搜索路径，
换来的是「用户看到的行为取决于机器上装了哪个版本的 adb」这类不可控差异。需要用自己的
adb 的开发者直接用终端即可；本工具的价值在于把多条命令编排好一次执行。

**为什么用专用端口**：同一个端口上不同版本的 adb client 会互相结束对方的 server
（`adb server version doesn't match this client`），内置 adb 用专用端口才能与用户机器上既有的
adb 生态（IDE、终端）并存。

**端口注入走进程环境而非命令串**：`ShellCommandBuilder.applyShellEnv` 统一写
`ANDROID_ADB_SERVER_PORT`，覆盖 `SimpleShell` / `StreamingShell` / `InteractiveShell` 全部通道；
`ArgvProcessRunner`（agent 的 argv 通道）单独补同一份注入。命令文本因此与「用不用内置 adb」
无关，历史命令可原样回放。

## 释放语义

- 落点 `<数据根>/adb/v<版本>/`，版本来自随包清单；版本升目录升。
- 首次释放做全量 sha256 校验；通过后进程内记住结论，此后只做 `isFile` 判断——解析端每 5 秒
  轮询一次，逐次重算十余 MB 哈希不可接受。
- **升级不冲突**：旧版本目录是本应用自己释放的产物（靠目录内的 `manifest.properties` 印记
  识别），升级时自动清理；只有认不出的用户文件才报 Conflict 并给处置建议。
- 启动期由 `BundledAdbWarmupInitializer`（低优组）预热一次；失败不阻断启动，但设备功能
  不可用——原因经 `DeviceHub.endpointUnavailableReason` 送达设置页与设备状态栏。
  🔴 该初始化器恒返回 true：初始化器失败会让 `InitResult.Failed` 落地，而主窗对**任何**
  Failed 都渲染整窗「初始化失败 + 退出应用」——adb 释放失败绝不该把应用顶掉。

## 退出收口

`AdbServerShutdownParticipant` 在应用退出时执行 `adb -P <专用端口> kill-server`，只结束自己那个
端口的 server。注册顺序必须在投屏/抓取等会话参与者之后——那些收尾要用 server（隧道清理、
显示触点恢复）。

## 设备问题态

`adb devices -l` 里状态不是 `device` 的行（`unauthorized` / `offline` / `no permissions (...)`
等）归入 `DeviceIssue`，与可用的 `DeviceState` 分开：它们不能执行任何命令，但正是
「插上了却连不上」的答案。设备状态栏在「未连接」态优先展示这些原文。

🔴 解析时注意两点：`adb devices` 输出的串号与状态之间是 **TAB** 而非空格；`no permissions`
这类状态是**多词**（含括号与分号），按空白切词取首项会把它截成 `no`，再也匹配不上任何引导分支。

## 构建期下载

任务 `:adb-core:prepareBundledAdb`（资源不入库，`.gitignore` 已忽略）。三个源依次尝试，
sha256 逐字节一致：

1. `https://dl.google.com/android/repository`
2. `https://googledownloads.cn/android/repository`
3. `https://mirrors.cloud.tencent.com/AndroidSDK`

钉的是版本化 URL（`platform-tools_r37.0.1-{win,linux,darwin}.zip`）而非 latest——latest 的内容
随官方更新静默变化，校验和会失效。注意版本化归档用缩写 `win`，latest 链接用全称 `windows`。

离线或下载失败的补齐方式见 [core/libs/adb/README.md](../../core/libs/adb/README.md)。

## 许可边界

只随包分发 `adb`（Windows 加两个 DLL）与 `NOTICE.txt`。platform-tools 归档里的
`mke2fs` / `make_f2fs` / `make_f2fs_casefold` 属 e2fsprogs / f2fs-tools 的 GPL 系许可，超出
「内置 adb」的分发边界，不随包分发。登记见仓库根 `NOTICE`。

## 已知平台差异

| 平台 | 说明 |
|------|------|
| Windows | 官方只发 x86 归档，ARM 版 Windows 走 x64 模拟；非 Google VID 的设备需 OEM USB 驱动 |
| Linux | 官方无 arm64 归档，构建期在该平台直接失败；非 root 访问设备需 udev 规则 |
| macOS | darwin 包是 x86_64+arm64 通用二进制；经 jar 释放的二进制不带 quarantine 标记，不触发 Gatekeeper |

USB 权限层（Windows 驱动 / Linux udev）不在应用内解决，设备连接失败时由设备状态栏给出人话
原因与无线调试备选路径。
