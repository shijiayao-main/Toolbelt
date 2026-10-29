package com.jiaoay.easy_dev_toolbox.page.archive

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * 字节切分分卷压缩包（.zip.NNN 族）的识别、分组与流式复原。
 *
 * ## 覆盖范围（Tier 0 = 字节切分族完整支持）
 * - `.zip.001` / `.zip.002` / …（数字卷族，含 `.7z.001` 同形态）
 * - `.7z.001` / `.7z.002` / …
 * - `-partaa` / `-partab` / …（字母卷族）
 *
 * 三族共同点：**分卷只是原文件按字节切开**，按序 `SequenceInputStream` 串联即
 * 复原完整字节流——zip 的 LOC 结构顺序读天然透明（ZipInputStream 从流头读起）。
 * 零新依赖（纯 JDK）。
 *
 * ## 解码分派与不支持族
 * - `.z01` 族（PKZip 规范分卷：分段标记 + 跨段中央目录）与 7z 容器（含 `.7z.001`
 *   字节切分串联复原）已落地解压——经 [VolumeAndSevenZip]
 *   （commons-compress），本类只承担识别/归组/完整性校验，解压分派见归档页 process；
 * - `-partaa` 字母卷（rar 风格）：识别成功但唯一保留拒绝族——切分工具私有惯例多、
 *   串联语义未验证，归组后明示「该格式不支持解压」（不静默丢弃）；
 * - rar 容器本体：无主流 Java 库，不支持。
 *
 * 分卷后缀放行**只改 feature-archive 本地判定**（[isSplitArchiveName]），不动
 *    `SafeExtract.isArchive`——后者被多域共享（agent 工具 extract_archive
 *    工具 / lark-bridge PreprocessPipeline autoExtract / 本页），放行会改变桥接域
 *    预处理行为（behavior-contract.md §4.7 契约面）。
 */
internal object SplitArchive {

    /** 数字卷后缀（.001/.002/…；001-999 由正则约束） */
    private val NUMERIC_SPLIT = Regex("\\.(zip|7z)\\.(\\d{3})$")

    /** 字母卷后缀（-partaa/-partab/…；aa-zz） */
    private val ALPHA_SPLIT = Regex("-part([a-z]{2})$")

    /** PKZip 规范分卷（.z01…）：识别后归组建卡（解压经 [VolumeAndSevenZip]，不走本类串联路径） */
    private val PKZIP_SPLIT = Regex("\\.z(\\d{2})$")

