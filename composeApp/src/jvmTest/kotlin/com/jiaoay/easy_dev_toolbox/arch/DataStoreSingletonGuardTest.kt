package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DataStore 唯一实例扫描守护测试（AGENTS.md「关键约定」第 2 条的守护面）。
 *
 * 守护目标：生产源码禁止自行构造 preferences DataStore，持久化一律经 `:core` 的
 * `AppDataStore` 进程唯一实例。自建构造点有两条硬后果，缺守护时只靠人工纪律：
 * - 同一 preferences 文件出现第二个 DataStore 实例，androidx 在启动/首读写时抛
 *   「There are multiple DataStores active for the same file」——进程级崩溃；
 * - 绕开 `LocalDataManager` 写路径的 post-edit 密钥文件硬化钩子（`hardenSecretFile` →
 *   `SecretFilePerms.harden600`），密钥 preferences 文件以 umask 默认权限落盘。
 *
 * ## 扫描面与归一化
 *
 * - 扫描根：项目根下每个模块目录的生产源集（`src/main` 与各 `Main` 后缀源集——
 *   jvmMain/commonMain 等；测试源集 jvmTest/test 不在列；纯 JVM lib 模块用 `src/main`）。
 *   新模块/新源集落盘即自动纳管，无需改本测试。
 * - 守护的是「真实构造点」而非「文字提及」：匹配前先 [normalizeKotlinSource] 归一化——
 *   剥行注释、嵌套块注释（含 KDoc）、字符串字面量内容，保留换行对齐行号。配方与
 *   [LarkSdkIsolationTest] 相同（同属类型依赖守护，不复用其 private helper）。
 * - 匹配 token = [FACTORY_TOKEN]（`DataStoreFactory`，`PreferenceDataStoreFactory` 的
 *   超串）：一次覆盖 import / 成员 import（`import …PreferenceDataStoreFactory.create`
 *   后裸调 `create` 的旁路）/ 类限定真实调用 / datastore-core 的裸 `DataStoreFactory`——
 *   import 本身即构造意图的证据，一并收口。
 *
 * ## 白名单（收口/子集语义 + 防腐化）
 *
 * - 断言 = 归一化后命中文件集合 ⊆ [whitelistedFactoryPaths]；白名单条目必须仍有真实
 *   命中——构造点被重构移走后条目必须同步删除，防止白名单退化为无人清理的死条目。
 * - 🔴 扩展契约：新增合法 DataStore 构造点时，必须**先**在 [whitelistedFactoryPaths]
 *   登记理由，并核对上述两条硬后果如何保全（同文件唯一实例 + 写路径密钥硬化），
 *   **再**落生产代码——顺序颠倒会红构建，这正是本守护的目的。
 * - 空/缩水扫描 = 永绿后门：生产 .kt 文件数跌破 [MIN_PRODUCTION_KT_FILES] 即红。
 */
class DataStoreSingletonGuardTest {

    /** 一条违规命中：相对项目根路径 + 归一化后行号 + 命中行原文（去首尾空白） */
    private data class FactoryHit(val relativePath: String, val line: Int, val codeLine: String)

    // ------------------------------------------------------------------ A 组：构造点收口

