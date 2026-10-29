package com.jiaoay.easy_dev_toolbox.core.data

import java.io.File

object FileUtils {
    private const val APP_DATA_DIR_NAME = ".toolbelt"

    /**
     * 旧数据根目录名（改名迁移专用——[LegacyDataRootMigration] 整树改名后不再读取）。
     * 除迁移与出站打码双形态兼容外，禁止以任何形式引用（新写路径恒用 [getAppDataDir]）。
     */
    internal const val LEGACY_APP_DATA_DIR_NAME = ".easy_dev_toolbox"

    private const val LOG_DIR_NAME = "logs"
    private const val DATA_STORE_DIR_NAME = ".datastore"
    private const val LARK_DIR_NAME = "lark"
    private const val DB_DIR_NAME = "db"
    private const val AUDIT_DIR_NAME = "audit"
    private const val DEVLOGS_DIR_NAME = "devlogs"
    private const val MEMORY_DIR_NAME = "memory"
    private const val LOGWORKBENCH_DIR_NAME = "logworkbench"
    private const val RUNS_DIR_NAME = "runs"
    private const val VOICE_DIR_NAME = "voice"
    private const val TRANSCRIPTS_DIR_NAME = "transcripts"
    private const val NOTES_DIR_NAME = "notes"
    private const val MODELS_DIR_NAME = "models"
    private const val NATIVE_CACHE_DIR_NAME = "native-cache"
    private const val SCRCPY_DIR_NAME = "scrcpy"
    private const val ADB_DIR_NAME = "adb"
    private const val SHELL_TOOLS_FILENAME = "shell_tools.json"
    private const val SHELL_TOOLS_CUSTOM_FILENAME = "shell_tools_custom.json"

    /** 可见产物树根名（产物树挂在系统 Documents 目录下：`<home>/Documents/Toolbelt`） */
    private const val VISIBLE_EXPORT_ROOT_NAME = "Toolbelt"
    private const val DOCUMENTS_DIR_NAME = "Documents"
    private const val REPORTS_EXPORT_SUBDIR_NAME = "reports"
    private const val RECORDINGS_EXPORT_SUBDIR_NAME = "recordings"
    private const val PERF_EXPORT_SUBDIR_NAME = "perf"
    private const val LOGCAT_EXPORT_SUBDIR_NAME = "logcat"
    private const val SNAPSHOTS_EXPORT_SUBDIR_NAME = "snapshots"
    private const val KNOWLEDGE_EXPORT_SUBDIR_NAME = "knowledge"

    fun getLogDir(): File {
        return File(getAppDataDir(), LOG_DIR_NAME)
    }

    fun getDatastoreDir(): File {
        return File(getAppDataDir(), DATA_STORE_DIR_NAME)
    }

    /**
     * 飞书桥接数据根（配置文档 / 审计 JSONL / 发送者统计 / 单实例锁目录）。
     * 桥接内核是纯 JVM lib（jvmTarget 11），不得反向依赖本模块，故数据根由宿主经此访问器注入。
     */
    fun getLarkDir(): File {
        return File(getAppDataDir(), LARK_DIR_NAME)
    }

    /**
     * 数据库目录：agent 会话/记忆/知识库的 SQLite 落点
     * （`db/toolbelt.db`，WAL 模式三文件 db/-wal/-shm）。对齐 [getLarkDir] 宿主注入先例。
     */
    fun getDbDir(): File {
        return File(getAppDataDir(), DB_DIR_NAME)
    }

    /**
     * 只读诊断域审计目录（sys.* 工具执行落盘：`audit/sys-audit-<yyyy-MM-dd>.jsonl` 纯追加）。
     * 目录在宿主机密根内 ⇒ 桥接出站排除表（SensitiveFilePolicy 判据一）整树拒发——
     * 防擦痕迹式回读（审计不可发去飞书群）。目录按需创建（首次落盘时 mkdirs），不预建。
     */
    fun getAuditDir(): File {
        return File(getAppDataDir(), AUDIT_DIR_NAME)
    }

