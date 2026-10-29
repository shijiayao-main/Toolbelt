package com.jiaoay.easy_dev_toolbox.page.archive

import com.jiaoay.utils.SafeExtract
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * Tier 1 解码入口：PKZip 规范分卷（`.z01…` 族）与 7z 容器。
 *
 * ## 为何不能走 SplitArchive 路径
 * SplitArchive 只做「字节切分复原」（.zip.001 族直接串联成流喂 ZipInputStream）；
 * PKZip 规范分卷**不是**字节切分——每卷自带分段标记、中央目录跨段分布，ZipInputStream 无法
 * 解读；7z 是 LZMA 系容器，无纯 JDK 解码器。两者都需 commons-compress
 *（唯一进本模块的第三方压缩库）。
 *
 * ## zip-slip 防护
 * 🔴 与 [SafeExtract.safeChild] 契约逐字对齐（behavior-contract.md §4.7）：绝对路径 / `..` 穿越 / 链接条目
 *    跳过。SafeExtract 是禁止重写块，本文件是规则的单侧重放（新逃生面出现时两处都要修）。
 *
 * ## 判定集纪律
 * 本文件不改 `SafeExtract.isArchive`（多域共享：agent 工具 fs.* / lark-bridge
 * PreprocessPipeline autoExtract / 本页）——新格式只进本页本地判定 [isTier1ArchiveName]，
 * 桥接域与既有调用方的行为不变（真机语义稳定优先）。
 */
internal object VolumeAndSevenZip {

    /** PKZip 规范分卷（.z01…，两到三位卷号） */
    private val PKZIP_VOLUME = Regex("\\.z(\\d{2,3})$")

    /** 7z 容器（含 7z 字节切分分卷 .7z.001 的复原流入口由 SplitArchive 串联后仍进本类） */
    private const val SEVENZ_EXT = ".7z"

