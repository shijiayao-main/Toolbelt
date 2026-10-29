package com.jiaoay.easy_dev_toolbox.core.system

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * glibc malloc 运行时调参（启动期一次性 install，FFM 直调 `mallopt`，零第三方依赖）。
 *
 * 为什么需要：投屏/解码链每帧产生多 MB 级 native 分配/释放对（skia 像素缓冲、ffmpeg
 * 帧缓冲），glibc 的**动态 mmap 阈值**被历史释放一路上浮后，这类分配迁入 per-thread
 * arena；而分配与释放常跨线程（解码线程 alloc、skiko Cleaner 线程 free），被释放块
 * 滞留 arena 空闲链表不还 OS——arena 数默认上界 = 8 × CPU 核数（多核机即上百个），
 * 高水位相乘后进程 RSS 可驻留数 GB **已释放**内存（Java 堆正常、RSS 不回落即此形态；
 * 机制链与取证详见 docs/core/native-memory.md）。
 *
 * 两参数契约（值 + 单位 + 依据）：
 * - `M_MMAP_THRESHOLD = 131072`（128KB，glibc 静态默认值）：mallopt 显式设置即
 *   **关闭动态上浮**（glibc mallopt 契约）——≥128KB 的分配恒走 mmap，free 即 munmap
 *   归还 OS，RSS 跟随活内存而非分配器高水位；本参数是主修复面。
 * - `M_ARENA_MAX = 2`（默认 8 × CPU 核数）：压制小分配的 arena 滞留面兜底；代价 =
 *   至多 2 个 arena 锁的竞争，桌面负载下无感。
 *
 * 启动契约：main() 第一行调用，须先于一切 skia/JavaCV 原生分配（后到者无效——已建
 * arena 与已抬阈值不回溯）；平台门控非 Linux 静默跳过（mallopt 仅 glibc 存在，
 * macOS/Windows 分配器无此参数面）；native 调用整段 runCatching——调参失败只降级
 * （记 WARN 日志），绝不阻断启动。
 */
object NativeAllocatorTuner {

    private const val TAG = "NativeAlloc"

    /** glibc malloc.h 参数号（稳定 ABI 值）：M_MMAP_THRESHOLD */
    private const val M_MMAP_THRESHOLD = -3

    /** glibc malloc.h 参数号（稳定 ABI 值）：M_ARENA_MAX */
    private const val M_ARENA_MAX = -8

    /** ≥128KB 恒走 mmap（glibc 静态默认值 128KB，显式设置钉死即关动态上浮） */
    private const val MMAP_THRESHOLD_BYTES = 131072

    /** arena 上限 = 2（默认 8 × CPU 核数，多核机上百个；兜底压制滞留面） */
    private const val ARENA_LIMIT = 2

    /**
     * 平台门控：mallopt 仅 glibc（Linux）存在。
     * null（os.name 缺失）与非 Linux 平台一律不支持（[install] 静默跳过）。
     */
    internal fun isSupportedPlatform(osName: String?): Boolean =
        osName?.lowercase()?.let { "linux" in it } == true

    /**
     * 应用调参（幂等——重复调用只重复设置同值，无副作用）。
     *
     * @return true = 主修复面（mmap 阈值钉死）生效；false = 平台不支持或 native 调用
     *   降级失败（两个方向都不抛异常——见类 KDoc 启动契约）
     */
    fun install(): Boolean {
        if (!isSupportedPlatform(System.getProperty("os.name"))) return false
        val applied = runCatching { tuneWithMallopt() }
            .onFailure { e ->
                JLog.w(TAG) { "glibc malloc 调参降级（native 调用失败，RSS 高水位面无修复）：${e.message}" }
            }
            .getOrNull()
        return applied == true
    }

    /** FFM 直调 mallopt：主修复面（mmap 阈值）生效即算成功；arena 上限失败仅记日志 */
    private fun tuneWithMallopt(): Boolean {
        val lookup = SymbolLookup.libraryLookup("libc.so.6", Arena.global())
        val symbol = lookup.find("mallopt").orElse(null) ?: return false
        val mallopt = Linker.nativeLinker().downcallHandle(
            symbol,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
        val mmapPinned = mallopt.invoke(M_MMAP_THRESHOLD, MMAP_THRESHOLD_BYTES) as Int == 1
        val arenaCapped = mallopt.invoke(M_ARENA_MAX, ARENA_LIMIT) as Int == 1
        if (mmapPinned) {
            JLog.i(TAG) {
                "glibc malloc 调参生效：M_MMAP_THRESHOLD=${MMAP_THRESHOLD_BYTES}B 钉死（动态上浮关闭）" +
                    if (arenaCapped) "，M_ARENA_MAX=$ARENA_LIMIT" else "（M_ARENA_MAX 设置失败，仅小分配滞留面无兜底）"
            }
        }
        return mmapPinned
    }
}
