# 内置 adb 二进制

`:adb-core` 在构建期从 Google 官方下载 Android platform-tools，只解出 `adb`（Windows 加
`AdbWinApi.dll` / `AdbWinUsbApi.dll`）与 `NOTICE.txt`，落到本模块的
`src/main/resources/adb/<平台>/`，随 jar 打包；运行期由 `AdbBinaryReleaser` 释放到
`~/.toolbelt/adb/v<版本>/` 再执行。

**这些文件不入库**（`.gitignore` 已忽略），正常构建会自动下载。

## 自动下载

```bash
./gradlew :adb-core:prepareBundledAdb
```

任务按构建机平台取对应归档，依次尝试三个源（任一个成功即停，校验值三元一致）：

| 顺序 | 源 |
|------|-----|
| 1 | `https://dl.google.com/android/repository` |
| 2 | `https://googledownloads.cn/android/repository` |
| 3 | `https://mirrors.cloud.tencent.com/AndroidSDK` |

## 手动补齐（离线 / 内网 / 下载持续失败）

1. 下载 `platform-tools_r37.0.1-{win,linux,darwin}.zip`（注意：版本化归档用缩写 `win`）。
2. 放到 `adb-core/build/adb-download/` 目录下（目录不存在则新建）。
3. 重新执行 `./gradlew :adb-core:prepareBundledAdb`。

也可直接把解出的文件放进 `adb-core/src/main/resources/adb/<平台>/`，但**必须**同时提供
`manifest.properties`（运行期据此校验）：

```
version=37.0.1
platform=<windows|linux|darwin>
files=adb.exe,AdbWinApi.dll,AdbWinUsbApi.dll,NOTICE.txt
sha256.adb.exe=<sha256>
sha256.AdbWinApi.dll=<sha256>
sha256.AdbWinUsbApi.dll=<sha256>
sha256.NOTICE.txt=<sha256>
```

Linux / macOS 的 `files` 为 `adb,NOTICE.txt`。

## 为什么只取这几个文件

platform-tools 归档里还有 `mke2fs` / `make_f2fs` / `make_f2fs_casefold` 等文件，分属
e2fsprogs / f2fs-tools 的 GPL 系许可，超出「内置 adb」的分发边界，不随包分发。

## Windows 归档的平台差异

官方只发布 x86 的 Windows 归档；Windows on ARM 走 x64 模拟执行。USB 驱动层在 ARM 设备上
的兼容性未验证。

## Linux 只有 x86-64

官方不提供 Linux arm64 归档，构建期在该平台直接失败（不产出运行期才缺 adb 的安装包）。
