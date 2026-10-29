package com.jiaoay.easy_dev_toolbox.core.data

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.awt.Desktop
import java.io.File

/**
 * 桌面环境工具：收敛各页面重复的"打开文件夹"实现。
 */
object DesktopUtils {

    private const val TAG = "DesktopUtils"

    /** 打开 [path] 所在文件夹（文件管理器），失败静默记日志 */
    fun openInFolder(path: String) {
        runCatching {
            val dir = File(path).parentFile
            if (dir != null && dir.exists()) {
                Desktop.getDesktop().open(dir)
            }
        }.onFailure { e ->
            JLog.e(TAG, e) { "openInFolder failed: $path" }
        }
    }

    /**
     * 打开文件本体（系统默认程序），失败降级 [openInFolder]。
     *
     * 兜底链：`.md` 等扩展名在系统无关联程序时 `Desktop.open(File)` 抛 IOException
     * ——onFailure 一次 JLog 落盘 + 降级打开所在目录，避免无关联环境按钮全哑
     * （openInFolder 内部自持 runCatching，降级失败同样静默有日志——两级容错）。
     */
    fun openFile(path: String) {
        runCatching {
            Desktop.getDesktop().open(File(path))
        }.onFailure { e ->
            JLog.e(TAG, e) { "openFile failed: $path" }
            openInFolder(path)
        }
    }
}
