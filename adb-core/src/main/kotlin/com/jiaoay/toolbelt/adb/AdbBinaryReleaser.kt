package com.jiaoay.toolbelt.adb

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 随包 adb 的释放结果。
 *
 * 四种结果都不抛异常——释放是启动期可容忍失败的准备工作，失败只意味着「这次用不了内置
 * adb」，应用仍应能靠用户自装的 adb 或 PATH 上的 adb 工作。
 */
sealed class AdbReleaseOutcome {
    /** 本次调用完成了文件写入（含升级时清理旧版本后写入新版本） */
    data class Released(val executable: File) : AdbReleaseOutcome()

    /** 目标版本已在位且内容校验通过，未写任何文件 */
    data class AlreadyPresent(val executable: File) : AdbReleaseOutcome()

    /**
     * 目标目录被无法识别的文件占用，拒绝写入也不删除。
     *
     * [hint] 给人看的人话处置建议；调用方必须把它送达用户可见面，
     * 不得静默回退到 PATH——否则没装 adb 的用户只会看到「没有设备」。
     */
    data class Conflict(val hint: String) : AdbReleaseOutcome()

    /** 资源缺失、校验不符或写入失败；[reason] 供诊断日志与用户可见面使用 */
    data class Failed(val reason: String) : AdbReleaseOutcome()
}

/**
 * 从随包资源释放当前平台的 adb 到用户数据目录，并缓存「本进程已校验」结论。
 *
 * ## 释放目录与版本
 *
 * 目录为 `<注入的数据根>/adb/v<版本>/`，版本来自随包资源清单——版本升目录升，
 * 旧版本目录自然不被复用。清单同时承担构建期版本戳职责（`src/main/resources/adb/<平台>`）。
 *
 * ## 升级时为何不 Conflict
 *
 * 旧版本目录是**上一版应用自己释放的**产物，不是用户文件。若把它当冲突拒绝写入，
 * 应用一升级内置 adb 就静默失效——对没装 adb 的用户等于设备功能全灭。故按释放印记
 * 判别：目录内清单与随包清单的**文件名集合**一致即视为自家产物，直接清理覆盖；
 * 只有认不出的文件（用户自置）才 Conflict。
 *
 * ## 校验代价（两段式）
 *
 * 全量 sha256 只在进程内首次释放/校验时做一次：解析端每 5 秒轮询一次、每条 adb 命令
 * 前再读一次，逐次重算 10 MB 哈希不可接受。首验通过后本实例记住结论，此后走纯
 * `isFile` + 可执行位判断。
 *
 * ## 线程模型
 *
 * [ensureReleased] 可被多线程并发调用（轮询协程与用户操作可能同时首触）；用 [lock]
 * 串行化首释，快速路径在锁外先判。
 */