    /**
     * A-1：全仓生产源码归一化后含 [FACTORY_TOKEN] 的文件集合必须 ⊆ [whitelistedFactoryPaths]，
     * 且白名单条目必须仍有真实命中（防腐化）。
     */
    @Test
    fun `A datastore factory construction is confined to whitelisted files`() {
        val root = locateProjectRoot()
        val sources = collectProductionSources(root)
        // 空/缩水扫描 = 永绿后门：目录定位或剪枝异常时必须显式失败而非静默通过
        check(sources.size >= MIN_PRODUCTION_KT_FILES) {
            "生产源码 .kt 扫描文件数异常（${sources.size} < $MIN_PRODUCTION_KT_FILES 下限）：" +
                "目录定位/剪枝可能出错，守护会静默失效"
        }
        val hits = sources.flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            normalizeKotlinSource(file.readText()).split('\n').mapIndexedNotNull { idx, line ->
                if (FACTORY_TOKEN in line) FactoryHit(rel, idx + 1, line.trim()) else null
            }
        }
        val hitsByFile = hits.groupBy { it.relativePath }
        val offenders = hitsByFile.keys - whitelistedFactoryPaths.keys
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("DataStore 构造点越权：白名单 ${whitelistedFactoryPaths.keys} 之外出现 $FACTORY_TOKEN：")
                    offenders.sorted().forEach { path ->
                        hitsByFile.getValue(path).forEach {
                            appendLine("  - ${it.relativePath}:${it.line}: ${it.codeLine}")
                        }
                    }
                    appendLine("持久化必须经 :core 的 AppDataStore 进程唯一实例（宿主域 key 走 EasyDataManager，")
                    appendLine("feature 域 key 走各模块 ConfigStore，明细见 docs/core/data.md）。自建 DataStore 会同时撞：")
                    appendLine("  ① 同一 preferences 文件多实例启动即崩（multiple DataStores active）；")
                    appendLine("  ② 绕开 LocalDataManager 写路径密钥文件硬化钩子（hardenSecretFile）。")
                    appendLine("确有合法新增构造点：先在 whitelistedFactoryPaths 登记理由并核对①②如何保全，再落代码。")
                },
            )
        }
        // 防腐化：白名单条目指向的构造点被移走/删除后，条目必须同步清理
        val stale = whitelistedFactoryPaths.keys - hitsByFile.keys
        assertTrue(
            stale.isEmpty(),
            "白名单含已无真实命中的死条目（构造点已被移走？删条目）：$stale",
        )
        // 自检：白名单条目必须解析到磁盘上的真实文件（路径写错/文件被移动即红，防排除逻辑退化成不扫）
        val dangling = whitelistedFactoryPaths.keys.filterNot { File(root, it).isFile }
        assertTrue(
            dangling.isEmpty(),
            "白名单条目指向不存在的文件（路径漂移？）：$dangling",
        )
    }

    // ------------------------------------------------------------------ B 组：归一化自锁

    /**
     * B-1：归一化 + 匹配的单元测试（A 组的设计核心，必须单独锁死）。
     *
     * 覆盖：合法注释/KDoc 文字提及（各 ConfigStore KDoc 的真实形态）、合法字符串字面量提及
     * （错误消息/日志文案形态）、违规 import + 类限定调用、违规成员 import + 裸调用旁路。
     */
    @Test
    fun `B normalization strips comments and strings but keeps real factory usage`() {
        // 合法①：注释/KDoc 内的文字提及
        val legalDocMention = """
            /**
             * 经 :core 的 AppDataStore 进程唯一实例读写（feature 禁止自行 PreferenceDataStoreFactory.create）。
             */
            val x = 1 // 行尾提及 DataStoreFactory
            /* 块注释提及 PreferenceDataStoreFactory */
        """.trimIndent()
        assertTrue(
            findFactoryHitLines(legalDocMention).isEmpty(),
            "注释/KDoc 内的文字提及不应判为违规（守护的是真实构造点而非文字提及），" +
                "实际命中行=${findFactoryHitLines(legalDocMention)}",
        )

        // 合法②：字符串字面量内的文字提及
        val legalStringMention = """
            fun check() = error("禁止自行 PreferenceDataStoreFactory.create，须走 AppDataStore")
            val raw = "DataStoreFactory 出现在字符串里"
        """.trimIndent()
        assertTrue(
            findFactoryHitLines(legalStringMention).isEmpty(),
            "字符串字面量内的文字提及不应判为违规，实际命中行=${findFactoryHitLines(legalStringMention)}",
        )

        // 违规③：import + 类限定真实调用（LocalDataManager 的合法形态在白名单内，其余位置均判违规）
        val illegalCall = """
            import androidx.datastore.preferences.core.PreferenceDataStoreFactory
            val store = PreferenceDataStoreFactory.create { file }
        """.trimIndent()
        assertEquals(
            listOf(1, 2),
            findFactoryHitLines(illegalCall),
            "import 与类限定真实调用必须均判命中（import 本身即构造意图的证据）",
        )

        // 违规④：成员 import + 裸 create（绕开类限定连写的旁路，靠 import 行命中兜住）
        val illegalMemberImport = """
            import androidx.datastore.preferences.core.PreferenceDataStoreFactory.create
            val store = create { file }
        """.trimIndent()
        assertEquals(
            listOf(1),
            findFactoryHitLines(illegalMemberImport),
            "成员 import 旁路必须经 import 行命中（裸 create 行无 token，靠 import 收口）",
        )
    }

    // ------------------------------------------------------------------ 扫描 / 归一化实现

    /** 归一化后逐行返回含 [FACTORY_TOKEN] 的 1-based 行号；供 A 组与 B 组共用 */
    private fun findFactoryHitLines(rawSource: String): List<Int> =
        normalizeKotlinSource(rawSource).split('\n')
            .mapIndexedNotNull { idx, line -> if (FACTORY_TOKEN in line) idx + 1 else null }

    /**
     * 收集全部生产源码 .kt：项目根下每个模块目录的 `src/main` 与各 `Main` 后缀源集
     * （jvmTest/test 等测试源集不在列）。按 [EXCLUDED_DIR_NAMES] 剪枝构建产物目录。
     */
    private fun collectProductionSources(root: File): List<File> {
        val sources = mutableListOf<File>()
        for (module in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            val src = File(module, "src")
            if (!src.isDirectory) continue
            for (sourceSet in src.listFiles { f -> f.isDirectory }.orEmpty()) {
                val isProduction = sourceSet.name == "main" || sourceSet.name.endsWith("Main")
                if (!isProduction) continue
                sources += sourceSet
                    .walkTopDown()
                    .onEnter { it.name !in EXCLUDED_DIR_NAMES }
                    .filter { it.isFile && it.extension == "kt" }
            }
        }
        return sources
    }

    /**
     * 归一化：剥离行注释（`//`）、块注释内容（`/* ... */`，Kotlin 可嵌套故按深度配对）、
     * 字符串字面量内容（普通 `"..."` 含 `\"` 转义、原始 `"""..."""`），保留全部换行以维持
     * 行号对齐。已知近似（沿 [LarkSdkIsolationTest] 同款取舍）：含引号的字符字面量
     * （`'"'`）不识别，误伤范围被「字符串态遇换行保守回退」限制在单行内。
     */
    private fun normalizeKotlinSource(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                // 行注释：跳到行尾（换行由后续迭代原样保留）
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                // 块注释（含 KDoc）：丢弃内容，保留内部换行；按深度配对处理嵌套
                c == '/' && next == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < source.length && depth > 0) {
                        when {
                            source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> { depth++; i += 2 }
                            source[i] == '*' && i + 1 < source.length && source[i + 1] == '/' -> { depth--; i += 2 }
                            else -> { if (source[i] == '\n') sb.append('\n'); i++ }
                        }
                    }
                }
                // 原始字符串 """..."""：保留首尾三引号，丢弃内容，保留内部换行
                c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                    sb.append("\"\"\"")
                    i += 3
                    while (i < source.length) {
                        if (source[i] == '"' && i + 1 < source.length && source[i + 1] == '"' &&
                            i + 2 < source.length && source[i + 2] == '"'
                        ) {
                            sb.append("\"\"\""); i += 3; break
                        }
                        if (source[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                // 普通字符串 "..."：保留首尾引号，丢弃内容；处理 \ 转义；遇换行保守回退代码态（行号仍对齐）
                c == '"' -> {
                    sb.append('"'); i++
                    while (i < source.length) {
                        val sc = source[i]
                        if (sc == '\\' && i + 1 < source.length) { i += 2; continue }
                        if (sc == '"') { sb.append('"'); i++; break }
                        if (sc == '\n') { sb.append('\n'); i++; break }
                        i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private companion object {

        /** 匹配 token：`PreferenceDataStoreFactory` 的超串，同时覆盖裸 `DataStoreFactory` 与 import 行 */
        const val FACTORY_TOKEN = "DataStoreFactory"

        /** 全仓生产 .kt 下限（现阶段约 500+，留足余量）：跌破说明扫描根丢失/剪枝出错，守护会静默失效 */
        const val MIN_PRODUCTION_KT_FILES = 300

        /** AppDataStore 单例底层——全仓唯一合法构造点的相对项目根路径（A-1 白名单基线锚） */
        const val APP_DATA_STORE_FACTORY_PATH =
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/LocalDataManager.kt"

        /**
         * DataStore 构造点白名单：相对项目根路径 → 登记理由。
         *
         * 🔴 扩展契约：新增合法构造点必须**先**在此登记（附理由 + 两条硬后果的保全核对：
         * 同文件唯一实例、写路径密钥硬化挂钩），**再**落生产代码；构造点被移走/删除后
         * 必须同步删条目（A-1 防腐化断言强制）。
         */
        val whitelistedFactoryPaths: Map<String, String> = mapOf(
            APP_DATA_STORE_FACTORY_PATH to
                "AppDataStore 单例的底层：进程唯一 preferences DataStore 构造点，" +
                "post-edit 密钥文件硬化钩子（hardenSecretFile）挂在其写路径",
        )

        /** 剪枝目录名（构建产物 / IDE / 临时目录，任何深度命中即不进入） */
        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".clikt_tmp", ".review_tmp", ".idea", "node_modules", ".git",
        )
    }
}
