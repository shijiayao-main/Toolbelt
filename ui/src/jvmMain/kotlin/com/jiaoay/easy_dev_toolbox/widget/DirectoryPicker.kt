package com.jiaoay.easy_dev_toolbox.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 系统目录选择器（Swing JFileChooser，仅目录模式）。
 *
 * 为什么用 Swing 而非 AWT FileDialog：Linux 上 FileDialog 的目录选择模式不可靠，
 * 统一走 JFileChooser(DIRECTORIES_ONLY)。
 * 观感：JFileChooser 默认 Metal 主题与系统原生对话框差距大，弹出前切系统 LookAndFeel
 * （Linux 上即 GTK 原生主题），与 XLog 页 AWT FileDialog 的原生观感对齐。
 *
 * 线程约定（调用方无需关心，依据要写清）：Compose Desktop 的 UI 线程即 AWT EDT，
 * 模态弹窗若直接在 UI 线程弹出会冻结整个界面。本挂起函数内部先 withContext(Dispatchers.IO)
 * 切出 UI 线程，再经 SwingUtilities.invokeAndWait 把 JFileChooser 的创建与模态显示派回
 * EDT 执行（Swing 单线程规则）：IO 线程仅阻塞等待结果，Compose UI 线程全程不被阻塞；
 * 函数返回后调用方协程恢复在原上下文（主线程），可安全回填 Compose 状态。
 *
 * @param title 对话框标题
 * @return 选中的目录（绝对路径）；取消或关闭时返回 null（owner 缺失时以无 owner 模态弹出，不影响功能）
 */
suspend fun pickDirectory(title: String = "选择目录"): File? = withContext(Dispatchers.IO) {
    val owner = java.awt.Window.getWindows()
        .filterIsInstance<java.awt.Frame>()
        .firstOrNull()
    runCatching {
        var selected: File? = null
        SwingUtilities.invokeAndWait {
            // 弹出前切系统 LookAndFeel（Linux 即 GTK 原生主题）：默认 Metal 观感与原生对话框
            // 差距大（用户反馈「丑」）；缺 GTK 库等场景失败时静默回退默认观感，不影响功能
            runCatching {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
            }
            val chooser = JFileChooser()
            chooser.dialogTitle = title
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.isMultiSelectionEnabled = false
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                selected = chooser.selectedFile?.takeIf { it.isDirectory }
            }
        }
        selected
    }.getOrNull()
}
