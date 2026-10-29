package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 存储路径字面量守护测试（数据根定名 `~/.toolbelt` 批的防回潮面）。
 *
 * 守护目标：数据根与可见产物树的**路径构造单源纪律**——
 * - 旧根名 `.easy_dev_toolbox` 只允许出现在「改名迁移」与「出站打码双形态兼容」两类
 *   刻意契约位（白名单钉死），任何新写路径出现旧根名即红（静默漂移面）；
 * - 可见产物树名 `"Toolbelt"` 不允许出现在**路径构造上下文**（`File(`/`resolve(`/`Path`）
 *   ——路径构造唯一合法真源 = `:core` FileUtils 的可见产物树访问器族；窗口标题/菜单/
 *   MCP 服务名等**展示串**不受限（裸字符串扫描会误中 14 处展示串，故只扫构造上下文）；
 * - 数据根子目录不允许 `File(getAppDataDir(), "字面量")` 形态直拼——新子目录必须升
 *   FileUtils 访问器（`getXxxDir()`），否则「存储位置开关」无法枚举完整路径面。
 *
 * ## 扫描面
 * 项目根下每个模块目录的生产源集（`src/main` 与各 `Main` 后缀源集；测试源集不在列），
 * 配方与 [DataStoreSingletonGuardTest] 同款。A 组扫**字符串字面量内容**（注释/KDoc 不扫——
 * 与归一化剥串的 DataStore 守护互为镜像：那边守类型引用、这边守路径串）；
 * B/C 组扫原始行文本（路径构造与串字面量同行的行级近似，KDoc 已注明）。
 *
 * 🔴 扩展契约：新增合法位点必须**先**改本测试（白名单登记理由/扩展模式），再落生产代码。
 * 空/缩水扫描 = 永绿后门：生产 .kt 文件数跌破 [MIN_PRODUCTION_KT_FILES] 即红。
 */
class StoragePathLiteralGuardTest {

    private data class Hit(val relativePath: String, val line: Int, val text: String)

    // ------------------------------------------------------------------ A 组：旧根名字面量白名单

