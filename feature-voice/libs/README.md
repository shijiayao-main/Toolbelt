# feature-voice/libs —— sherpa-onnx jar（不入库）

本目录下的 5 个 jar 是 **git 忽略的构建产物**（`.gitignore` 规则 `feature-voice/libs/*.jar`），
首次构建时由 `:feature-voice` 的 Gradle 任务 `downloadSherpaJars` 自动从
[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) v1.13.8 release
下载并做 sha256 校验；缺失或损坏时自动重下。历史沿革：曾整体入库（2026-09-19），
2026-09-20 经 git 历史手术剥离出库（~39MB → 仓库瘦身至 ~5MB）。

## 清单（版本 1.13.8，sha256）

| 文件 | sha256 |
|------|--------|
| `sherpa-onnx-jvm-1.13.8.jar` | `77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b` |
| `sherpa-onnx-native-lib-linux-x64-1.13.8.jar` | 完整值见 `feature-voice/build.gradle.kts` 常量表 |
| `sherpa-onnx-native-lib-osx-aarch64-1.13.8.jar` | 同上 |
| `sherpa-onnx-native-lib-osx-x64-1.13.8.jar` | 同上 |
| `sherpa-onnx-native-lib-win-x64-1.13.8.jar` | 同上 |

> 完整 sha256 常量表以 `feature-voice/build.gradle.kts`（`sherpaJars` map）为唯一真源，
> 本 README 只做来历说明，避免两处维护漂移。

## 手动补齐（直链失效/离线环境兜底）

```bash
cd <repo>/feature-voice/libs
base=https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8
for f in sherpa-onnx-jvm sherpa-onnx-native-lib-linux-x64 \
         sherpa-onnx-native-lib-osx-aarch64 sherpa-onnx-native-lib-osx-x64 \
         sherpa-onnx-native-lib-win-x64; do
  curl -fsSL -o "$f-1.13.8.jar" "$base/$f-1.13.8.jar"
done
# 校验：sha256sum -c 对照 build.gradle.kts 常量表（或跑 ./gradlew :feature-voice:downloadSherpaJars 自动校验）
```

## 注意

- 离线 `--offline` 构建在缺 jar 时会失败（需要联网首拉，或按上表手动放置）。
- 版本升级 = 改 `build.gradle.kts` 常量表（文件名 + sha256 + URL 版本号），jar 会自动重下。
