// files：文件名清洗 + 体积阈值。
// 消费者 = DownloadStore
//    （sanitizeFileName/sanitizeDirSegment/常量）与 ReferencedMessageResolver（sanitizeFileName/enforceSizeLimit）；
//    FileHandler 仅登记回执、不消费本文件。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import java.io.File

/**
 * 下载文件名清洗 + 体积阈值（纯函数集合）。
 *
 * ## 与网关的分工
 * - **SDK 2.8.5 无流式下载路径**（只有 `ByteArrayOutputStream`）⇒ 无法「边下边写」；
 * - 下载**并发**已由网关侧 `Semaphore(2)`（`downloadSingleFlight=2`）限制 ⇒ lib 侧**不得**重复实现并发限制；
 * - `ResourceDescriptor` **无 size 字段** ⇒ `fileMessage.maxFileSizeBytes` 的语义只能是
 *   **「下载后拒绝阈值」**：先落盘，再按 [enforceSizeLimit] 判定，超阈值即**删除文件并拒绝**。
 *
 * 本 object 体内只有不可变常量，无可变全局状态。
 */
object DownloadPolicy {

    /** 文件名非法字符（路径分隔符、控制字符、.. 序列的组成部分） */
    private val ILLEGAL_CHARS = Regex("[/\\\\\\x00-\\x1f]")

    /**
     * 🔴 Windows 文件系统专属非法字符全集。
     *
     * 基础规则只清分隔符与控制字符，遗漏了 Windows 上非法、但在 Linux/macOS 合法的：
     * `< > : " | ? *`。飞书资源名含这些字符时 Windows 落盘直接失败（且失败信息不可读）。
     * 其中 `:` 还兼有**路径穿越语义**（`C:` 形式的盘符切换）。
     *
     * 与 `LogTagFilter.sanitizeTagName`（:log-tools）是**同一语义的两份实现**——
     * 那边早已补全，这边漏了。因 `:lark-bridge` 以 `implementation` 单向依赖 `:log-tools`
     * 且对方是 `internal`，跨模块不可见，故此处**复制同一字面表**；
     * 漂移由跨模块契约测试守护（见 WindowsFileNameContractTest）。
     */
    private val WINDOWS_ILLEGAL_CHARS = Regex("""[<>:"|?*/\\]""")

    /** Windows 保留设备名（不分大小写；带扩展名形态同样被拒——判定只看主名） */
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * 文件名主名长度上限（**UTF-8 字节**，不含扩展名概念——截断点含扩展名一起算）。
     *
     * 为什么是 100 字节：下载区路径已是四层嵌套
     * （`<root>/downloads/<senderId>/<date>/<msgId12>_<name>`），Windows 全路径上限 260，
     * root 本身可能占几十字符 ⇒ 给主名留 100 的余量。
     * 🔴 按**字节**而非码点计：Linux/macOS 的 NAME_MAX 是 255 **字节**——
     * 中文常态 3 字节/字，100 码点可达 300 字节，实测 257 字节即 `mkdir ENAMETOOLONG`。
     */
    private const val MAX_BASENAME_BYTES = 100

