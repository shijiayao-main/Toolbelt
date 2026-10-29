package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * agent 工具面文件入参围栏覆盖守护测试。
 *
 * 守护目标：core/llm/tools 下每个 StructuredSimpleTool 子类的 Args 形参凡含路径
 * （属性名判据见 [isPathParamName]），该工具类体内必须出现 ToolExecutionContext
 * 的 authorizeWithGate/authorize 围栏校验调用。LLM 的路径入参不可信（幻觉路径 +
 * 文件内容注入，风险语义见 ToolExecutionContext 类头）——漏调围栏时无编译错误、
 * 无运行异常、围栏静默失效；本守护把该安全不变量钉成红构建门禁。
 *
 * ## 路径形参匹配集（全量盘点推导，勿放宽）
 *
 * 判据 = 属性名等于 `path` 或以 `_path` 结尾。该判据来自对现网全部 31 个工具
 * Args 属性的全量盘点：路径形参恰好全部落在判据内（file_path / path / dir_path /
 * archive_path / old_file_path / new_file_path / log_path，共 19 个工具持有），
 * 且无任何非路径属性以 `_path` 结尾——故不做更宽的「含 path 字样」匹配
 * （会误伤 xpath 之类的非路径命名），也不做目录名/dir 之类的猜义匹配
 * （泛化误伤面不可控；非常规命名的文件入参由 B 组分类断言兜住，见下）。
 *
 * ## 分类完整性（防新增工具静默绕过）
 *
 * 只有「路径形参 ⇒ 须调围栏」一条断言时，新增零文件 I/O 工具天然通过、无人审视，
 * 守护退化为「守存量不守增量」。故 B 组另加分类断言：每个被发现的工具必须命中
 * 二者之一——①类体内调用围栏；②在 [noFileInputWhitelist] 登记无文件入参或
 * 围栏外合法形态的理由。新增工具不落任何一类即红——先改测试锚（登记理由并核对
 * 文件入参如何被围栏）再落生产代码，顺序颠倒即红构建，这正是守护的目的。
 *
 * ## 扫描面与归一化
 *
 * - 扫描根 = core/llm/tools 生产源集；类体与 Args 体用花括号配对切片；
 * - 匹配前先 [normalizeKotlinSource] 归一化（剥注释与字符串内容、保留换行）——
 *   注释里的 authorizeWithGate 文字、字符串里的 file_path 默认值均不判为真实
 *   调用/形参；配方与 [LarkSdkIsolationTest] 相同（private helper 不跨文件复用）；
 * - 解析器正确性由 C 组内联样本单独锁死；扫描数量下限防目录重构后扫描器空转
 *   永绿；类体/Args 体不可定位时直接红（解析器失明不得静默跳过）；
 * - StructuredSimpleTool 子类只允许出现在 core/llm/tools（D 组）——在 feature/lib
 *   模块另立工具面会同时逃过本守护与 [ToolStructuredDisciplineTest] 的结构化纪律。
 *   🔴 扫盘型守护验收必须 --rerun-tasks --no-build-cache（AGENTS.md 铁律）。
 */
class ToolFenceCoverageGuardTest {

    /** 一个被发现的 agent 工具：类名 + 宿主文件 + Args 中的路径形参 + 类体是否调用围栏 */
    private data class ToolScan(
        val className: String,
        val relativePath: String,
        val pathParams: List<String>,
        val callsFence: Boolean,
    )

    // ------------------------------------------------------------------ A 组：路径形参 → 围栏调用