    /**
     * 设备日志快照目录（agent 设备域 dev.snaplog 的产物落点，`devlogs/`）。
     * 目录在宿主机密根内 ⇒ 桥接出站排除表（SensitiveFilePolicy 判据一）整树拒发——
     * 设备日志快照含个人数据残迹，文件本体永不外发，出群只有分析结论转述。
     * 目录按需创建（首次落盘时 mkdirs），不预建。
     */
    fun getDevlogsDir(): File {
        return File(getAppDataDir(), DEVLOGS_DIR_NAME)
    }

    /**
     * 设备文件拉取默认落点（`~/Downloads`）。刻意取应用数据根**之外**的用户
     * 下载目录：拉取产物是用户文件（送工作台分析/外部工具打开），落在
     * `~/.toolbelt` 会混入宿主机密根且不可见；目录按需创建（拉取内核
     * 首次落盘时 mkdirs），不预建。
     */
    fun getDownloadsDir(): File {
        return File(System.getProperty("user.home"), "Downloads")
    }

    fun getShellToolsFile(): File {
        return File(getAppDataDir(), SHELL_TOOLS_FILENAME)
    }

    fun getShellToolsCustomFile(): File {
        return File(getAppDataDir(), SHELL_TOOLS_CUSTOM_FILENAME)
    }

    /** L2 记忆目录（`memory/`——工作台/智能体中心/调查工作台三装配点共用的路径真源） */
    fun getMemoryDir(): File {
        return File(getAppDataDir(), MEMORY_DIR_NAME)
    }

    /** 日志工作台 run 目录（`logworkbench/runs/`——run 专属子目录由调用方按 runId 追加） */
    fun getLogworkbenchRunsDir(): File {
        return File(File(getAppDataDir(), LOGWORKBENCH_DIR_NAME), RUNS_DIR_NAME)
    }

    /** 语音转写稿目录（`voice/transcripts/`） */
    fun getVoiceTranscriptsDir(): File {
        return File(File(getAppDataDir(), VOICE_DIR_NAME), TRANSCRIPTS_DIR_NAME)
    }

    /** 语音纪要导出目录（`voice/notes/`） */
    fun getVoiceNotesDir(): File {
        return File(File(getAppDataDir(), VOICE_DIR_NAME), NOTES_DIR_NAME)
    }

    /** 语音模型目录（`models/`——ASR/VAD 模型子目录由调用方追加） */
    fun getModelsDir(): File {
        return File(getAppDataDir(), MODELS_DIR_NAME)
    }

    /** JNI native 解包缓存目录（`native-cache/`——sherpa_onnx 系统属性注入面） */
    fun getNativeCacheDir(): File {
        return File(getAppDataDir(), NATIVE_CACHE_DIR_NAME)
    }

    /** scrcpy server jar 释放目录（`scrcpy/`） */
    fun getScrcpyDir(): File {
        return File(getAppDataDir(), SCRCPY_DIR_NAME)
    }

    /** 内置 adb 释放根目录（`adb/`；版本子目录 `v<版本>/` 由释放器自行创建） */
    fun getAdbDir(): File {
        return File(getAppDataDir(), ADB_DIR_NAME)
    }

    /**
     * 可见产物树根的用户配置（进程内单源；null/blank = 未配置走默认根）。
     * 访问器是同步函数（各产物写点在任意线程直调），DataStore 读是挂起协程——
     * 故配置值经 [applyVisibleExportRoot] 写入本单源：宿主启动期 initializer
     * 读盘后应用，运行时改配置经设置页 setter 同步写回。
     * 生产写点仅 [applyVisibleExportRoot] 一个（调用方仅上述两处——人工纪律，
     * 同 `ShellCommandBuilder.shellType` 可写单源先例）；jvmTest 注入/重置同经它。
     */
    private var configuredVisibleExportRoot: String? = null

