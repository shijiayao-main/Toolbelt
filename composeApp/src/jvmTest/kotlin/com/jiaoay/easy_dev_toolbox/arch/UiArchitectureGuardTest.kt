package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UI 架构守护测试（visual-polish Wave 7 / design.md D6）：jvmMain 源码扫描防回归。
 *
 * 对剥除注释后的源码逐行断言，违反即 fail 并报文件 + 行号：
 * 1. [androidx.compose.material3.MaterialTheme.shapes]——圆角一律走 `AppShapes`
 *    （`AppShapes.kt` 为唯一样式组装点）；
 * 2. `animateScrollToItem`——流式输出/日志行零动画铁律，滚动一律即时 `scrollToItem` + 底部跟随；
 * 3. 裸写 `tween(` / `spring(`（含显式泛型写法 `tween<Float>(`）——动画规格唯一定义点 `core/theme/Motion.kt`；
 * 4. `ElevatedCard`——卡片容器一律 `AppCardStyles.tonal` 扁平卡；
 * 5. `alpha = 0.x` 数字字面量——透明度一律 `AppAlpha` token；
 * 6. 裸 `xx.dp` 数字字面量（RAW_DP）——间距/图标尺寸/圆角一律 `Dimens` / `AppShapes` token，
 *    新增档位先在 token 文件扩充定义再使用；`0.dp` 零值全局有据保留（docs 清单），
 *    由正则直接排除，其余例外按值登记（见 [rawDpAllowedValuesByPath]）；
 * 7. `xx.5.sp` 半像素字号（HALF_PIXEL_SP，DS-17 · 重设计批 0）——DL-F09 禁半像素 sp，
 *    跨平台渲染不可控（Windows DWM 整像素吸附/部分 GPU 小数 glyph 模糊），零白名单；
 * 8. `BlurMaskFilter`/`MaskFilter.blur` 辉光模糊（NO_GLOW，DS-17）——DL-F06 禁 glow，
 *    豁免面 = :ui `widget/glass/`（GlassMaterials 柔边用途，独立 @Test 形态同 GLASS_SCOPE）；
 * 9. emoji 字符（EMOJI，DS-17）——DL 禁 emoji 代图标；代码区字符串内命中即报。
 *    豁免面（文件级白名单）：distill 输出协议符号 ✓/✗（EvidenceModels/WorkbenchPrompts
 *    ——解析标记非图标）与 CLI 输出文案（KbImportCli/DistillPage ErrorBanner——CLI 生态惯例）。
 *    `dashPathEffect` 虚线作用域（DASH_SCOPE，DS-17）——DS-09（批 3）落地后已翻转激活：
 *    豁免面 `ui/widget/decor/`（[DECOR_ALLOWED_PATH_PREFIX]），decor 外命中即违规。
 *
 * 白名单（相对路径 → 豁免规则，键为相对 `src/jvmMain/kotlin` 根的路径）镜像
 * `docs/core/theme.md`「有据保留」清单；
 * **新增例外必须先在 docs/core/theme.md 登记理由，再入白名单**（文档先行评审）。
 * 白名单条目必须仍有真实命中（见 `whitelist entries still match real code` 与
 * `raw dp whitelist values still match real code`），
 * 例外代码删除后须同步清理，防止白名单腐化为绕过规则的永久后门。
 */
class UiArchitectureGuardTest {

    /** 一条守护规则：id + 匹配模式 + 语义说明（对齐 docs/core/theme.md 对应章节） */
    private data class GuardRule(val id: String, val pattern: Regex, val description: String)

    /** 剥除注释后的单个源文件（按行存放，行号与原文件对齐；relativePath 相对所属模块的 jvmMain/kotlin 根，moduleName = 所属模块目录名） */
    private class SourceFile(val relativePath: String, val moduleName: String?, val codeLines: List<String>)

    private data class Violation(
        val relativePath: String,
        val line: Int,
        val description: String,
        val codeLine: String,
    )

