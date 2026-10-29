package com.jiaoay.toolbelt.device

// OPEN(真机门禁): 真机 `ls -la` 与 `stat -c '%F|%s|%Y|%A|%U|%G'` 输出格式校准
// （本层 fixture 按实测基线实现：toybox 0.8.4 八列长格式、stat 管道分隔单行）——
// 真机格式联验随浏览面板挂载后的真机走查一并做，登记不阻塞离线验收。

/**
 * 设备文件域纯解析层（字符串 → 模型，零副作用零 IO）：命令面语义 = adb shell
 * 程序语义，命令构造与执行调度在 [DeviceFileBrowser]。本层只吃已定形态——
 * `ls -l` 长格式与 `stat -c` 管道分隔单行。
 *
 * 失败语义：对不认识的输出抛 [IllegalArgumentException]（message 面向用户呈现），
 * 禁静默回空列表——ls 输出跨 ROM 差异大，整体格式不符必须显式失败。adb shell
 * 走 pty 时行尾可能带 `\r`，所有入口统一剥除。
 *
 * 格式基线：真机实测（toybox 0.8.4）——`ls -la` 为 8 列长格式
 * （权限/硬链数/属主/属组/尺寸/日期/时间/名称），日期恒 `YYYY-MM-DD HH:MM`
 * 形态；`stat -c` 机器可读单行可用。
 *
 * 已知残余（登记）：设备侧 `ls` 对文件名零转义——含换行的文件名会打断行边界，
 * 断口尾行不满足列形态则防御性跳过（首条目名截断），整体解析不因此失败；
 * 双格式探测以第 2 列是否数字定硬链列归属，无硬链列且属主为纯数字 UID 的
 * ROM 输出会误判为带硬链列形态（该行不可解析则跳过）。
 */
internal object DeviceFileParsers {

    /** `total N` 摘要行（ls 长格式行首固定形态；条目行以权限列开头，永不相撞） */
    private val totalLine = Regex("""^total\s+\d+$""")

    /** 权限列形态：类型字符（- d l b c s p）+ 权限位（含 ACL/SELinux 尾标 `.`/`+`） */
    private val permsToken = Regex("""^[bcdlsp-][-rwxsStT.+]{9,}$""")

    /**
     * 日期段两已定形态的 token 校验：
     * - ISO 形态（toybox 基线）：`YYYY-MM-DD` + `HH:MM[:SS]`（2 token）；
     * - 月名形态（GNU/POSIX ls）：月缩写 + 日 + `HH:MM` 或 4 位年（3 token）。
     * 日期 token 不满足任一已定形态的行按不可识别防御（整输出零可识别行 = 显式失败，
     * 不猜列位——固定列位吃未知日期形态会静默错切名称列）。
     */
    private val isoDateToken = Regex("""^\d{4}-\d{1,2}-\d{1,2}$""")
    private val monthAbbrToken = Regex("""^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)$""")
    private val dayOfMonthToken = Regex("""^\d{1,2}$""")
    private val timeOfDayToken = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val yearToken = Regex("""^\d{4}$""")

    private val whitespace = Regex("\\s+")

    /** symlink 名与目标的拆分点（ls 固定输出形态） */
    private const val SYMLINK_ARROW = " -> "

