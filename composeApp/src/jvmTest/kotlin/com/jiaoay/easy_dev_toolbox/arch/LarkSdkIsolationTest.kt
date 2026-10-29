package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 飞书 SDK 隔离守护测试。
 *
 * 目标：把「SDK 只允许出现在 :lark-gateway」这条架构约束固化为红构建门禁——施工者
 * 一旦违规即失败，不靠人工纪律。断言面与 `openspec/changes/archive/2026-09-10-rewrite-lark-bridge/`
 * 的 config-domain-spec §14 grep 断言清单对齐；硬约束「shadowJar 禁止 relocate Gson/protobuf」
 * 「lib 是 jvmTarget 11 不能依赖 :core」在此钉死。
 *
 * - A 组：SDK 依赖声明隔离（build.gradle.kts 集合精确收口 + implementation 非 api + 版本目录坐标）。
 * - B 组：SDK 类型不泄漏（全仓 .kt，lark-gateway/ 之外不得出现 SDK/传递依赖黑名单前缀）。
 * - C 组：shadowJar 禁止 relocate（SDK 内部反射依赖 Gson/protobuf 原始包名，relocate 会在运行时炸）。
 * - D 组：jvmTarget 方向守护——lib 模块不得依赖宿主 jvm21 模块，源码不得引用宿主包。
 * - E 组：lib 侧 ProcessBuilder 收口到封闭清单实现点（exec/ProgramLauncher.kt）。
 * - F 组：静态全局可变状态守护——禁止 object/companion 级或顶层 @Volatile var。
 *
 * ## 字符串与注释归一化（B/D/E/F 的匹配前提，务必理解这个设计取舍）
 *
 * 本测试守护的是「**类型依赖**」而非「**文字提及**」。因此在做黑名单/关键字匹配之前，
 * 先用 [normalizeKotlinSource] 对源码做轻量归一化：去掉行注释（`//` 之后）、块注释内容
 * （`/* ... */`，含跨行 KDoc）、以及字符串字面量内容（普通 `"..."` 含转义 `\"`、原始 `"""..."""`），
 * 保留换行以维持行号对齐，**再**做匹配。这样：
 * - `core/.../LogbackHelper.kt:46` 的 `context.getLogger("com.lark.oapi")`（字符串字面量）⇒ 必须通过；
 * - 同文件 `:44` 的注释提及 `com.lark.oapi`、以及任何文档性注释 ⇒ 必须通过；
 * - 真实的 `import com.lark.oapi.channel.LarkChannel` 或 `com.google.gson.Gson()` 类型使用 ⇒ 必须失败。
 *
 * 归一化本身的正确性由 [B normalization strips comments and string literals but keeps real type usage]
 * 用内联样本单独锁死（合法字符串字面量 / 合法注释 / 违规 import / 违规 FQCN 四种），确保不误伤也不漏判。
 *
 * ## 模块缺失容忍（D/E/F）
 *
 * 三个 lib 模块全部存在；缺失只可能是目录重构/改名/误删——那是 D/E/F 必须**红出来**
 * 而不是静默漏扫的场景。D/E/F 对缺失目录一律 assertTrue 失败；
 * 「LIB_MODULES 与 settings.gradle.kts 模块清单一致性」由 `ScanSurfaceSelfCheckTest` 独立守护
 * （平行元测试，不改本文件）。
 *
 * ## E/F 的源集范围
 *
 * E/F 只扫生产源集 `src/main/kotlin`。这是刻意的：spec §14.2/§14.4 的 grep 范围精确写的就是
 * `src/main/kotlin/`——生产纪律只约束交付物；测试源集里的一次性观测/脚手架代码（非交付物）不纳入。
 *
 * ## 复用约定
 *
 * 与 [UiArchitectureGuardTest] 同目录同包，复用其风格（`kotlin("test")`、剥注释后逐行断言、
 * 失败信息报文件+行号、自 user.dir 逐级向上探测项目根）。但 [UiArchitectureGuardTest.locateJvmMainRoots]
 * 只扫 `src/jvmMain/kotlin`，覆盖不到纯 JVM 模块的 `src/main/kotlin`，故本文件自带更广的扫描实现，
 * 不修改、不依赖原文件的 private helper。
 */