    private val rules: List<GuardRule> = listOf(
        GuardRule(
            id = RULE_MATERIAL_SHAPES,
            pattern = Regex("""MaterialTheme\s*\.\s*shapes"""),
            description = "禁止 MaterialTheme.shapes（圆角一律 AppShapes）",
        ),
        GuardRule(
            id = RULE_ANIMATE_SCROLL,
            pattern = Regex("""animateScrollToItem"""),
            description = "禁止 animateScrollToItem（流式输出零动画铁律，即时 scrollToItem + 底部跟随）",
        ),
        GuardRule(
            id = RULE_MOTION_SPEC,
            // [<(] 同时覆盖 tween( 与显式泛型 tween<Float>(——泛型参数插在函数名与括号之间，
            // 只匹配 ( 会留下绕过盲区
            pattern = Regex("""\b(tween|spring)\s*[<(]"""),
            description = "禁止裸写 tween(/spring(（含显式泛型 tween<Float>( 等写法，动画规格唯一定义点 core/theme/Motion.kt）",
        ),
        GuardRule(
            id = RULE_ELEVATED_CARD,
            pattern = Regex("""\bElevatedCard\b"""),
            description = "禁止 ElevatedCard（卡片容器一律 AppCardStyles.tonal 扁平卡）",
        ),
        GuardRule(
            id = RULE_RAW_ALPHA,
            pattern = Regex("""alpha\s*=\s*0\.\d"""),
            description = "禁止 alpha = 0.x 字面量（透明度一律 AppAlpha token）",
        ),
        GuardRule(
            id = RULE_HALF_PIXEL_SP,
            // DS-17 · 重设计批 0：xx.5.sp 半像素字号禁写——DL-F09（零白名单）
            pattern = Regex("""\d+\.5\s*\.\s*sp\b"""),
            description = "禁止 xx.5.sp 半像素字号（DL-F09：跨平台渲染不可控；字号档一律整值 sp 或 Typography token）",
        ),
        GuardRule(
            id = RULE_EMOJI,
            // DS-17 · 重设计批 0：生产文案禁 emoji 代图标——只扫剥注释后代码区（字符串内容含
            // KDoc/注释已剥离）；协议符号与 CLI 输出走文件级白名单（whitelistedRulesByPath）
            pattern = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]"""), // raw string：Java 正则 \x{...} Unicode 区间
            description = "禁止 emoji 代图标（DL 图标语义条款；协议符号/CLI 输出见白名单有据保留）",
        ),
        GuardRule(
            id = RULE_RAW_DP,
            // 匹配非零数字字面量后跟 .dp（含 0.5.dp 小数与 "16 . dp" 空白写法，\b 防止 10.dp
            // 中的 0.dp 子串误判）；0.dp / 0.0.dp 零值不匹配——零值是 Material 惯用写法
            // （defaultElevation = 0.dp），docs「有据保留」按全局条目登记，正则直接排除
            // 而非逐文件登记，避免合理新代码被误伤
            pattern = Regex("""\b(?:[1-9]\d*(?:\.\d+)?|0\.\d*[1-9]\d*)\s*\.\s*dp\b"""),
            description = "禁止裸 xx.dp 字面量（间距/图标尺寸/圆角一律 Dimens/AppShapes token，新档位先在 token 文件扩充）",
        ),
    )

    /**
     * 白名单：相对路径 → 豁免规则 id 集合（单一真源 = docs/core/theme.md「有据保留」清单）。
     *
     * 键为相对 `src/jvmMain/kotlin` 根的完整相对路径（含包目录，如
     * `com/jiaoay/easy_dev_toolbox/core/theme/Motion.kt`，与违规报告展示的路径同一坐标系，
     * 可直接从报错信息复制）——文件名匹配会放过任意目录下的同名文件，路径精确匹配
     * 堵住该绕过面。
     *
     * 新增例外必须先在 docs/core/theme.md 登记理由再入本表；
     * 例外代码删除后须同步清理本表与文档（见 `whitelist entries still match real code` 防腐化断言）。
     */
    private val whitelistedRulesByPath: Map<String, Set<String>> = mapOf(
        // 动画规格唯一定义点：tween/spring 只允许出现在 Motion.kt（docs「动效设计 token」）
        "com/jiaoay/easy_dev_toolbox/core/theme/Motion.kt" to setOf(RULE_MOTION_SPEC),
        // 尺寸/圆角 token 定义本体：dp 字面量只允许出现在 Dimens.kt / AppShapes.kt（docs「尺寸设计 token」）
        "com/jiaoay/easy_dev_toolbox/core/theme/Dimens.kt" to setOf(RULE_RAW_DP),
        "com/jiaoay/easy_dev_toolbox/core/theme/AppShapes.kt" to setOf(RULE_RAW_DP),
        // EMOJI 豁免（DS-17 · 重设计批 0）：✓/✗ 是 distill 输出协议的解析标记（非 UI 图标），
        // 出现在协议定义与解析两侧；✅/❌/⛔ 是 CLI echo 与 ErrorBanner 横幅文案（CLI 生态惯例）。
        "com/jiaoay/easy_dev_toolbox/core/evidence/EvidenceModels.kt" to setOf(RULE_EMOJI),
        "com/jiaoay/easy_dev_toolbox/core/llm/WorkbenchPrompts.kt" to setOf(RULE_EMOJI),
        "com/jiaoay/easy_dev_toolbox/core/data/db/KbImportCli.kt" to setOf(RULE_EMOJI),
        "com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillPage.kt" to setOf(RULE_EMOJI),
        // （R3 白名单清理 2026-09-25：ImageThemeCard loading 行 MaterialTheme.shapes.medium
        //  改 AppShapes.medium——MATERIAL_SHAPES 整文件豁免随之删除，规则归零例外）
    )

    /**
     * RAW_DP 值级白名单：相对路径 → 允许的 dp 值集合（数字字符串，如 "16"）。
     *
     * 单一真源 = docs/core/theme.md「有据保留」清单：dp 字面量例外以值立据
     * （IconButton 触摸目标、控件功能尺寸、细粒度装饰等），故豁免粒度是
     * 「文件 → 允许值」而非整文件——同一文件里未登记的新值仍会违规，豁免面
     * 不随登记而扩大。一行多处 dp 字面量时全部命中值都在集合内才放行（见 [lineViolation]）。
     *
     * 新增例外必须先在 docs/core/theme.md 登记理由再入本表；例外代码删除/替换后
     * 须同步清理本表与文档（见 `raw dp whitelist values still match real code` 防腐化断言）。
     */
    private val rawDpAllowedValuesByPath: Map<String, Set<String>> = mapOf(
        // 窗口默认尺寸
        "com/jiaoay/easy_dev_toolbox/main.kt" to setOf("1280", "860"),
        // （S2 调查工作台：PageDefault 欢迎页退役，40/20/32 白名单条目随之清理）
        // （批 4a TaskListSection 收敛后三页裸 32dp 已全数替换为 iconMedium token，
        //  filesplit/logtag 两文件的白名单条目随之清理）
        // 色轮对话框控件功能尺寸：预览色点 48dp、色点描边 1dp、色相条高 16dp、渐变区高 180dp、指示圈 16dp + 2dp 描边
        "com/jiaoay/easy_dev_toolbox/page/settings/ColorPickerDialog.kt" to setOf("48", "1", "16", "180", "2"),
        // 图片提取卡功能尺寸：种子色点 40dp、预览色点 22dp、标签列宽 64dp、loading 行 strokeWidth 2dp
        // （蜂群回归 2026-10-06：拖放区 100dp 随 zone 单行化退役——值移出白名单）
        "com/jiaoay/easy_dev_toolbox/page/settings/ImageThemeCard.kt" to setOf("2", "40", "64", "22"), // DS-04：14 归 space14 消解
        // 主题选项行/主题卡：边框宽 1/2dp（未选中/选中态——值随主题橱窗族迁至 ThemeShowcase.kt）
        "com/jiaoay/easy_dev_toolbox/page/settings/ThemeShowcase.kt" to setOf("1", "2"),
        // 面板宽高 80/200dp、拖拽把手（可点击区高 16dp + 视觉条 32×4dp + 圆角 2dp）、grabber 箭头 14dp、IconButton 32dp
        // （P2 三舱迁移：输出面板迁底面板、历史面板迁 End 槽位、DragHandle 删除后，仅剩设备状态圆点 10dp）
        // （SH-05 · 重设计批 10：设备状态圆点 10dp 改 Dimens.dotIndicatorSize 6dp token，
        //  条目随之清零——ShellToolsPage.kt 值级白名单整条退役）
        // （P2 三舱迁移：输出面板形态上提 PanelHost + panel_* key，VM 面板状态面删除——条目清零）
        // （XA-20 · 重设计批 11：图标钮命中区收敛 MICS 32——ShellHistoryPanel 24dp×2+28dp×1
        //  全收 minimumInteractiveSize，值面清零——条目整条退役）
        // 输入框高度 140dp、紧凑行内 IconButton 20dp、行内图标 14dp
        // （XA-20 · 重设计批 11：IconButton 24dp 收 minimumInteractiveSize——20dp 紧凑档有据保留）
        "com/jiaoay/easy_dev_toolbox/page/shelltools/list/custom/ShellCustomEditorDialog.kt" to
            setOf("140", "20", "14"),
        // JSON 输入框高度 160dp
        "com/jiaoay/easy_dev_toolbox/page/shelltools/list/custom/ShellToolsGroupDialogs.kt" to setOf("160"),
        // strokeWidth 2dp、细粒度装饰图标 13/14dp
        // （XA-20 · 重设计批 11：IconButton 触摸目标 28dp×5 收 minimumInteractiveSize）
        "com/jiaoay/easy_dev_toolbox/page/shelltools/list/node/ShellToolsChildNode.kt" to
            setOf("2", "13", "14"),
        // （XA-20 · 重设计批 11：IconButton 触摸目标 28dp×4 收 minimumInteractiveSize，
        //  值面清零——条目整条退役）
        // IconButton 紧凑 IconButton 20dp
        // （R3 白名单清理 2026-09-25：行内进度圈/图标 14dp、strokeWidth 2dp 收编
        //  Dimens.progressInline/strokeThin token，值移出白名单）
        // （P2 三舱迁移：页内 ShellOutputPanel 变体删除后 tonalElevation 1dp 消费点消失，值移出白名单）
        // （2026-09-23 反馈批二：过滤栏固定 height(44.dp) 随裁切修复删除——改走
        //  OutlinedTextFieldDefaults.DecorationBox 紧凑档 contentPadding，44dp 值随之清理）
        // （XA-20 · 重设计批 11：IconButton 28dp×4 收 minimumInteractiveSize——
        //  20dp SearchField 清除钮有据保留不在收敛面）
        // （2026-10-14 UX 走查批：过滤栏清除关键字钮命中区 20dp 收
        //  Dimens.minimumInteractiveSize（MICS 32，深底 console 语境难点中）——
        //  本文件裸 dp 值面清零，条目整条退役）
        // 拖放区默认高度 140dp、边框宽 2dp
        "com/jiaoay/easy_dev_toolbox/widget/FileDropZone.kt" to setOf("140"), /* TL-D5② 2dp→hairline 后白名单值清理 */
        // strokeWidth 2dp
        // （XA-20 · 重设计批 11：IconButton 触摸目标 36dp×2 收 minimumInteractiveSize）
        "com/jiaoay/easy_dev_toolbox/widget/TaskCard.kt" to setOf("2"),
    )

    /** 扫描结果惰性缓存：全部受守护模块 jvmMain 的 .kt 文件（剥注释、按行；relativePath 相对各自模块根） */
    private val scannedFiles: List<SourceFile> by lazy {
        val roots = locateJvmMainRoots()
        roots.flatMap { root ->
            // 模块根目录的上一级即模块目录（<projectRoot>/<module>/src/jvmMain/kotlin）
            val moduleName = root.parentFile?.parentFile?.parentFile?.name
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .map { file ->
                    SourceFile(
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        moduleName = moduleName,
                        codeLines = stripKotlinComments(file.readText()).split('\n'),
                    )
                }
        }
            .also { files ->
                // 空扫描 = 永绿后门：目录定位异常时必须显式失败而非静默通过；
                // 文件数下限：低于历史规模（120+ 文件）说明某模块根丢失后静默缩水
                check(files.isNotEmpty()) { "jvmMain 扫描结果为空：${roots.joinToString { it.path }}（目录定位异常？）" }
                check(files.size >= 100) {
                    "jvmMain 扫描文件数异常（${files.size} < 100 下限）：模块根可能缺失（${roots.joinToString { it.path }}）"
                }
                // 白名单坐标系 = relativePath 相对各自模块根：跨模块同路径会让白名单键二义，必须显式失败
                val duplicated = files.groupingBy { it.relativePath }.eachCount().filterValues { it > 1 }
                check(duplicated.isEmpty()) {
                    "跨模块出现同 relativePath 文件（白名单坐标二义）：${duplicated.keys.joinToString()}"
                }
            }
    }

    // ------------------------------------------------------------------ 规则断言

    /** package 声明捕获（feature 隔离规则用）：取 com.jiaoay.easy_dev_toolbox. 前缀后的完整 FQN */
    private val packageRegex = Regex("""^\s*package\s+(com\.jiaoay\.easy_dev_toolbox\.\S+)""")

    @Test
    fun `MaterialTheme shapes stays banned outside AppShapes`() {
        assertNoViolations(RULE_MATERIAL_SHAPES)
    }

    @Test
    fun `animateScrollToItem stays banned everywhere`() {
        assertNoViolations(RULE_ANIMATE_SCROLL)
    }

    @Test
    fun `raw tween and spring calls stay banned outside Motion`() {
        assertNoViolations(RULE_MOTION_SPEC)
    }

    @Test
    fun `ElevatedCard stays banned in favor of tonal cards`() {
        assertNoViolations(RULE_ELEVATED_CARD)
    }

    @Test
    fun `raw alpha literals stay banned in favor of AppAlpha`() {
        assertNoViolations(RULE_RAW_ALPHA)
    }

    @Test
    fun `raw dp literals stay banned in favor of Dimens tokens`() {
        assertNoViolations(RULE_RAW_DP)
    }

    /**
     * 玻璃作用域守护（2026-09-23 液态玻璃批 1）：`dev.chrisbanes.haze` import 只允许
     * 出现在 :ui 模块 `widget/glass/` 目录（[GLASS_ALLOWED_PATH_PREFIX]）——
     * 玻璃材质单文件收口，消费方一律走包装修饰符，防止 haze 类型四处开花
     * （升级/换库只动一个文件；复刻 coil3 图片库隔离先例）。
     *
     * 扫描面：全部受守护模块 jvmMain（haze 若被 feature/宿主直接 import 即红）。
     * :core 零 haze 依赖（Glass.kt 是纯色 token，无 Haze 类型）由本规则一并钉死。
     * 批 3.5-c 加固：正则由 import 行放宽为**全行匹配**——拦 `dev.chrisbanes.haze.*`
     * 全限定名调用（FQ 调用不经 import 即可绕过的面；FQ 调用先例：WorkspaceTabs
     * IconButton / PageHome EasyDataManager）。注释/字符串由既有剥注释逻辑豁免。
     */
    @Test
    fun `haze imports stay confined to ui glass package`() {
        val hazeImportRegex = Regex("""dev\.chrisbanes\.haze""")
        val violations = scannedFiles.flatMap { file ->
            // :ui 的 glass 包豁免；其余一切文件命中即违规
            if (file.moduleName == "ui" && file.relativePath.startsWith(GLASS_ALLOWED_PATH_PREFIX)) {
                return@flatMap emptyList()
            }
            file.codeLines.mapIndexedNotNull { index, line ->
                if (hazeImportRegex.containsMatchIn(line)) {
                    Violation(
                        relativePath = file.relativePath,
                        line = index + 1,
                        description = "haze 引用越界（import 或 FQ 调用）（${file.moduleName ?: "?"}）：只允许 ${GLASS_ALLOWED_PATH_PREFIX}，" +
                            "消费方走包装修饰符 appGlassChrome/Panel/Sheet/Thin",
                        codeLine = line,
                    )
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("GLASS_SCOPE 规则命中 ${violations.size} 处：")
                    violations.forEach {
                        appendLine("  - ${it.relativePath}:${it.line} ${it.description}")
                        appendLine("      ${it.codeLine.trim()}")
                    }
                },
            )
        }
    }

    /**
     * NO_GLOW（DS-17 · 重设计批 0）：BlurMaskFilter/MaskFilter.blur 辉光模糊禁写——
     * DL-F06 禁 glow（唯一高光语言 = 1dp 受光缘发丝）。豁免面 = :ui `widget/glass/`
     * （GlassMaterials 柔边用途），与 GLASS_SCOPE 同为目录级独立 @Test 形态。
     */
    @Test
    fun `blur mask filter glow stays confined to glass materials`() {
        // DS-17 终验补（验证蜂群抓「死样例」）：正则写坏时本测试必须会红——
        // 样例行命中断言（正则失效=自检空转防回归）
        val glowRegex = Regex("""BlurMaskFilter|MaskFilter\.blur""")
        listOf(
            "val mask = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)",
            "paint.maskFilter = MaskFilter.blur(8f)",
        ).forEach { line ->
            assertTrue(
                glowRegex.containsMatchIn(line),
                "NO_GLOW 正则未命中样例行：$line（正则写坏——自检空转）",
            )
        }
        val violations = scannedFiles.flatMap { file ->
            if (file.moduleName == "ui" && file.relativePath.startsWith(GLASS_ALLOWED_PATH_PREFIX)) {
                return@flatMap emptyList()
            }
            file.codeLines.mapIndexedNotNull { index, line ->
                if (glowRegex.containsMatchIn(line)) {
                    Violation(
                        relativePath = file.relativePath,
                        line = index + 1,
                        description = "NO_GLOW：BlurMaskFilter/blur 辉光越界（${file.moduleName ?: "?"}）——" +
                            "唯一高光语言 = 1dp 受光缘发丝（DL-F06 禁 glow）；柔边只许 ${GLASS_ALLOWED_PATH_PREFIX}",
                        codeLine = line,
                    )
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("NO_GLOW 规则命中 ${violations.size} 处：")
                    violations.forEach {
                        appendLine("  - ${it.relativePath}:${it.line} ${it.description}")
                        appendLine("      ${it.codeLine.trim()}")
                    }
                },
            )
        }
    }

    /**
     * DASH_SCOPE（DS-17 定义 · DS-09 · 重设计批 3 翻转激活）：`dashPathEffect`
     * 虚线作用域——DL-M20 规定虚线只给降级/未完成/探测示意态容器，且虚线实现
     * 单点收口在 `ui/widget/decor/`（DS-09 落点 DashedBox，[DECOR_ALLOWED_PATH_PREFIX]）。
     * 消费方一律挂 `Modifier.appDashedBorder` 包装修饰符，不自写 dash
     * （防虚线段律/取色四处开花——升级/调档只动一个文件，复刻 coil3/haze 隔离先例）。
     *
     * 扫描面：全部受守护模块 jvmMain——decor/ 外任何 dashPathEffect 命中即违规。
     * （DS-09 落地前本规则为缓挂态——「全仓零命中」断言；2026-10-01 随 DS-09
     * 新件同 commit 翻转为「仅 decor/ 目录允许」。）
     */
    @Test
    fun `dash path effect stays confined to decor package`() {
        // DS-17 终验补（验证蜂群抓「死样例」）：正则写坏时本测试必须会红；
        // 小写正则对大写 DashPathEffect 不命中是**预期豁免面行为**（产品侧唯一
        // 合法写法 = 小写 dashPathEffect 参数名——样例行与产品写法一致）
        val dashRegex = Regex("""dashPathEffect""")
        listOf(
            "paint.pathEffect = dashPathEffect(floatArrayOf(4f, 4f), 0f)",
            "Modifier.appDashedBorder().drawBehind { pathEffect = DashPathEffect(arrayOf(4f), 0f) }".replace("DashPathEffect", "dashPathEffect"),
        ).forEach { line ->
            assertTrue(
                dashRegex.containsMatchIn(line),
                "DASH_SCOPE 正则未命中样例行：$line（正则写坏——自检空转）",
            )
        }
        val violations = scannedFiles.flatMap { file ->
            if (file.moduleName == "ui" && file.relativePath.startsWith(DECOR_ALLOWED_PATH_PREFIX)) {
                return@flatMap emptyList()
            }
            file.codeLines.mapIndexedNotNull { index, line ->
                if (dashRegex.containsMatchIn(line)) {
                    Violation(
                        relativePath = file.relativePath,
                        line = index + 1,
                        description = "DASH_SCOPE：dashPathEffect 越界（${file.moduleName ?: "?"}）——只允许 " +
                            DECOR_ALLOWED_PATH_PREFIX + "（DS-09 DashedBox 单点收口），" +
                            "消费方一律挂 Modifier.appDashedBorder 包装修饰符（DL-M20 虚线只给降级态）",
                        codeLine = line,
                    )
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("DASH_SCOPE 规则命中 ${violations.size} 处：")
                    violations.forEach {
                        appendLine("  - ${it.relativePath}:${it.line} ${it.description}")
                        appendLine("      ${it.codeLine.trim()}")
                    }
                },
            )
        }
    }

    /**
     * feature 模块隔离守护（模块化拆分引入）：feature 源文件不得 import 其他 feature 的
     * 页面域包，也不得 import 宿主自有包（home/settings/debug 页、host/ 装配、App 根）——
     * 跨域复用必须下沉 :core/:ui，或经导航注册契约回传，防止横向耦合随迭代复活。
     *
     * 判定坐标系（2026-09-11 修复恒不生效 bug 后重写）：import 的「包部」（去末段类名）
     * 必须落在该模块自身 package 声明全集内——从扫描文件的真实 package 声明派生，
     * 而非按模块名硬猜（feature-logkit 合法拥有 xlog/logtag/filesplit/logkit 四个页面
     * 子域并互引共享基座，猜「feature-logkit → page.logkit」会误伤其余三域）。
     * page.ToolPage 例外——导航枚举物理位于宿主 page 包，属共享契约。
     * 宿主装配面（core.AppViewModel / 根包 App）显式点名封禁：feature 经注册契约
     * 获得宿主回调，不直接引用宿主可组合根。
     * core/entity 为跨模块共享层，不参与宿主自有判定。
     */
    @Test
    fun `feature modules stay isolated from each other and from host`() {
        val importRegex = Regex("""^\s*import\s+(com\.jiaoay\.easy_dev_toolbox\.\S+)""")

        /** 模块自有包域全集：各源文件 package 声明去掉 com.jiaoay.easy_dev_toolbox. 前缀 */
        fun ownedPackageDomainsOf(module: String): Set<String> =
            scannedFiles
                .filter { it.moduleName == module }
                .flatMap { file ->
                    file.codeLines.mapNotNull { line ->
                        packageRegex.find(line)?.groupValues?.get(1)?.removePrefix("com.jiaoay.easy_dev_toolbox.")
                    }
                }
                .toSet()

        val featureDomains = scannedFiles.mapNotNull { it.moduleName }
            .filter { it.startsWith("feature-") }
            .associateWith { ownedPackageDomainsOf(it) }
        // 宿主自有包域 = composeApp 全部 package 声明 − 跨模块共享层（core/entity）
        val hostOwnDomains = ownedPackageDomainsOf("composeApp") - setOf("core", "entity")

        val violations = scannedFiles.flatMap { file ->
            val module = file.moduleName ?: return@flatMap emptyList()
            if (!module.startsWith("feature-")) return@flatMap emptyList()
            val ownDomains = featureDomains[module].orEmpty()
            file.codeLines.mapIndexedNotNull { index, line ->
                val imported = importRegex.find(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                // 共享导航契约例外（物理在宿主 page 包的 ToolPage）
                if (imported == "com.jiaoay.easy_dev_toolbox.page.ToolPage") return@mapIndexedNotNull null
                // 宿主装配面点名封禁：feature 经注册契约回传，不直接引用宿主可组合根/全局 VM
                if (imported == "com.jiaoay.easy_dev_toolbox.core.AppViewModel" ||
                    imported == "com.jiaoay.easy_dev_toolbox.App"
                ) {
                    return@mapIndexedNotNull "import $imported：宿主装配面不得被 feature 直接引用（经注册契约回传）"
                }
                // import 包部 = 去掉末段类名（根包类无点分隔，包部为空串）
                val remainder = imported.removePrefix("com.jiaoay.easy_dev_toolbox.")
                val pkg = if ('.' in remainder) remainder.substringBeforeLast('.') else ""
                when {
                    // 宿主自有包（根包/宿主页 home/settings/debug、host/ 装配）——先于 page 域判定，
                    // 宿主页 import 报「宿主自有包」而非「其他页面域」，语义精确
                    pkg in hostOwnDomains ->
                        "import $imported：feature 不得引用宿主自有包；需要的能力下沉 :core/:ui 或经注册契约回传"
                    // page 域跨 feature 页面引用：包部必须在本模块自有包域内
                    pkg.startsWith("page.") && pkg !in ownDomains ->
                        "import $imported：feature 不得引用其他页面域（跨域复用下沉 :core/:ui 或经注册契约回传）"
                    else -> null
                }
            }.map { desc ->
                Violation(relativePath = file.relativePath, line = 0, description = desc, codeLine = "")
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("feature 隔离规则命中 ${violations.size} 处：")
                    violations.forEach { appendLine("  - ${it.relativePath}: ${it.description}") }
                },
            )
        }
    }

    // ------------------------------------------------------------------ 守护自检

    @Test
    fun `guard rules really catch violations`() {
        // 自检：六条规则对样例代码必须全部命中——证明守护真的能失败，而非扫描器空转
        val sample = listOf(
            "val shape = MaterialTheme.shapes.medium",
            "listState.animateScrollToItem(9)",
            "val spec = tween(durationMillis = 240)",
            "val spec2 = spring(dampingRatio = 1f)",
            "ElevatedCard { }",
            "Color.Black.copy(alpha = 0.5)",
            "Modifier.size(16.dp)",
            // DS-17 · 重设计批 0 扩容（rules 列表新成员）
            "val fs = 11.5.sp",
            "完成 \uD83C\uDF89", // emoji 字符（🎉）样例——EMOJI 规则命中面
        )
        rules.forEach { guard ->
            assertTrue(
                sample.any { guard.pattern.containsMatchIn(it) },
                "规则 ${guard.id} 未命中样例代码，守护可能失效",
            )
        }
        // 自检补充：MOTION_SPEC 每种写法必须逐一命中——显式泛型 tween<Float>(240) 的
        // 泛型参数插在函数名与括号之间，只匹配 ( 的旧正则会漏掉该写法（防盲区回归）
        val motionSpecRule = rule(RULE_MOTION_SPEC)
        listOf(
            "val spec = tween(durationMillis = 240)",
            "val spec = tween<Float>(240)",
            "val spec = spring(dampingRatio = 1f)",
            "val spec = spring<Float>(dampingRatio = 1f)",
        ).forEach { line ->
            assertTrue(
                motionSpecRule.pattern.containsMatchIn(line),
                "规则 MOTION_SPEC 未命中样例：$line（正则存在绕过盲区）",
            )
        }
        // 自检补充：RAW_DP 命中面（整数/小数/空白写法）与豁免面（token 引用、sp、零值）
        // 逐一验证——只排零值的正则若写宽会误伤 0.dp 惯用写法，写窄会放过 0.5.dp 小数
        val rawDpRule = rule(RULE_RAW_DP)
        listOf(
            "Modifier.size(16.dp)",
            "strokeWidth = 2.dp",
            "val gap = 0.5.dp",
            "val w = 1280 . dp",
        ).forEach { line ->
            assertTrue(
                rawDpRule.pattern.containsMatchIn(line),
                "规则 RAW_DP 未命中样例：$line（正则存在绕过盲区）",
            )
        }
        listOf(
            "Modifier.size(Dimens.space16)", // token 引用，不含 dp 字面量
            "val fontSize = 8.sp", // 字号单位，非 dp
            "elevation = 0.dp", // 零值全局有据保留（docs 清单）
            "elevation = 0.0.dp",
        ).forEach { line ->
            assertFalse(
                rawDpRule.pattern.containsMatchIn(line),
                "规则 RAW_DP 不应命中样例：$line（正则误伤豁免面）",
            )
        }

        // DS-17 · 重设计批 0：HALF_PIXEL_SP 命中/豁免对
        val halfPixelRule = rule(RULE_HALF_PIXEL_SP)
        listOf(
            "val fs = 11.5.sp",
            "val fs2 = 12.5 . sp", // 空白写法防盲区
        ).forEach { line ->
            assertTrue(
                halfPixelRule.pattern.containsMatchIn(line),
                "规则 HALF_PIXEL_SP 未命中样例：$line（半像素字号绕过盲区）",
            )
        }
        listOf(
            "val fs = 12.sp", // 整值合法
            "val size = 16.dp", // dp 单位非 sp
            "val fs = 12.0.sp", // .0 整数小数写法不属半像素
        ).forEach { line ->
            assertFalse(
                halfPixelRule.pattern.containsMatchIn(line),
                "规则 HALF_PIXEL_SP 不应命中样例：$line（误伤整值/非 sp 面）",
            )
        }
        // 自检补充（批 3.5-c）：GLASS_SCOPE 全行匹配双命中面——import 行与 FQ 调用行
        val glassScopeSampleLines = listOf(
            "import dev.chrisbanes.haze.HazeState",
            "dev.chrisbanes.haze.hazeSource(state)", // FQ 调用（不经 import 的绕过面）
        )
        glassScopeSampleLines.forEach { line ->
            assertTrue(
                Regex("""dev\.chrisbanes\.haze""").containsMatchIn(line),
                "GLASS_SCOPE 未命中样例：$line（FQ 调用绕过盲区回归）",
            )
        }
        // 自检补充（DS-09 · 重设计批 3 翻转）：DASH_SCOPE 命中面 + 豁免面坐标判定
        val dashScopeRegex = Regex("""dashPathEffect""")
        assertTrue(
            dashScopeRegex.containsMatchIn("val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))"),
            "DASH_SCOPE 未命中生产写法样例（dashPathEffect 正则失效）",
        )
        // 豁免面 = :ui 模块 widget/decor/ 前缀（与扫描实现同款 startsWith 判定）
        assertTrue(
            "com/jiaoay/easy_dev_toolbox/widget/decor/DashedBox.kt".startsWith(DECOR_ALLOWED_PATH_PREFIX),
            "DS-09 落点文件必须落在 DECOR_ALLOWED_PATH_PREFIX 豁免面内（坐标判定失效）",
        )
        assertFalse(
            "com/jiaoay/easy_dev_toolbox/widget/glass/GlassMaterials.kt".startsWith(DECOR_ALLOWED_PATH_PREFIX),
            "decor 外文件不得被误豁免（前缀判定过宽）",
        )
        // DS-08/DS-09 新件消费值锚（值断言在 :core/:ui 测试；此处钉消费表达式形态）
        // ——见 GhostCardTest/DashedBoxSceneGuardTest，本测试只管作用域越界。
    }

    @Test
    fun `comment mentions do not trigger rules`() {
        // 自检：注释里的提及（迁移说明、KDoc）必须被剥离，只有代码区命中才算违规
        val rawQuote = "\"\"\""
        val sample = stripKotlinComments(
            """
            // 旧实现 listState.animateScrollToItem(last) 已移除
            val a = 1 // tween(100) 历史写法
            /* MaterialTheme.shapes.medium 亦不再使用 */
            /* ElevatedCard 已替换为 tonal 卡 */
            val b = spring(stiffness = 1.0f)
            val url = "https://example.com/anchor" // 字符串内的 // 非注释
            val s = "/* 不是块注释 */"
            val esc = "\"" // 转义引号后的行注释仍应剥离
            val raw = $rawQuote 原始串内 // 与 /* 皆非注释 $rawQuote
            val after = 3
            """.trimIndent(),
        )
        assertFalse(sample.contains("animateScrollToItem"), "行注释内容应被剥离")
        assertFalse(sample.contains("tween("), "行尾注释内容应被剥离")
        assertFalse(sample.contains("MaterialTheme.shapes"), "块注释内容应被剥离")
        assertFalse(sample.contains("ElevatedCard"), "块注释内容应被剥离")
        assertTrue(sample.contains("spring("), "代码区内容必须保留，否则扫描器漏报")
        // 字符串感知：字符串态内不识别注释起始，字符串内容与行内后续代码不被误剥
        assertTrue(
            sample.contains("https://example.com/anchor"),
            "字符串内的 // 不应触发行注释剥离（URL 误剥会隐藏后续代码）",
        )
        assertTrue(sample.contains("/* 不是块注释 */"), "字符串内的 /* 不应触发块注释剥离")
        assertTrue(
            sample.contains("val esc = \"\\\"\""),
            "转义引号应正确终止字符串，其后行注释仍被剥离",
        )
        assertTrue(
            sample.contains("原始串内 // 与 /* 皆非注释"),
            "原始字符串内的注释符不应被剥离（内容保留供扫描）",
        )
        assertTrue(sample.contains("val after = 3"), "原始字符串后的代码必须保留")
    }

    @Test
    fun `whitelist entries still match real code`() {
        // 防白名单腐化：每条豁免都必须按相对路径对应仍存在的真实命中——
        // 例外代码删除后白名单若不清理，将来同路径的文件将永久绕过规则
        whitelistedRulesByPath.forEach { (relativePath, ruleIds) ->
            val file = scannedFiles.firstOrNull { it.relativePath == relativePath }
            if (file == null) {
                fail(
                    "白名单文件 $relativePath 已不存在于 jvmMain，" +
                        "请同步清理白名单与 docs/core/theme.md「有据保留」清单",
                )
            }
            ruleIds.forEach { ruleId ->
                val guard = rule(ruleId)
                val hits = file.codeLines.count { guard.pattern.containsMatchIn(it) }
                assertTrue(
                    hits > 0,
                    "白名单 $relativePath:$ruleId 无真实命中（例外已失效，" +
                        "请清理白名单与 docs/core/theme.md「有据保留」清单）",
                )
            }
        }
    }

    @Test
    fun `raw dp whitelist values still match real code`() {
        // 防值级白名单腐化：每个允许值必须仍以 dp 字面量真实存在于对应文件——
        // 例外代码清理/替换后白名单若不清理，该文件的豁免集合将名存实亡，
        // 且掩盖真实命中数缩水（规则对齐度悄然下降）
        val rawDpGuard = rule(RULE_RAW_DP)

        // 结构自检：整文件豁免 RAW_DP 的文件不得再出现在值级白名单（重复登记掩盖豁免面扩大）
        val fullyExemptPaths = whitelistedRulesByPath
            .filterValues { ruleIds -> RULE_RAW_DP in ruleIds }
            .keys
        val duplicatedPaths = rawDpAllowedValuesByPath.keys intersect fullyExemptPaths
        assertTrue(
            duplicatedPaths.isEmpty(),
            "文件 ${duplicatedPaths.joinToString()} 同时整文件豁免与按值豁免 RAW_DP，应只保留整文件豁免",
        )

        rawDpAllowedValuesByPath.forEach { (relativePath, allowedValues) ->
            val file = scannedFiles.firstOrNull { it.relativePath == relativePath }
                ?: fail(
                    "RAW_DP 白名单文件 $relativePath 已不存在于 jvmMain，" +
                        "请同步清理白名单与 docs/core/theme.md「有据保留」清单",
                )
            val presentValues = file.codeLines
                .flatMap { line -> rawDpGuard.pattern.findAll(line).map { match -> extractDpValue(match.value) } }
                .toSet()
            val staleValues = allowedValues - presentValues
            assertTrue(
                staleValues.isEmpty(),
                "RAW_DP 白名单 $relativePath 的值 ${staleValues.joinToString("/")}dp 已不存在于该文件" +
                    "（例外已失效，请清理白名单与 docs/core/theme.md「有据保留」清单）",
            )
        }
    }

    /**
     * 白名单总数锚（评审修复批 2026-09-23——P1-6 键数失实防再犯）：
     * 值级条目数增删时本断言红，提醒同步改两处 docs 键数——
     * - docs/core/theme.md「迁移状态」节（S7 终态复核小节）
     * - docs/ui-style-guide.md 7.1 正则细节节（RAW_DP 条）
     *
     * 现行口径（XA-20 · 重设计批 11：图标钮命中区收敛 MICS 32——六文件 24/28/36dp
     * 命中收 minimumInteractiveSize，ShellHistoryPanel/ShellToolsParentNode 两值面
     * 清零整条退役、四文件值集收缩）：值级 10 条 + 整文件豁免 7 键 = 17 键
     * （前口径 SH-05 · 重设计批 10：12+7=19）。
     * 🔴 双改点流程提示：删除例外代码时先改代码 → 本断言红 → 同步清理白名单
     *    与 docs 键数后再提交（红是流程锚，不是误报）。
     */
    @Test
    fun `raw dp whitelist total key count matches docs`() {
        assertEquals(
            9,
            rawDpAllowedValuesByPath.size,
            "RAW_DP 值级白名单条目数应为 9（XA-20 · 重设计批 11 收编后 10 键；" +
                "2026-10-14 UX 走查批：ShellOutputPanel 过滤栏清除钮 20dp 收 Dimens.minimumInteractiveSize " +
                "值面清零——条目整条退役 10→9）" +
                "——增删条目须同步更新本断言与 docs 键数" +
                "（docs/core/theme.md 迁移状态节 + docs/ui-style-guide.md 7.1 的键数口径——" +
                "现行：9 值级 + 7 整文件 = 16 键）",
        )
        assertEquals(
            7,
            whitelistedRulesByPath.size,
            "整文件豁免键数应为 7（Motion.kt/Dimens.kt/AppShapes.kt + DS-17·重设计批 0 EMOJI 四文件：" +
                "EvidenceModels/WorkbenchPrompts/KbImportCli/DistillPage——协议符号与 CLI 输出有据保留）；" +
                "增删整文件豁免须同步更新本断言与 docs 键数",
        )
    }

    /**
     * MICS 桌面紧凑档装配锚（2026-09-23 批一：provides 0.dp → 32.dp 回正）：[AppTheme]
     * 装配处必须提供 `LocalMinimumInteractiveComponentSize provides Dimens.minimumInteractiveSize`
     * （0.dp 全关实验在真机暴露 FilterChip 塌缩与按钮命中过小两个副作用——批一定档
     * 32dp：小钮恢复 ≥32dp 标准命中而不回 48dp 触屏档）。删除该 provision 或改回
     * 0.dp 即 FilterChip 塌缩回归，本断言防静默回退。
     */
    @Test
    fun `mics compact desktop size stays wired in app theme`() {
        val file = scannedFiles.firstOrNull {
            it.relativePath == "com/jiaoay/easy_dev_toolbox/core/theme/AppTheme.kt"
        } ?: fail("AppTheme.kt 不在 jvmMain 扫描面内（:core 模块根丢失？）")
        assertTrue(
            file.codeLines.any {
                it.contains("LocalMinimumInteractiveComponentSize provides Dimens.minimumInteractiveSize")
            },
            "AppTheme.kt 必须装配 LocalMinimumInteractiveComponentSize provides " +
                "Dimens.minimumInteractiveSize（MICS 32dp 桌面紧凑档——删除或改回 0.dp 会使" +
                "FilterChip 塌缩与按钮命中过小两个真机副作用回归）",
        )
    }

    // ------------------------------------------------------------------ 扫描实现

    private fun rule(id: String): GuardRule = rules.first { it.id == id }

    /** 从 RAW_DP 匹配片段（如 "16.dp"、"0.5 . dp"）提取数值串（"16"/"0.5"），用于值级白名单比对 */
    private val dpNumberRegex = Regex("""\d+(?:\.\d+)?""")

    private fun extractDpValue(matched: String): String =
        dpNumberRegex.find(matched)?.value
            ?: error("RAW_DP 匹配片段不含数字：\"$matched\"（规则正则与值提取逻辑不一致）")

    /**
     * 单行违规判定：返回违规说明（RAW_DP 含未豁免值），null = 该行放行。
     *
     * RAW_DP 特例：dp 例外按值登记（[rawDpAllowedValuesByPath]），该行全部命中值都在
     * 允许集合内时放行；任一值未登记即违规，说明中列出未豁免值便于直接定位。
     * 其余规则维持「命中即违规」。
     */
    private fun lineViolation(relativePath: String, codeLine: String, guard: GuardRule): String? {
        if (!guard.pattern.containsMatchIn(codeLine)) return null
        if (guard.id != RULE_RAW_DP) return guard.description
        val allowedValues = rawDpAllowedValuesByPath[relativePath].orEmpty()
        val unlistedValues = guard.pattern.findAll(codeLine)
            .map { match -> extractDpValue(match.value) }
            .filter { it !in allowedValues }
            .distinct()
            .toList()
        if (unlistedValues.isEmpty()) return null
        return "${guard.description}（该行未豁免 dp 值：${unlistedValues.joinToString(", ")}）"
    }

    private fun assertNoViolations(ruleId: String) {
        val guard = rule(ruleId)
        val violations = scannedFiles.flatMap { file ->
            if (ruleId in whitelistedRulesByPath[file.relativePath].orEmpty()) {
                return@flatMap emptyList()
            }
            file.codeLines.withIndex()
                .mapNotNull { indexed ->
                    lineViolation(file.relativePath, indexed.value, guard)?.let { description ->
                        Violation(
                            relativePath = file.relativePath,
                            line = indexed.index + 1,
                            description = description,
                            codeLine = indexed.value.trim(),
                        )
                    }
                }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("架构守护规则 [${guard.id}] ${guard.description} 命中 ${violations.size} 处：")
                    violations.forEach {
                        appendLine("  - ${it.relativePath}:${it.line}: ${it.codeLine}")
                        // RAW_DP 行级说明含未豁免值，附注帮助直接定位需登记/替换的值
                        if (it.description != guard.description) appendLine("      ${it.description}")
                    }
                },
            )
        }
    }

    /**
     * 定位全部受守护的 jvmMain 源码根（多模块）：自 user.dir 逐级向上探测项目根
     * （含 settings.gradle.kts），然后枚举项目根下每个子目录的 src/jvmMain/kotlin
     * （存在即收录）。新功能模块落盘即自动纳管，无需改本测试。
     *
     * 自检：jvmMain 根数与文件数下限防"模块根丢失后静默缩水"——
     * 现阶段仅 composeApp 一根也放行（下限 1），:core/:ui 等模块落地后自然纳管；
     * 真正的防盗门是文件数下限（模块根误删会跌破历史规模）。
     */
    private fun locateJvmMainRoots(): List<File> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val roots = dir.listFiles { f -> f.isDirectory }
                    .orEmpty()
                    .filter { File(it, "src/jvmMain/kotlin").isDirectory }
                    .map { File(it, "src/jvmMain/kotlin") }
                check(roots.isNotEmpty()) {
                    "未发现任何 jvmMain 模块根（模块目录定位异常？）"
                }
                return roots
            }
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    /**
     * 剥除行注释（//）与块注释（/** */、/* */），保留代码与换行——
     * 换行保留使剥除后文本的行号与原文件对齐，违规可报出准确行号。
     *
     * 字符串感知：普通 `"..."`（`\` 转义生效）与 `"""..."""` 原始字符串（可跨行、无转义）
     * 各自成态，字符串态内不识别注释起始——字符串里的 `//`（如 URL）或 `/*...*/` 片段不会误剥
     * 后续代码；字符串内容本身保留供规则扫描（宁可多报不漏报）。普通字符串语法上不跨行，
     * 遇换行保守回退代码态，把状态错位（如罕见的 `'"'` 字符字面量）限制在单行内；
     * 字符字面量含引号等罕见形态不做识别（已知近似，现有源码无此写法）。
     */
    private fun stripKotlinComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inString = false // 普通 "..." 字符串态
        var inRawString = false // """...""" 原始字符串态
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inRawString -> when {
                    // 结束三引号；内容（含换行与注释符）原样保留供规则扫描
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = false
                    }
    
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
    
                inString -> when {
                    // \ 转义：连同伴随字符一起保留，不改变字符串态
                    c == '\\' && i + 1 < source.length -> {
                        sb.append(c).append(source[i + 1])
                        i += 2
                    }
    
                    // 字符串结束，内容保留
                    c == '"' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }
    
                    // 普通字符串不跨行：非法换行保守回退代码态（行号仍对齐）
                    c == '\n' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }
    
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
    
                else -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = true
                    }
    
                    c == '"' -> {
                        sb.append(c)
                        inString = true
                        i++
                    }
    
                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                        while (i < source.length && source[i] != '\n') i++
                    }
    
                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                        i += 2
                        while (i < source.length) {
                            if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                                i += 2
                                break
                            }
                            if (source[i] == '\n') sb.append('\n')
                            i++
                        }
                    }
    
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val RULE_MATERIAL_SHAPES = "MATERIAL_SHAPES"
        const val RULE_ANIMATE_SCROLL = "ANIMATE_SCROLL"
        const val RULE_MOTION_SPEC = "MOTION_SPEC"
        const val RULE_ELEVATED_CARD = "ELEVATED_CARD"
        const val RULE_RAW_ALPHA = "RAW_ALPHA"
        const val RULE_RAW_DP = "RAW_DP"
        const val RULE_HALF_PIXEL_SP = "HALF_PIXEL_SP"
        const val RULE_EMOJI = "EMOJI"
        const val RULE_NO_GLOW = "NO_GLOW"
        const val RULE_DASH_SCOPE = "DASH_SCOPE"
        const val RULE_GLASS_SCOPE = "GLASS_SCOPE"

        /**
         * GLASS_SCOPE 豁免面（2026-09-23 液态玻璃批 1）：haze import 唯一落点 =
         * :ui 模块 `widget/glass/` 目录（relativePath 前缀判定）。
         * 消费方（含宿主壳）一律走包装修饰符 appGlassChrome/Panel/Sheet/Thin，
         * 不直接引 dev.chrisbanes.haze 类型（复刻 coil3「图片库零四处引入」先例）。
         */
        const val GLASS_ALLOWED_PATH_PREFIX = "com/jiaoay/easy_dev_toolbox/widget/glass/"

        /**
         * DASH_SCOPE 豁免面（DS-09 · 重设计批 3 · 2026-10-01 翻转激活）：
         * `dashPathEffect` 唯一落点 = :ui 模块 `widget/decor/` 目录（relativePath 前缀判定，
         * 与 [GLASS_ALLOWED_PATH_PREFIX] 同款形态）。DS-09 DashedBox 单点收口虚线实现，
         * 消费方一律挂 `Modifier.appDashedBorder` 包装修饰符（DL-M20 虚线只给降级态）。
         */
        const val DECOR_ALLOWED_PATH_PREFIX = "com/jiaoay/easy_dev_toolbox/widget/decor/"
    }
}