    /** 是否为分卷压缩包文件名（三族全识别——字母卷族仅识别建组后明示拒绝） */
    fun isSplitArchiveName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return NUMERIC_SPLIT.containsMatchIn(lower) || ALPHA_SPLIT.containsMatchIn(lower) || PKZIP_SPLIT.containsMatchIn(lower)
    }

    /**
     * 是否为「本类路径可完整解压」的分卷族：字节切分 + zip 容器（串联流直喂
     * ZipInputStream）。7z 切分组同样可解压但走 7z 串联复原 + SevenZFile 路径
     *（[VolumeAndSevenZip.joinSevenZipSplits]），不经本谓词；字母卷族恒拒绝。
     */
    fun isExtractableSplitName(fileName: String): Boolean =
        fileName.lowercase().matches(".+\\.zip\\.\\d{3}".toRegex())

    /**
     * 拖入的一批文件按「同一分卷组」归组。
     *
     * @return 分卷组列表（每组 = 同基名的全部卷，按卷序排列）；单文件若是分卷
     *         之一也归组（缺失卷在 [group.checkCompleteness] 检出）。
     */
    fun group(parts: List<File>): List<SplitGroup> {
        return parts
            .filter { isSplitArchiveName(it.name) }
            .groupBy { baseName(it.name) }
            .map { (base, files) -> SplitGroup(baseName = base, parts = orderParts(files)) }
    }

    /** 分卷组：基名 + 按序排列的卷 */
    data class SplitGroup(val baseName: String, val parts: List<File>)

    /**
     * 完整性校验：卷编号必须从首卷连续到已提供的末卷。
     *
     * ⚠️ Tier 0 的诚实边界：无法验证「末卷之后没有缺失」——用户只拖了 001/002 而
     * 003 在别的目录时，串联流会在 zip 中央目录校验处失败（CRC/EOCD 错误），
     * 此时错误信息如实传递（比静默解一半好）。此函数只检出「已拖入批次内部」的缺口。
     */
    fun checkCompleteness(group: SplitGroup): String? {
        if (group.parts.isEmpty()) return null
        val kinds = group.parts.map { kind(it.name) }
        if (kinds.toSet().size > 1) {
            return "卷后缀形态混杂（${kinds.toSet().joinToString("、")}），不是同一组"
        }
        val numbers = group.parts.mapIndexed { _, file -> volumeNumber(file.name) }
        val expected = numbers.min()..numbers.max()
        val missing = (expected - numbers.toSet()).toList()
        if (missing.isNotEmpty()) {
            val sample = group.parts.first().name
            return "缺少分卷：${missing.joinToString("、") { volumeLabel(sample, it) }}"
        }
        return null
    }

    /**
     * 按序串联分卷为一条流（字节切分复原）。
     *
     * ⚠️ 组内卷序由 [group] 产出时已排序；本函数信任调用方传入有序列表。
     */
    fun open(parts: List<File>): InputStream = SequenceInputStream(
        java.util.Collections.enumeration(parts.map { FileInputStream(it) }),
    )

    // ── 内部：名字解析 ──

    /** 组内卷排序（按卷编号升序） */
    private fun orderParts(files: List<File>): List<File> =
        files.sortedBy { volumeNumber(it.name) }

    /** 基名（只剥卷号段，保留容器后缀）：`foo.zip.001` → `foo.zip`；`bar-partaa` → `bar`。
     *  大小写防御：正则匹配在 lower 上做、截断在原名上做（原名后缀含大写时
     *  removeSuffix 会失败，故用 dropLast(匹配串长度)——与原名大小写无关）。 */
    private fun baseName(fileName: String): String {
        val lower = fileName.lowercase()
        NUMERIC_SPLIT.find(lower)?.let { m ->
            // 只剥末段卷号（.001）：m.value 是 ".zip.001"，剥卷号段 = 去掉末尾 ".NNN"
            val volumeSuffix = Regex("\\.\\d{3}$").find(lower)!!
            return fileName.dropLast(volumeSuffix.value.length)
        }
        ALPHA_SPLIT.find(lower)?.let { return fileName.dropLast(it.value.length) }
        PKZIP_SPLIT.find(lower)?.let { return fileName.dropLast(it.value.length) }
        return fileName
    }

    /** 卷形态（用于同组混杂校验） */
    private fun kind(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            NUMERIC_SPLIT.containsMatchIn(lower) -> "数字卷"
            ALPHA_SPLIT.containsMatchIn(lower) -> "字母卷"
            else -> "PKZip 卷"
        }
    }

    /** 卷编号（数字卷直接取；字母卷 aa=0/ab=1/…；PKZip 卷 z01=1/…） */
    private fun volumeNumber(fileName: String): Int {
        val lower = fileName.lowercase()
        NUMERIC_SPLIT.find(lower)?.let { return it.groupValues[2].toInt() }
        ALPHA_SPLIT.find(lower)?.let { m ->
            val a = m.groupValues[1]
            return (a[0] - 'a') * 26 + (a[1] - 'a')
        }
        PKZIP_SPLIT.find(lower)?.let { return it.groupValues[1].toInt() }
        return -1
    }

    /** 卷后缀形态辅助：为缺失卷生成「基名.00N」式标签（错误信息用） */
    private fun volumeLabel(sampleName: String, number: Int): String {
        val lower = sampleName.lowercase()
        return when {
            NUMERIC_SPLIT.containsMatchIn(lower) -> "%03d".format(number)
            ALPHA_SPLIT.containsMatchIn(lower) -> {
                val a = 'a' + (number / 26); val b = 'a' + (number % 26)
                "-part$a$b"
            }
            else -> ".z%02d".format(number)
        }
    }
}
