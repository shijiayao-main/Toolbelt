package com.jiaoay.easy_dev_toolbox.page.archive

import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel
import com.jiaoay.easy_dev_toolbox.widget.task.ProcessResult
import com.jiaoay.easy_dev_toolbox.widget.task.Validation
import com.jiaoay.utils.SafeExtract
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/**
 * 压缩包解压（zip / tar / tar.gz / tgz / 单文件 gz + **分卷族** + **7z**）。
 *
 * 零 LLM 依赖：基础能力来自 [SafeExtract]——**纯 JDK 实现**（`ZipInputStream` /
 * `GZIPInputStream` + 手写 ustar 解析），不调用任何系统命令（无 `unzip` / `tar`），
 * 故不依赖用户本机环境；zip-slip 防护（绝对路径 / `..` 穿越 / 符号链接条目拒绝）已在其内。
 *
 * ## 分卷支持
 * - **字节切分族** `.zip.NNN`（可只拖部分卷）→ 按基名归组 → **一组分卷一张卡**
 *   → 按序 `SequenceInputStream` 串联喂 `ZipInputStream` 完整解压（纯 JDK 零新依赖）；
 * - **7z 切分族** `.7z.NNN` → 组卡 → process 串联还原完整容器再进
 *   7z 解码（LZMA）；
 * - **PKZip 规范分卷** `.z01…zNN`：组卡 → process 走 commons-compress
 *   `ZipFile` 跨段直读——末卷是 `基名.zip`（中央目录所在），**不在分卷组内**
 *   （组基名无 .zip 后缀），缺末卷由 process 如实报错；
 * - 三放行族组内卷号连续性同由 [SplitArchive.checkCompleteness] 把关：
 *   缺卷（组内编号不连续）→ 建卡前报错，不建残卷卡；
 * - `-partNN` 字母卷：识别后仍提示不支持（切分工具私有惯例多，串联语义未验证）；
 *   rar 无主流 Java 库，永久不支持——混拖时不再静默丢弃分卷。
 *
 * 判定集纪律：`.z01`/`.7z` 放行**只进本页本地判定**（[VolumeAndSevenZip]），
 *    不动 `SafeExtract.isArchive`（三域共享判定集，桥接域预处理行为不因本页扩容而变）。
 *
 * 任务流复用 [FileTaskViewModel]；分卷组是 **N 文件 → 1 卡**建模（组卡显示名 =
 * 基名如 `foo.zip`，组代表 = 首卷），经基类 [FileTaskViewModel.submitTask] 缝实现。
 * zip-slip 防护语义逐字对齐 [SafeExtract]（`safeChild` 同款规则在组卡路径重放）。
 */
