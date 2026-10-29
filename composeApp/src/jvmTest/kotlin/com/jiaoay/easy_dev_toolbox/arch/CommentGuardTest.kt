// 注释宪法守护（AGENTS.md「关键约定」第 9 条）：注释只写「为什么/契约/指引」三类。
// 拦截五类机械化可判定的违规：历史叙事（日期/批次号/过程词）、悬空 KDoc 链接、
// 注释掉的声明、（棘轮 baseline 管理 narrative 类存量）、注释区引用 *.html 设计稿。
// 另守一条计数棘轮：注释区 🔴（真门禁标记）行数按文件登记上限，只降不升。
//
// 🔴 验收纪律：本测试是扫盘型守护，一律 `--rerun-tasks --no-build-cache` 跑（build cache 会假绿）。
// 🔴 扫描面：全部模块 src/jvmMain/kotlin 与 src/main/kotlin 双源集（lib 域是 src/main）；
//    narrative 三正则与 mockupHtmlRef 同时管全部模块测试双源集（src/jvmTest/kotlin 与
//    src/test/kotlin——测试面叙事同宪法管辖，快照基线锁存量只降不升）；悬空 KDoc 链接与
//    注释掉的声明不扩测试面（测试面误伤面大，另行评估）。fixture 字符串数据行天然不在
//    扫描面（extractCommentLines 只抽注释区）。
// 🔴 baseline 语义 = 「尚未治理的存量」不是「永久豁免」：每文件命中数只许降不许升，
//    归零条目必须删除；调大 baseline 须在 commit message 给理由。存量分域消化，
//    每域完成即把对应文件条目归零删除。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CommentGuardTest {

    // ── 扫描面 ──

    private val projectRoot: File by lazy { locateProjectRoot() }

    /**
     * 全部生产源文件（双源集）：jvmMain（宿主域）+ main（lib 域 + includeBuild 的 build-logic），相对项目根路径。
     */
    private val productionSources: List<Pair<File, String>> by lazy {
        val roots = mutableListOf<Pair<File, String>>()
        val moduleDirs = projectRoot.listFiles { f -> f.isDirectory }
            .orEmpty()
            .filter { File(it, "src/jvmMain/kotlin").isDirectory || File(it, "src/main/kotlin").isDirectory }
            .filter { it.name !in EXCLUDED_MODULE_DIRS }
        for (mod in moduleDirs) {
            for (srcSet in listOf("src/jvmMain/kotlin", "src/main/kotlin")) {
                val root = File(mod, srcSet)
                if (root.isDirectory) {
                    root.walkTopDown()
                        .filter { it.isFile && it.extension in setOf("kt", "kts") }
                        .forEach { roots.add(it to it.relativeTo(projectRoot).invariantSeparatorsPath) }
                }
            }
        }
        check(roots.size >= MIN_SOURCE_FILES) {
            "扫描面异常：仅发现 ${roots.size} 个生产源文件（下限 $MIN_SOURCE_FILES）——模块根定位或源集枚举失效"
        }
        roots
    }

    /**
     * 全部测试源文件（双源集）：宿主域 jvmTest + lib 域 test。narrative 三正则与
     * mockupHtmlRef 在此面上同宪法管辖（基线棘轮锁存量）；悬空 KDoc 链接与注释掉的
     * 声明不扩此面（测试面误伤面大，另行评估）。
     */
    private val testSources: List<Pair<File, String>> by lazy {
        val roots = mutableListOf<Pair<File, String>>()
        val moduleDirs = projectRoot.listFiles { f -> f.isDirectory }
            .orEmpty()
            .filter { it.name !in EXCLUDED_MODULE_DIRS }
        for (mod in moduleDirs) {
            for (srcSet in listOf("src/jvmTest/kotlin", "src/test/kotlin")) {
                val root = File(mod, srcSet)
                if (root.isDirectory) {
                    root.walkTopDown()
                        .filter { it.isFile && it.extension in setOf("kt", "kts") }
                        .forEach { roots.add(it to it.relativeTo(projectRoot).invariantSeparatorsPath) }
                }
            }
        }
        roots
    }

    // ── 规则 ──

    /** 日期叙事：年-月-日 / 年/月/日 数字形态（字符串字面量已由注释区提取排除） */
    private val narrativeDate = Regex("""20[2-9]\d[-/.]\d{1,2}[-/.]\d{1,2}""")

    /**
     * 批次/工单/卡号叙事：工单前缀-数字族、跨进程任务号型、单字母短号、字母对/字母数字对、
     * P 批、批号三形态（数字/汉字/带圈数字）、验收流程批词、两位任务号、十六进制易混短形——
     * 各形态以正则原文为唯一真源（正则是字符串字面量不入注释区），此处不复述具体 token 防自命中。
     */
    private val narrativeBatch = Regex(
        """\bM\d-T\d+\b""" +
            // 🔴 负向后顾：避开十六进制色值中段（色值字串的中段两字符不构成工单号叙事）
            """|(?<![#A-Fa-f0-9])\b(?:WB|DS|DL-[FM]|DOC|XA|GLB|SYS|LB|AG|SH|TL|BC|IA|F(?!1\b))-?\d+""" +
            """|\b(?:WB|DS|DL-[FM]|DOC|XA|GLB|SYS|LB|AG|SH|TL|BC|IA|F)-[A-Z]+\d""" +
            """|\bR-?\d{1,2}\b|\bD\d{1,2}\b|\bB\d\b""" +
            // 字母-字母 / 字母-数字形态的工单前缀（原文为上方交替分支，不复述防自命中）
            """|\bG-[AB]\b|\bN-\d""" +
            """|\bP[0-5]\b\s*批""" +
            """|批\s?\d|批[一二三四五六七八九十①②③④⑤⑥⑦⑧⑨⑩]""" +
            // 空白+带圈数字形态——上方数字支与无空白带圈支各拦其一，本支补齐空白形
            """|批\s?[①-⑩]""" +
            """|下线批|重设计批|终验|对稿|回归批""" +
            """|\bT\d{2}\b""" +
            // 裁决补的盲区变体：P 系段形、S 段形、跨进程号、方案行号、流程骨架词、R 延长形（原文见正则）
            """|\bP[0-5][a-c]\b""" +
            """|\bP[0-5]-\w\b""" +
            """|\bS5[a-c]\b""" +
            """|\bC7\b""" +
            """|\bNF-\d+\b""" +
            """|方案\s?line""" +
            """|骨架批""" +
            """|\bxa\d+\b|\b0b\b""",
    )

    /** 过程叙事词（一次性决策/评审/修正过程词——正则原文为唯一真源，不复述 token 防自命中） */
    private val narrativeProcess = Regex(
        "旧实现|新实现|旧架构|主人拍板|主人裁定|主人点名|蜂群|对抗评审|决策\\s?\\d+|裁定\\s?\\d+|勘误",
    )

    /** 注释掉的声明级代码（// val x = ...；只拦声明，不拦描述性文字） */
    private val commentedOutDecl = Regex("""^\s*//\s*(?:private\s|internal\s|public\s)?(?:val|var|fun|class|object|interface|import)\s""")

    /**
     * 注释区引用 `*.html` 设计稿（零容忍）：生产注释必须自足可求值（值 + 单位 + 依据
     * 直接写进注释），不得依赖设计稿预览文件；稿面出处随注释自足登记
     * （见 docs/architecture.md「注释治理」节）。
     * 只匹配「文件名.html」形态；`text/html` 之类 MIME 写法无点号前缀，天然不命中；
     * 字符串字面量已被 [extractCommentLines] 排除，URL/示例串安全。
     */
    private val mockupHtmlRef = Regex("""[A-Za-z0-9_-]+\.html\b""")

    // ── 悬空 KDoc 链接 ──

    /** 全仓声明符号索引（class/object/interface/fun/val/var 后的标识符 + fun interface + enum 常量 + typealias） */
    private val declaredSymbols: Set<String> by lazy {
        val decl = Regex(
            """(?:\b(?:class|object|interface)\s+|\bfun\s+(?:interface\s+)?|\b(?:val|var)\s+|\btypealias\s+)""" +
                """(?:<[^>]*>\s+)?([A-Za-z_][A-Za-z0-9_]*)""",
        )
        val result = mutableSetOf<String>()
        for ((file, _) in productionSources) {
            // 声明索引只看代码区——剥注释后扫描（注释里的「fun xxx」示例不得入索引）
            val code = stripCommentsKeepStrings(file.readText())
            decl.findAll(code).forEach { result.add(it.groupValues[1]) }
            // enum 常量（enum class X { A, B, C } 的条目；全大写与首字母大写两种形态都有）
            val enumBody = Regex("""\benum class\s+\w+[^{]*\{([^}]*)\}""")
            for (m in enumBody.findAll(code)) {
                m.groupValues[1].split(',')
                    .map { it.trim().substringBefore('(').trim() }
                    .filter { it.isNotEmpty() && it[0].isUpperCase() }
                    .forEach { result.add(it) }
            }
            // sealed/interface 子类型名（sealed class X 的实现类在其他文件声明，经类名索引已覆盖）
        }
        // 各文件 import 段标识符（跨模块引用的符号）
        val imp = Regex("""^import\s+[\w.]+\.([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.MULTILINE)
        for ((file, _) in productionSources) {
            imp.findAll(file.readText()).forEach { result.add(it.groupValues[1]) }
        }
        // 测试源集类名（KDoc 引用测试类合法，如 [WorkspaceTabsTest]）
        val testRoots = projectRoot.listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { mod ->
                listOf("src/jvmTest/kotlin", "src/test/kotlin").mapNotNull { ss ->
                    File(mod, ss).takeIf { it.isDirectory }
                }
            }.flatten()
        for (root in testRoots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    decl.findAll(stripCommentsKeepStrings(f.readText())).forEach { result.add(it.groupValues[1]) }
                }
        }
        // Kotlin/JDK 常用类型（KDoc 引用标准库类型合法，不属本仓声明面）
        result.addAll(JDK_COMMON_TYPES)
        result
    }

    /** KDoc 内 [Symbol] 链接（仅星块注释形态） */
    private val kdocLink = Regex("""\[([A-Z][A-Za-z0-9_]*)]""")

    // ── baseline 棘轮 ──

    /**
     * 叙事类存量棘轮 baseline（按落盘时工作区现状快照，覆盖生产+测试双源集）：path → 允许的最大命中行数。
     * 只许降不许升；归零条目删除。**这里没有的文件 = 零容忍**（新文件/已清文件写叙事注释直接红）。
     */
    private val narrativeBaseline: Map<String, Int> = mapOf(
"composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/AppHostGuardTest.kt" to 7,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ChannelSeamGuardTest.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ContentSoftRepoGuardTest.kt" to 2,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/GuardTestSupport.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/KnowledgeUriPrefixSingleSourceGuardTest.kt" to 7,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/LarkSdkIsolationTest.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/LightTagCapsuleGuardTest.kt" to 9,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/MacBundleIdGuardTest.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/McpSdkIsolationTest.kt" to 3,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ModuleGraphGuardTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ScanSurfaceSelfCheckTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/SearchFieldGuardTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/SherpaOnnxIsolationTest.kt" to 2,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/TaskCardOverflowMenuGuardTest.kt" to 5,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/TaskEmptyHintGuardTest.kt" to 8,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/TonalCardShapeGuardTest.kt" to 5,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ToolCatalogDisciplineTest.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ToolStructuredDisciplineTest.kt" to 3,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/UiArchitectureGuardTest.kt" to 48,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/AppViewModelOpenTabsTest.kt" to 10,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/host/AppStatusBarShapeGuardTest.kt" to 6,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/host/EasyDataManagerLogFileEnabledTest.kt" to 2,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/host/GatewayStatusDotColorGuardTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/host/HotkeyHubTest.kt" to 9,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/host/PageUsageTrackerTest.kt" to 2,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/host/StatusBreadcrumbPathGuardTest.kt" to 3,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugViewModelTest.kt" to 10,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/dialog/DialogDismissWordingGuardTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/BackButtonAndTransitionGuardTest.kt" to 5,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPaletteFrecencyTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/OnboardingCardTest.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageReportTest.kt" to 21,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteParamFormTest.kt" to 1,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/ParamCommandCatalogTest.kt" to 2,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/SpaceMemberPanelTest.kt" to 4,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/ToolPageRegistryNavTest.kt" to 33,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/ToolPageWiringGuardTest.kt" to 26,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/WorkspaceTabsTest.kt" to 6,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/SettingsPageCopyGuardTest.kt" to 15,
        "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/ToolsCompactTabGuardTest.kt" to 4,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/DistillIngest.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/KoogDistillPrompts.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/AgentToolCatalog.kt" to 8,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/AnalyzeLogTool.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/DecryptTools.kt" to 5,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/KnowledgeTools.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/SysProbeTools.kt" to 4,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/SysReadConfigTools.kt" to 4,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/ToolResult.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/runtime/StatusSignals.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/system/SysAuditLog.kt" to 2,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/buffer/FileBufferTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/DatabaseTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/DistillIngestTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/FlowsMigrationTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/KnowledgeEngineTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/KnowledgeManageTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/KnowledgeMdImporterTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/RunStoreTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/RunsMigrationTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/SessionLedgerTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/SpacesMigrationTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/SqliteChatHistoryProviderTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/SqliteSessionStoreManagementTest.kt" to 2,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/evidence/EvidenceModelsTest.kt" to 4,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/ShellCommandBuilderTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/StreamingShellKillTest.kt" to 2,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/StreamingShellTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/TimestampsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/initializer/JInitializerManagerTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/KoogAgentPromptPresetsTest.kt" to 4,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/KoogUserStyleMapTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/LlmConfigStoreTest.kt" to 3,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/McpServerConfigKeyTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/SourceRootsMapTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/agent/AgentToolMiddlewareTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/AnalyzeLogToolTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/DecryptToolsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/DistillToolsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/FileToolsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/LogTaskToolsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/MemoryManagerTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/MemoryToolsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/SysProbeToolsTest.kt" to 2,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/SysReadConfigToolsTest.kt" to 2,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/ToolExecutionContextAllowedRootsTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/log/GlobalUncaughtExceptionHandlerTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/log/KotlinLoggingLogImplTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/media/AudioTranscoderTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/AiEnabledSignalTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/ToolUsageSignalTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/shelltools/AdbCommandRewriterTest.kt" to 1,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/AlphaTest.kt" to 11,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/AppShapesTest.kt" to 5,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/DimensTest.kt" to 16,
        "core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/MotionTest.kt" to 1,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiContributions.kt" to 1,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillViewModel.kt" to 1,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiSettingsPageGuardTest.kt" to 8,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiSettingsViewModelTest.kt" to 4,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiToolsToggleTest.kt" to 2,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillAbandonTest.kt" to 1,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillAppButtonGuardTest.kt" to 5,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillAttributionTest.kt" to 1,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillLoadingEmptyStateGuardTest.kt" to 5,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillSessionTest.kt" to 1,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillTagsFieldGuardTest.kt" to 1,
        "feature-ai/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillViewModelTest.kt" to 1,
        "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchiveViewModel.kt" to 1,
        "feature-archive/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchiveViewModelTest.kt" to 7,
        "feature-archive/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/SplitArchiveTest.kt" to 11,
        "feature-archive/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/VolumeAndSevenZipTest.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgePage.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/ScaledNumberField.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/BridgeLogTools.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogSystemPrompt.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/log/LogConsolePanel.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/BridgeStatusPanel.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/ConfigCheckFormatter.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/CockpitContentSoftGuardTest.kt" to 5,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/CockpitRemainingPillGuardTest.kt" to 13,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgeManagerSandboxTest.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgeViewModelTest.kt" to 6,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LbGuardOperationBarTest.kt" to 10,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LegacyConfigImporterTest.kt" to 13,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/ListConfigCommitTest.kt" to 4,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/PillLabelGuardTest.kt" to 16,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/TaskErrorHintTest.kt" to 3,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitLogicTest.kt" to 9,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanelLogicTest.kt" to 4,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/LbGuardCapabilityCardTest.kt" to 8,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/LbGuardQuickSetupTest.kt" to 6,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/QuickSetupGateTest.kt" to 2,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/BridgeLogToolsTest.kt" to 2,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/BridgeSourceRootsIsolationTest.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KnowledgeBaseTest.kt" to 2,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogAgentRunnerTest.kt" to 23,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/SessionHistoryPersistenceTest.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/log/LBGuardLogConsoleTest.kt" to 4,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/security/BridgeSecurityCardLogicTest.kt" to 20,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/DonutSlicesTest.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsByDayTest.kt" to 7,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsKindTest.kt" to 6,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsReadFailureTest.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsSenderDetailTest.kt" to 1,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/LbGuardConfigCheckTest.kt" to 3,
        "feature-larkbridge/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/LbGuardStatusPanelTest.kt" to 1,
        "feature-logkit/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logkit/DeepAnalyzeWiringGuardTest.kt" to 4,
        "feature-logkit/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logkit/LogKitParamCopyGuardTest.kt" to 2,
        "feature-logkit/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterPageWiringGuardTest.kt" to 2,
        "feature-logkit/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterViewModelTest.kt" to 2,
        "feature-logworkbench/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/EvidenceTimelineOffsetTest.kt" to 5,
        "feature-logworkbench/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchDeepLinkTest.kt" to 4,
        "feature-logworkbench/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/RelativeTimeTest.kt" to 3,
        "feature-logworkbench/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchSessionTest.kt" to 2,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpAgentToolsTest.kt" to 1,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposePageGuardTest.kt" to 15,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposePortConfirmTest.kt" to 2,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposeViewModelActiveRunsTest.kt" to 1,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposeViewModelLogStampTest.kt" to 1,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposeViewModelToolFamilyTest.kt" to 1,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpRunRegistryTest.kt" to 3,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpServerManagerTest.kt" to 2,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpServerSmokeTest.kt" to 1,
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpToolRegistrarTest.kt" to 2,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowContributions.kt" to 2,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowPage.kt" to 3,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowViewModel.kt" to 7,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/QuestSession.kt" to 5,
        "feature-quest/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowNamingGuardTest.kt" to 3,
        "feature-quest/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowRecentCardGuardTest.kt" to 4,
        "feature-quest/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/QuestSessionTest.kt" to 5,
        "feature-screencast/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScrcpySpikeRunner.kt" to 1,
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/ShellOutputPanel.kt" to 2,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/IconTouchTargetGuardTest.kt" to 6,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellExecutionEngineOutputTest.kt" to 1,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellExecutionEngineRobustnessTest.kt" to 1,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellOutputPanelConsoleGuardTest.kt" to 11,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellOutputPanelWiringGuardTest.kt" to 6,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellRetryRewriteGuardTest.kt" to 1,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsDeviceSelectDialogVerbGuardTest.kt" to 4,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsListNoMatchGuardTest.kt" to 2,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsRuntimeTest.kt" to 1,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsTreeEmptyStateGuardTest.kt" to 4,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsViewModelDetectingWiringGuardTest.kt" to 3,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/config/ShellToolsConfigParserTest.kt" to 1,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/config/ToolNodeSearchFilterTest.kt" to 1,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryManagerFlushTest.kt" to 2,
        "feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryManagerRemoveClearTest.kt" to 2,
        "feature-timestamp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampConverterDiffBatchTest.kt" to 1,
        "feature-timestamp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampConverterTest.kt" to 3,
        "feature-timestamp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampEnterKeyWiringGuardTest.kt" to 3,
        "feature-timestamp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampRecentChipsWiringGuardTest.kt" to 6,
        "feature-timestamp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampViewModelRecentTest.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/GithubModelDownloader.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceContributions.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceViewModel.kt" to 1,
        "feature-voice/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/LiveTranscribeTest.kt" to 1,
        "feature-voice/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/ModelStoreTest.kt" to 1,
        "feature-voice/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceAttributionTest.kt" to 1,
        "feature-voice/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceReviewMarkdownTest.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/Bridge.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeAssembler.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/CliSupport.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/BridgeConfig.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/BridgeFields.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/ProcessRegistry.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/ProgramLauncher.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/Admission.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/ChatLanes.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/RateGovernor.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/BridgeAppExitEdtBudgetTest.kt" to 4,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/BridgeAssemblerParityTest.kt" to 41,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/BridgeConcurrencyContractTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/BridgeConnectionSignalTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/BridgeLifecycleTest.kt" to 23,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/BridgeRegisterAppTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/GatewayConfirmNotifierTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/XlogKeyFallbackTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/agent/ArtifactsTest.kt" to 5,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/agent/ProgressAccumulatorWindowTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/agent/ProgressCardRendererTest.kt" to 7,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/cli/CheckCommandTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/cli/CliConfigOverrideTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/cli/CliExitProcessTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/cli/HandleEventCommandTest.kt" to 5,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/cli/ServeCommandTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/cli/VerboseConsoleLevelTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/BridgeConfigDefaultsTest.kt" to 8,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/BridgeConfigRoundTripTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/BridgeFieldsTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/ConfigAtomicWriteTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/ConfigMigrationUnknownKeyPreservedTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/ConfigMigrationsTest.kt" to 10,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/ConfigRepositoryTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/ConfigValidatorTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/config/LegacyDocumentMapperTest.kt" to 19,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/confirm/ConfirmServiceTest.kt" to 31,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/confirm/RiskClassifierTest.kt" to 4,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/confirm/ToolConfirmGateTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/CancellationKillsProcessTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/DrainJoinTimeoutTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/EnvAllowlistTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/IdleWatchdogTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/LaunchCommandTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/PipeBackpressureTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/ProcessRegistryTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/ProgramLauncherTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/SensitiveFilePolicyTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/StartStopLeakTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/TimeoutKillsProcessTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/exec/WorkspacePolicyTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/files/CardReplyLedgerTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/files/DownloadPolicyTest.kt" to 11,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/files/DownloadStoreTest.kt" to 7,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/files/FileRegistryTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/files/PreprocessPipelineTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/files/WindowsFileNameContractTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/handler/AgentPipelinePumpTest.kt" to 6,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/handler/ArtifactUploadPipelineTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/handler/EngineReservedWordsTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/handler/FileReferenceFlowTest.kt" to 14,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/handler/HandlerGateAndWeightTest.kt" to 8,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/handler/SystemHandlerStatusTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/AuditLogTest.kt" to 14,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/JsonlSinkDropCountTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/JsonlTailReaderTest.kt" to 8,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/LogBusTest.kt" to 5,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/ObserveStatusTest.kt" to 8,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/SenderStatsTest.kt" to 10,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/observe/UserNameResolverTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/AccessPolicyTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/AdmissionReplyCancellationTest.kt" to 4,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/AdmissionTimeoutTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/CountersTest.kt" to 5,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/DispatchAgentRouteTest.kt" to 4,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/DownloadCardSessionTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/FallbackDispatchTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/IntakeGuardTest.kt" to 5,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/IntakeOnRejectedTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/IntakeStageTest.kt" to 3,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/NativeChatSurfaceTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/PipelineTestSupport.kt" to 12,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/RateGovernorTest.kt" to 5,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/RejectDiagnosticsMaskingTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/ReplySinkAnchoringTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/ReplySinkNativeCapabilitiesTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/ReplySinkUniquenessTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/RoutingTest.kt" to 1,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/SingleInstanceLockTest.kt" to 2,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/SyntheticGatewayTest.kt" to 9,
        "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/TestSharedFakes.kt" to 1,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/GatewayModel.kt" to 1,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkCardStreamSink.kt" to 3,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/CardStreamShapeTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/CardStreamTestSupport.kt" to 3,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/CardThrottleTest.kt" to 6,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/ContactDiagnosticsTest.kt" to 2,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/FileDownloadStreamingTest.kt" to 9,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/GatewayCloseReleasesThreadsTest.kt" to 8,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/GatewayDispatcherTest.kt" to 8,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/GatewayLimitsTest.kt" to 7,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/Handler3SecondTest.kt" to 5,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/HistoryTextBriefTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/LarkErrorMapperTest.kt" to 5,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/LarkInboundMapperTest.kt" to 7,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/LarkOutboundMapperTest.kt" to 3,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/LarkPolicyMapperTest.kt" to 12,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/OnFinishedContractTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/QuotedFetchObservabilityTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/QuotedParseTest.kt" to 5,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/ResolveSenderProfileTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/RotationFailureTerminatesTest.kt" to 3,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/SdkDownloadTransportTest.kt" to 5,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/SegmentRotationTest.kt" to 7,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/Stream10MinRotationTest.kt" to 4,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/StreamTerminalSignalTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/WsInboundGatewayTest.kt" to 1,
        "lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/WsInboundTranslatorTest.kt" to 2,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/WsClient.kt" to 1,
        "lark-ws/src/test/kotlin/com/jiaoay/lark/ws/JdkWsConnectionDeliveryTest.kt" to 1,
        "lark-ws/src/test/kotlin/com/jiaoay/lark/ws/WsClientTest.kt" to 1,
        "lark-ws/src/test/kotlin/com/jiaoay/lark/ws/WsDispatcherTest.kt" to 1,
        "lark-ws/src/test/kotlin/com/jiaoay/lark/ws/WsEventParserTest.kt" to 1,
        "lark-ws/src/test/kotlin/com/jiaoay/lark/ws/WsFrameCodecTest.kt" to 1,
        "log-tools/src/main/kotlin/com/jiaoay/log/cli/LogUtilsCommand.kt" to 1,
        "log-tools/src/main/kotlin/com/jiaoay/log/pipeline/LogAnalysisPipeline.kt" to 2,
        "log-tools/src/test/kotlin/com/jiaoay/log/pipeline/LogAnalysisPipelineTest.kt" to 1,
        "log-tools/src/test/kotlin/com/jiaoay/utils/SafeExtractTest.kt" to 11,
        "log-tools/src/test/kotlin/com/jiaoay/xlog/LogDecodeCharacterizationTest.kt" to 1,
        "log-tools/src/test/kotlin/com/jiaoay/xlog/postprocess/TimestampNormalizerTest.kt" to 2,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/AvatarImage.kt" to 2,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/FileDropZone.kt" to 1,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/TagsField.kt" to 1,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/chat/ChatComposer.kt" to 3,
        "ui/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/widget/GhostCardTest.kt" to 6,
        "ui/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/widget/PillLabelTest.kt" to 3,
        "ui/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/widget/device/DeviceStatusBarCapsuleGuardTest.kt" to 5,
        "ui/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/widget/text/MarkdownTextTest.kt" to 1,
    )

    /**
     * 🔴 标记计数棘轮 baseline：path → 允许的最大「注释区含 🔴 的行数」。
     * 语义对齐 [narrativeBaseline]：只许降不许升；归零条目必须删除；这里没有的文件 = 零容忍。
     * 🔴 的宪法口径 = 真门禁标记（违反即出错/数据损坏/不可逆，AGENTS.md「关键约定」第 9 条）；
     * 消化存量 = 普通强调去标降级，或把真门禁改写为不依赖标记的自足契约句。
     */
    private val redMarkBaseline: Map<String, Int> = mapOf(
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/AppViewModel.kt" to 2,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/AppTitleBar.kt" to 3,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/HotkeyHub.kt" to 1,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/UndecoratedResizeOverlay.kt" to 3,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/agentcenter/AgentCenterPage.kt" to 2,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugPage.kt" to 1,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugViewModel.kt" to 3,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPalette.kt" to 3,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/OnboardingCard.kt" to 1,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteEmptyState.kt" to 2,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteParamForm.kt" to 1,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/ParamCommandCatalog.kt" to 1,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/SpaceBar.kt" to 2,
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/ToolsPage.kt" to 3,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/BomStripper.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/FileUtils.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/LocalDataManager.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/Database.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/DistillIngest.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/QuestStore.kt" to 3,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/RunStore.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/db/SqliteSessionStore.kt" to 4,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/evidence/EvidenceModels.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/InteractiveShell.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/ShellCommandBuilder.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/SimpleShell.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/StreamingShell.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/LlmClientFactory.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/LlmConfigStore.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/SourceRootsMap.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/agent/AgentToolMiddleware.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/agent/KoogToolLoop.kt" to 3,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/AskUserModels.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/AskUserTool.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/DecryptTools.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/FileTools.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/OutputCapMiddleware.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/ToolExecutionContext.kt" to 3,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/media/AudioTranscoder.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/CommandActions.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/ParamCommands.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/SpaceAttribution.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/runtime/AppHost.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/runtime/CapabilitySnapshot.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/AppTheme.kt" to 2,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/AppThemeSpec.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/Glass.kt" to 1,
        "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/SurfaceColors.kt" to 1,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiToolsToggle.kt" to 2,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/LlmModelCatalog.kt" to 1,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/ModelIdDropdown.kt" to 1,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillSession.kt" to 1,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillViewModel.kt" to 3,
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/KbManagePanel.kt" to 1,
        "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchiveContributions.kt" to 1,
        "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchivePage.kt" to 1,
        "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchiveViewModel.kt" to 4,
        "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/VolumeAndSevenZip.kt" to 5,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/BridgeErrorMapping.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/BridgeStore.kt" to 6,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/GrantProbe.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/JsonFileDialog.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgeManager.kt" to 3,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgePage.kt" to 3,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgeViewModel.kt" to 8,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LegacyConfigImporter.kt" to 5,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/OperationBar.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitLogic.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanel.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanelLogic.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/BridgeToolRegistry.kt" to 4,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KnowledgeBase.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogAgentRunner.kt" to 5,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogBridgeTools.kt" to 11,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogDispatchStrategy.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogSessionHistory.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/KoogSystemPrompt.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/log/LogConsolePanel.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/SenderDetailPanel.kt" to 2,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStats.kt" to 1,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsPanel.kt" to 3,
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/ConfigCheckFormatter.kt" to 1,
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchSession.kt" to 1,
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchViewModel.kt" to 3,
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpAgentTools.kt" to 1,
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpRunRegistry.kt" to 3,
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpServerManager.kt" to 6,
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpToolRegistrar.kt" to 4,
        // 真门禁：池并发契约（违反即 App 崩溃——无锁裸读池槽与装帧瞬态窗相交）
        "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastDecoder.kt" to 1,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowViewModel.kt" to 2,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/QuestHistoryProvider.kt" to 1,
        "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/QuestSession.kt" to 5,
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsConfigRepository.kt" to 1,
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsRuntime.kt" to 1,
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/config/ShellToolsConfigLoader.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/LiveTranscribe.kt" to 2,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/MicCapture.kt" to 2,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/ModelStore.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/SherpaTranscriber.kt" to 4,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VadEngine.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt" to 1,
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceViewModel.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/Bridge.kt" to 37,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeAssembler.kt" to 29,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeGateways.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeLog.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/Main.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/agent/AgentRunner.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/agent/Artifacts.kt" to 7,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/agent/ProgressAccumulator.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/agent/ProgressCardRenderer.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/BridgeConfigCommand.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/CheckCommand.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/CliSupport.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/HandleEventCommand.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/ServeCommand.kt" to 6,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/BridgeConfig.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/BridgeErrorCode.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/BridgeFields.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/BridgeJson.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/ConfigMigrations.kt" to 11,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/ConfigPaths.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/ConfigRepository.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/ConfigValidator.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/DefaultBridgeConfig.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/GatewayErrorMapping.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/LegacyDocumentMapper.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/config/NativeCapability.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/confirm/ConfirmRuntimeStatus.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/confirm/ConfirmService.kt" to 7,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/confirm/RiskClassifier.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/confirm/ToolConfirmGate.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/dispatch/BridgeDispatchers.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/EnvAllowlist.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/LaunchCommand.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/ProcessRegistry.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/ProgramLauncher.kt" to 9,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/SensitiveFilePolicy.kt" to 8,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/exec/WorkspacePolicy.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/CardReplyLedger.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/DownloadPolicy.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/DownloadStore.kt" to 6,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/FileRegistry.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/PreprocessPipeline.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/ReferencedMessageResolver.kt" to 9,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/handler/AgentPipeline.kt" to 15,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/handler/BuiltinHandlers.kt" to 13,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/handler/EngineReservedWords.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/handler/Responder.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/AuditLog.kt" to 8,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/BridgeStatus.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/JsonlTailReader.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/LogBus.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/MetricsSampler.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/ObserveSeams.kt" to 11,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/ObservingLog.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/SenderStats.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/StringTruncation.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/UserNameResolver.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/AccessControl.kt" to 5,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/Admission.kt" to 23,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/CardJson.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/ChatLanes.kt" to 6,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/DispatchPayload.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/DownloadCardSession.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/EventIntake.kt" to 9,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/HandlerKinds.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/HandlerOutcome.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/InboundQueue.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/MessageDeduper.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/MessageHandler.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/NativeChatSurface.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/PermitGate.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/PipelineCounters.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/PipelineRouter.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/PipelineSnapshot.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/PolicyGate.kt" to 8,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/ProgressSink.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/RateGovernor.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/ReplySink.kt" to 8,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/RequestContext.kt" to 2,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/SingleInstanceLock.kt" to 4,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/StaleFilter.kt" to 3,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/SyntheticGateway.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/TimeoutPolicy.kt" to 1,
        "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/UnsupportedKindNotice.kt" to 1,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/CardStreamSink.kt" to 3,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/ContactDiagnostics.kt" to 2,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/DownloadRangeParsers.kt" to 2,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/GatewayModel.kt" to 5,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkCardStreamSink.kt" to 19,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkErrorMapper.kt" to 2,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkInboundMapper.kt" to 4,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkOutboundMapper.kt" to 7,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkPolicy.kt" to 6,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkPolicyMapper.kt" to 2,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/MessageGateway.kt" to 7,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/QuotedContentParsers.kt" to 1,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/RegisterAppFlow.kt" to 2,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkCardStreamPort.kt" to 1,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt" to 33,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/Tuning.kt" to 10,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/WsClientTransportFactory.kt" to 3,
        "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/WsInboundTransport.kt" to 1,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/JdkWsConnection.kt" to 5,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/WsClient.kt" to 3,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/WsDispatcher.kt" to 1,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/WsEventParser.kt" to 3,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/WsFrame.kt" to 1,
        "lark-ws/src/main/kotlin/com/jiaoay/lark/ws/WsModels.kt" to 1,
        "log-tools/src/main/kotlin/com/jiaoay/utils/SafeExtract.kt" to 9,
        "log-tools/src/main/kotlin/com/jiaoay/xlog/LogDecode.kt" to 1,
        "log-tools/src/main/kotlin/com/jiaoay/xlog/stream/XLogChunkReader.kt" to 1,
        "test-fixtures/src/main/kotlin/com/jiaoay/testfixtures/KoogFakeExecutor.kt" to 1,
        "test-fixtures/src/main/kotlin/com/jiaoay/testfixtures/UserHomeIsolation.kt" to 1,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/BottomFollowScroll.kt" to 1,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SidePanelHost.kt" to 1,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/EvidenceTimeline.kt" to 3,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/interactions/SubmitKey.kt" to 1,
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/FileTaskViewModel.kt" to 1,
    )

    // ── 测试 ──

    @Test
    fun `narrative comments stay within baseline ratchet`() {
        val offenders = mutableListOf<String>()
        var totalHits = 0
        for ((file, relPath) in productionSources + testSources) {
            val hits = countNarrativeHits(file.readText())
            totalHits += hits
            val max = narrativeBaseline[relPath]
            if (max == null) {
                if (hits > 0) offenders.add("  $relPath：$hits 行叙事（baseline 无此条目 = 零容忍）")
            } else if (hits > max) {
                offenders.add("  $relPath：$hits 行 > baseline $max（只许降不许升）")
            }
        }
        // 防腐化：baseline 里指向已删除文件的条目必须清理
        val allPaths = (productionSources + testSources).map { it.second }
        val staleEntries = narrativeBaseline.keys.filter { path -> path !in allPaths }
        assertTrue(staleEntries.isEmpty(), "baseline 含已消失文件条目（删除之）：$staleEntries")
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("注释叙事违规（AGENTS.md『注释宪法』——历史归 commit message，知识归 docs/，不进源码）：")
                offenders.forEach { appendLine(it) }
                appendLine("处置：叙事段删除（历史进 commit message）；契约知识迁 docs/ 后留锚；本文件 baseline 只许改小。")
            },
        )
    }

    /**
     * 🔴 注释计数棘轮：每文件「注释区含 🔴 的行数」不得超出 [redMarkBaseline]。
     * 计数只吃注释区（[extractCommentLines] 状态机剥字符串字面量）——字符串里的 🔴 不计。
     * 🔴 的宪法口径 = 真门禁标记（违反即出错/数据损坏/不可逆），普通强调不得使用 🔴。
     */
    @Test
    fun `red mark comment lines stay within baseline ratchet`() {
        // 计数口径自检（must-match 样例）：提取状态机写坏时先红这里，棘轮不空转
        listOf(
            "// 🔴 违例即数据损坏（行注释）" to 1,
            "/* 🔴 不可逆覆盖（块注释） */" to 1,
            "val s = \"plain\" // 🔴 同行字符串不遮蔽行注释命中" to 1,
            "/*\n * 🔴 多行块注释首行\n * 🔴 多行块注释次行\n */" to 2,
        ).forEach { (sample, expected) ->
            assertTrue(
                countRedMarkHits(sample) == expected,
                "🔴 计数样例未命中（计数失真——棘轮空转）：$sample 期望 $expected",
            )
        }
        // 计数口径自检（must-not-match 样例）：字符串字面量里的 🔴 / 引号内伪注释绝不计数
        listOf(
            "val s = \"🔴 字符串字面量不计\"",
            "val raw = \"\"\"🔴 raw string 不计\"\"\"",
            "val s = \"// 🔴 引号内伪注释不计\"",
        ).forEach { sample ->
            assertTrue(
                countRedMarkHits(sample) == 0,
                "🔴 计数误伤字符串字面量样例（口径过宽）：$sample",
            )
        }
        val offenders = mutableListOf<String>()
        val hitsByPath = mutableMapOf<String, Int>()
        for ((file, relPath) in productionSources) {
            val hits = countRedMarkHits(file.readText())
            hitsByPath[relPath] = hits
            val max = redMarkBaseline[relPath]
            if (max == null) {
                if (hits > 0) offenders.add("  $relPath：$hits 行 🔴（baseline 无此条目 = 零容忍）")
            } else if (hits > max) {
                offenders.add("  $relPath：$hits 行 > baseline $max（只许降不许升）")
            }
        }
        // 防腐化：baseline 里指向已消失文件的条目必须清理
        val staleEntries = redMarkBaseline.keys.filter { path -> productionSources.none { it.second == path } }
        assertTrue(staleEntries.isEmpty(), "redMarkBaseline 含已消失文件条目（删除之）：$staleEntries")
        // 防空转：已归零文件的条目必须删除（登记面只收仍有 🔴 的文件）
        val zeroedEntries = redMarkBaseline.keys.filter { hitsByPath[it] == 0 }
        assertTrue(zeroedEntries.isEmpty(), "redMarkBaseline 含已归零文件条目（删除之）：$zeroedEntries")
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("🔴 注释计数超棘轮（AGENTS.md『注释宪法』——🔴 只标真门禁，普通强调不得使用）：")
                offenders.forEach { appendLine(it) }
                appendLine("处置：普通强调去 🔴 标、改写为不依赖标记的自足契约句；真门禁保留时不得新增行数或新文件；")
                appendLine("本文件 redMarkBaseline 只许改小，归零条目删除。")
            },
        )
    }

    /**
     * 双向同步锚（对齐 UiArchitectureGuardTest 白名单总数锚先例）：两个棘轮 baseline 的
     * 条目数与 docs/architecture.md「注释治理」节的键数口径互锁——增删条目须同 commit
     * 同步 docs 数字，改 docs 数字须同 commit 同步 baseline；单边改动即红。
     * 治理文档曾自称基线为空、与源码实况矛盾（漂移过一次），本断言防同类漂移复发。
     */
    @Test
    fun `baseline entry counts match docs`() {
        val docsText = File(projectRoot, "docs/architecture.md").readText()
        assertTrue(
            docsText.contains("`narrativeBaseline` 现为 ${narrativeBaseline.size} 条"),
            "docs/architecture.md「注释治理」节与 narrativeBaseline 键数漂移：" +
                "守护侧现为 ${narrativeBaseline.size} 条——两侧须同 commit 同步（双向锚）",
        )
        assertTrue(
            docsText.contains("`redMarkBaseline` 现为 ${redMarkBaseline.size} 条"),
            "docs/architecture.md「注释治理」节与 redMarkBaseline 键数漂移：" +
                "守护侧现为 ${redMarkBaseline.size} 条——两侧须同 commit 同步（双向锚）",
        )
    }

    @Test
    fun `no dangling kdoc links`() {
        val offenders = mutableListOf<String>()
        for ((file, relPath) in productionSources) {
            val text = file.readText()
            val lines = text.split('\n')
            var inKdoc = false
            var kdocStart = -1
            val kdocRanges = mutableListOf<Pair<Int, Int>>() // 行号区间（0 基，含）
            for ((idx, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (!inKdoc && trimmed.startsWith("/**")) {
                    inKdoc = true
                    kdocStart = idx
                    if (trimmed.contains("*/")) { inKdoc = false; kdocRanges.add(kdocStart to idx) }
                } else if (inKdoc && trimmed.endsWith("*/")) {
                    inKdoc = false
                    kdocRanges.add(kdocStart to idx)
                }
            }
            for ((start, end) in kdocRanges) {
                for (i in start..end) {
                    for (m in kdocLink.findAll(lines[i])) {
                        val symbol = m.groupValues[1]
                        if (symbol !in declaredSymbols && symbol !in DANGLING_LINK_ALLOWED) {
                            offenders.add("  $relPath:${i + 1}：[{$symbol}] 不指向任何已知声明")
                        }
                    }
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("悬空 KDoc 链接（AGENTS.md『注释宪法』——[链接] 必须指向真实存在的符号）：")
                offenders.forEach { appendLine(it) }
                appendLine("处置：改为纯文本（反引号包名字）或删除；若符号应存在，先恢复符号再写链接。")
                appendLine("白名单仅限跨模块 internal/未知构建器符号；新增白名单条目须附理由注释。")
            },
        )
    }

    @Test
    fun `no commented-out declarations`() {
        val offenders = mutableListOf<String>()
        for ((file, relPath) in productionSources) {
            for ((idx, line) in file.readLines().withIndex()) {
                if (commentedOutDecl.matches(line)) {
                    offenders.add("  $relPath:${idx + 1}：${line.trim().take(80)}")
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("注释掉的声明级代码（AGENTS.md『注释宪法』——死代码删除，不注释保留）：")
                offenders.forEach { appendLine(it) }
                appendLine("处置：直接删除（历史在 git）；测试需要稳定字符串时抽代码常量，不藏注释。")
            },
        )
    }

    /**
     * 注释区引用 `*.html` 设计稿 = 零容忍（无 baseline，生产+测试双源集）。注释必须
     * 自足可求值，稿面出处随注释自足登记；字符串字面量与 MIME 写法（text/html）不在拦截面。
     */
    @Test
    fun `no mockup html references in comments`() {
        val offenders = mutableListOf<String>()
        for ((file, relPath) in productionSources + testSources) {
            for ((idx, line) in extractCommentLines(file.readText()).withIndex()) {
                for (m in mockupHtmlRef.findAll(line)) {
                    offenders.add("  $relPath:${idx + 1}：${m.value}（…${line.trim().take(60)}）")
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("注释区引用 *.html 设计稿（AGENTS.md『注释宪法』——注释必须自足可求值，稿面引用清零）：")
                offenders.forEach { appendLine(it) }
                appendLine("处置：改写为自足契约句（值 + 单位 + 依据直接写进注释）。")
            },
        )
    }

    // ── 工具 ──

    /** 注释区含 🔴 的行数（[extractCommentLines] 保证字符串字面量不入注释区——只数注释行） */
    private fun countRedMarkHits(source: String): Int =
        extractCommentLines(source).count { RED_MARK in it }

    private fun countNarrativeHits(source: String): Int {
        val commentLines = extractCommentLines(source)
        var hits = 0
        for (line in commentLines) {
            if (narrativeDate.containsMatchIn(line) ||
                narrativeBatch.containsMatchIn(line) ||
                narrativeProcess.containsMatchIn(line)
            ) {
                hits++
            }
        }
        return hits
    }

    /**
     * 提取注释区文本（行号对齐：非注释字符替换为空格）。
     * 状态机六态：CODE/LINE/BLOCK/STRING/RAW/CHAR——字符串字面量（含 URL、日期值、示例）
     * 绝不进入注释区文本，叙事规则不吃字符串。
     */
    private fun extractCommentLines(source: String): List<String> {
        val text = source
        val out = CharArray(text.length) { ' ' }
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < n && text[i] != '\n') { out[i] = text[i]; i++ }
                }
                c == '/' && next == '*' -> {
                    out[i] = '/'; out[i + 1] = '*'; i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            depth++; out[i] = '/'; out[i + 1] = '*'; i += 2
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            depth--; out[i] = '*'; if (depth > 0) out[i + 1] = '/'; i += 2
                        } else {
                            out[i] = text[i]; i++
                        }
                    }
                }
                c == '"' && next == '"' && i + 2 < n && text[i + 2] == '"' -> {
                    // raw string：跳过内容（不入注释区）
                    i += 3
                    while (i < n) {
                        if (text[i] == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') { i += 3; break }
                        if (text[i] == '\n') out[i] = '\n'
                        i++
                    }
                }
                c == '"' -> {
                    // 普通字符串：跳过（不入注释区）
                    i++
                    while (i < n) {
                        if (text[i] == '\\') { i += 2; continue }
                        if (text[i] == '"') { i++; break }
                        if (text[i] == '\n') { out[i] = '\n'; i++; break } // 不跨行
                        i++
                    }
                }
                c == '\'' -> {
                    // 字符字面量：跳过
                    i++
                    while (i < n) {
                        if (text[i] == '\\') { i += 2; continue }
                        if (text[i] == '\'') { i++; break }
                        if (text[i] == '\n') { i++; break }
                        i++
                    }
                }
                c == '\n' -> { out[i] = '\n'; i++ }
                else -> i++
            }
        }
        return String(out).split('\n')
    }

    /** 剥注释保留代码（声明索引用——与 extractCommentLines 相反方向） */
    private fun stripCommentsKeepStrings(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else ' '
            when {
                c == '/' && next == '/' -> { while (i < n && source[i] != '\n') i++ }
                c == '/' && next == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (source[i] == '/' && i + 1 < n && source[i + 1] == '*') { depth++; i += 2 }
                        else if (source[i] == '*' && i + 1 < n && source[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                }
                c == '"' && next == '"' && i + 2 < n && source[i + 2] == '"' -> {
                    sb.append("\"\"\""); i += 3
                    while (i < n) {
                        if (source[i] == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                            sb.append("\"\"\""); i += 3; break
                        }
                        sb.append(source[i]); i++
                    }
                }
                c == '"' -> {
                    sb.append(c); i++
                    while (i < n) {
                        if (source[i] == '\\') { sb.append(source[i]).append(source[i + 1]); i += 2; continue }
                        sb.append(source[i])
                        val wasQuote = source[i] == '"'
                        i++
                        if (wasQuote) break
                    }
                }
                c == '\'' -> {
                    sb.append(c); i++
                    while (i < n) {
                        if (source[i] == '\\') { sb.append(source[i]).append(source[i + 1]); i += 2; continue }
                        sb.append(source[i])
                        val wasQuote = source[i] == '\''
                        i++
                        if (wasQuote) break
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MIN_SOURCE_FILES = 450

        /** 🔴 标记本字（U+1F534，真门禁标记）；注释区行计数见 redMarkBaseline 棘轮 */
        const val RED_MARK = "🔴"

        /** 与 ModuleGraphGuardTest 等排除口径一致的目录（扫描模块根时跳过）；
         *  build-logic 是 includeBuild 独立构建（Kotlin DSL 插件源码），注释同样受宪法管辖、
         *  不在排除之列——有 src/main/kotlin 即经 productionSources 的模块目录枚举自然收录 */
        val EXCLUDED_MODULE_DIRS = setOf("build", ".git", ".idea", "gradle")

        /** Kotlin/JDK 常用类型（KDoc 引用标准库类型合法；声明索引不含它们） */
        val JDK_COMMON_TYPES = setOf(
            "IllegalStateException", "IllegalArgumentException", "UnsupportedOperationException",
            "ProcessBuilder", "AutoCloseable", "Float", "Long", "Int", "String", "Boolean",
            "Duration", "Pair", "List", "Map", "Set", "Sequence",
            "FiniteAnimationSpec", "DurationBasedAnimationSpec",
        )

        /** 悬空链接显式白名单（新增须附理由）：跨模块 internal/构建器生成符号等 KDoc 无法解析的合法引用 */
        val DANGLING_LINK_ALLOWED: Set<String> = setOf(
            // （暂空——初建时已清掉当时的全部悬空链接，历史见 git log）
        )
    }
}