    /**
     * A-1：生产源码字符串字面量含 `easy_dev_toolbox` 的文件集合必须 ⊆
     * [legacyRootLiteralWhitelist]，且白名单条目必须仍有真实命中（防腐化）。
     * 白名单两类刻意契约位：迁移常量（FileUtils/AppDataStore）与出站打码旧形态（SensitiveFilePolicy）。
     */
    @Test
    fun `A legacy root literal is confined to migration and masking contract sites`() {
        val root = locateProjectRoot()
        val sources = collectProductionSources(root)
        check(sources.size >= MIN_PRODUCTION_KT_FILES) {
            "生产源码 .kt 扫描文件数异常（${sources.size} < $MIN_PRODUCTION_KT_FILES 下限）：" +
                "目录定位/剪枝可能出错，守护会静默失效"
        }
        val hits = sources.flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            collectStringLiterals(file.readText()).mapNotNull { (line, literal) ->
                if ("easy_dev_toolbox" in literal) Hit(rel, line, literal) else null
            }
        }
        val hitsByFile = hits.groupBy { it.relativePath }
        val offenders = hitsByFile.keys - legacyRootLiteralWhitelist.keys
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("旧数据根名字面量越权：白名单 ${legacyRootLiteralWhitelist.keys} 之外出现 easy_dev_toolbox 串：")
                    offenders.sorted().forEach { path ->
                        hitsByFile.getValue(path).forEach {
                            appendLine("  - ${it.relativePath}:${it.line}: ${it.text.take(120)}")
                        }
                    }
                    appendLine("数据根已定名 ~/.toolbelt：新写路径恒经 core/data/FileUtils 访问器；")
                    appendLine("确有合法新增位点（如新的迁移/打码兼容串）：先在 legacyRootLiteralWhitelist 登记理由，再落代码。")
                },
            )
        }
        val stale = legacyRootLiteralWhitelist.keys - hitsByFile.keys
        assertTrue(stale.isEmpty(), "白名单含已无真实命中的死条目（旧根位点被移除？删条目）：$stale")
        val dangling = legacyRootLiteralWhitelist.keys.filterNot { File(root, it).isFile }
        assertTrue(dangling.isEmpty(), "白名单条目指向不存在的文件（路径漂移？）：$dangling")
    }

    // ------------------------------------------------------------------ B 组：可见产物树路径构造上下文

    /**
     * B-1：原始行同时含 `"Toolbelt"` 串字面量与路径构造标记（`File(`/`resolve(`/`Path`）
     * 的生产命中必须为零——可见产物树路径构造唯一合法真源 = FileUtils 访问器
     * （`getReportsExportDir` 等）。行级近似：跨行拆开的构造不在此列（同文件既有
     * 构造全部单行，新违规按惯例同样单行；KDoc 明示取舍）。
     */
    @Test
    fun `B visible export root literal never appears in path construction context`() {
        val root = locateProjectRoot()
        val offenders = collectProductionSources(root).flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            file.readText().lines().mapIndexedNotNull { idx, line ->
                val hasRootLiteral = "\"Toolbelt\"" in line
                val hasPathConstruction = "File(" in line || "resolve(" in line || "Path" in line
                if (hasRootLiteral && hasPathConstruction) Hit(rel, idx + 1, line.trim()) else null
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("可见产物树名 \"Toolbelt\" 出现在路径构造上下文（File/resolve/Path 同行）：")
                    offenders.forEach { appendLine("  - ${it.relativePath}:${it.line}: ${it.text}") }
                    appendLine("可见产物树路径必须经 core/data/FileUtils 的可见产物树访问器族")
                    appendLine("（getReportsExportDir 等）；展示串（窗口标题/菜单/MCP 服务名）不受本守护限制。")
                },
            )
        }
    }

    // ------------------------------------------------------------------ C 组：数据根子目录直拼禁令

    /**
     * C-1：数据根子目录不允许 `File(getAppDataDir(), "字面量")` / `getAppDataDir().resolve("字面量")`
     * 形态直拼——子目录必须升 FileUtils 访问器（路径值不变的重构），否则存储位置开关
     * 无法枚举完整路径面。命中必须为零。
     */
    @Test
    fun `C data root child directories are constructed only via FileUtils accessors`() {
        val root = locateProjectRoot()
        val offenders = collectProductionSources(root).flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            file.readText().lines().mapIndexedNotNull { idx, line ->
                if (DIRECT_CHILD_CONSTRUCTION_PATTERNS.any { it in line }) {
                    Hit(rel, idx + 1, line.trim())
                } else {
                    null
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("数据根子目录直拼越权（getAppDataDir() + 字面量）：")
                    offenders.forEach { appendLine("  - ${it.relativePath}:${it.line}: ${it.text}") }
                    appendLine("子目录必须经 core/data/FileUtils 访问器（getXxxDir()）构造；")
                    appendLine("新增子目录先在 FileUtils 落访问器再改调用面。")
                },
            )
        }
    }

    // ------------------------------------------------------------------ 扫描实现

    /**
     * 提取源码字符串字面量内容（跳过注释/KDoc——A 组守的是真实字面量而非文档提及）。
     * 返回 (1-based 行号, 字面量内容) 列表。已知近似（同 [DataStoreSingletonGuardTest]
     * 归一化家族）：`'\"'` 字符字面量会被误判为串起点，保守遇换行回退把误伤限制在单行内；
     * 普通串未闭合同样遇换行保守回退。
     */
    private fun collectStringLiterals(source: String): List<Pair<Int, String>> {
        val hits = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                c == '\n' -> { line++; i++ }
                // 行注释：跳到行尾
                c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') i++
                // 块注释（含 KDoc）：跳过内容，换行计数维持行号
                c == '/' && next == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < source.length && depth > 0) {
                        when {
                            source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> { depth++; i += 2 }
                            source[i] == '*' && i + 1 < source.length && source[i + 1] == '/' -> { depth--; i += 2 }
                            else -> { if (source[i] == '\n') line++; i++ }
                        }
                    }
                }
                // 原始字符串 """..."""
                c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                    i += 3
                    val startLine = line
                    val sb = StringBuilder()
                    while (i < source.length) {
                        if (source[i] == '"' && i + 2 < source.length &&
                            source[i + 1] == '"' && source[i + 2] == '"'
                        ) {
                            i += 3
                            break
                        }
                        if (source[i] == '\n') line++
                        sb.append(source[i])
                        i++
                    }
                    hits += startLine to sb.toString()
                }
                // 普通字符串 "..."（\ 转义；未闭合遇换行保守回退）
                c == '"' -> {
                    i++
                    val startLine = line
                    val sb = StringBuilder()
                    while (i < source.length) {
                        val sc = source[i]
                        if (sc == '\\' && i + 1 < source.length) { sb.append(sc); i++; continue }
                        if (sc == '"') { i++; break }
                        if (sc == '\n') break
                        sb.append(sc)
                        i++
                    }
                    hits += startLine to sb.toString()
                }
                else -> i++
            }
        }
        return hits
    }

    /**
     * 收集全部生产源码 .kt（配方与 [DataStoreSingletonGuardTest] 同款）：模块目录下
     * `src/main` 与各 `Main` 后缀源集，构建产物目录剪枝。
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

    private companion object {

        /** 全仓生产 .kt 下限：跌破说明扫描根丢失/剪枝出错，守护会静默失效 */
        const val MIN_PRODUCTION_KT_FILES = 300

        /** 构建产物目录剪枝（同 DataStoreSingletonGuardTest 配方） */
        val EXCLUDED_DIR_NAMES = setOf("build", "out", "node_modules")

        /**
         * 数据根子目录直拼模式（C 组）：getAppDataDir() 与**字符串字面量**同行的构造形态。
         * 只匹配带引号的字面量子段——FileUtils 访问器自身的常量构造
         * （`File(getAppDataDir(), LOG_DIR_NAME)`）是合法实现面，不在本守护射程。
         */
        val DIRECT_CHILD_CONSTRUCTION_PATTERNS = listOf(
            "File(FileUtils.getAppDataDir(), \"",
            "File(getAppDataDir(), \"",
            "getAppDataDir().resolve(\"",
        )

        /**
         * 旧根名 `easy_dev_toolbox` 字面量白名单（A 组）：相对项目根路径 → 登记理由。
         * - FileUtils：旧数据根常量（改名迁移判定用，迁移完成前不可删）；
         * - AppDataStore：旧 DataStore 文件名（LocalDataManager 首读写 rename 迁移用）；
         * - SensitiveFilePolicy：出站打码旧根形态（历史文本/未迁移机器防漏杀，恒保留）。
         */
        val legacyRootLiteralWhitelist: Map<String, String> = mapOf(
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/FileUtils.kt" to
                "旧数据根常量：改名迁移判定唯一真源",
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/AppDataStore.kt" to
                "旧 DataStore 文件名：首读写 rename 迁移",
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/SensitiveFilePolicy.kt" to
                "出站打码旧根形态：历史文本/未迁移机器防漏杀（安全契约，恒保留）",
        )
    }
}