    /** 应用用户配置的可见产物树根（trim 后 blank = 清除配置回默认根） */
    fun applyVisibleExportRoot(path: String?) {
        configuredVisibleExportRoot = path?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 可见产物树根：默认 `<home>/Documents/Toolbelt`——一切「用户要拿走/拖给别的
     * 工具/分享」的交付物的公共父目录（reports / recordings / perf / logcat /
     * snapshots / knowledge）。与机密根 [getAppDataDir] 语义分离：整树可出群，
     * 不进桥接排除表。用户配置（[applyVisibleExportRoot] 应用过非空值时）优先于默认根。
     *
     * 平台事实：Windows 本地化系统只改 Explorer 显示名（"文档"），物理目录恒为
     * `Documents`——硬编码可靠；Linux 无 xdg-user-dirs 的环境可能无该目录，
     * 由 [getVisibleExportDir] 的 mkdirs 链自建。旧默认根 `~/Toolbelt/` 的存量
     * 产物不迁移不回读（可再生产物，用户可手动搬）。
     * [home] 参数化供单测注入临时根（仅在未配置自定义根时参与解析）。
     */
    fun getVisibleExportRoot(home: File = File(System.getProperty("user.home"))): File {
        configuredVisibleExportRoot?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(File(home, DOCUMENTS_DIR_NAME), VISIBLE_EXPORT_ROOT_NAME)
    }

    private fun visibleExportDir(
        subdirName: String,
        home: File,
    ): File = File(getVisibleExportRoot(home), subdirName)
        .also { runCatching { it.mkdirs() } }

    /** 报告导出目录：`<home>/Documents/Toolbelt/reports/`（父目录按需创建；创建失败留给调用方写盘报错） */
    fun getReportsExportDir(home: File = File(System.getProperty("user.home"))): File =
        visibleExportDir(REPORTS_EXPORT_SUBDIR_NAME, home)

    /** 投屏录屏目录：`<home>/Documents/Toolbelt/recordings/`（父目录按需创建） */
    fun getRecordingsExportDir(home: File = File(System.getProperty("user.home"))): File =
        visibleExportDir(RECORDINGS_EXPORT_SUBDIR_NAME, home)

    /** 性能 CSV 导出目录：`<home>/Documents/Toolbelt/perf/`（父目录按需创建） */
    fun getPerfExportDir(home: File = File(System.getProperty("user.home"))): File =
        visibleExportDir(PERF_EXPORT_SUBDIR_NAME, home)

    /** logcat 抓取产物目录：`<home>/Documents/Toolbelt/logcat/`（父目录按需创建） */
    fun getLogcatExportDir(home: File = File(System.getProperty("user.home"))): File =
        visibleExportDir(LOGCAT_EXPORT_SUBDIR_NAME, home)

    /** 投屏快照目录：`<home>/Documents/Toolbelt/snapshots/`（父目录按需创建） */
    fun getSnapshotsExportDir(home: File = File(System.getProperty("user.home"))): File =
        visibleExportDir(SNAPSHOTS_EXPORT_SUBDIR_NAME, home)

    /** 知识包导出目录：`<home>/Documents/Toolbelt/knowledge/`（父目录按需创建） */
    fun getKnowledgeExportDir(home: File = File(System.getProperty("user.home"))): File =
        visibleExportDir(KNOWLEDGE_EXPORT_SUBDIR_NAME, home)

    /**
     * 宿主应用数据根（`~/.toolbelt`）——`lark/`、`.datastore/`、`logs/` 等机密根子目录的公共父目录。
     * 根名一次定名（发版批）：后续「存储位置开关」经本访问器族生效，根名不再改。
     *
     * 🔴 用途（出站敏感文件排除的判据 1）：出站交付的宿主机密根。取本层而非
     *    [getLarkDir] 子目录，是因为 `.datastore/toolbelt.preferences_pb` 里存着
     *    **明文 LLM API Key** 与 xlog 私钥——只防 `lark/` 会在同级目录留缺口。
     */
    fun getAppDataDir(): File {
        return File(System.getProperty("user.home"), APP_DATA_DIR_NAME)
    }

    /**
     * 旧数据根（`~/.easy_dev_toolbox`，改名迁移前的落点）。
     * 只读兼容用途：[LegacyDataRootMigration] 迁移判定；禁止新写路径引用。
     */
    internal fun getLegacyAppDataDir(): File {
        return File(System.getProperty("user.home"), LEGACY_APP_DATA_DIR_NAME)
    }
}