    /**
     * 解析 `ls -l` 长格式输出为条目列表（dotfile 含与否由命令层旗标决定，本函数
     * 不区分）。防御与失败语义：
     * - `total N` 摘要行与 `.`/`..` 条目不进结果（`./..` 行仍计为可识别行——空目录
     *   返回空列表是合法读数，不触发失败）；
     * - 单行不可解析跳过不拖垮好行；但输出含候选行却零行可识别（整体格式不符）
     *   显式失败，message 带首条不可识别行样例；
     * - 返回保持 ls 输出序。
     */
    internal fun parseLsEntries(output: String): List<DeviceFileEntry> {
        val lines = output.replace("\r", "").lines()
        val entries = mutableListOf<DeviceFileEntry>()
        var recognizedRows = 0
        var candidateSeen = false
        var unrecognizedSample: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (totalLine.matches(line)) continue
            candidateSeen = true
            val entry = parseLsRow(line) ?: run {
                if (unrecognizedSample == null) unrecognizedSample = line
                continue
            }
            recognizedRows++
            if (entry.name == "." || entry.name == "..") continue
            entries += entry
        }
        if (recognizedRows == 0 && candidateSeen) {
            throw IllegalArgumentException(
                "ls 输出格式不符，零行可解析（样例：${unrecognizedSample?.take(80) ?: "（空）"}）",
            )
        }
        return entries
    }

    /**
     * 单行解析：双格式分支以第 2 列是否数字探测硬链列——带硬链列 8 列
     * （权限 硬链 属主 属组 尺寸 日期段 名称…）/ 无硬链列 7 列
     * （权限 属主 属组 尺寸 日期段 名称…）；日期段按 ISO（2 token）/月名（3 token）
     * 两已定形态校验后定名称列起点。名称列 join 还原含空格名。不满足任一已定
     * 形态返回 null（调用方按坏行防御）。
     */
    private fun parseLsRow(line: String): DeviceFileEntry? {
        val parts = line.split(whitespace)
        val perms = parts.firstOrNull() ?: return null
        if (!permsToken.matches(perms)) return null
        val hasLinks = parts.getOrNull(1)?.toLongOrNull() != null
        val sizeIdx = if (hasLinks) 4 else 3
        val size = parts.getOrNull(sizeIdx)?.toLongOrNull() ?: return null
        val dateStart = sizeIdx + 1
        val nameIdx = when {
            isoDateToken.matches(parts.getOrNull(dateStart) ?: "") &&
                timeOfDayToken.matches(parts.getOrNull(dateStart + 1) ?: "") -> dateStart + 2

            monthAbbrToken.matches(parts.getOrNull(dateStart) ?: "") &&
                dayOfMonthToken.matches(parts.getOrNull(dateStart + 1) ?: "") &&
                (timeOfDayToken.matches(parts.getOrNull(dateStart + 2) ?: "") ||
                    yearToken.matches(parts.getOrNull(dateStart + 2) ?: "")) -> dateStart + 3

            else -> return null
        }
        if (parts.size < nameIdx + 1) return null
        val type = DeviceFileType.fromPermissionString(perms)
        val rawName = parts.drop(nameIdx).joinToString(" ")
        var name = rawName
        var target: String? = null
        if (type == DeviceFileType.SYMLINK) {
            val arrowIdx = rawName.indexOf(SYMLINK_ARROW)
            if (arrowIdx >= 0) {
                name = rawName.substring(0, arrowIdx)
                target = rawName.substring(arrowIdx + SYMLINK_ARROW.length)
            } else {
                // 断链形态（名内含换行把 ` -> 目标` 打断到下一行）：保非 null 不变量
                target = ""
            }
        }
        return DeviceFileEntry(
            name = name,
            type = type,
            symlinkTarget = target,
            sizeBytes = size,
            permissions = perms,
            owner = parts[if (hasLinks) 2 else 1],
            group = parts[if (hasLinks) 3 else 2],
            modifiedTime = parts.subList(dateStart, nameIdx).joinToString(" "),
        )
    }

    /**
     * 解析 `stat -c '%F|%s|%Y|%A|%U|%G'` 单行输出。六列管道分隔；类型列只认
     * directory/symbolic link/regular file 三形态（其余特种文件类型显式失败——
     * stat 是备面不是主路径，宁可失败不猜）。任一列不符抛
     * [IllegalArgumentException]，message 带原行样例。
     */
    internal fun parseStatLine(output: String): DeviceFileStat {
        val line = output.replace("\r", "").trim()
        val cols = line.split('|')
        if (cols.size != 6) {
            throw IllegalArgumentException("stat 输出非六列（${cols.size}）：${line.take(80)}")
        }
        val type = when (cols[0].trim()) {
            "directory" -> DeviceFileType.DIRECTORY
            "symbolic link" -> DeviceFileType.SYMLINK
            "regular file" -> DeviceFileType.FILE
            else -> throw IllegalArgumentException("stat 类型列不认识（${cols[0]}）")
        }
        val size = cols[1].toLongOrNull()
            ?: throw IllegalArgumentException("stat 尺寸列非数字（${cols[1]}）")
        val epoch = cols[2].toLongOrNull()
            ?: throw IllegalArgumentException("stat 时间列非数字（${cols[2]}）")
        return DeviceFileStat(
            type = type,
            sizeBytes = size,
            modifiedEpochSeconds = epoch,
            permissions = cols[3],
            owner = cols[4],
            group = cols[5],
        )
    }
}