class ArchiveViewModel(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileTaskViewModel(ioDispatcher) {

    /**
     * 普通文件的整批判校验（分卷在 [addFiles] 分流摘出后不进本函数）。
     *
     * 拖放区已按 accepts 过滤，此处是第二道闸——防「accepts 与 process 判定漂移」
     * 时静默失败（既有语义，回归用例钉死）。
     */
    override fun validate(files: List<File>): Validation {
        val unsupported = files.filter {
            !SafeExtract.isArchive(it.name) &&
                !SplitArchive.isSplitArchiveName(it.name) &&
                !VolumeAndSevenZip.isTier1ArchiveName(it.name)
        }
        return if (unsupported.isEmpty()) {
            Validation.Passed
        } else {
            Validation.Error(
                "以下文件不是支持的压缩格式（${SUPPORTED_FORMATS}）：" +
                    unsupported.joinToString("、") { it.name },
            )
        }
    }

    /**
     * 批量入口接管：批次先分流——分卷组与普通压缩包两路。
     *
     * 普通压缩包 → 基类既有逐文件路径（super，含 [validate] 二道闸）；分卷组 →
     * 归组校验后一组一卡。混拖批次两路都跑，任何一路的失败不阻断另一路。
     *
     * 🔴 inputError 聚合收口：两路（super 建卡路 / 分卷组路）的拒绝原因先收集进
     *    `errors`，末尾**单次赋值**（勿各自直接置 _inputError——相互覆写丢失错误对象）。
     *    ≤[INPUT_ERROR_MAX_LINES] 条逐条并列（换行分隔），超限取前 N 条 +
     *    「…等 N 组」计数尾行；两路全过则清空（null）——与基类 Passed 清空语义一致。
     *    regulars 过闸才走 super 建卡（其 Passed 分支置的 null 随后被聚合赋值覆盖，
     *    行为不变）；[Validation.SilentReject] 保持静默（不提示不建卡）。
     */
    override fun addFiles(files: List<File>) {
        val splits = files.filter { SplitArchive.isSplitArchiveName(it.name) }
        // 混拖边：PKZip 组的「基名.zip」末卷不是分卷，先摘出防其漏进 regulars 建假卡
        val pkzipFinals = collectPkzipFinalVolumes(splits, files)
        val regulars = files.filterNot { it in splits || it in pkzipFinals }

        val errors = mutableListOf<String>()
        if (regulars.isNotEmpty()) {
            when (val verdict = validate(regulars)) {
                is Validation.Error -> errors += verdict.message
                // SilentReject 在归档域不可达（validate 只出 Passed/Error）——防御分支
                // 不再静默：万一上游扩展出静默语义也明示未受理（R14 拒绝必带原因）
                Validation.SilentReject -> errors += "部分文件未通过校验，未受理"
                Validation.Passed -> super.addFiles(regulars)
            }
        }
        if (splits.isNotEmpty()) {
            addSplitGroups(splits, errors)
        }
        _inputError.value = when {
            errors.isEmpty() -> null
            errors.size <= INPUT_ERROR_MAX_LINES -> errors.joinToString("\n")
            else -> errors.take(INPUT_ERROR_MAX_LINES).joinToString("\n") +
                "\n…等 ${errors.size} 组"
        }
    }

    private fun collectPkzipFinalVolumes(splits: List<File>, batch: List<File>): List<File> {
        val pkzipBaseNames = SplitArchive.group(splits)
            .filter { group -> group.parts.any { VolumeAndSevenZip.isPkzipVolumeName(it.name) } }
            .map { it.baseName }
        if (pkzipBaseNames.isEmpty()) return emptyList()
        return batch.filter { regular ->
            regular !in splits && pkzipBaseNames.any { regular.name.equals("$it.zip", ignoreCase = true) }
        }
    }

    /**
     * 分卷归组建组卡（三分派放行）。
     *
     * - **zip / 7z 切分组**：[SplitArchive.checkCompleteness] 卷号连续性校验（对
     *   `.zip.NNN` 与 `.7z.NNN` 同样适用）→ 过则一组一卡（组代表 = 首卷）；
     * - **PKZip 规范分卷组**（.zNN）：同过 checkCompleteness（.zNN 卷号连续性校验）
     *   → 过则建卡——末卷「基名.zip」不进组 parts（见 [collectPkzipFinalVolumes]），
     *   缺末卷不在建卡前拦（末卷不是 .zNN，卷号校验管不到它），由
     *   [processPkzipVolumes] 解压时如实报错；
     * - **-partNN 字母卷**：唯一保留拒绝族（切分工具私有惯例多，串联语义未验证），
     *   inputError 文案不建卡（识别成功但不静默丢弃）。
     *
     * 🔴 本函数只做「错误收集 + 建卡」——错误文案**append 进 [errors]**（由
     *    [addFiles] 聚合单次赋值，后组不再覆写前组），过闸组照常 [submitTask] 建卡
     *    （拒绝组不阻断同批次其他组，现语义保留）。
     */
    private fun addSplitGroups(splits: List<File>, errors: MutableList<String>) {
        SplitArchive.group(splits).forEach { group ->
            val kindLabel = when {
                group.parts.any { it.name.lowercase().matches(".+\\.zip\\.\\d{3}".toRegex()) } -> "zip"
                group.parts.any { it.name.lowercase().matches(".+\\.(7z)\\.\\d{3}".toRegex()) } -> "7z"
                group.parts.any { it.name.lowercase().matches(".+-part[a-z]{2}".toRegex()) } -> "rar 风格字母卷"
                else -> "PKZip 规范分卷"
            }
            if (kindLabel == "rar 风格字母卷") {
                // 错误条首行文案（支持族清单 = 「.zip.001 字节切分 / .7z.001 / .z01 PKZip 分卷」——
                // NNN 泛化写法与「7z 切分」措辞是转写漂移（「字节切分」对位词丢失）
                errors += "「${group.baseName}」已识别为 $kindLabel 分卷，该格式不支持解压" +
                    "（当前支持：.zip.001 字节切分 / .7z.001 / .z01 PKZip 分卷）"
                return@forEach
            }
            // 三放行族共用的组内完整性闸（zip/7z 数字卷 + PKZip .zNN 卷号连续性）
            val completeness = SplitArchive.checkCompleteness(group)
            if (completeness != null) {
                errors += completeness
                return@forEach
            }
            val first = group.parts.first()
            // N→1 建卡：组显示名 = 基名（PKZip 组带首卷文件名后缀——组基名无 .zip 后缀）
            // 组显示名 = 基名直用，组代表 = 首卷；
            // groupBadge = 类型/卷数徽章（.pill.mute「PKZip 组 · N 卷」）
            val displayName = if (kindLabel == "PKZip 规范分卷") first.name else group.baseName
            // badge 词形：
            // PKZip→「PKZip 组」/ zip→「zip 切分组」/ 7z→「7z 切分组」——kindLabel
            // 本体承担错误文案不动，badge 处单独映射
            val badgeWord = when (kindLabel) {
                "PKZip 规范分卷" -> "PKZip 组"
                "7z" -> "7z 切分组"
                "zip" -> "zip 切分组"
                else -> kindLabel
            }
            val badge = "$badgeWord · ${group.parts.size} 卷"
            submitTask(fileName = displayName, file = first, groupBadge = badge)
        }
    }

    /**
     * 产物目录（各分派支路的统一定义——提交去重与取消收口共用；分派与解压路径
     * 各自的 destDir 构造必须与本函数一致，否则去重键/收口路径漂移）。
     */
    private fun destDirFor(file: File): File {
        val lower = file.name.lowercase()
        return when {
            VolumeAndSevenZip.isPkzipVolumeName(lower) -> {
                val base = lower.dropLast(Regex("\\.z\\d{2,3}$").find(lower)!!.value.length)
                File(file.parentFile, "$base$EXTRACTED_SUFFIX")
            }
            else -> File(file.parentFile, "${groupBaseName(file)}$EXTRACTED_SUFFIX")
        }
    }

    override fun outputTargetsOf(file: File): Set<String> =
        setOf(destDirFor(file).absolutePath)

    /**
     * 产物目录在盘即挂覆盖裁决（与 logkit 重跑 affordance 同口径——重解压是合并
     * 语义时旧目录残留文件与新解压内容混杂，用户读到过期数据零提示）。
     * 输入文件路径的在途去重由基类并入（taskTargetsOf）。
     */
    override fun existingOutputConflictsOf(file: File): List<File> =
        listOfNotNull(destDirFor(file).takeIf { it.exists() })

    /**
     * 覆盖前清空旧产物目录（递归——默认 [File.delete] 对非空目录 no-op，
     * 残留旧文件正是本覆写要根治的混杂源）。
     */
    override fun deleteStaleOutputsOf(file: File) {
        existingOutputConflictsOf(file).forEach { it.deleteRecursively() }
    }

    /**
     * 取消收口：产物目录在盘即如实回填（「已取消 · 部分产物已保留」）。不删——
     * 部分解出的条目是派生物（重拖经覆盖确认可整目录再生），保留供用户查验已
     * 取出内容。
     */
    override fun onTaskCancelled(file: File): String? {
        val destDir = destDirFor(file)
        return if (destDir.isDirectory) destDir.absolutePath else null
    }

    /**
     * 解包到同级 `${基名}_extracted/` 目录（实际分流体——[process] 包裹 runCatching
     * + 成功信号，本函数只做分流）。
     *
     * 组卡（代表 = 首卷）与普通文件在此分流：组卡经 [SplitArchive.open] 串联整组
     * 喂 ZipInputStream（zip-slip `safeChild` 规则在组路径重放，语义与
     * [SafeExtract] 一致）；普通文件走原 [SafeExtract.extract]。
     */
    override suspend fun process(file: File): ProcessResult = runCatching {
        // 协作取消检查点（解压循环逐条目边界——[VolumeAndSevenZip] 与 [extractZipStream]）：
        // 任务取消后解压即停，已解出条目保留并经 [onTaskCancelled] 回填
        val job = coroutineContext[Job]
        val result = doProcess(file) { job?.ensureActive() }
        // 使用计数口径：解压完成（产物成功）= 一次真实使用
        if (result is ProcessResult.Success) {
            ToolUsageSignal.report(ToolUsageSignal.PageId.ARCHIVE)
        }
        result
    }.getOrElse { e ->
        // 取消是控制流非失败：原样上抛（基类 catch 据此落 Cancelled + 产物收口），
        // 若在此折算成 Failure，取消任务会被谎报为解压失败
        if (e is kotlinx.coroutines.CancellationException) throw e
        ProcessResult.Failure(e.message?.take(200) ?: (e::class.simpleName ?: "解压失败"))
    }

    private fun doProcess(file: File, checkCancelled: () -> Unit): ProcessResult {
        val name = file.name.lowercase()
        return when {
            // PKZip 规范分卷（.zNN 数据卷；中央目录在「基名.zip」末卷——缺末卷如实报错）
            VolumeAndSevenZip.isPkzipVolumeName(name) -> processPkzipVolumes(file, checkCancelled)

            // 7z 单文件直解
            name.endsWith(".7z") -> processSevenZip(file, file.name, checkCancelled)

            // 字节切分族：.7z.NNN 串联还原后进 7z 解码；.zip.NNN 原路径
            SplitArchive.isSplitArchiveName(name) -> when {
                Regex("\\.7z\\.\\d{3}$").containsMatchIn(name) -> processSevenZipSplits(file, checkCancelled)
                else -> processZipSplits(file, checkCancelled)
            }

            // 普通压缩包：SafeExtract 原路径（log-tools 共享件——循环检查点不入库，
            // 取消在 doProcess 返回后的挂起点被观察到，产物按收口钩子保留）
            else -> {
                val destDir = File(file.parentFile, "${file.name}$EXTRACTED_SUFFIX")
                val outputs = SafeExtract.extract(file, destDir)
                if (outputs.isEmpty()) {
                    ProcessResult.Failure("压缩包内无可解出的条目（可能全被安全策略拒绝）")
                } else {
                    ProcessResult.Success(destDir.absolutePath)
                }
            }
        }
    }

    /**
     * PKZip 规范分卷组解压。
     *
     * 组 = 同基名的 .zNN 数据卷 + 「基名.zip」末卷（跨段中央目录所在）。commons-compress
     * 的 ZipFile 直读首卷（内部按分段标记跳卷定位）；末卷缺失是结构性错误（无法定位目录）。
     */
    private fun processPkzipVolumes(file: File, checkCancelled: () -> Unit): ProcessResult {
        val lower = file.name.lowercase()
        val base = lower.dropLast(Regex("\\.z\\d{2,3}$").find(lower)!!.value.length)
        val dir = file.parentFile ?: file.absoluteFile.parentFile
        val volumes = dir?.listFiles()
            ?.filter { it.isFile && it.name.lowercase().startsWith("$base.") }
            ?.filter { VolumeAndSevenZip.isPkzipVolumeName(it.name) || it.name.lowercase() == "$base.zip" }
            .orEmpty()
        val finalVolume = volumes.firstOrNull { it.name.lowercase() == "$base.zip" }
            // 缺末卷卡 hint + design-language.md:389 错误四要素：
            // 问题定位段之后必须带修正指引段「补齐后重新拖入」（四要素缺修正项）
            ?: return ProcessResult.Failure(
                "缺少分卷末卷 $base.zip（PKZip 分卷的中央目录在末卷，缺它无法定位条目清单）——补齐后重新拖入",
            )
        val destDir = File(file.parentFile, "${base}$EXTRACTED_SUFFIX")
        // 🔴 传参 = 末卷首位 + 全组数据卷：末卷作 ZipFile 入口、数据卷撑起 commons-compress
        //    追卷上限（×传入卷数）——只传末卷属下限用法，真 3+ 盘归档（.z01+.z02+.zip）
        //    会被「Too many disks」误拒。
        val outputs = VolumeAndSevenZip.extractPkzipVolumes(
            listOf(finalVolume) + volumes.filter { it != finalVolume },
            destDir,
            checkCancelled,
        )
        return if (outputs.isEmpty()) {
            ProcessResult.Failure("压缩包内无可解出的条目（可能全被安全策略拒绝）")
        } else {
            ProcessResult.Success(destDir.absolutePath)
        }
    }

    /** 7z 单文件解压。 */
    private fun processSevenZip(file: File, displayBase: String, checkCancelled: () -> Unit): ProcessResult {
        val destDir = File(file.parentFile, "${displayBase}$EXTRACTED_SUFFIX")
        val outputs = VolumeAndSevenZip.extractSevenZip(file, destDir, checkCancelled)
        return if (outputs.isEmpty()) {
            ProcessResult.Failure("压缩包内无可解出的条目（可能全被安全策略拒绝）")
        } else {
            ProcessResult.Success(destDir.absolutePath)
        }
    }

    /**
     * 7z 字节切分分卷（.7z.NNN）：串联还原为完整容器再解。
     *
     * 🔴 产物路径：还原容器不落 `_joined.tmp` 临时目录——临时目录若作为产物根，
     *    `processSevenZip` 按容器所在目录推产物目录会把产物埋进
     *    `基名_extracted_joined.tmp/基名_extracted/`（闸门放行前不可达、放行后
     *    首次暴露的死路径）。产物直接落源文件同级 `基名_extracted/`
     *    （与其他族路径约定一致），临时目录用后即删。
     */
    private fun processSevenZipSplits(file: File, checkCancelled: () -> Unit): ProcessResult {
        val dir = file.parentFile ?: file.absoluteFile.parentFile
        val siblings = dir?.listFiles()
            ?.filter { it.isFile && SplitArchive.isSplitArchiveName(it.name) }
            .orEmpty()
        val parts = SplitArchive.group(siblings)
            .firstOrNull { groupBaseName(it.parts.first()) == groupBaseName(file) }?.parts
            ?: listOf(file)
        val base = groupBaseName(file) // foo.7z（剥 .NNN 后以 .7z 结尾）
        val destDir = File(file.parentFile, "${base}$EXTRACTED_SUFFIX")
        val joinedDir = File(destDir.parentFile, destDir.name + "_joined.tmp")
        val joined = VolumeAndSevenZip.joinSevenZipSplits(parts, joinedDir)
        try {
            val outputs = VolumeAndSevenZip.extractSevenZip(joined, destDir, checkCancelled)
            return if (outputs.isEmpty()) {
                ProcessResult.Failure("压缩包内无可解出的条目（可能全被安全策略拒绝）")
            } else {
                ProcessResult.Success(destDir.absolutePath)
            }
        } finally {
            joined.delete()
            joinedDir.delete()
        }
    }

    private fun processZipSplits(file: File, checkCancelled: () -> Unit): ProcessResult {
        val dir = file.parentFile ?: file.absoluteFile.parentFile
        val siblings = dir?.listFiles()
            ?.filter { it.isFile && it.name.lowercase().startsWith(groupBaseName(file).lowercase() + ".") }
            ?.filter { SplitArchive.isSplitArchiveName(it.name) }
            .orEmpty()
        val parts = SplitArchive.group(siblings).firstOrNull { groupBaseName(it.parts.first()) == groupBaseName(file) }?.parts
            ?: listOf(file)
        val destDir = File(file.parentFile, "${groupBaseName(file)}$EXTRACTED_SUFFIX")
        val outputs = extractZipStream(SplitArchive.open(parts), destDir, checkCancelled)
        return if (outputs.isEmpty()) {
            ProcessResult.Failure("压缩包内无可解出的条目（可能全被安全策略拒绝）")
        } else {
            ProcessResult.Success(destDir.absolutePath)
        }
    }

    /**
     * 流式 zip（.zip.NNN 串联族）写盘路径，闸门与 [VolumeAndSevenZip] 的 PKZip 路径同源：
     * 流式读不到条目的压缩后大小（无中央目录），压缩比闸跳过，只做累计字节 + 条目数两闸
     * （[VolumeAndSevenZip.ExtractGates] / [VolumeAndSevenZip.copyWithLimits]）。
     * 超限抛异常而非静默跳过（上层转用户提示），条目名仍过 [safeChild] zip-slip 校验。
     */
    private fun extractZipStream(input: java.io.InputStream, destDir: File, checkCancelled: () -> Unit): List<File> {
        destDir.mkdirs()
        val files = mutableListOf<File>()
        val gates = VolumeAndSevenZip.ExtractGates()
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                // 协作取消检查点：逐条目边界（用户取消 → 停止解压，已解出条目保留）；
                // 回调形态与 [VolumeAndSevenZip] 同款——本函数处于非挂起的 doProcess 链
                checkCancelled()
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                gates.admitEntry()
                val target = safeChild(destDir, entry.name) ?: continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    VolumeAndSevenZip.copyWithLimits(zis, out, entry.name, 0L, checkCancelled, gates)
                }
                files.add(target)
                zis.closeEntry()
            }
        }
        return files
    }

    /** zip-slip 校验（[SafeExtract.safeChild] 规则的本地重放，见 [extractZipStream] KDoc） */
    private fun safeChild(destDir: File, entryName: String): File? = runCatching {
        val target = File(destDir, entryName).canonicalFile
        val destRoot = destDir.canonicalFile
        if (!target.path.startsWith(destRoot.path + File.separator)) null else target
    }.getOrNull()

    /** 分卷基名（只剥卷号段，保留容器后缀与原大小写：foo.zip.001 → foo.zip） */
    internal fun groupBaseName(firstVolume: File): String {
        val m = Regex("\\.\\d{3}$").find(firstVolume.name.lowercase()) ?: return firstVolume.name
        return firstVolume.name.dropLast(m.value.length)
    }

    companion object {
        /** 产物目录后缀（产物目录约定沿用历史值） */
        const val EXTRACTED_SUFFIX = "_extracted"

        /** inputError 聚合上限行数：超出取前 N 条并列 + 「…等 N 组」计数尾行 */
        const val INPUT_ERROR_MAX_LINES = 3

        /** 支持格式的用户可读清单（与判定放行面同源：SafeExtract + 分卷 + Tier 1） */
        const val SUPPORTED_FORMATS = "zip / tar / tar.gz / tgz / gz / zip 分卷（.zip.001…）/ PKZip 分卷（.z01…）/ 7z（含 .7z.001 分卷）"

        @androidx.compose.runtime.Composable
        fun getViewModel(): ArchiveViewModel =
            androidx.lifecycle.viewmodel.compose.viewModel { ArchiveViewModel() }
    }
}
