package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellType
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * 持久化配置仓库（宿主域）。所有 key 与默认值集中在本文件，读写走 [LocalDataManager]（含内存缓存）。
 *
 * 每个配置项由一个 const key + 一对 reified 挂起方法组成；由于 DataStore 读取必须挂起，
 * 这里不用属性委托（getValue 无法挂起），reified read/write 委托对继承
 * [DomainConfigStore] 共享骨架（唯一真源），测试缝留在本 object（见 [store]）。
 *
 * 域拆分约定（模块化拆分引入）：各 feature 域的 key 与读写方法已迁往各自模块的
 * ConfigStore（key 字符串原样保留，存量配置零迁移）；本对象只保留宿主自身域
 * （外观 / 导航 / 调试开关 / 日志开关 / Shell 类型）。
 */
object EasyDataManager : DomainConfigStore() {


    // ── key 定义（宿主域）──

    private const val KEY_COLOR_SCHEME = "color_scheme"
    private const val KEY_CUSTOM_SEED_COLOR = "custom_seed_color"
    private const val KEY_LAST_PAGE = "last_page"
    private const val KEY_PAGE_USE_COUNTS = "page_use_counts"
    // KEY_APP_CENTER_SORT（app_center_sort）随应用中心页退役成为死数据不读不删
    // （本仓既有纪律：key 不删、读写面移除）
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DEBUG_PAGE_ENABLED = "debug_page_enabled"
    private const val KEY_LOG_FILE_ENABLED = "log_file_enabled"
    private const val KEY_SHELL_TYPE = "shell_type"
    private const val KEY_VISIBLE_EXPORT_ROOT = "visible_export_root"
    private const val KEY_WINDOW_X = "window_x"
    private const val KEY_WINDOW_Y = "window_y"
    private const val KEY_WINDOW_WIDTH = "window_width"
    private const val KEY_WINDOW_HEIGHT = "window_height"
    private const val KEY_SCREENCAST_DETACHED_WINDOW_X = "screencast_detached_window_x"
    private const val KEY_SCREENCAST_DETACHED_WINDOW_Y = "screencast_detached_window_y"
    private const val KEY_SCREENCAST_DETACHED_WINDOW_WIDTH = "screencast_detached_window_width"
    private const val KEY_SCREENCAST_DETACHED_WINDOW_HEIGHT = "screencast_detached_window_height"

    // ── inspector 形态（宽度 + 窄轨全局单键）──

    private const val KEY_INSPECTOR_WIDTH = "inspector_width"
    private const val KEY_INSPECTOR_RAIL = "inspector_rail"

    // ── 材质开关（glassEnabled 已退役——blur 只属浮层且恒真玻璃，无全局玻璃语义；
    //    DataStore 旧键留存量不迁移）──

    private const val KEY_AURORA_ENABLED = "appearance_aurora_enabled"

    /**
     * 全局持久化存储实例：共享 :core 的 [AppDataStore] 进程唯一实例
     * （同文件多 DataStore 实例会启动即崩——feature 域 ConfigStore 全部经 AppDataStore）。
     *
     * internal var 仅为支持 jvmTest 以独立实例（临时目录）替换验证读写行为，
     * 生产代码不得重新赋值。
     */
    internal var store: LocalDataManager = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    // ── 外观 ──

    suspend fun getColorSchemeName(): String = read(KEY_COLOR_SCHEME, "Default")
    suspend fun setColorSchemeName(name: String) = write(KEY_COLOR_SCHEME, name)

    /** 自定义主题种子色（ARGB Long）；-1 表示未设置 */
    suspend fun getCustomSeedColor(): Long = read(KEY_CUSTOM_SEED_COLOR, -1L)
    suspend fun setCustomSeedColor(argb: Long) = write(KEY_CUSTOM_SEED_COLOR, argb)

    suspend fun getThemeModeName(): String = read(KEY_THEME_MODE, "System")
    suspend fun setThemeModeName(name: String) = write(KEY_THEME_MODE, name)

    suspend fun getDebugPageEnabled(): Boolean = read(KEY_DEBUG_PAGE_ENABLED, false)
    suspend fun setDebugPageEnabled(enabled: Boolean) = write(KEY_DEBUG_PAGE_ENABLED, enabled)

    /** 文件日志开关（设置页"开启日志存储"），默认关闭；跨重启保留 */
    suspend fun getLogFileEnabled(): Boolean = read(KEY_LOG_FILE_ENABLED, false)
    suspend fun setLogFileEnabled(enabled: Boolean) = write(KEY_LOG_FILE_ENABLED, enabled)

    /** 环境背景 aurora 开关（设置页「环境背景」；默认开——关闭停绘光斑） */
    suspend fun getAuroraEnabled(): Boolean = read(KEY_AURORA_ENABLED, true)
    suspend fun setAuroraEnabled(enabled: Boolean) = write(KEY_AURORA_ENABLED, enabled)

    /**
     * 可见产物树根的用户自定义路径（设置页「产物目录」；空串 = 默认
     * `~/Documents/Toolbelt/`——报告/录屏/快照/性能/logcat 等交付物的公共父目录）。
     * 生效通道：启动期 initializer 读入 `FileUtils` 进程单源（访问器同步读，
     * 不起协程）；运行时改配置经 AppViewModel setter 同步写回单源。
     */
    suspend fun getVisibleExportRoot(): String = read(KEY_VISIBLE_EXPORT_ROOT, "")
    suspend fun setVisibleExportRoot(path: String) = write(KEY_VISIBLE_EXPORT_ROOT, path)

    // ── 导航 ──

