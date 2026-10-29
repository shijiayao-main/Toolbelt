package com.jiaoay.easy_dev_toolbox.core.system

/**
 * 只读诊断域（sys.* 工具族）输出脱敏器：纯函数、零 IO、行级遮蔽。
 *
 * ## 为什么必须脱敏（不只是 env 条目）
 * 诊断输出回灌 LLM 后，可经恒注册免确认的直述/回卡类工具原样转述出会话——
 * 读取-转述外泄通道真实存在；故一切 L0 只读输出（命令输出 / 配置文件内容 /
 * 日志尾部）统一过本脱敏器，不能只靠输出上界兜底。
 *
 * ## 遮蔽形态（三层，逐行依次施加）
 * 1. **行首锚定赋值行**（`KEY=VALUE` 与 `export KEY=VALUE`——覆盖 env 输出、INI 形态配置）：
 *    命中遮蔽键判据时，值整体（至行尾）替换为 [MASK]；
 * 2. **行中子串赋值**（journalctl 形态——行首是时间戳、凭据赋值出现在消息段中，
 *    如 `Oct 14 10:00:00 host app[123]: token=xxx`）：逐键判定，命中即值段
 *    （引号段或非空白连续段）替换为 [MASK]；已遮蔽段再扫幂等；
 * 3. **JSON 键值形态**（`"token":"xxx"`——日志里应用输出的 JSON 消息行）：引号内
 *    键名逐键判定，命中即值替换为 [MASK]。裸 `key: value` 冒号形态仍不遮
 *    （无引号边界无法区分配置值与散文，误伤面不可控）。
 * - 遮蔽键判据（键名大写化后二选一命中）：含 TOKEN/PASSWORD/PASSWD/SECRET/
 *   CREDENTIAL 子串；或以 KEY 结尾（`API_KEY`/`SECRETKEY`——覆盖 `_` 分段与
 *   大写连写两种常见 secret 命名）；
 * - 非赋值行原样放行；空值赋值（`KEY=` / `KEY=""`）无泄密面，原样放行。
 *
 * ## 恒明文放行清单（[ALLOWED_KEYS]）
 * 输入法与桌面会话环境变量族是诊断对象本体，显式登记恒明文——放行判定先于
 * 遮蔽判定：遮蔽键集未来加宽时，本清单保证诊断主变量不被误伤。
 * 本清单**只许收不许扩**（子集棘轮由单测钉死）：加条目 = 扩明文面，须走提案评审。
 *
 * ## 有意不遮（误伤取舍，显式登记）
 * - 复数形态键名（fcitx5 的 `TriggerKeys=`/`EnumerateForwardKeys=` 等热键定义）
 *   不以 KEY 结尾——它们是快捷键配置不是凭据，遮蔽会消灭输入法诊断核心信息；
 * - 行中 URL query 段的 `?key=xxx` 会被行中形态一并遮蔽——凭据型 query 参数
 *   泛滥且遮蔽是 fail-safe 方向（遮蔽不丢诊断主体），显式接受。
 */
object SysOutputSanitizer {

    /** 遮蔽替代文本（恒定三字符——遮蔽后的回执不含任何原值残段） */
    const val MASK = "***"

    /**
     * 恒明文放行的诊断变量（键名大写形态比对）：输入法与桌面会话环境变量族
     * （printenv 条目输出 / .xprofile export 行同受本清单保护）。
     * 只许收不许扩（子集棘轮由 SysOutputSanitizerTest 钉死）。
     */
    val ALLOWED_KEYS: Set<String> = setOf(
        "GTK_IM_MODULE", "QT_IM_MODULE", "XMODIFIERS", "SDL_IM_MODULE", "INPUT_METHOD",
        "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "XDG_SESSION_DESKTOP",
        "DESKTOP_SESSION", "LANG", "LC_ALL",
    )

    /** 遮蔽键子串（键名大写化后包含即遮蔽） */
    private val MASK_SUBSTRINGS = listOf("TOKEN", "PASSWORD", "PASSWD", "SECRET", "CREDENTIAL")

    /** KEY 结尾形态（`API_KEY` 分段连写与 `SECRETKEY` 无分隔连写） */
    private val keyEnding = Regex("""KEY$""")

    /** 赋值行（可选 export 前缀；键名允许字母数字与 `.`/`_`/`-` 分隔） */
    private val assignment = Regex("""^(\s*(?:export\s+)?)([A-Za-z_][A-Za-z0-9_.-]*)(\s*=\s*)(.*)$""")

