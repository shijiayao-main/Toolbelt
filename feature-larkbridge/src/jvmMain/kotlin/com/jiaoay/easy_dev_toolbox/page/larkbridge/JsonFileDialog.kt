package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JSON 文件选择对话框（导入 / 导出用），镜像 `:ui` 的 `pickDirectory` 的 Swing 互操作写法。
 *
 * 🔴 [JFileChooser] 是阻塞 AWT 调用，必须在 [Dispatchers.IO] + [SwingUtilities.invokeAndWait] 内执行
 * （在 UI 线程直接调用会冻结界面事件循环）；
 * 返回 null = 用户取消或异常（runCatching 吞掉，不阻断 UI）。
 *
 * @param save true = 保存对话框（导出）；false = 打开对话框（导入）
 */
suspend fun pickJsonFile(save: Boolean, title: String): File? = withContext(Dispatchers.IO) {
    val owner = java.awt.Window.getWindows()
        .filterIsInstance<java.awt.Frame>()
        .firstOrNull()
    runCatching {
        var selected: File? = null
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.FILES_ONLY
            }
            val result = if (save) chooser.showSaveDialog(owner) else chooser.showOpenDialog(owner)
            if (result == JFileChooser.APPROVE_OPTION) selected = chooser.selectedFile
        }
        selected
    }.getOrNull()
}

/**
 * 用系统文件管理器打开目录（「打开配置目录」操作）。只读打开，不创建、不写入任何文件。
 * 目录不存在或平台不支持时静默失败（runCatching），不抛给 UI。
 */
fun openInFileManager(path: java.nio.file.Path) {
    runCatching {
        val file = path.toFile()
        if (java.awt.Desktop.isDesktopSupported() && file.exists()) {
            java.awt.Desktop.getDesktop().open(file)
        }
    }
}
