package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AWT 系统文件选择器（FileDialog——各平台原生外观；[pickDirectory] 的文件选择对偶）。
 *
 * 线程约定与 [pickDirectory] 同：本挂起函数内部切 [Dispatchers.IO]（模态阻塞不冻结
 * Compose UI 线程），返回后调用方协程恢复原上下文。owner 取当前 ComposeWindow
 * （Frame 子类——本仓既有 4 份拷贝的统一配方）。
 *
 * 收敛说明：全仓 pickFiles/pickParamFile/pickAdbExecutable/pickLogFile 类文件选择
 * 统一走本件（勿再散写同配方拷贝）。
 */
suspend fun pickOpenFiles(title: String, multiple: Boolean = false, filter: (File) -> Boolean = { true }): List<File> =
    withContext(Dispatchers.IO) {
        val owner = java.awt.Window.getWindows()
            .filterIsInstance<java.awt.Frame>()
            .firstOrNull() ?: return@withContext emptyList()
        val dialog = java.awt.FileDialog(owner, title, java.awt.FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        runCatching { dialog.isVisible = true }
        val selected = dialog.files ?: return@withContext emptyList()
        selected.filter { it.exists() && filter(it) }
    }

suspend fun pickSaveFile(title: String, suggestedName: String? = null): File? = withContext(Dispatchers.IO) {
    val owner = java.awt.Window.getWindows()
        .filterIsInstance<java.awt.Frame>()
        .firstOrNull()
    val dialog = java.awt.FileDialog(owner, title, java.awt.FileDialog.SAVE)
    if (suggestedName != null) dialog.file = suggestedName
    runCatching { dialog.isVisible = true }
    val fileName = dialog.file ?: return@withContext null
    val directory = dialog.directory ?: return@withContext null
    File(directory, fileName)
}