    /**
     * 上次主区内容 key（主区 tab/页面内容 key 的 name 持久化）。
     * 恢复经 [com.jiaoay.easy_dev_toolbox.core.data.loadEnum] 兜底：存量用户存有
     * 已删枚举名（如 Default 欢迎页）时落工作台家（AppViewModel init 钉死）。
     */
    suspend fun getLastPageName(): String = read(KEY_LAST_PAGE, "LogWorkbench")
    suspend fun setLastPageName(name: String) = write(KEY_LAST_PAGE, name)

    // ── 使用统计（palette frecency 供数）──

    /** 计数 map 的 JSON 快照（空串 = 无记录）；解析失败回落空 map（旧/损坏数据不致命） */
    suspend fun getPageUseCounts(): Map<String, Int> {
        val raw = read(KEY_PAGE_USE_COUNTS, "")
        if (raw.isBlank()) {
            return emptyMap()
        }
        return runCatching { PageUsageCountsSerializer.decode(raw) }.getOrDefault(emptyMap())
    }

    /**
     * 使用计数落盘：信号到达时独立写计数 key
     * （不搭切页便车——last_page 与计数是两个独立事件流，合并写配方已退役）。
     */
    suspend fun setUsageCounts(countsJson: String) {
        write(KEY_PAGE_USE_COUNTS, countsJson)
    }

    /**
     * last_page 落盘（切页只写 last_page，计数不随行）。
     *
     * [countsJson] 参数是旧合并写配方的兼容保留位：null/非 null 均只写
     * last_page——计数改经 [setUsageCounts] 在信号到达时独立落盘。保留签名
     * 是让存量调用点（测试）零改动；生产切页链传 null。
     */
    suspend fun setLastPageWithUsageCounts(lastPage: String, countsJson: String?) {
        write(KEY_LAST_PAGE, lastPage)
    }

    // ── 窗口几何（main.kt 窗口态持久化，配方同 lastPage）──

    /** 窗口位置与尺寸（未记录哨兵 = width/height ≤ 0，默认 -1；x/y 允许负值——多显示器副屏在主屏左侧/上方时坐标系为负） */
    suspend fun getWindowX(): Int = read(KEY_WINDOW_X, -1)
    suspend fun getWindowY(): Int = read(KEY_WINDOW_Y, -1)
    suspend fun getWindowWidth(): Int = read(KEY_WINDOW_WIDTH, -1)
    suspend fun getWindowHeight(): Int = read(KEY_WINDOW_HEIGHT, -1)

    /** 几何四元组一次 edit 原子落盘（单一快照不撕裂，且免 4 次整文件读-改-写） */
    suspend fun setWindowGeometry(x: Int, y: Int, width: Int, height: Int) {
        activeStore.setValues(
            mapOf(
                KEY_WINDOW_X to x,
                KEY_WINDOW_Y to y,
                KEY_WINDOW_WIDTH to width,
                KEY_WINDOW_HEIGHT to height,
            ),
        )
    }

    // ── 投屏独立窗几何（main.kt 独立视口窗态持久化，配方同窗口几何）──

    /** 投屏独立窗位置与尺寸（未记录哨兵 = width/height ≤ 0，默认 -1；x/y 允许负值——多显示器副屏在主屏左侧/上方时坐标系为负） */
    suspend fun getScreencastDetachedWindowX(): Int = read(KEY_SCREENCAST_DETACHED_WINDOW_X, -1)
    suspend fun getScreencastDetachedWindowY(): Int = read(KEY_SCREENCAST_DETACHED_WINDOW_Y, -1)
    suspend fun getScreencastDetachedWindowWidth(): Int = read(KEY_SCREENCAST_DETACHED_WINDOW_WIDTH, -1)
    suspend fun getScreencastDetachedWindowHeight(): Int = read(KEY_SCREENCAST_DETACHED_WINDOW_HEIGHT, -1)

    /** 几何四元组一次 edit 原子落盘（配方同 setWindowGeometry） */
    suspend fun setScreencastDetachedWindowGeometry(x: Int, y: Int, width: Int, height: Int) {
        activeStore.setValues(
            mapOf(
                KEY_SCREENCAST_DETACHED_WINDOW_X to x,
                KEY_SCREENCAST_DETACHED_WINDOW_Y to y,
                KEY_SCREENCAST_DETACHED_WINDOW_WIDTH to width,
                KEY_SCREENCAST_DETACHED_WINDOW_HEIGHT to height,
            ),
        )
    }

    // ── Shell 类型（App 级状态，唯一读写方为 AppViewModel）──

    suspend fun getShellTypeName(): String {
        val default = if (ShellCommandBuilder.isMac) ShellType.ZSH.name else ShellType.BASH.name
        return read(KEY_SHELL_TYPE, default)
    }

    suspend fun setShellTypeName(name: String) = write(KEY_SHELL_TYPE, name)

    // ── inspector 形态（调查工作台）：per-page sidebar_* / dock_items / panel_* 键
    //     是死数据——不读不删（历史遗留键，写路径已消失）──

    /** inspector 宽（dp；非法/缺失回退 token 默认） */
    suspend fun getInspectorWidth(): Float =
        read(KEY_INSPECTOR_WIDTH, Dimens.sideBarWidthDefault.value)

    /** inspector 窄轨态（true = 折叠为 icon 轨；默认展开） */
    suspend fun getInspectorRailMode(): Boolean =
        read(KEY_INSPECTOR_RAIL, false)

    /** 宽度 + 窄轨二元组一次 edit 原子落盘（配方同 setWindowGeometry） */
    suspend fun setInspectorState(
        widthDp: Float,
        railMode: Boolean,
    ) {
        activeStore.setValues(
            mapOf(
                KEY_INSPECTOR_WIDTH to widthDp,
                KEY_INSPECTOR_RAIL to railMode,
            ),
        )
    }
}
