# native 内存与 glibc malloc 调参（NativeAllocatorTuner）

> 主题：投屏/解码链多 MB 级 native 分配导致 RSS 高水位棘升的机制与修复。
> 生产件：`core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/system/NativeAllocatorTuner.kt`（FFM 直调 `mallopt`，main() 第一行生效）。
> 取证基线：2026-10-14 首轮排查（定谳主因）+ 位图池化后复测，两轮结论合流见下文「两轮取证合流」节；原始报告为本机留档件，不入库。

---

## 机制链（为什么 Java 堆正常、RSS 却驻留数 GB）

**信号特征**：`jcmd <pid> GC.heap_info` 显示 Java 堆 used 仅 ~137MB（上限 2GB），而进程 RSS 高达 GB 级且停止增长后不回落；`/proc/<pid>/smaps` 里是**上百个 64MB 地址对齐的 ~53MB 匿名映射块**——这是 glibc per-thread malloc arena 的指纹（每 arena 64MB 预留、mprotect 提交其中一部分）。

成因四环，环环叠加：

1. **每帧多 MB 级 native 分配/释放对**：投屏解码链（skia `installPixels` 像素缓冲、ffmpeg 帧缓冲、sws 换算）以 20-30fps 频率分配又释放 2.5MB 级缓冲。位图池化（`ScreencastFrameBitmapPool`，见 `feature-screencast/.../ScreencastDecoder.kt` 类 KDoc）已把**活内存**上界压到 3 槽 × 单帧缓冲——Java 堆全程 ~100MB 是其成立的证据。
2. **glibc 动态 mmap 阈值棘升**：glibc 默认凡释放过一个直接 mmap 的块，就把 mmap 阈值动态上浮（上限 32MB）。阈值一旦越过帧缓冲尺寸（2.5-8MB），这类分配从「mmap 分配、free 即 munmap 还 OS」退化为「走 arena、free 后滞留空闲链表」。
3. **分配/释放跨线程**：解码线程 alloc、skiko Cleaner 线程 free——glibc 为避锁竞争给不同线程各开 arena，默认上界 8 × CPU 核数（20 核机即 ~160 个；池化版本实测观察 ~180 个块）。
4. **arena 只涨不还**：arena 内被释放的块进空闲链表，不主动归还 OS（`M_TRIM_THRESHOLD` 只作用于堆顶）。每 arena 高水位相乘（~180 × ~53MB ≈ 9.5GB）= RSS 驻留，且**停投屏也不回落**——只有重启进程能释放。

**两轮取证合流**：首轮排查定谳的第一主因（每帧新建 Bitmap 依赖 phantom Cleaner + GC 节奏释放，native 分配率 50MB/s 对 GC 分钟级节奏隐形）已由位图池化修复（释放改为 SkPixelRef 引用计数当帧确定性）。池化版本复测（RSS 稳 11.4GB、15 秒零增长）证明剩余根因**全部落在上述 glibc 分配器层**——首轮排查当时把 allocator 调参列为「辅助」，是在 GC 蓄水主因前提下的降级表述；主因消除后它升级为主修复面。

## 修复契约（NativeAllocatorTuner）

启动期经 FFM（`java.lang.foreign`，JVM 25 自带、`--enable-native-access=ALL-UNNAMED` 已在 composeApp jvmArgs）直调 glibc `mallopt(int, int)`：

| 参数 | 值 | 依据与效果 |
|------|-----|-----------|
| `M_MMAP_THRESHOLD` | 131072（128KB） | glibc 静态默认值；**mallopt 显式设置即关闭动态上浮**——≥128KB 分配恒走 mmap，free 即 munmap 还 OS。主修复面。 |
| `M_ARENA_MAX` | 2 | 默认 8 × CPU 核数；压到 2 堵住小分配的 arena 滞留面兜底。代价 = 至多 2 个 arena 锁竞争，桌面负载无感。 |

行为契约（违反即破坏修复或启动）：

- **main() 第一行调用**，须先于一切 skia/JavaCV 原生分配——mallopt 只影响后续分配，已建 arena 与已抬阈值不回溯。
- **平台门控**：mallopt 仅 glibc（Linux）存在，非 Linux 静默跳过（投屏 native 随构建平台自适应选 classifier，与本门控无关）。
- **降级不阻断**：native 调用整段 runCatching，失败只记 WARN——调参是优化面，不许把启动赌在它上面。
- **幂等**：重复 install 只是重复设置同值。
- **FFM 收口单点**：全仓生产源码里 `java.lang.foreign`/`SymbolLookup`/`FunctionDescriptor`/`downcallHandle` 只许出现在 `NativeAllocatorTuner.kt`，由 `composeApp/src/jvmTest/.../arch/ForeignLinkerIsolationTest.kt` 守护（同 LarkSdkIsolationTest/SherpaOnnxIsolationTest 模式）。

## 验收口径

投屏运行 10 分钟（10s 间隔采样 RSS），曲线斜率 ≈ 0（±50MB 抖动内）；Java 堆 used 无变化（~100MB）。附加观测面：`/proc/<pid>/smaps` 中 64MB 对齐匿名块应从 ~180 个降到个位数（arena 数被压到 2）。

## 相关面

- `-Xmx2g` 堆上限真源：`composeApp/build.gradle.kts` 的 `jvmArgs`（dev-run 与打包共用）。
- 位图池契约（native 活内存上界）：`ScreencastDecoder.kt` 类 KDoc + `ScreencastFrameBitmapPoolTest`。
- 环境变量路线（`MALLOC_ARENA_MAX`/`MALLOC_MMAP_THRESHOLD_`）被否决的原因：jpackage 原生启动器（ELF）无注入点，dev-run/打包/任意启动方式无法统一覆盖；进程内 mallopt 单一代码点全覆盖。
