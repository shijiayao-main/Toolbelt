package com.jiaoay.easy_dev_toolbox.page.shelltools

/**
 * 控制台行净化器（每条命令一实例）：ANSI 转义序列剥离 + 裸 \r 覆盖语义折叠。
 *
 * 流式层只按 \n 分行，进度条类输出的裸 \r 保留在行内容中（如 adb push 的
 * "45%\r67%\r100%"）：折叠取最后一个 \r 段 = 只留最终态——防 5000 行滚动窗口
 * 被进度 tick 灌爆；行尾孤立 \r（CRLF 残留）剥除；ANSI CSI/OSC 序列剥离防
 * 控制字符上屏。
 *
 * @return 净化后的行；null = 整行折叠为空（纯 \r 残留），调用方跳过不上屏
 */
internal class ConsoleLineSanitizer {

    fun sanitize(raw: String): String? {
        val noAnsi = raw.replace(ANSI_REGEX, "")
        val withoutTrailingCr = noAnsi.trimEnd('\r')
        if (withoutTrailingCr.contains('\r')) {
            // 进度覆盖语义：最后一个 \r 段是唯一有效终态
            val finalState = withoutTrailingCr.substringAfterLast('\r')
            return finalState.ifEmpty { null }
        }
        if (raw.isEmpty()) return ""
        return withoutTrailingCr
    }

    companion object {
        /** ANSI 转义：CSI 序列 + OSC 序列（BEL/ST 两种终止）+ 单字符编码选择序列 */
        private val ANSI_REGEX =
            Regex("\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[()][0-9A-Za-z])")
    }
}