class AdbBinaryReleaser(
    /** 数据根下的 adb 目录（由调用方注入，通常是 `<数据根>/adb`） */
    private val adbRootDir: File,
    /** 当前平台；null = 不认识的系统，直接返回 [AdbReleaseOutcome.Failed] */
    private val platform: AdbPlatform? = AdbPlatform.current(),
    /** 资源加载缝：默认走本类 classloader；测试可注入内存资源 */
    private val resourceLoader: (String) -> ByteArray? = { path ->
        AdbBinaryReleaser::class.java.getResourceAsStream(path)?.use { it.readBytes() }
    },
) {

    private val lock = Any()
    private val verified = AtomicBoolean(false)

    /** 首验通过时记下的版本（快速路径据此拼可执行文件路径，不必重读资源清单） */
    @Volatile
    private var verifiedVersion: String? = null

    /** 本进程内是否已确认内置 adb 可用（供解析端快速判断，无需再走文件系统） */
    val isVerified: Boolean get() = verified.get()

    /**
     * 确保内置 adb 已释放并就绪。
     *
     * 幂等：同一实例重复调用，首次通过校验后不再触碰磁盘哈希、也不再读资源清单。
     */
    fun ensureReleased(): AdbReleaseOutcome {
        val targetPlatform = platform
            ?: return AdbReleaseOutcome.Failed("当前系统不支持内置 adb（os.name=${System.getProperty("os.name")}）")

        fastPath(targetPlatform)?.let { return it }

        synchronized(lock) {
            fastPath(targetPlatform)?.let { return it }
            return releaseLocked(targetPlatform)
        }
    }

    /** 已验快速路径：不读资源、不算哈希，只做一次 stat */
    private fun fastPath(targetPlatform: AdbPlatform): AdbReleaseOutcome? {
        if (!verified.get()) return null
        val version = verifiedVersion ?: return null
        val executable = File(versionDir(version), targetPlatform.executableName)
        if (executable.isFile) return AdbReleaseOutcome.AlreadyPresent(executable)
        // 文件在进程生命周期内被外部删除/移动：撤销已验标记，走完整流程重建
        verified.set(false)
        verifiedVersion = null
        return null
    }

    /**
     * 撤销量产校验标记并从磁盘删除本版本目录（退出收口等场景可用）。
     * 删除失败不抛异常——残留目录会在下次 [ensureReleased] 时按自家产物清理。
     */
    fun discardReleased(): Boolean {
        val version = verifiedVersion
        verifiedVersion = null
        verified.set(false)
        val target = version ?: readManifest(platform ?: return false)?.getProperty("version") ?: return false
        val dir = versionDir(target)
        return !dir.exists() || dir.deleteRecursively()
    }

    // ── 释放流程 ─────────────────────────────────────────────────────────

    private fun releaseLocked(targetPlatform: AdbPlatform): AdbReleaseOutcome {
        val manifest = readManifest(targetPlatform)
            ?: return AdbReleaseOutcome.Failed(
                "随包 adb 资源缺失（adb/${targetPlatform.resourceKey}/manifest.properties 未找到）——" +
                    "构建时 prepareBundledAdb 任务可能未执行；见 core/libs/adb/README.md",
            )
        val version = manifest.getProperty("version")
            ?: return AdbReleaseOutcome.Failed("随包 adb 资源清单缺少 version")
        val files = manifest.getProperty("files")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return AdbReleaseOutcome.Failed("随包 adb 资源清单缺少 files")

        val dir = versionDir(version)

        if (dir.isDirectory) {
            when (val existing = verifyExisting(dir, files, manifest, version)) {
                VerifyResult.OK -> {
                    verifiedVersion = version
                    verified.set(true)
                    return AdbReleaseOutcome.AlreadyPresent(File(dir, targetPlatform.executableName))
                }
                VerifyResult.MISMATCH -> return AdbReleaseOutcome.Failed(
                    "内置 adb 已存在但内容校验不符（$dir）——文件可能损坏或被替换；删除该目录后重启应用即可恢复",
                )
                VerifyResult.STALE_SELF -> {
                    // 自家旧产物（版本号不同但清单同名文件）：清理后写新版本，不打扰用户
                    dir.deleteRecursively()
                }
            }
        } else if (dir.exists()) {
            return AdbReleaseOutcome.Conflict(
                "内置 adb 目录被同名文件占用（$dir）——请删除该文件后重启应用",
            )
        }

        // 同根下其他版本目录：认得出是自家产物就清理，认不出就 Conflict
        val selfCheck = sweepStaleSelfDirs(dir, files)
        if (selfCheck != null) return selfCheck

        return writeRelease(targetPlatform, dir, files, manifest, version)
    }

    private enum class VerifyResult { OK, MISMATCH, STALE_SELF }

    private fun verifyExisting(
        dir: File,
        files: List<String>,
        manifest: Properties,
        version: String,
    ): VerifyResult {
        // 版本内嵌目录名已隔离版本；同名文件齐备且哈希相符才算 OK，否则按内容不符处理
        for (name in files) {
            val file = File(dir, name)
            if (!file.isFile) return VerifyResult.STALE_SELF
            val expected = manifest.getProperty("sha256.$name") ?: return VerifyResult.MISMATCH
            if (sha256(file) != expected) return VerifyResult.MISMATCH
        }
        return VerifyResult.OK
    }

    /**
     * 清理同根下其他 `v*` 目录；发现无法识别的目录返回 Conflict 结果，否则返回 null。
     *
     * 判别依据是**释放印记**（`manifest.properties`）：本应用释放的目录必然带它，
     * 用户自置目录不会有。只看文件名集合会把「正好只放了 adb 一个文件的用户目录」
     * 误判成自家产物删掉——删用户文件不可逆，宁可在认不出时停下问人。
     */
    private fun sweepStaleSelfDirs(targetDir: File, files: List<String>): AdbReleaseOutcome? {
        val siblings = adbRootDir.listFiles()?.filter { it.isDirectory && it != targetDir } ?: return null
        val known = files + MANIFEST_STAMP
        for (dir in siblings) {
            val contents = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            if (contents.isEmpty()) continue
            val looksLikeOurs = MANIFEST_STAMP in contents && contents.all { it in known }
            if (looksLikeOurs) {
                dir.deleteRecursively()
            } else {
                return AdbReleaseOutcome.Conflict(
                    "内置 adb 目录下存在无法识别的目录（${dir.name}）——请确认其中没有你需要的文件后删除，" +
                        "或清空 $adbRootDir 后重启应用",
                )
            }
        }
        return null
    }

    private fun writeRelease(
        targetPlatform: AdbPlatform,
        dir: File,
        files: List<String>,
        manifest: Properties,
        version: String,
    ): AdbReleaseOutcome {
        if (!dir.mkdirs() && !dir.isDirectory) {
            return AdbReleaseOutcome.Failed("无法创建内置 adb 目录（$dir）——请检查磁盘权限")
        }
        for (name in files) {
            val expected = manifest.getProperty("sha256.$name")
                ?: return AdbReleaseOutcome.Failed("随包 adb 清单缺少 $name 的校验值")
            val bytes = resourceLoader("/adb/${targetPlatform.resourceKey}/$name")
                ?: return AdbReleaseOutcome.Failed("随包资源缺失：adb/${targetPlatform.resourceKey}/$name")
            val actual = sha256(bytes)
            if (actual != expected) {
                return AdbReleaseOutcome.Failed("随包资源 $name 校验不符（期望 $expected，实际 $actual）")
            }
            val out = File(dir, name)
            try {
                FileOutputStream(out).use { it.write(bytes) }
            } catch (e: Exception) {
                dir.deleteRecursively()
                return AdbReleaseOutcome.Failed("写入 $name 失败：${e.message}")
            }
            // Unix 可执行位：jar 资源不带权限信息，只能在落盘时补；Windows 靠扩展名
            if (name == targetPlatform.executableName && !targetPlatform.isWindows) {
                if (!out.setExecutable(true, false)) {
                    dir.deleteRecursively()
                    return AdbReleaseOutcome.Failed("无法为内置 adb 设置可执行位（$out）")
                }
            }
        }
        // 释放印记：版本戳写下后，下次启动能确认这批文件是自家产物（也是升级清理的判别依据）
        runCatching { File(dir, MANIFEST_STAMP).writeText(manifest.render()) }
        verifiedVersion = version
        verified.set(true)
        return AdbReleaseOutcome.Released(File(dir, targetPlatform.executableName))
    }

    // ── 资源与路径 ────────────────────────────────────────────────────────

    private fun readManifest(targetPlatform: AdbPlatform): Properties? {
        val bytes = resourceLoader("/adb/${targetPlatform.resourceKey}/manifest.properties") ?: return null
        return Properties().apply {
            runCatching { load(bytes.inputStream().reader(Charsets.UTF_8)) }
        }.takeIf { it.getProperty("version") != null }
    }

    /** 清单文本形态（释放印记写盘用；键有序便于人工排查） */
    private fun Properties.render(): String = buildString {
        val files = getProperty("files").orEmpty()
        appendLine("version=${getProperty("version")}")
        appendLine("platform=${getProperty("platform")}")
        appendLine("files=$files")
        files.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            appendLine("sha256.$name=${getProperty("sha256.$name")}")
        }
    }

    private fun versionDir(version: String): File = File(adbRootDir, "v$version")

    private fun executableFile(targetPlatform: AdbPlatform, version: String?): File? =
        version?.let { File(versionDir(it), targetPlatform.executableName) }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_STAMP = "manifest.properties"
    }
}