    /**
     * Tier 1 格式判定（本页放行面；不进 SafeExtract.isArchive）。
     * `.z01` 族（任意卷）或 `.7z`；7z 字节切分（.7z.001）由 [SplitArchive.isSplitArchiveName]
     * 先行归组，串联流进来时文件名基名是 `.7z`——process 路径按 [SEVENZ_EXT] 分派。
     */
    fun isTier1ArchiveName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return PKZIP_VOLUME.containsMatchIn(lower) || lower.endsWith(SEVENZ_EXT)
    }

    /** 是否为 PKZip 分卷族（归组路径用；7z 单文件不走组卡）。 */
    fun isPkzipVolumeName(fileName: String): Boolean =
        PKZIP_VOLUME.containsMatchIn(fileName.lowercase())

    /**
     * 解 PKZip 规范分卷组到 [destDir]。
     *
     * commons-compress 的 [ZipFile] 原生支持跨段中央目录（`ZipFile.Builder` 传首卷即可，
     * `setUseUnicodeExtraFields` 保留文件名编码容错）。
     *
     * @param volumes 同组全部卷（**.zip 末卷必须在内**——PKZip 分卷约定：.z01…zNN 是数据卷，
     *                基名.zip 是中央目录所在末卷；缺末卷无法定位目录，错误如实上抛）
     * @param checkCancelled 协作取消检查点（解压循环逐条目/逐块边界调用——任务取消后
     *                       即停止写产物；默认空实现 = 无取消语义的调用方零成本）
     * @return 解出的文件列表（绝对路径；跳过目录与被安全策略拒绝的条目）
     */
    fun extractPkzipVolumes(volumes: List<File>, destDir: File, checkCancelled: () -> Unit = {}): List<File> {
        require(volumes.isNotEmpty()) { "分卷组为空" }
        val first = volumes.first()
        // 🔴 分卷数上限——commons-compress 默认 unlimited，
        //    ZipFile 打开分卷时会「追卷读」（按 .z01…zNN 递推），用户误选一个目录下
        //    海量同基名卷（或恶意构造）时无限吞文件描述符。上限取「传入卷数 × 2」：
        //    正常组（末卷 + 数据卷）恰好覆盖（+1 余量），异常膨胀即 IllegalArgumentException。
        //    （volumes 已由调用方按基名分组，×2 覆盖「末卷不在列表内」的常见用法。）
        val zf = ZipFile.builder()
            .setFile(first)
            .setUseUnicodeExtraFields(true)
            .setMaxNumberOfDisks((volumes.size * 2L).coerceAtLeast(2L))
            .get() as ZipFile
        return useZipFile(zf, destDir, checkCancelled)
    }

    /**
     * 解 7z 容器到 [destDir]（单文件或分卷串联流——串联回来的 7z 流经 [SplitArchive.open]
     * 复原为完整容器字节，SevenZFile 可直接读）。
     *
     * 内存上限（SevenZFile 构造参数）只限 LZMA 解码字典内存、不限解出字节——解出量
     * 由 [ExtractGates] 的三闸管住：条目数 + 累计 2GiB（逐 64KiB 写盘块强制）+ 整容器
     * 压缩比（累计解出量÷容器字节，10MiB 落盘后起查——7z 条目的 compressedSize 读取器
     * 是 commons-compress 包私有、Kotlin 侧不可达，整容器比值是不新增解析面的替代取数）。
     *
     * @param sevenZipFile 完整 7z 容器文件（分卷族请先串联还原为单文件再进本函数）
     * @param checkCancelled 协作取消检查点（解压循环逐条目边界调用——语义同 [extractPkzipVolumes]）
     */
    fun extractSevenZip(sevenZipFile: File, destDir: File, checkCancelled: () -> Unit = {}): List<File> {
        destDir.mkdirs()
        val files = mutableListOf<File>()
        // 🔴 7z 解压必须设内存上限。
        //    commons-compress 的默认 maxMemoryLimitKiB = Integer.MAX_VALUE——LZMA 字典可达 4GiB，
        //    叠加「打包产物未设 -Xmx」，大字典 .7z 直接 OutOfMemoryError 而非可读失败。
        //    512MiB 是 OOM 防护上界（只拒大字典解码器；小字典膨胀炸弹不被其拒，写盘量由
        //    ExtractGates 管）；超限时 commons-compress 抛 MemoryLimitException
        //    （CompressException→IOException 子类，含可读文案），由上层转用户提示。
        SevenZFile.builder()
            .setFile(sevenZipFile)
            .setMaxMemoryLimitKiB(512 * 1024)
            .get()
            .use { szf ->
            val gates = ExtractGates(containerBytes = sevenZipFile.length())
            while (true) {
                // 协作取消检查点：逐条目边界
                checkCancelled()
                // 1.28 API：getNextEntry()（nextEntry 是 Kotlin 属性名误写；javap 核实）
                val entry = szf.getNextEntry() ?: break
                if (entry.isDirectory) continue
                gates.admitEntry()
                val target = safeChild(destDir, entry.name) ?: continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    copyWithLimits(szf.getInputStream(entry), out, entry.name, 0L, checkCancelled, gates)
                }
                files.add(target)
            }
        }
        return files
    }

    /** 解压防护常量（与 :log-tools 的 SafeExtract 同口径；此处无跨模块依赖故本地持有）。 */
    private const val MAX_TOTAL_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024   // 2 GiB
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val MAX_EXTRACT_ENTRIES = 100_000
    private const val RATIO_CHECK_FLOOR_BYTES = 10L * 1024 * 1024            // 10 MiB

    /**
     * 单次解压的运行态闸门（三闸常量的跨条目载体——条目数 + 累计字节 + 整容器压缩比）。
     * 一次解压调用一个实例；本模块三条写盘路径（PKZip 分卷 / 7z / 流式 zip）共用同一闸。
     * 🔴 超限抛 [IllegalArgumentException] 而非静默跳过——静默会让用户以为解压成功但文件不全。
     *
     * @param containerBytes 整容器比闸基数（容器文件字节）。0 = 该闸关闭——PKZip/流式 zip
     *   路径已有逐条目压缩比闸（compressedSize 可达）；仅 7z 路径传入（compressedSize
     *   包私有不可达，整容器比值是替代取数）。
     */
    internal class ExtractGates(private val containerBytes: Long = 0L) {
        private var usedBytes = 0L
        private var entryCount = 0L

        /** 条目数闸（文件条目计数，目录条目不计——每写盘条目前调用）。 */
        fun admitEntry() {
            if (++entryCount > MAX_EXTRACT_ENTRIES) {
                throw IllegalArgumentException("压缩包条目数超过上限（$MAX_EXTRACT_ENTRIES）——疑似解压炸弹，已中止")
            }
        }

        /** 累计字节闸 + 整容器比闸（每写盘块后调用——跨条目累计，非单条目配额）。 */
        fun addBytes(n: Long, entryName: String) {
            usedBytes += n
            if (usedBytes > MAX_TOTAL_EXTRACTED_BYTES) {
                throw IllegalArgumentException("解压总量超过安全上限（2GB）——疑似解压炸弹，已中止。条目：$entryName")
            }
            if (containerBytes > 0 && usedBytes > RATIO_CHECK_FLOOR_BYTES &&
                usedBytes / containerBytes > MAX_COMPRESSION_RATIO
            ) {
                throw IllegalArgumentException(
                    "容器压缩比异常（已解出 $usedBytes 字节，容器 $containerBytes 字节，上限 $MAX_COMPRESSION_RATIO:1）" +
                        "——疑似解压炸弹，已中止。条目：$entryName",
                )
            }
        }
    }

    /**
     * 受限拷贝（本模块三条写盘路径共用）：累计字节闸（[ExtractGates.addBytes]）+
     * 单条目压缩比闸。目录条目与被 zip-slip 策略拒绝的条目不经本函数（无写盘即无闸耗）。
     *
     * @param compressedSize 条目压缩后大小；<= 0 = 未知（流式 zip / 7z 无可达的压缩后
     *        大小时单条目比闸跳过——流式 zip 由累计字节闸兜底，7z 另有整容器比闸兜底）
     * @return 实际写入字节数
     */
    internal fun copyWithLimits(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        entryName: String,
        compressedSize: Long,
        checkCancelled: () -> Unit,
        gates: ExtractGates,
    ): Long {
        var written = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            // 协作取消检查点：逐块边界（单条目超大时取消不必等整条目写完）
            checkCancelled()
            val n = input.read(buf)
            if (n < 0) break
            written += n
            gates.addBytes(n.toLong(), entryName)
            if (compressedSize > 0 && written > RATIO_CHECK_FLOOR_BYTES) {
                val ratio = written / compressedSize
                if (ratio > MAX_COMPRESSION_RATIO) {
                    throw IllegalArgumentException("条目压缩比异常（${ratio}:1，上限 ${MAX_COMPRESSION_RATIO}:1）——疑似解压炸弹，已中止。条目：$entryName")
                }
            }
            out.write(buf, 0, n)
        }
        return written
    }

    /** [ZipFile] 条目遍历 + zip-slip 过滤 + 落盘（分卷路径共用）。 */
    private fun useZipFile(zf: ZipFile, destDir: File, checkCancelled: () -> Unit): List<File> {
        destDir.mkdirs()
        val files = mutableListOf<File>()
        // 累计 2GiB + 条目数 + 压缩比三闸（PKZip 路径与 SafeExtract 同口径）。
        val gates = ExtractGates()
        zf.use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                // 协作取消检查点：逐条目边界
                checkCancelled()
                val entry = entries.nextElement() as ZipArchiveEntry
                if (entry.isDirectory) continue
                gates.admitEntry()
                val target = safeChild(destDir, entry.name) ?: continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    copyWithLimits(zip.getInputStream(entry), out, entry.name, entry.compressedSize, checkCancelled, gates)
                }
                files.add(target)
            }
        }
        return files
    }

    /** zip-slip 校验（[SafeExtract.safeChild] 规则的单侧重放：canonical 沙箱内才放行）。 */
    private fun safeChild(destDir: File, entryName: String): File? = runCatching {
        val target = File(destDir, entryName).canonicalFile
        val destRoot = destDir.canonicalFile
        if (!target.path.startsWith(destRoot.path + File.separator)) null else target
    }.getOrNull()

    /**
     * 串联 7z 字节切分分卷为完整容器（.7z.001 族：Tier 0 已识别但当时无解码器只提示；
     * 现串联到临时单文件再解）。
     *
     * @return 还原的完整 7z 文件（调用方负责删除）
     */
    fun joinSevenZipSplits(parts: List<File>, destDir: File): File {
        val target = File(destDir, "${SplitArchiveBaseName.of(parts.first())}")
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            SplitArchive.open(parts).use { input -> input.copyTo(out) }
        }
        return target
    }
}

/** 分卷基名（[SplitArchive.baseName] 的公开窄面——join 产物命名用）。 */
internal object SplitArchiveBaseName {
    fun of(firstVolume: File): String {
        val name = firstVolume.name
        val lower = name.lowercase()
        Regex("\\.\\d{3}$").find(lower)?.let { return name.dropLast(it.value.length) }
        return name
    }
}
