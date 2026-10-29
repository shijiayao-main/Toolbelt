package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import kotlin.random.Random

/**
 * 按下时刻设备端实拍（快照真源）：`adb shell screencap` 落设备临时文件 → `adb pull`
 * 到本地产物 → 设备端清理。
 *
 * 为什么不取预览流最新帧：预览帧经「设备采集 → 编码 → 网络 → 解码 → 发布」整条管线，
 * 相对设备真实屏幕恒滞后（解码积压上界 16 帧），且分辨率跟随预览 maxSize 档（非原生）；
 * 实拍直接取设备当下屏幕，恒为按下瞬间内容 + 原生分辨率——「截图 = 当前投屏屏幕」的
 * 语义由本件承载。
 *
 * 失败契约：任一步失败（screencap 非零退出 / pull 失败 / 产物缺失或非 PNG）返回
 * false 并清理本地半拉残件——调用方回退预览帧路径，快照不静默失败。设备端临时文件
 * 清理为尽力语义（rm 失败不阻塞回执，残留由下次 adb 重连或人工清理）。
 *
 * 路径契约：设备端临时文件落 `/sdcard`（shell 用户可写，与设备文件域同源根），文件名
 * 含时钟毫秒 + 随机后缀防同毫秒连拍互踩；本地产物路径由调用方整形（无空白字符——
 * pull 命令行不引号包裹）。
 *
 * 线程模型：调用方须在 IO 调度器（adb 往返秒级）。
 */
internal class ScreencastDeviceSnapshot(
    private val gateway: ScrcpyAdbGateway,
    private val clock: () -> Long,
) {

    /**
     * 实拍到 [target]（本地产物路径，命名归调用方）。成功 = [target] 为合法 PNG；
     * 失败恒返回 false 且 [target] 不留残件。清理命令在判成败之前发（成败判定看本地
     * 产物，不看 adb 回显）。
     */
    suspend fun capture(target: File): Boolean {
        val remote = "$REMOTE_DIR/$REMOTE_PREFIX${clock()}_${randomSuffix()}.png"
        if (gateway.execute("adb shell screencap -p $remote").getOrNull()?.exitCode != 0) {
            return false
        }
        val pulled = runCatching {
            gateway.execute("adb pull $remote ${target.absolutePath}").getOrNull()?.exitCode == 0
        }.getOrDefault(false)
        runCatching { gateway.execute("adb shell rm -f $remote") }
            .onFailure { JLog.d(TAG) { "snapshot remote cleanup failed: $remote (${it.message})" } }
        if (!pulled || !target.isFile || target.length() == 0L || !isPng(target)) {
            runCatching { target.delete() }
            return false
        }
        return true
    }

    private fun isPng(file: File): Boolean {
        file.inputStream().use { stream ->
            val head = ByteArray(PNG_MAGIC.size)
            var read = 0
            while (read < head.size) {
                val count = stream.read(head, read, head.size - read)
                if (count < 0) return false
                read += count
            }
            return PNG_MAGIC.contentEquals(head)
        }
    }

    private fun randomSuffix(): String = Random.nextInt(0x10000).toString(16)

    private companion object {
        const val TAG = "ScreencastDeviceSnapshot"
        const val REMOTE_DIR = "/sdcard"
        const val REMOTE_PREFIX = "toolbelt_snap_"
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