    /** JSON 键值形态（`"token":"xxx"`——键名与值均在双引号内） */
    private val jsonAssignment = Regex(""""([A-Za-z_][A-Za-z0-9_.-]*)"\s*:\s*"([^"\n]*)"""")

    /**
     * 行级脱敏：三层形态逐行依次施加，其余行原样。
     * 行数与换行守恒（超长输出的截断由工具层 capAt 上界负责，本函数不截断）。
     */
    fun sanitize(output: String): String =
        output.lineSequence().joinToString("\n") { sanitizeLine(it) }

    private fun sanitizeLine(line: String): String =
        maskJsonAssignments(maskMidLineAssignments(sanitizeAnchoredAssignment(line)))

    /** 行首锚定赋值行：命中遮蔽键判据时值整体（至行尾）替换为 [MASK]。 */
    private fun sanitizeAnchoredAssignment(line: String): String {
        val m = assignment.find(line) ?: return line
        val key = m.groupValues[2]
        if (!shouldMaskKey(key)) return line
        if (m.groupValues[4].isBlank()) return line
        return m.groupValues[1] + key + m.groupValues[3] + MASK
    }

    /**
     * 行中子串赋值：逐键判定，命中即值段替换为 [MASK]（键与等号周围空白收拢为 `KEY=`）。
     *
     * 手写单趟线性扫描而非正则：等价正则对「超长无空白连续段」（base64 blob /
     * minified JSON / 无换行堆栈）呈 O(n²) 回溯爆炸——实测 32K 字符单行 7.5 秒、
     * 2M 字符超两分钟不返回。脱敏跑在 sys.* 全量捕获输出上（捕获上限 2M 字符），
     * 等价于可被病理日志触发的挂死面，且单条测试实测耗时 127 秒。
     * 本实现每个字符至多被键名/值段各消费一次，O(n) 且无回溯。
     *
     * 语义与旧正则逐位等价（单测锚不变，含 `key=TOKEN=1` 只遮首段、已遮蔽段幂等、
     * 值段遇 `,;"'` 或空白截止）。
     */
    private fun maskMidLineAssignments(line: String): String {
        val n = line.length
        var out: StringBuilder? = null
        var copiedUntil = 0
        var i = 0
        while (i < n) {
            if (!isKeyBodyChar(line[i])) {
                i++
                continue
            }
            // 最大键体连续段 [runStart, runEnd)；正则的键名必须从 [A-Za-z_] 起始，
            // 段首若为数字（`123abc=1`）须从段内首个合法起始字符算键名起点。
            val runStart = i
            var runEnd = i
            while (runEnd < n && isKeyBodyChar(line[runEnd])) runEnd++
            var keyStart = runStart
            while (keyStart < runEnd && !isKeyStartChar(line[keyStart])) keyStart++
            if (keyStart == runEnd) {
                i = runEnd
                continue
            }
            // 键名与 `=` 之间的空白
            var eq = runEnd
            while (eq < n && line[eq].isWhitespace()) eq++
            if (eq >= n || line[eq] != '=') {
                i = runEnd
                continue
            }
            // `=` 之后的值段起点
            var valueStart = eq + 1
            while (valueStart < n && line[valueStart].isWhitespace()) valueStart++
            val valueEnd = valueEndOf(line, valueStart, n)
            if (valueEnd < 0) {
                // 引号未闭合：正则全部分支失败 ⇒ 整段不构成匹配，跳过该键体段
                i = runEnd
                continue
            }
            val key = line.substring(keyStart, runEnd)
            val hasValue = valueEnd > valueStart
            if (hasValue && shouldMaskKey(key)) {
                val sb = out ?: StringBuilder(n).also { out = it }
                sb.append(line, copiedUntil, keyStart)
                sb.append(key).append('=').append(MASK)
                copiedUntil = valueEnd
            }
            // 无论是否遮蔽都跳过整个匹配（与正则 replace 的非重叠推进一致）
            i = maxOf(valueEnd, eq + 1)
        }
        val sb = out ?: return line
        sb.append(line, copiedUntil, n)
        return sb.toString()
    }

    /**
     * 值段结束下标（不含）；返回 [valueStart] 表示值可缺席的空值形态。
     * 引号段未闭合返回 -1（正则三条分支全败 = 本键不成匹配）。
     * 三个分支与旧正则一一对应：`"..."` / `'...'` / `[^\s,;"']+`。
     */
    private fun valueEndOf(line: String, valueStart: Int, n: Int): Int {
        if (valueStart >= n) return valueStart
        when (line[valueStart]) {
            '"' -> {
                val close = line.indexOf('"', valueStart + 1)
                return if (close < 0) -1 else close + 1
            }
            '\'' -> {
                val close = line.indexOf('\'', valueStart + 1)
                return if (close < 0) -1 else close + 1
            }
        }
        var end = valueStart
        while (end < n && !isValueTerminator(line[end])) end++
        return end
    }

    /** 非引号值段的截止字符集（= 旧正则 `[^\s,;"']`） */
    private fun isValueTerminator(c: Char): Boolean =
        c.isWhitespace() || c == ',' || c == ';' || c == '"' || c == '\''

    private fun isKeyStartChar(c: Char): Boolean =
        (c in 'A'..'Z') || (c in 'a'..'z') || c == '_'

    private fun isKeyBodyChar(c: Char): Boolean =
        isKeyStartChar(c) || (c in '0'..'9') || c == '.' || c == '-'

    /** JSON 键值形态：引号内键名逐键判定，命中即值替换为 [MASK]。 */
    private fun maskJsonAssignments(line: String): String =
        jsonAssignment.replace(line) { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            if (value.isBlank() || !shouldMaskKey(key)) m.value else """"$key":"$MASK""""
        }

    /** 遮蔽键判定：恒明文清单先放行，再按子串/KEY 结尾判据遮蔽 */
    private fun shouldMaskKey(key: String): Boolean {
        val upper = key.uppercase()
        if (upper in ALLOWED_KEYS) return false
        return MASK_SUBSTRINGS.any { upper.contains(it) } || keyEnding.containsMatchIn(upper)
    }
}