    /**
     * 文件名清洗（🔒 字面值契约：`../../etc/passwd` 不含分隔符、不含 `..`，测试逐字锚定）。
     *
     * 规则：
     * 1. 路径分隔符/控制字符 → `_`，`..` → `_`，首尾 `_` 去掉，空结果回退 `"file"`；
     * 2. **Windows 非法字符 `<>:"|?*` → `_`**（`:` 兼防盘符穿越）；
     * 3. **命中保留设备名时加 `_` 后缀**（保留扩展名）；
     * 4. **去除尾部空白与点号**（Windows 静默忽略尾部点号，
     *    会产生「文件确实存在、但用户按原名找不到」的怪象）；
     * 5. **长度按字节截断**（防全路径突破 260 与单段 NAME_MAX）。
     *
     * 🔴 三个易错点（改动前先读懂，测试逐字锚定）：
     * **(a) 保留名保护与 trim 的顺序**：trim 的字符集含 `_` ⇒ 若先加 `_` 后缀再 trim，
     * `"CON"`→`"CON_"`→`"CON"` 原样返回（保护被自己抹掉）。必须**先做尾字符清理，再判保留名并追加 `_`**
     * ——追加是最后一步，无人再抹。
     * **(b) 长度按字节计**：目录段的实际形态是 `safeMessage + "_" + safeName` 两段叠加，
     * 且 Linux/macOS 的 NAME_MAX 是 **255 字节**而非码点——中文常态即 3 字节/字。
     * **(c) 空输入兜底会串目录**：`""` / `".."` / `"___"` 全部兜底成字面 `"file"`，多个不同入参
     * 混用同一目录。**`"file"` 兜底是既有契约（测试逐字锚定，不能改）**，
     * 故「拒绝非法段」的职责下移到 [sanitizeDirSegment] 与 [DownloadStore.dirFor]。
     */
    fun sanitizeFileName(name: String): String {
        // 1. 原有规则：分隔符 / 控制字符 / `..` 序列
        val base = name.replace(ILLEGAL_CHARS, "_").replace("..", "_")
        // 2. Windows 非法字符补全集
        val substituted = base.replace(WINDOWS_ILLEGAL_CHARS, "_")
        // 3. 拆主名/扩展名（保留名判定只看主名）
        val dot = substituted.lastIndexOf('.')
        val stem = if (dot > 0) substituted.substring(0, dot) else substituted
        val ext = if (dot > 0) substituted.substring(dot) else ""
        // 4. 尾字符清理**先于**保留名判定（见 (a)）：
        //    Windows 忽略尾部点号与空格，混排时须循环清除；trim('_') 保留原契约语义
        //    （`./../x` → `x` 而非 `___x`，且承担「替换产生的碎下划线被清掉」的职责）。
        val cleanStem = stem.trim { it == '_' || it.isWhitespace() || it == '.' }
        // 🔴 扩展名只清尾部空白与点号、**不 trim 前导 `.`**——`.` 是扩展名的构成字符
        //    （`report.txt...` → stem `report` + ext `.txt`）。曾因把 `.` 一并 trim 而丢点。
        val cleanExt = ext.trimEnd { it == '_' || it.isWhitespace() || it == '.' }
        if (cleanStem.isBlank() && cleanExt.isBlank()) return FILE_FALLBACK_NAME // 既有兜底（🔒 测试锚定，不可改）
        // 5. 保留设备名判定 + 追加 `_`（最后一步，绝不被后续 trim 掉）——扩展名原样保留
        val safeStem =
            if (cleanStem.isBlank()) "_"
            else if (cleanStem.uppercase() in WINDOWS_RESERVED_NAMES) "${cleanStem}_"
            else cleanStem
        // 6. 长度上限（按 UTF-8 字节，见 (b)）
        return truncateByBytes(safeStem + cleanExt, MAX_BASENAME_BYTES)
    }

