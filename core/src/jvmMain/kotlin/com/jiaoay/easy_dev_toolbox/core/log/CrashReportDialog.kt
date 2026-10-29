package com.jiaoay.easy_dev_toolbox.core.log

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * 崩溃诊断报告对话框（Swing 形态，呈现唯一入口）。
 *
 * 为什么是 Swing 对话框而不是 Compose 覆盖层：覆盖层依赖「组合仍活着、重组仍可达」
 * ——而未捕获异常恰好可能摧毁组合（渲染期异常）或发生在窗口存在之前（启动期）/
 * 后台线程；Swing 对话框独立于 Compose 场景，CMP 窗口本身是 AWT 窗口，与本对话框
 * 共用同一事件泵，三条路径（EDT 渲染期异常 / 启动早期异常 / 后台线程异常）下
 * 均可达。这是崩溃兜底的核心诉求：呈现通道不能依赖可能已经坏掉的东西。
 *
 * 线程契约：可在任意线程调用——EDT 上直接呈现（模态对话框在 EDT 上开副事件循环），
 * 非 EDT 经 [SwingUtilities.invokeAndWait] 阻塞呈现（模态关闭后返回）。阻塞语义
 * 是有意的：崩溃线程在用户读报告、复制诊断信息之前不得继续走完死亡/委托流程。
 *
 * 自保护硬约束：本入口不得向调用方抛出任何异常——崩溃处理链上没有下一层兜底，
 * 对话框自身故障（无显示器/EDT 不可用/剪贴板不可用）一律降级 [fallbackPrint]
 * （stderr），报告内容保证不丢。
 */
internal object CrashReportDialog {

    fun show(report: String) {
        try {
            // 无显示器环境（纯 CLI/CI）无对话框可言，直接降级 stderr
            if (GraphicsEnvironment.isHeadless()) {
                fallbackPrint(report)
                return
            }
            if (SwingUtilities.isEventDispatchThread()) {
                showOnEdt(report)
            } else {
                SwingUtilities.invokeAndWait { showOnEdt(report) }
            }
        } catch (_: Throwable) {
            // AWT 故障/等待中断等一切呈现故障：降级 stderr，不丢报告
            fallbackPrint(report)
        }
    }

    /** 在 EDT 上创建并模态展示（阻塞至用户关闭） */
    private fun showOnEdt(report: String) {
        val textArea = JTextArea(report).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            lineWrap = false
        }
        val dialog = JDialog()
        dialog.title = "Toolbelt - 未处理异常"
        dialog.isModal = true
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.layout = BorderLayout()

        dialog.add(
            JLabel("应用遇到未处理异常。可复制以下诊断信息进行反馈；关闭后应用可能退出或处于不稳定状态。"),
            BorderLayout.NORTH,
        )
        dialog.add(JScrollPane(textArea).apply { preferredSize = Dimension(760, 420) }, BorderLayout.CENTER)

        val copyButton = JButton("复制诊断信息")
        copyButton.addActionListener {
            // 剪贴板在 Linux 桌面可能不可用（无 clipboard manager 端即抛），复制结果
            // 经按钮文案回显，失败时提示手动选中复制——复制失败不得吞掉对话框
            val copied = try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report), null)
                true
            } catch (_: Throwable) {
                false
            }
            copyButton.text = if (copied) "已复制" else "复制失败，请手动选中复制"
        }
        val openLogDirButton = JButton("打开日志文件夹")
        openLogDirButton.addActionListener {
            // 反馈方排障通常需要完整日志文件而非对话框里的尾部片段；日志目录对用户是
            // 不可见心智（隐藏数据目录下的 logs/），此钮直达文件管理器。
            // 目录缺失（文件日志开关从未开启过）时按需补建再打开——按钮语义恒为
            // 「打开目录」，目录内是否有 app.log 由日志开关决定，不在本钮判断。
            // 打开失败不得向 EDT 抛异常（EDT 未捕获异常会二次进入全局兜底）——
            // 降级为按钮文案回显目录绝对路径，保住用户自找日志的线索。
            val logDir = FileUtils.getLogDir()
            val opened = try {
                if (!logDir.exists()) logDir.mkdirs()
                Desktop.getDesktop().open(logDir)
                true
            } catch (_: Throwable) {
                false
            }
            if (!opened) {
                openLogDirButton.text = "打开失败：${logDir.absolutePath}"
            }
        }
        val closeButton = JButton("关闭")
        closeButton.addActionListener { dialog.dispose() }
        dialog.add(
            JPanel().apply {
                add(copyButton)
                add(Box.createHorizontalStrut(8))
                add(openLogDirButton)
                add(Box.createHorizontalStrut(8))
                add(closeButton)
            },
            BorderLayout.SOUTH,
        )

        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun fallbackPrint(report: String) {
        System.err.println(report)
    }
}