class LarkSdkIsolationTest {

    /** 一条 SDK 泄漏违规：相对项目根路径 + 行号 + 命中前缀 + 归一化后的代码行 */
    private data class SdkLeak(val relativePath: String, val line: Int, val prefixes: List<String>, val codeLine: String)

    // ------------------------------------------------------------------ A 组：依赖声明隔离

    /**
     * A-1：全仓所有 `build.gradle.kts` 中声明 SDK 的文件集合必须精确等于 `{lark-gateway/build.gradle.kts}`。
     *
     * 版本目录别名 `oapi-sdk` 在 build 脚本里的访问器是 `libs.oapi.sdk`（点号），直接坐标写法是
     * `com.larksuite.oapi:oapi-sdk`（连字符），故用 [sdkGradleTokenRegex] 同时覆盖 `oapi.sdk` / `oapi-sdk`。
     * 空扫描会使集合为空而 != 期望集合，天然堵住「扫描器空转导致永绿」的后门。
     */
    @Test
    fun `A oapi sdk dependency is declared only in lark gateway build script`() {
        val root = locateProjectRoot()
        val declaring = root.walkBuildScripts()
            .filter { sdkGradleTokenRegex.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals(
            setOf(GATEWAY_BUILD_SCRIPT),
            declaring,
            "SDK 依赖声明必须精确收口在 $GATEWAY_BUILD_SCRIPT（全仓唯一 SDK 依赖点）；实际命中=$declaring",
        )
    }

    /**
     * A-2：`:lark-gateway` 必须以 `implementation`（非 `api`）声明 SDK。
     *
     * 这是「在 :lark-bridge/:feature-larkbridge 里写 `import com.lark.oapi.*` 直接编译失败」这道
     * 最强隔离（编译期）的前提——一旦改成 `api`，SDK 类型会随依赖传递泄漏到下游，隔离失效。
     */
    @Test
    fun `A lark gateway declares sdk via implementation not api`() {
        val root = locateProjectRoot()
        val script = File(root, GATEWAY_BUILD_SCRIPT)
        assertTrue(script.isFile, "$GATEWAY_BUILD_SCRIPT 缺失（M1 应已建立 :lark-gateway）")
        val text = script.readText()
        assertTrue(
            text.contains("implementation(libs.oapi.sdk)"),
            "$GATEWAY_BUILD_SCRIPT 必须以 implementation(libs.oapi.sdk) 声明 SDK",
        )
        assertFalse(
            apiSdkRegex.containsMatchIn(text),
            "$GATEWAY_BUILD_SCRIPT 不得以 api(libs.oapi.sdk) 声明 SDK——api 会让下游模块 " +
                "`import com.lark.oapi.*` 编译通过，编译期隔离（最强一道）失效",
        )
    }

    /**
     * A-3：版本目录 `oapi-sdk` 坐标钉死为 `com.larksuite.oapi:oapi-sdk` 版本 `2.8.6`，且全仓不得出现
     * `kotlinx-coroutines-jdk8`（方案已解包验证 `FutureKt` 在 `kotlinx-coroutines-core-jvm` 内，
     * 本断言防止后人手滑把 jdk8 变体加回来）。升级 SDK 时同 commit 改此锚并逐条复核
     * docs/pages/lark-bridge.md「SDK 已知缺陷」节。
     */
    @Test
    fun `A version catalog pins oapi sdk coordinates and forbids coroutines jdk8`() {
        val root = locateProjectRoot()
        val catalog = File(root, "gradle/libs.versions.toml")
        assertTrue(catalog.isFile, "gradle/libs.versions.toml 缺失")
        val text = catalog.readText()
        assertTrue(
            text.contains("module = \"com.larksuite.oapi:oapi-sdk\""),
            "版本目录 oapi-sdk 坐标必须为 com.larksuite.oapi:oapi-sdk",
        )
        assertTrue(
            text.contains("oapi-sdk = \"2.8.6\""),
            "版本目录 oapi-sdk 版本必须钉在 2.8.6",
        )
        assertFalse(
            text.contains("kotlinx-coroutines-jdk8"),
            "禁止新增 kotlinx-coroutines-jdk8——FutureKt 已在 kotlinx-coroutines-core-jvm 内（方案已解包验证）",
        )
    }

    // ------------------------------------------------------------------ B 组：类型不泄漏

    /**
     * B-1：全仓所有 `.kt`（覆盖 src/main/kotlin、src/jvmMain/kotlin、src/commonMain/kotlin、
     * src/test/kotlin、src/jvmTest/kotlin 等全部源集），路径不在 `lark-gateway/` 下的文件，
     * 归一化后不得出现 [sdkBlacklistPrefixes] 中任一前缀。
     *
     * 黑名单含 M1 实测追加的两项：`org.apache.commons.logging`（commons-logging 1.2 随 httpclient
     * 传递进来）与 `ApacheHttpClientTransport`（SDK 里全树无实例化点的死代码路径，禁止任何人启用）。
     */
    @Test
    fun `B sdk types never leak outside lark gateway`() {
        val root = locateProjectRoot()
        val allKt = root.walkKotlinFiles()
        // 空/缩水扫描 = 永绿后门：目录定位或剪枝异常时必须显式失败而非静默通过
        check(allKt.size >= MIN_KT_FILES) {
            "全仓 .kt 扫描文件数异常（${allKt.size} < $MIN_KT_FILES 下限）：目录定位/剪枝可能出错，守护会静默失效"
        }
        val gatewayKt = allKt.filter { it.relativeTo(root).invariantSeparatorsPath.startsWith("$GATEWAY_MODULE/") }
        // 自检：lark-gateway 必须真的被扫到并被排除（否则「排除」写成 typo 会退化为不扫 gateway，无人察觉）
        assertTrue(
            gatewayKt.isNotEmpty(),
            "扫描未在 $GATEWAY_MODULE/ 下发现任何 .kt（package-info.kt 应存在）——排除逻辑或目录定位可能失效",
        )
        val outside = allKt - gatewayKt.toSet()
        val leaks = outside.flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            normalizeKotlinSource(file.readText()).split('\n').mapIndexedNotNull { idx, line ->
                val hits = sdkBlacklistPrefixes.filter { line.contains(it) }
                if (hits.isEmpty()) null else SdkLeak(rel, idx + 1, hits, line.trim())
            }
        }
        if (leaks.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("SDK 类型泄漏：$GATEWAY_MODULE/ 之外出现 SDK/传递依赖黑名单前缀 ${leaks.size} 处：")
                    leaks.forEach {
                        appendLine("  - ${it.relativePath}:${it.line}: ${it.codeLine}")
                        appendLine("      命中前缀：${it.prefixes.joinToString(", ")}")
                    }
                    appendLine("SDK 只允许出现在 :lark-gateway 模块内（B-1 用目录前缀白名单，模块内 import SDK 的文件数不限：")
                    appendLine("  SdkLarkGateway/LarkCardStreamSink/RegisterAppFlow 均合法）；其余模块需能力请经纯 Kotlin 网关契约（MessageGateway 等）间接使用。")
                },
            )
        }
    }

    /**
     * B-2：归一化 + 匹配函数的单元测试（本组的设计核心，必须单独锁死）。
     *
     * 用内联多行样本覆盖四种形态：合法字符串字面量、合法注释（行/块/行尾）、违规 import、
     * 违规 FQCN 类型使用。验证 [normalizeKotlinSource] + [sdkBlacklistPrefixes] 匹配既不误伤也不漏判。
     */
    @Test
    fun `B normalization strips comments and string literals but keeps real type usage`() {
        // 合法①：字符串字面量内的 SDK 名（复刻 LogbackHelper.kt:46 的真实形态）
        val legalStringLiteral = """
            fun init() {
                context.getLogger("com.lark.oapi").level = Level.WARN
                val s = "com.google.gson 与 com.larksuite 出现在字符串里"
                val esc = "转义\"com.lark.oapi 引号后仍应被剥离"
            }
        """.trimIndent()
        assertTrue(
            findSdkPrefixes(legalStringLiteral).isEmpty(),
            "字符串字面量内的 SDK 名不应判为违规（守护的是类型依赖而非文字提及），实际命中=${findSdkPrefixes(legalStringLiteral)}",
        )

        // 合法②：注释内的 SDK 名（行注释 / 行尾注释 / 块注释 / KDoc）
        val legalComments = """
            // 压制 com.lark.oapi 噪音
            val x = 1 // 行尾提及 com.larksuite
            /* com.google.gson 已随 SDK 吸收 */
            /**
             * org.apache.http 与 com.google.common 的文档性提及。
             */
            val y = 2
        """.trimIndent()
        assertTrue(
            findSdkPrefixes(legalComments).isEmpty(),
            "注释内的 SDK 名不应判为违规，实际命中=${findSdkPrefixes(legalComments)}",
        )

        // 违规③：真实 import
        val illegalImport = """
            import com.lark.oapi.channel.LarkChannel
            import com.google.gson.Gson
        """.trimIndent()
        assertEquals(
            listOf("com.google.gson", "com.lark.oapi"),
            findSdkPrefixes(illegalImport).sorted().distinct(),
            "真实 import 必须判为违规",
        )

        // 违规④：真实 FQCN 类型使用（含死代码路径 ApacheHttpClientTransport）
        val illegalFqcn = """
            val g = com.google.gson.Gson()
            private var c: org.apache.http.client.HttpClient? = null
            val t = ApacheHttpClientTransport()
        """.trimIndent()
        assertTrue(
            findSdkPrefixes(illegalFqcn).isNotEmpty(),
            "真实 FQCN 类型使用必须判为违规",
        )
        assertTrue(
            findSdkPrefixes(illegalFqcn).containsAll(listOf("com.google.gson", "org.apache.http", "ApacheHttpClientTransport")),
            "FQCN 样本应命中 gson/http/ApacheHttpClientTransport 三类前缀，实际=${findSdkPrefixes(illegalFqcn)}",
        )
    }

    // ------------------------------------------------------------------ C 组：禁止 relocate

    /**
     * C：全仓所有 `build.gradle.kts` 与 `build-logic` 下的 `.kt` 不得出现任何 `relocate(` 调用（当前应为零）。
     *
     * 理由：SDK 内部反射依赖 Gson/protobuf 的**原始包名**，relocate 会在运行时炸。
     * 若未来确有需要，必须先改本测试并在方案 / AGENTS.md 登记理由。
     * 对 build 脚本用原文匹配（不归一化）——build 脚本里出现 `relocate(` 无论注释与否都应触发人工复核。
     */
    @Test
    fun `C shadowJar never relocates any package`() {
        val root = locateProjectRoot()
        val scripts = root.walkBuildScripts()
        val buildLogicKt = File(root, "build-logic").walkKotlinFiles()
        val offenders = (scripts + buildLogicKt)
            .filter { it.readText().contains("relocate(") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
        assertTrue(
            offenders.isEmpty(),
            "禁止任何 relocate( 调用（命中：$offenders）——SDK 内部反射依赖 Gson/protobuf 原始包名，" +
                "relocate 会在运行时炸；确有需要必须先改本测试并在方案/AGENTS.md 登记理由",
        )
    }

    // ------------------------------------------------------------------ D 组：jvmTarget 方向守护

    /**
     * D：lib 模块（jvmTarget 11）不得反向依赖宿主 jvm21 模块。
     *
     * - build.gradle.kts 不得出现 `project(":core")` / `project(":ui")` / `project(":composeApp")`
     *   / `project(":feature-larkbridge")`；
     * - 源码不得引用宿主包 `com.jiaoay.easy_dev_toolbox`（用 B 的同一套归一化）——lib 侧自建
     *   BridgeLog/BridgeErrorCode/PathExpansion，数据根路径由宿主**注入**而非引用 FileUtils。
     */
    @Test
    fun `D lib modules never depend on host jvm21 modules`() {
        val root = locateProjectRoot()
        LIB_MODULES.forEach { moduleName ->
            val moduleDir = File(root, moduleName)
            // 🔴 缺失即红不是静默空过：三个 lib 模块全部存在，目录缺失只可能是重构/改名/误删——
            //    那正是 D 组必须红出来而不是静默漏扫的场景。守护锚：ScanSurfaceSelfCheckTest.B。
            assertTrue(moduleDir.isDirectory, "lib 模块 :$moduleName 目录缺失——D 组扫描面漂移，请核对 LIB_MODULES 与实际模块")
            if (!moduleDir.isDirectory) return@forEach
            // D-1：build.gradle.kts 反向依赖
            val script = File(moduleDir, "build.gradle.kts")
            if (script.isFile) {
                val text = script.readText()
                forbiddenHostProjectDeps.forEach { dep ->
                    assertFalse(
                        text.contains(dep),
                        ":$moduleName 是 jvmTarget 11 lib，不得依赖宿主 jvm21 模块 $dep（反向依赖非法，编译失败）",
                    )
                }
            }
            // D-2：源码宿主包引用（扫全部 src/，归一化后匹配）
            val srcDir = File(moduleDir, "src")
            if (srcDir.isDirectory) {
                srcDir.walkKotlinFiles().forEach { file ->
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    normalizeKotlinSource(file.readText()).split('\n').forEachIndexed { idx, line ->
                        assertFalse(
                            line.contains(HOST_PACKAGE_REF),
                            ":$moduleName 源码不得引用宿主包 $HOST_PACKAGE_REF（$rel:${idx + 1}）——" +
                                "lib 侧自建 BridgeLog/BridgeErrorCode，数据根路径由宿主注入",
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ E 组：ProcessBuilder 收口

    /**
     * E：lib 侧 ProcessBuilder 只允许出现在封闭清单 `processBuilderAllowedSuffixes`：
     * `exec/ProgramLauncher.kt`（短命 argv 程序调用语义）。
     *
     * 理由（写进守护，防止后人图省事复用 core Shell）：方案明确**不使用** `core/extensions/` 的
     * `SimpleShell`/`StreamingShell`/`InteractiveShell` 跑 agent——它们是 **shell 命令行语义**，
     * 必经 `ShellCommandBuilder.wrap()` 走 `cmd.exe /c` 或 `/bin/zsh -l`，且 `processCharset` 在 Windows
     * 是 ANSI，与 claude `stream-json` 的 UTF-8 冲突；agent 调用是 **argv 程序语义**，须自建 ProgramLauncher。
     *
     * 🔴 扩容仍为封闭清单（不退化为目录级放行）：`launch()` 的「启动即关 stdin、总超时包全程、
     *    返回即进程终」短命语义由 ProgramLauncher 独占，新增 ProcessBuilder 收口点必须先改本锚
     *    （`processBuilderAllowedSuffixes`）再落生产代码。
     *
     * 采用「收口/子集」语义（所有含 ProcessBuilder 的文件路径必须以清单内后缀结尾），
     * 而非硬性「集合精确等于」——后者在模块存在但收口文件尚未创建的中间态会误报，
     * 也与 :lark-gateway（本就没有 ProcessBuilder，空集合合法）不兼容。对齐 spec §14.2，扫 `src/main/kotlin`。
     * `:lark-bridge` 缺失时打印跳过说明。
     */
    @Test
    fun `E lib ProcessBuilder is confined to the closed launcher list`() {
        val root = locateProjectRoot()
        LIB_MODULES.forEach { moduleName ->
            val prodSrc = File(root, "$moduleName/src/main/kotlin")
            // 🔴 缺失即红，不静默空过（守护锚：ScanSurfaceSelfCheckTest.B）。
            assertTrue(prodSrc.isDirectory, "lib 模块 :$moduleName 生产源集缺失——E 组扫描面漂移，请核对 LIB_MODULES")
            if (!prodSrc.isDirectory) return@forEach
            val offenders = prodSrc.walkKotlinFiles()
                .filter { file -> normalizeKotlinSource(file.readText()).contains("ProcessBuilder") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .filter { rel -> processBuilderAllowedSuffixes.none(rel::endsWith) }
            assertTrue(
                offenders.isEmpty(),
                ":$moduleName 的 ProcessBuilder 只允许出现在封闭清单 $processBuilderAllowedSuffixes" +
                    "（exec/ProgramLauncher.kt = 短命 argv 程序调用语义）；违规文件=$offenders。理由：core/extensions 的 Shell 是 shell 命令行语义" +
                    "（必经 ShellCommandBuilder.wrap() 走 cmd.exe /c 或 /bin/zsh -l，Windows processCharset 为 ANSI），" +
                    "与 claude stream-json 的 UTF-8 冲突",
            )
        }
    }

    // ------------------------------------------------------------------ F 组：静态全局可变状态

    /**
     * F：静态全局可变状态守护——lib 生产源集禁止 **object/companion object 体内** 或 **顶层** 的 `@Volatile var`。
     *
     * 静态全局可变状态曾导致 stale 实例 bug——lib 侧一律构造注入。
     *
     * **作用域感知守护**：行级 co-occurrence 启发式（「同文件出现 object/companion 且出现 @Volatile var
     * ⇒ fail」）会误伤两类合法写法——① 类级 `@Volatile var`（如 LarkCardStreamSink 的
     * segIndex/open、SdkLarkGateway 的 channel/botOpenId），② 文件里存在 `data object` / 匿名 `object :`
     * 声明。故用花括号配对计数（[scanBraces]）+ object 声明区间（[findObjectRegions]）判定 `@Volatile var`
     * 的**词法作用域**：仅当它落在 `object X { … }` / `companion object { … }` 体内，或位于顶层（花括号深度 0）
     * 时才判违规。类级 @Volatile var、data object、匿名 object : 一律不违规；顶层 @Volatile var 仍违规。
     * 归一化（剥注释/字符串）+ 跳过字符字面量（`'{'`）后再计数，避免 LarkOutboundMapper 的 `sb.append('{')`
     * 干扰深度。对齐 spec §14.4（扫 `src/main/kotlin`）；测试源集脚手架（非交付物）不纳入生产纪律。
     * `:lark-bridge` 缺失时打印跳过说明。
     */
    @Test
    fun `F no static volatile mutable state in lib modules`() {
        val root = locateProjectRoot()
        LIB_MODULES.forEach { moduleName ->
            val prodSrc = File(root, "$moduleName/src/main/kotlin")
            // 🔴 缺失即红，不静默空过（守护锚：ScanSurfaceSelfCheckTest.B）。
            assertTrue(prodSrc.isDirectory, "lib 模块 :$moduleName 生产源集缺失——F 组扫描面漂移，请核对 LIB_MODULES")
            if (!prodSrc.isDirectory) return@forEach
            val offenders = prodSrc.walkKotlinFiles().flatMap { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val normalized = normalizeKotlinSource(file.readText())
                findStaticVolatileVarOffenders(normalized).map { pos -> "$rel:${lineOf(normalized, pos)}" }
            }
            assertTrue(
                offenders.isEmpty(),
                "禁止 lib 生产源集的 object/companion object 体内或顶层 @Volatile var 静态全局可变状态" +
                    "（应改构造注入）；命中=${offenders.joinToString(", ")}",
            )
        }
    }

    // ------------------------------------------------------------------ 扫描 / 归一化实现

    /** 归一化后逐行扫描，返回命中的黑名单前缀（去重）；供 B-1 与 B-2 单元测试共用 */
    private fun findSdkPrefixes(rawSource: String): List<String> =
        normalizeKotlinSource(rawSource).split('\n')
            .flatMap { line -> sdkBlacklistPrefixes.filter { line.contains(it) } }
            .distinct()

    /**
     * F 组核心：返回 normalized 源码里所有「静态 @Volatile var」的 `var` 关键字位置（char offset）。
     * 判定：@Volatile 紧跟的 var，若落在 object/companion object 体内（[findObjectRegions]），或位于顶层
     * （花括号深度 0，[scanBraces]）⇒ 违规。类体内的实例级 @Volatile var（深度 > 0 且不在 object 区间）不违规。
     */
    private fun findStaticVolatileVarOffenders(normalized: String): List<Int> {
        val depthAt = scanBraces(normalized)
        val objectRegions = findObjectRegions(normalized)
        val offenders = mutableListOf<Int>()
        for (m in volatileRegex.findAll(normalized)) {
            val varPos = volatileVarPosition(normalized, m.range.last + 1) ?: continue
            val inObject = objectRegions.any { varPos in it }
            if (inObject || depthAt.getOrElse(varPos) { 0 } == 0) offenders.add(varPos)
        }
        return offenders
    }

    /**
     * 从 `@Volatile` 之后（index [from]）扫描其标注的声明：跳过空白与合法修饰符，命中 `var` 返回其位置，否则 null。
     * `@Volatile` 只能标注 var 属性；保留 var 校验以与旧启发式语义一致（容忍 `@Volatile` 出现在其它语境）。
     */
    private fun volatileVarPosition(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) { i++; continue }
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val word = s.substring(start, i)
                if (word == "var") return start
                if (word in VOLATILE_MODIFIERS) continue
                return null   // 遇到 fun/val/其它标识符 ⇒ 不是 @Volatile var
            }
            return null       // 遇到 { } ( ) = ; 等 ⇒ 终止
        }
        return null
    }

    /**
     * 花括号深度数组：depthAt[i] = 位置 i 处未闭合的 `{` 数。跳过字符字面量（`'{'`/`'}'`/`'\''`/`'\u007B'`）；
     * 字符串内容已被 [normalizeKotlinSource] 清空，故无需再处理字符串内的花括号。
     */
    private fun scanBraces(s: String): IntArray {
        val depthAt = IntArray(s.length)
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\'') {                 // 字符字面量：整体跳过
                depthAt[i] = depth
                i++
                while (i < s.length) {
                    if (s[i] == '\\') { if (i + 1 < s.length) depthAt[i + 1] = depth; i += 2; continue }
                    depthAt[i] = depth
                    if (s[i] == '\'') { i++; break }
                    i++
                }
                continue
            }
            depthAt[i] = depth
            if (c == '{') depth++
            else if (c == '}') { if (depth > 0) depth-- }
            i++
        }
        return depthAt
    }

    /**
     * 所有「静态状态 object」的体内花括号区间 `[open, close]`：命名 `object X { … }` 与 `companion object { … }`。
     * 🔴 `data object` 与匿名 `object :` **不计入**（二者不承载静态可变状态语义）。
     */
    private fun findObjectRegions(s: String): List<IntRange> {
        val regions = mutableListOf<IntRange>()
        for (m in objectKeywordRegex.findAll(s)) {
            val lastWord = s.substring(0, m.range.first).trimEnd().takeLastWhile { it.isLetterOrDigit() || it == '_' }
            val isCompanion = lastWord == "companion"
            val isData = lastWord == "data"
            var q = m.range.last + 1
            while (q < s.length && s[q].isWhitespace()) q++
            val isAnonymous = q < s.length && s[q] == ':'
            val isNamed = q < s.length && (s[q].isLetter() || s[q] == '_')
            val isStaticObject = when {
                isData || isAnonymous -> false
                isCompanion || isNamed -> true
                else -> false
            }
            if (!isStaticObject) continue
            val open = s.indexOf('{', q)
            if (open < 0) continue
            val close = matchBrace(s, open)
            if (close > open) regions.add(open..close)
        }
        return regions
    }

    /** 返回与 [open] 处 `{` 配对的 `}` 位置（跳过字符字面量），无配对返回 -1。 */
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

    /** char offset → 1-based 行号（供 F 组失败信息定位）。 */
    private fun lineOf(s: String, pos: Int): Int {
        var line = 1
        for (i in 0 until minOf(pos, s.length)) if (s[i] == '\n') line++
        return line
    }

    /**
     * 归一化：剥离行注释（`//`）、块注释内容（`/* ... */` 含跨行 KDoc）、字符串字面量内容
     * （普通 `"..."` 含 `\"` 转义、原始 `"""..."""`），保留全部换行以维持行号对齐。
     *
     * 与 [UiArchitectureGuardTest] 的 `stripKotlinComments` 关键差异：后者**保留**字符串内容供规则扫描
     * （宁可多报），本函数**清空**字符串内容——因为 SDK 守护要放行 `getLogger("com.lark.oapi")` 这类
     * 合法字符串字面量，只对真实类型依赖（import / FQCN）报警。故本文件自带一份实现，不复用原 private helper。
     */
    private fun normalizeKotlinSource(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                // 行注释 //：跳到行尾（换行由后续迭代原样保留）
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                // 块注释 /* ... */：丢弃内容，保留内部换行。Kotlin 块注释可嵌套，故按深度配对
                // （否则本文件 KDoc 里出现的块注释符号示例会让浅层扫描提前闭合，误把注释余文当代码）
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
                        if (source[i] == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"') {
                            sb.append("\"\"\""); i += 3; break
                        }
                        if (source[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                // 普通字符串 "..."：保留首尾引号，丢弃内容；处理 \" 转义；遇换行保守回退代码态（行号仍对齐）
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

    /** 自 user.dir 逐级向上探测项目根（含 settings.gradle.kts）——复用 UiArchitectureGuardTest 的定位约定 */

    /** 自该目录向下遍历全部 `.kt`，按名剪枝 [EXCLUDED_DIR_NAMES]（覆盖全部源集，含纯 JVM 模块 src/main/kotlin） */
    private fun File.walkKotlinFiles(): List<File> =
        walkTopDown()
            .onEnter { it.name !in EXCLUDED_DIR_NAMES }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    /** 自该目录向下遍历全部 `build.gradle.kts`，按名剪枝 [EXCLUDED_DIR_NAMES] */
    private fun File.walkBuildScripts(): List<File> =
        walkTopDown()
            .onEnter { it.name !in EXCLUDED_DIR_NAMES }
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .sortedBy { it.path }
            .toList()

    private companion object {
        const val GATEWAY_MODULE = "lark-gateway"
        const val GATEWAY_BUILD_SCRIPT = "lark-gateway/build.gradle.kts"

        /** D/E/F 守护的 lib 模块（jvmTarget 11）；:lark-ws 零 SDK 依赖，纳入 F 组「禁 object 静态可变态」与 D 组方向守护。 */
        val LIB_MODULES = listOf("lark-gateway", "lark-bridge", "lark-ws", "test-fixtures", "adb-core", "device-core")

        const val HOST_PACKAGE_REF = "com.jiaoay.easy_dev_toolbox"

        /** 全仓 .kt 下限（当前约 175，留足余量）：跌破说明扫描根丢失/剪枝出错，守护会静默失效 */
        const val MIN_KT_FILES = 100

        /** 剪枝目录名（构建产物 / IDE / 临时目录，任何深度命中即不进入） */
        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".clikt_tmp", ".review_tmp", ".idea", "node_modules", ".git",
        )

        /**
         * SDK 及传递依赖黑名单前缀（B 组）。最后两项为实测追加：
         * `org.apache.commons.logging`（commons-logging 1.2 随 httpclient 传递进来）、
         * `ApacheHttpClientTransport`（SDK 全树无实例化点的死代码路径，禁止启用）。
         */
        val sdkBlacklistPrefixes = listOf(
            "com.lark.oapi",
            "com.larksuite",
            "com.google.gson",
            "org.apache.http",
            "com.google.common",
            "org.apache.commons.logging",
            "ApacheHttpClientTransport",
        )

        /** build 脚本里的 SDK 声明 token：兼容访问器 `oapi.sdk`（点）与直接坐标 `oapi-sdk`（连字符） */
        val sdkGradleTokenRegex = Regex("""oapi[.\-]sdk""")

        /** `api(libs.oapi.sdk)` 配置声明；`\bapi` 避开 `implementation(...)` 与 `oapi.sdk` 里的 "api" 子串 */
        val apiSdkRegex = Regex("""\bapi\s*\(\s*libs\.oapi\.sdk""")

        /** lib(11) 禁止反向依赖的宿主 jvm21 模块 */
        val forbiddenHostProjectDeps = listOf(
            "project(\":core\")",
            "project(\":ui\")",
            "project(\":composeApp\")",
            "project(\":feature-larkbridge\")",
        )

        /** `object` 关键字（归一化后仅代码区）；命名/companion/data/匿名 由 [findObjectRegions] 上下文区分 */
        val objectKeywordRegex = Regex("""\bobject\b""")

        /**
         * E 组 ProcessBuilder 收口封闭清单（相对路径后缀匹配）：短命 argv 程序调用语义（ProgramLauncher）。
         * 扩容必须先改本锚并登记理由，新增收口点不得外溢到清单外文件。
         */
        val processBuilderAllowedSuffixes = listOf("exec/ProgramLauncher.kt")

        /** `@Volatile` 注解 */
        val volatileRegex = Regex("""@Volatile\b""")

        /** `@Volatile` 与其 `var` 之间允许的修饰符（[volatileVarPosition] 跳过它们继续找 var） */
        val VOLATILE_MODIFIERS = setOf("private", "internal", "public", "protected", "lateinit")
    }
}
