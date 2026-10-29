package com.jiaoay.easy_dev_toolbox.page.tools

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

/**
 * 工具聚合页配置仓库：子签选择跨启动持久。
 *
 * 对照先例：last_page（AppViewModel）与时间戳格式（TimestampConfigStore）均持久化，
 * 同域口径统一——高频用户（每天解压/转换时间戳）重启不再多一次点击。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature/宿主域禁止自行
 * PreferenceDataStoreFactory）。读写样板继承 [DomainConfigStore] 共享骨架，
 * 测试缝留在本 object（见 [store]）。
 *
 * 越界语义：存值不在 0..12（手工改坏持久值如 13）时消费方回 0（第 1 签）——
 * 验收口径「越界回第 1 签」，刻意不用 coerceIn（coerceIn(13)=12 落第 13 签，
 * 坏值应视为脏数据回默认而非就近夹取）。
 */
object ToolsConfigStore : DomainConfigStore() {

    /** 子签 index：0=XLog 解密 / 1=Tag 过滤 / 2=文件分割 / 3=时间戳 / 4=解压 / 5=日志对比 / 6=正则测试 / 7=JSON 工具 / 8=Base64 提取 / 9=哈希校验 / 10=颜色转换 / 11=JWT 解析 / 12=ULID 工具（ToolsPage 十三签序） */
    private const val KEY_TOOLS_SELECTED_TAB = "tools_selected_tab"

    /** 十三签上限（含）；越界值视为脏数据回 0 */
    const val TAB_MAX_INDEX = 12

    // 预选通道目标子签锚（九签序单源）：palette 剪贴板识别推荐行等「跳工具页 +
    // 预选子签」的写入端 index——与 ToolsPage SegmentedControl 九签序逐字对齐
    // （增签/换序必须同 commit 改这里，锚漂移会落错签）
    /** XLog 解密子签（第 1 签） */
    const val TAB_XLOG_DECODE = 0

    /** 时间戳子签（第 4 签） */
    const val TAB_TIMESTAMP = 3

    /** 解压子签（第 5 签） */
    const val TAB_ARCHIVE = 4

    /** JSON 工具子签（第 8 签） */
    const val TAB_JSON_TOOL = 7

    /** Base64 提取子签（第 9 签） */
    const val TAB_BASE64_EXTRACT = 8

    /** 测试接缝：jvmTest 可整体替换为临时目录实例（对齐 EasyDataManager.store 既有约定） */
    internal var store = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    /**
     * 子签选择读取（默认 0 = 第 1 签 XLog 解密）。
     * 越界防御在消费方（ToolsPage 初值合成 `if (v in 0..[TAB_MAX_INDEX]) v else 0`）——store 层
     * 透传原始存值，坏值回默认的判定与「初值合成序 = 预选槽优先、回落持久值」
     * 收口在同一处。
     */
    suspend fun getSelectedTab(): Int = read(KEY_TOOLS_SELECTED_TAB, 0)

    /** 子签选择回写（切签直写无 debounce——低频交互，对齐 last_page 口径） */
    suspend fun setSelectedTab(index: Int) = write(KEY_TOOLS_SELECTED_TAB, index)
}