    /**
     * A：凡 Args 含路径形参的工具，类体内必须调用 authorizeWithGate/authorize
     * （或在 [noFileInputWhitelist] 登记围栏外合法形态的理由——扩展口）。
     */
    @Test
    fun `A tools with path-shaped args must call the fence`() {
        val tools = discoverTools()
        // 空/缩水扫描 = 永绿后门：目录定位或解析异常时必须显式失败而非静默通过
        check(tools.size >= MIN_TOOL_CLASSES) {
            "agent 工具扫描数量异常（${tools.size} < $MIN_TOOL_CLASSES 下限）：" +
                "目录定位/解析器可能出错，守护会静默失效"
        }
        val offenders = tools.filter {
            it.pathParams.isNotEmpty() && !it.callsFence && it.className !in noFileInputWhitelist
        }
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("agent 工具围栏覆盖缺口：以下工具的 Args 含路径形参但类体内未见 authorizeWithGate/authorize 调用：")
                    offenders.forEach {
                        appendLine("  - ${it.relativePath}: ${it.className}（路径形参：${it.pathParams.joinToString(", ")}）")
                    }
                    appendLine("LLM 的路径入参不可信（幻觉路径/文件内容注入），必须经 ToolExecutionContext.authorizeWithGate 围栏校验，")
                    appendLine("未命中允许集返回 DENIED 文案（LLM 可自行调整，任务不崩）。确属围栏外合法形态：")
                    appendLine("先在 noFileInputWhitelist 登记理由（并核对文件入参如何被围栏）再放行。")
                },
            )
        }
    }

    // ------------------------------------------------------------------ B 组：分类完整性 + 白名单防腐化

    /**
     * B：每个被发现的工具必须完成分类——调用围栏 ∨ 白名单登记。
     *
     * 这是防新增工具静默绕过的承重断言：新工具要么带围栏调用自然通过，
     * 要么必须先到 [noFileInputWhitelist] 登记理由（人审视其文件入参）。
     * 反向防腐化：白名单条目指向的类被删/改名/迁出扫描面后必须同步删条目。
     */
    @Test
    fun `B every structured tool is classified and whitelist stays fresh`() {
        val tools = discoverTools()
        check(tools.size >= MIN_TOOL_CLASSES) {
            "agent 工具扫描数量异常（${tools.size} < $MIN_TOOL_CLASSES 下限）：守护会静默失效"
        }
        val classNames = tools.map { it.className }
        assertEquals(classNames.size, classNames.distinct().size, "工具类名重复（扫描切片疑似跨类串体——先修解析器）")
        val fenceCalling = tools.filter { it.callsFence }.map { it.className }.toSet()
        val whitelisted = noFileInputWhitelist.keys
        val discovered = classNames.toSet()
        val unclassified = discovered - fenceCalling - whitelisted
        assertTrue(
            unclassified.isEmpty(),
            "未分类的 agent 工具（既不调用围栏、也未在 noFileInputWhitelist 登记理由）：" +
                "$unclassified。每个 StructuredSimpleTool 必须二选一：含文件入参 → 类体内调 " +
                "authorizeWithGate；无文件入参/围栏外合法形态 → 白名单登记理由（先改测试锚再落代码）",
        )
        val stale = whitelisted - discovered
        assertTrue(
            stale.isEmpty(),
            "白名单含未被扫描发现的工具类（类被删/改名/迁出扫描面？同步删条目）：$stale",
        )
    }

    // ------------------------------------------------------------------ C 组：解析器自锁

    /**
     * C：解析 + 归一化的单元测试（A/B 组的设计核心，必须单独锁死）。
     *
     * 覆盖四形态：围栏工具的正例（路径形参提取 + 围栏调用识别）、缺围栏的反例、
     * 注释/字符串诱饵（不判为形参/调用）、方法体内局部 val 路径名（不误入 Args 形参）。
     */
    @Test
    fun `C parser extracts args params and fence calls without decoy hits`() {
        // 正例：路径形参 + 围栏调用（executeTyped 内的局部 derived_path 不得误入 Args 形参）
        val fenced = """
            class SampleFencedTool(
                private val context: ToolExecutionContext,
                sink: ToolResultSink = ToolResultSink.NOOP,
            ) : StructuredSimpleTool<SampleFencedTool.Args>(
                sink = sink,
                argsType = typeToken<Args>(),
            ) {
                @Serializable
                data class Args(
                    @property:LLMDescription("Absolute path of the file")
                    val file_path: String,
                    @property:LLMDescription("Literal text")
                    val pattern: String,
                )

                override suspend fun executeTyped(args: Args): ToolResult {
                    val derived_path = context.authorizeWithGate(args.file_path)
                    return ToolResult.Ok("")
                }
            }
        """.trimIndent()
        val fencedScanned = scanToolClasses(fenced, "sample/Fenced.kt")
        assertEquals(listOf("SampleFencedTool"), fencedScanned.map { it.className }, "工具类声明必须被解析到")
        assertEquals(listOf("file_path"), fencedScanned.single().pathParams, "Args 路径形参提取失真")
        assertTrue(fencedScanned.single().callsFence, "类体内的 authorizeWithGate 调用必须被识别")

        // 反例：路径形参 + 零围栏调用（A 组的命中形态）
        val unfenced = """
            class SampleUnfencedTool(
                private val context: ToolExecutionContext,
            ) : StructuredSimpleTool<SampleUnfencedTool.Args>(
                sink = sink,
                argsType = typeToken<Args>(),
            ) {
                @Serializable
                data class Args(
                    val dir_path: String,
                    val limit: Int = 10,
                )

                override suspend fun executeTyped(args: Args): ToolResult = ToolResult.Ok("")
            }
        """.trimIndent()
        val unfencedScanned = scanToolClasses(unfenced, "sample/Unfenced.kt")
        assertEquals(listOf("dir_path"), unfencedScanned.single().pathParams, "dir_path 形参必须命中判据")
        assertTrue(!unfencedScanned.single().callsFence, "无围栏调用的工具不得被判为已围栏")

        // 诱饵：注释/KDoc 里的 authorizeWithGate 文字 + 字符串默认值里的 file_path + 非路径形参
        val decoy = """
            /**
             * 文档提及 file_path 与 authorizeWithGate(——不判为形参/调用。
             */
            class SampleDecoyTool(
                sink: ToolResultSink = ToolResultSink.NOOP,
            ) : StructuredSimpleTool<SampleDecoyTool.Args>(
                sink = sink,
                argsType = typeToken<Args>(),
            ) {
                @Serializable
                data class Args(
                    val keyword: String = "file_path",
                )

                // authorizeWithGate( 出现在行注释里
                override suspend fun executeTyped(args: Args): ToolResult = ToolResult.Ok("")
            }
        """.trimIndent()
        val decoyScanned = scanToolClasses(decoy, "sample/Decoy.kt")
        assertTrue(decoyScanned.single().pathParams.isEmpty(), "字符串默认值里的路径名不得判为形参（实际=${decoyScanned.single().pathParams}）")
        assertTrue(!decoyScanned.single().callsFence, "注释里的围栏调用文字不得判为真实调用")

        // 结构化基类声明本身不判为工具（防基类形态变化时被误收进扫描面）
        val base = """
            abstract class StructuredSimpleTool<TArgs : Any>(
                private val sink: ToolResultSink,
            ) : SimpleTool<TArgs>(
                argsType = argsType,
            ) {
                abstract suspend fun executeTyped(args: TArgs): ToolResult
            }
        """.trimIndent()
        assertTrue(scanToolClasses(base, "sample/Base.kt").isEmpty(), "StructuredSimpleTool 基类声明不得判为工具子类")
    }

    // ------------------------------------------------------------------ D 组：子类位置收口

    /**
     * D：全仓生产源码中 StructuredSimpleTool 子类只允许出现在 core/llm/tools。
     *
     * 在 feature/lib 模块另立工具面会让该工具同时逃过本守护（A/B 只扫 tools 目录）
     * 与 [ToolStructuredDisciplineTest]（同只扫 tools 目录）——围栏与三段式纪律
     * 双双失明。新增合法面（如 lib 侧独立工具族）必须先改本测试锚登记扫描根。
     */
    @Test
    fun `D structured tool subclasses live only in the guarded tools dir`() {
        val root = locateProjectRoot()
        val sources = collectProductionSources(root)
        check(sources.size >= MIN_PRODUCTION_KT_FILES) {
            "生产源码 .kt 扫描文件数异常（${sources.size} < $MIN_PRODUCTION_KT_FILES 下限）：守护会静默失效"
        }
        val offenders = sources.flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (rel.startsWith("$TOOLS_DIR/")) return@flatMap emptyList<String>()
            val normalized = normalizeKotlinSource(file.readText())
            toolClassDeclRegex.findAll(normalized)
                .filter { it.groupValues[1] != BASE_CLASS_NAME }
                .map { rel }
                .toList()
        }.toSortedSet()
        assertTrue(
            offenders.isEmpty(),
            "StructuredSimpleTool 子类出现在 core/llm/tools 之外（$offenders）——" +
                "agent 工具面收口在 tools 目录（围栏/结构化纪律的扫描面）之外会双双失明；" +
                "确需另立工具面：先改本测试锚登记新扫描根，并核对围栏覆盖",
        )
    }

    // ------------------------------------------------------------------ 扫描 / 解析实现

    /** 路径形参判据：属性名等于 path 或以 _path 结尾（匹配集推导见类 KDoc，勿放宽） */
    private fun isPathParamName(name: String): Boolean = name == "path" || name.endsWith("_path")

    /** 发现 tools 目录下全部 StructuredSimpleTool 子类；目录缺失即红不静默空过 */
    private fun discoverTools(): List<ToolScan> {
        val root = locateProjectRoot()
        val toolsDir = File(root, TOOLS_DIR)
        assertTrue(toolsDir.isDirectory, "工具目录缺失（$TOOLS_DIR）——目录被重构时本守护必须红出来而非静默漏扫")
        return toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .flatMap { file -> scanToolClasses(file.readText(), file.relativeTo(root).invariantSeparatorsPath) }
            .toList()
    }

    /**
     * 归一化后解析一段源码里的全部 StructuredSimpleTool 子类：类名 + Args 路径形参 +
     * 类体是否调用围栏。类体/Args 体不可定位时直接抛（解析器失明必须红，不得静默跳过）。
     * Args 不在类体内声明（共享 Args 形态）时路径形参不可见——B 组分类断言会逼出
     * 白名单登记，登记理由须核对共享 Args 的文件入参如何被围栏。
     */
    private fun scanToolClasses(rawSource: String, relativePath: String): List<ToolScan> {
        val normalized = normalizeKotlinSource(rawSource)
        val result = mutableListOf<ToolScan>()
        for (m in toolClassDeclRegex.findAll(normalized)) {
            val className = m.groupValues[1]
            if (className == BASE_CLASS_NAME) continue
            val open = normalized.indexOf('{', m.range.last)
            assertTrue(open >= 0, "$relativePath: $className 类体不可定位（源码形态漂移，先修本测试解析器）")
            val close = matchBrace(normalized, open)
            assertTrue(close > open, "$relativePath: $className 类体花括号不可配对（源码形态漂移，先修本测试解析器）")
            val body = normalized.substring(open, close + 1)
            val argsParams = extractArgsParamList(body)
            val pathParams = argsParams
                ?.let { argsPropRegex.findAll(it).map { g -> g.groupValues[1] }.filter(::isPathParamName).toList() }
                .orEmpty()
            result += ToolScan(className, relativePath, pathParams, fenceCallRegex.containsMatchIn(body))
        }
        return result
    }

    /**
     * 工具类体内嵌 `data class Args(...)` 的构造参数列表切片：Args 是无类体的数据类，
     * 属性全部声明在构造参数表里（类体后续方法内的局部 val 不入形参面）。
     * 类体内无 Args 声明时返回 null。参数表内的默认值可自带平衡括号（emptyList() 等），
     * 故按 [matchParen] 括号配对切片。
     */
    private fun extractArgsParamList(classBody: String): String? {
        val argsDecl = argsDeclRegex.find(classBody) ?: return null
        val open = argsDecl.range.last
        val close = matchParen(classBody, open)
        if (close <= open) return null
        return classBody.substring(open, close + 1)
    }

    /** 返回与 [open] 处 `(` 配对的 `)` 位置（跳过字符字面量），无配对返回 -1 */
    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '\'') {
                i++
                while (i < s.length) {
                    if (s[i] == '\\') { i += 2; continue }
                    if (s[i] == '\'') { i++; break }
                    i++
                }
                continue
            }
            if (c == '(') depth++
            else if (c == ')') { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

    /** 返回与 [open] 处 `{` 配对的 `}` 位置（跳过字符字面量），无配对返回 -1 */
    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '\'') {
                i++
                while (i < s.length) {
                    if (s[i] == '\\') { i += 2; continue }
                    if (s[i] == '\'') { i++; break }
                    i++
                }
                continue
            }
            if (c == '{') depth++
            else if (c == '}') { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

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
     * 字符串字面量内容（普通 `"..."` 含 `\"` 转义、原始 `"""..."""`），保留全部换行以
     * 维持行号对齐。已知近似（沿 [LarkSdkIsolationTest] 同款取舍）：含引号的字符字面量
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

        /** 守护扫描根（agent 工具面收口目录，D 组白名单锚） */
        const val TOOLS_DIR = "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools"

        /** 结构化基类名（基类声明不判为工具） */
        const val BASE_CLASS_NAME = "StructuredSimpleTool"

        /** tools 目录下工具数下限（现网 31 件，留余量）：跌破说明目录定位/解析出错，守护会静默失效 */
        const val MIN_TOOL_CLASSES = 25

        /** 全仓生产 .kt 下限（现网 500+，留足余量）：跌破说明扫描根丢失/剪枝出错 */
        const val MIN_PRODUCTION_KT_FILES = 300

        /**
         * 工具子类声明：`class X(...) : StructuredSimpleTool<...>`。
         * `[^{]*?` 不跨任何 `{`——构造参数列表内不出现花括号，类体开头 `{` 自然封住
         * 匹配区间，多工具同文件各自独立命中；基类声明不自带第二次 StructuredSimpleTool
         * 提及故天然不命中（另有 [BASE_CLASS_NAME] 兜底）。
         */
        val toolClassDeclRegex = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\b[^{]*?StructuredSimpleTool\s*<""")

        /** 工具类体内嵌的 Args 声明 */
        val argsDeclRegex = Regex("""\bdata\s+class\s+Args\s*\(""")

        /** Args 体属性名提取（归一化后 LLMDescription 字符串已剥离，仅剩真实声明） */
        val argsPropRegex = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)""")

        /** 围栏校验调用（authorizeWithGate 或 authorize——HITL 单一入口语义由 ToolStructuredDisciplineTest 钉死） */
        val fenceCallRegex = Regex("""authorize(?:WithGate)?\s*\(""")

        /** 剪枝目录名（构建产物 / IDE / 临时目录，任何深度命中即不进入） */
        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".clikt_tmp", ".review_tmp", ".idea", "node_modules", ".git",
        )

        /**
         * 无文件入参 / 围栏外合法形态的工具白名单：工具类名 → 登记理由。
         *
         * 🔴 扩展契约：新增 StructuredSimpleTool 必须先完成分类再落生产代码——含文件
         * 入参 → 类体内调 authorizeWithGate（自然通过）；无文件入参或围栏外合法形态 →
         * **先**在此登记理由（核对文件入参如何被围栏/为何无文件面），**再**落代码；
         * 工具被删/改名/迁出扫描面后必须同步删条目（B 组防腐化断言强制）。
         */
        val noFileInputWhitelist: Map<String, String> = mapOf(
            "ViewMemoryTool" to
                "记忆域自带围栏：File(dir, fileName) 由构造注入的 memoryDir 拼出，fileName 是裸文件名" +
                    "非路径（ASCII 白名单正则 + isInside canonical 包含性防御），文件系统访问钉死在 memoryDir 内",
            "AppendMemoryTool" to
                "记忆域自带围栏（同 ViewMemoryTool）：fileName 裸文件名 + memoryDir 注入 + isInside 防御",
            "ReplaceMemoryTool" to
                "记忆域自带围栏（同 ViewMemoryTool）：fileName 裸文件名 + memoryDir 注入 + isInside 防御",
            "DeleteMemoryTool" to
                "记忆域自带围栏（同 ViewMemoryTool）：fileName 裸文件名 + memoryDir 注入 + isInside 防御",
            "ReportStepTool" to
                "进度上报纯展示零文件 I/O：subject 等参数只进 sink 旁路与渲染文案，不触任何路径",
            "ProposeKnowledgeTool" to
                "候选知识暂存内存 buffer 零文件 I/O：sourceRefs 是 citation 展示文本，非文件访问入参",
            "ConvertTimestampTool" to
                "纯时间戳换算零文件 I/O：value 是时间戳/日期时间字符串",
            "AskUserTool" to
                "HITL 提问零文件 I/O：字段 type 含 FILE_PATH 但那是用户回答的表单域类型" +
                    "（答案回流 LLM，工具不读不写任何路径）",
            "SearchKnowledgeDocsTool" to
                "SQLite 知识库检索零文件系统 I/O：repo/name 是库内标识非文件路径",
            "ReadKnowledgeDocTool" to
                "SQLite 知识库读取零文件系统 I/O：repo/name 是库内标识非文件路径",
            "LookupTagTool" to
                "SQLite 知识库反查零文件系统 I/O：tag/repo 是库内标识非文件路径",
            "SearchGoldenSamplesTool" to
                "SQLite 案例库检索零文件系统 I/O：keyword/repo 是库内检索标识非文件路径",
            "WriteTodosTool" to
                "内存规划板（StateFlow）零文件 I/O：todos 只进任务级 TodoBoard",
            "WebFetchTool" to
                "网络白名单 GET 零文件系统 I/O：url 是 URL 非文件路径（不落盘不读盘）；" +
                    "域名白名单 + 私网段解析拒绝是本工具自带的独立防线，与文件围栏无关",
            "SysProbeTool" to
                "只读诊断白名单目录零文件路径入参：key 是编译期目录条目名（enum 精确匹配，" +
                    "选不了造不了命令）；service/lines/schema_key/uuid 槽值经白名单/钳制/形态校验后" +
                    "只进 argv 数组元素（ArgvProcessRunner argv 纯语义无 shell 解释），不触文件围栏",
            "SysRepairTool" to
                "修复动作白名单目录零文件路径入参：key 是编译期动作条目名（enum 精确匹配），" +
                    "uuid/schema_key/value 槽值经形态/白名单/schema 类型校验后只进 argv 数组元素" +
                    "（argv 纯语义无 shell 解释，执行经 ArgvProcessRunner），不触文件围栏；" +
                    "写面由逐动作确认卡（L1b）与执行面黑名单承载",
            "SysServiceTool" to
                "服务动作白名单目录零文件路径入参：service 是编译期白名单服务名（只读服务白名单" +
                    "剔除桌面会话级单元），action 是动作枚举（restart/start/stop），槽值只进 " +
                    "systemctl --user 的 argv 数组元素（argv 纯语义无 shell 解释，执行经 " +
                    "ArgvProcessRunner——执行件内缺 --user 恒拒），不触文件围栏；" +
                    "写面由逐动作确认卡（L2，DESTRUCTIVE 徽标）与会话档承载",
            "SysVerifyTool" to
                "验证回环零文件路径入参：key 是编译期修复动作条目名（enum 精确匹配，验证探查 " +
                    "argv 由映射推导——LLM 选不了探查命令）；uuid/schema_key/value 槽经形态/" +
                    "白名单/schema 类型校验后只进 argv 数组元素；before 是修复回执 pre-state " +
                    "快照的回传文本（非路径，只进比对函数）——不触文件围栏；只读探查复跑由 " +
                    "L0 会话档承载",
            "SysHandoffTool" to
                "命令外委零文件路径入参且零执行面：steps.command 是交用户本人在终端执行的" +
                    "命令文本（不进任何执行器，复制粘贴保真优先，不过围栏）；purpose/why/risk/" +
                    "rollback 是展示文本——工具不读不写任何路径、不触任何进程",
            "DeviceListTool" to
                "设备清单零文件路径入参：零参 Args（LLM 无任何入参）；设备列表来自设备检测单源" +
                    "（DeviceSource 缝注入，每次调用新检测）——serial 原样回显供 dev.snaplog 白名单 " +
                    "round-trip，不读不写任何文件路径，不触文件围栏",
            "SnaplogTool" to
                "设备日志快照零文件路径入参：serial 走当前检测列表白名单（LLM 只能回传检测单源" +
                    "刚报出的串号）、lines 走 10..5000 钳制，argv 由编译期固定模板组装后只进 " +
                    "ArgvProcessRunner 数组元素（argv 纯语义无 shell 解释）；产物落点钉死数据根 " +
                    "devlogs 目录（LLM 不可指定），落盘后由工具本体 register 进本任务围栏供 " +
                    "log.* 分析链消费——读取面由会话级总授权卡收窄，不触路径形参围栏",
            "SelfStatusTool" to
                "自观测零参 Args 零文件 I/O 零子进程：四段数据面（连接态/流水线计数/配置校验/" +
                    "sys-audit 聚合计数）全部经构造注入的 SelfStatusSource 缝在进程内现取，" +
                    "LLM 无任何入参（审计只报聚合计数，不读审计文件不落记录原文）——不触文件围栏",
            "WsRunTaskTool" to
                "受限任务执行零路径形参：command 是编译期配方枚举名（GRADLE/MAVEN/NPM/PNPM，" +
                    "LLM 不能造命令）、args 是纯 token 列表（空白/换行拒收）、cwd 恒由 gate 注入" +
                    "工作区根（非 LLM 输入）——进程执行经 ArgvProcessRunner（提权黑名单 + 进程树" +
                    "级联终止），每执行经确认卡人审，不触路径形参围栏",
        )
    }
}