    /**
     * 按 **UTF-8 字节**截断（见 (b)：NAME_MAX 是字节数，不是码点数）。
     * 逐码点累加，绝不切断代理对。
     * 写成 fold（而非 while + `var used`/`var i`）是为了满足本 object 的零可变状态契约——
     * 「object 体内零可变状态」由守护测试按 `\bvar\s+\w` 字面扫描，局部变量同样会被命中。
     */
    private fun truncateByBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        // 码点序列 → 逐步累加字节预算，超出即停
        val kept = s.codePoints().toArray().fold(StringBuilder() to 0) { (sb, used), cp ->
            if (used + utf8SizeOfCodePoint(cp) > maxBytes) sb to used
            else sb.appendCodePoint(cp) to (used + utf8SizeOfCodePoint(cp))
        }.first.toString()
        // 截断点可能正好落在 `_`/`.`/空白上（Windows 会忽略它们），清掉后再兜底
        return kept.trimEnd { it == '_' || it.isWhitespace() || it == '.' }.ifBlank { FILE_FALLBACK_NAME }
    }

    /** 单码点的 UTF-8 字节数（避免为计数反复分配编码结果）。 */
    private fun utf8SizeOfCodePoint(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /**
     * 删除文件/目录（递归；失败静默——尽力而为，不阻塞回复）。
     * 删除失败要可观测（记 warn）。
     */
    fun cleanup(file: File, log: BridgeLog = BridgeLog.Silent) {
        runCatching { file.deleteRecursively() }.onFailure {
            log.warn("$TAG cleanup failed: ${file.path} ${it.message}")
        }
    }

    /**
     * 「下载后超阈值即拒绝并删除」（与网关的分工契约）。
     *
     * 为什么是**下载后**判定：SDK 2.8.5 的 `ResourceDescriptor` 无 size 字段，无法预检；
     * 又因为 SDK 无流式下载路径，字节必然先进内存/落盘 ⇒ 只能在落盘后按实际长度判定。
     *
     * @param file 已落盘的文件
     * @param maxBytes `fileMessage.maxFileSizeBytes`（默认 100MB）；`<= 0` 视为不限制
     * @return true = 通过；false = 超阈值（**文件已被删除**，调用方回 `file_too_large` 语义的失败）
     */
    fun enforceSizeLimit(file: File, maxBytes: Long, log: BridgeLog = BridgeLog.Silent): Boolean {
        if (maxBytes <= 0L) return true
        val actual = runCatching { file.length() }.getOrDefault(0L)
        if (actual <= maxBytes) return true
        log.warn("$TAG 文件超过 maxFileSizeBytes，已删除并拒绝：${file.path} size=$actual limit=$maxBytes")
        cleanup(file, log)
        return false
    }

    /**
     * 消息 ID 前缀截断长度（防超长 ID 撑爆文件名；同时是 messageId 段的清洗上限）。
     */
    const val MESSAGE_ID_PREFIX_CHARS: Int = 12

    /**
     * **路径段清洗**（senderId / messageId 用）。
     *
     * [DownloadStore.dirFor] 若把 `senderId` 裸拼进 `<root>/downloads/<senderId>/...`——
     * CLI `handle-event` 的自定义 JSON 可传 `../../../tmp/x`（`take(12)` 得 `../../../tmp`），
     * 路径穿出下载根。真实飞书侧是 `ou_*`/`om_*` 服务端值，触发面窄，但必须清洗。
     *
     * 语义 = [sanitizeFileName] 的**段级收窄**版：单个目录段不含扩展名概念（保留名/尾点规则
     * 对目录段同样成立），且必须整体过一遍（不留 `..` 序列、不接分隔符）。
     *
     * 🔴 空/纯清洗字符入参：经 [sanitizeFileName] 的兜底会变成字面
     * `"file"`——多个不同入参（`""` / `".."` / `"___"`）于是混用同一物理目录，
     * 而账本按**原始** senderId 记账 ⇒ 配额计数与实体错位（一方 evict 递归删目录会删掉另一方
     * 的文件且计数不回退）。故此处必须区分「清洗后得名」与「清洗后仍是兜底」。
     *
     * @return 清洗后的段名；**null = 该段无法产出有意义的名字**（调用方应拒绝，不要拼路径）
     */
    fun sanitizeDirSegment(raw: String): String? {
        // 1. 入参本身无有效内容即拒（含纯空白 / 纯点 / 纯下划线——`sanitizeFileName` 会把它们兜底成 "file"）
        if (raw.isBlank() || raw.all { it == '.' || it == '_' || it.isWhitespace() }) return null
        val cleaned = sanitizeFileName(raw)
        // 2. 兜底值（"file"）或仍为相对段 ⇒ 拒（段名必须由入参真实产生）
        if (cleaned == FILE_FALLBACK_NAME || cleaned == "." || cleaned == "..") return null
        return cleaned
    }

    /** [sanitizeFileName] 的兜底名——目录段侧据此识别「没有真实名字」。 */
    const val FILE_FALLBACK_NAME: String = "file"

    /** 下载根目录名（不做配置项） */
    const val DOWNLOADS_DIRNAME: String = "downloads"

    /** 日期目录格式（🔒 路径含 `downloads/<yyyy-MM-dd>`，测试锚定） */
    const val DATE_PATTERN: String = "yyyy-MM-dd"

    private const val TAG = "DownloadPolicy"
}
