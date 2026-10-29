// 能力一级化批 D（2026-09-27）：渠道中立层接缝守护——把 docs/architecture.md「第二 IM 接缝的边界
// 规则」（原「写纸不写代码」）的渠道中立边界升格为红构建门禁。
//
// 三组守护面：
// - C 组（扫盘）：`lark-bridge/src/main` 的 `pipeline/` 与 `handler/` 目录**不得新建**
//   `LarkTarget.Chat(` / `CardSpec(` 构造点——现存点位白名单基线钉死（基线从当前 HEAD 实况 grep
//   得出，见 [PIPELINE_CARD_SPEC_SINKS]；LarkTarget.Chat( 在两目录现为零命中）。归一化用
//   [normalizeKotlinSource]（复制自 LarkSdkIsolationTest 同名 private 成员，见其 KDoc 的理由）——
//   剥注释/字符串后匹配，防 KDoc/文案里的字面提及误报。
// - D 组（反射）：L2 五工具 + L1 三工具的 Args **无 chat_id 参数**（目标会话钉死在实现闭包，
//   LLM 不可指定会话——批 C「Args 无 chat_id」硬边界的守护）。反射经 kotlinx `serializer()`
//   descriptor 读序列化字段名——🔴 不用 Java 反射 `Constructor.parameters`（项目未开
//   `-java-parameters`，实测 Parameter.getName() 返回 arg0/arg1，必假绿）。
// - E 组（反射）：`NativeChatSurface` 接口方法签名不出现飞书专属类型（LarkTarget/CardSpec/CardJson）；
//   渠道中立值类型（ChatHistoryPage/ChatInfo/ChatCreation/SenderProfile 等批 A 命名族）允许。
//
// 🔴 工具类是 internal（跨模块反射需 Class.forName 字节码可达，编译期引用不可行）——composeApp
//   jvmTest 的运行时 classpath 含 :feature-larkbridge 及其全部依赖（implementation 传递），可达。
// 🔴 本测试不改生产代码；红即生产代码错，停下汇报（批 D 纪律红线 1）。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ChannelSeamGuardTest {

    // ── C 组：pipeline/handler 构造点白名单（扫盘）──

    /**
     * C-1：`pipeline/` 与 `handler/` 目录（lark-bridge 生产源集）不得新建 `LarkTarget.Chat(` 构造点。
     *
     * 白名单基线（当前 HEAD 实况 grep）：两目录 **0 命中**（构造只允许在 BridgeAssembler / Bridge /
     * cli/SendCommand——它们是装配点与 CLI 入口，不在本守护面）。归一化后匹配（防注释提及误报）。
     * 若未来确需在 pipeline/handler 构造，必须先改本测试并把点位登记进白名单附理由。
     */
    @Test
    fun `C pipeline and handler dirs have no new LarkTarget Chat constructor sites`() {
        val root = locateProjectRoot()
        val scanRoot = File(root, LARK_BRIDGE_MAIN)
        assertTrue(scanRoot.isDirectory, "$LARK_BRIDGE_MAIN 缺失——扫描面漂移，守护失效")
        val offenders = scanDirs(root).flatMap { dir ->
            dir.walkKtFiles().flatMap { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val normalized = normalizeKotlinSource(file.readText())
                CONSTRUCTOR_PATTERNS.filter { normalized.contains(it) }.map { "$rel ⇢ ${it.trim()}" }
            }
        }
        // 白名单基线：pipeline/ 内合法的 CardSpec( 构造点全部收在 CardJson.kt（卡片构造唯一入口，
        // docs 边界规则钦定「只允许经 …与 CardJson」）；LarkTarget.Chat( 两目录零命中。
        val whitelist = PIPELINE_CARD_SPEC_SINKS
        val unexpected = offenders.filter { it !in whitelist }
        assertTrue(
            unexpected.isEmpty(),
            buildString {
                appendLine("pipeline/handler 出现白名单外的渠道专属构造点：")
                unexpected.forEach { appendLine("  - $it") }
                appendLine("边界规则（docs/architecture.md「第二 IM 接缝」节）：只允许经 RequestContextFactory.targetOf")
                appendLine("与 CardJson（卡片构造唯一入口）。确需新增点位：停下回报，先改文档与本白名单再动代码。")
            },
        )
        // 白名单自检：基线点位必须仍然全部真实命中（文件被删/改名/点位被合法移除时同步清理基线，防基线烂尾）
        val present = offenders.toSet()
        val stale = whitelist.filter { it !in present }
        assertTrue(
            stale.isEmpty(),
            "白名单基线点位已不再命中（$stale）——点位被移除/重构时请同步清理本基线，防基线烂尾",
        )
    }

    /**
     * C-2：扫盘面规模自检（ScanSurfaceSelfCheckTest.C 的纪律锚——扫盘守护必须自带规模下限）。
     * pipeline/ + handler/ 当前 28 个 .kt；跌破 60% 说明目录定位/剪枝出错（守护会静默失效）。
     */
    @Test
    fun `C scan surface size self check`() {
        val root = locateProjectRoot()
        val files = scanDirs(root).flatMap { it.walkKtFiles() }
        assertTrue(
            files.size >= MIN_SCAN_FILES,
            "pipeline/handler 扫描面异常（${files.size} < $MIN_SCAN_FILES）——目录定位/剪枝出错，守护静默失效",
        )
    }

    // ── D 组：L1/L2 工具 Args 无 chat_id（反射守护）──

    /**
     * D-1：L2 五工具的 Args 序列化字段名**不含 chat_id**（含任何形态的 chatId 变体）。
     *
     * 反射路线：`Args.Companion.serializer().descriptor.elementNames`（kotlinx.serialization 编译器
     * 插件产物，K2 通用）。🔴 不用 Java 反射构造参数名——项目未开 `-java-parameters`，
     * `Parameter.getName()` 恒返回 arg0/arg1（本批实测），那样会全量假绿。
     *
     * 类锚：工具类是 internal，经 `Class.forName(FQCN)` 字节码可达（运行时 classpath 含
     * :feature-larkbridge 及其依赖）；类名/字段名锚与源码逐字一致（拼写错 = ClassNotFound 红，不假绿）。
     */
    @Test
    fun `D L2 five tools Args have no chat_id field`() {
        val loader = toolClassLoader()
        L2_TOOL_CLASSES.forEach { (toolClass, why) ->
            val fields = serialFieldNames(loader, toolClass)
            assertTrue(
                fields.isNotEmpty(),
                "$toolClass 的 Args 反射面为空——序列化插件产物缺失或类锚漂移（假绿风险，须修探针）",
            )
            val offenders = fields.filter { it.lowercase().replace("_", "") == CHAT_ID_TOKEN }
            assertTrue(
                offenders.isEmpty(),
                "L2 工具 Args 出现会话指定参数：$toolClass.Args.$offenders（$why）——" +
                    "目标会话必须钉死在 NativeChatSurface 实现闭包，LLM 不可指定会话（批 C 硬边界）",
            )
        }
    }

    /**
     * D-2：L1 三工具同形态守扩展（批 C KDoc：L1 硬边界同 P4 区——当前会话作用域，无 chat_id 参数）。
     * 守的是「未来给 L1 加跨会话参数」的漂移；字段名锚同 D-1。
     */
    @Test
    fun `D L1 three tools Args keep session-scoped shape`() {
        val loader = toolClassLoader()
        L1_TOOL_CLASSES.forEach { (toolClass, why) ->
            val fields = serialFieldNames(loader, toolClass)
            assertTrue(fields.isNotEmpty(), "$toolClass 的 Args 反射面为空——探针失效风险，须修")
            val offenders = fields.filter { it.lowercase().replace("_", "") == CHAT_ID_TOKEN }
            assertTrue(
                offenders.isEmpty(),
                "L1 工具 Args 出现会话指定参数：$toolClass.Args.$offenders（$why）——L1 恒为当前会话作用域",
            )
        }
    }

    /**
     * D-3：Args 反射探针自身的健全性——用「已知字段名」的正反样本锁死 [serialFieldNames] 的读法：
     * SendImageTool.Args 实测 = [file_path, display_name]（源码 KoogBridgeTools.kt 的 SendImageTool.Args）。
     * 读法漂移（如误走 Java 反射拿 arg0/arg1）时这里先红，D-1/D-2 不会静默假绿。
     */
    @Test
    fun `D args reflection probe reads real serialization field names`() {
        val loader = toolClassLoader()
        val names = serialFieldNames(loader, "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.SendImageTool")
        assertEquals(
            listOf("file_path", "display_name"),
            names,
            "Args 反射探针读到的字段名与源码不符——探针读法漂移（如误用 Java 反射参数名），先修探针再谈守护",
        )
    }

    // ── E 组：NativeChatSurface 接口的渠道中立签名（反射守护）──

    /**
     * E：`NativeChatSurface` 接口方法签名不出现飞书专属类型（LarkTarget/CardSpec/CardJson）。
     *
     * 渠道中立值类型（ChatHistoryPage/ChatInfo/ChatCreation/SenderProfile 等批 A「渠道中立命名」族）
     * **允许**——区分名单见 [FORBIDDEN_SIMPLE_NAMES] / [ALLOWED_SIMPLE_NAMES]。
     * 返回类型 + 参数类型全量反射核对（含泛型实参的原始类拆解）。
     */
    @Test
    fun `E native chat surface signatures stay channel neutral`() {
        val surface = Class.forName(NATIVE_SURFACE_FQCN)
        val forbiddenHits = mutableListOf<String>()
        for (method in surface.methods.filter { it.declaringClass == surface }) {
            val sink = { role: String, type: Class<*> ->
                val hit = forbiddenSimpleNameOf(type)
                if (hit != null) forbiddenHits.add("${method.name} $role → $hit")
            }
            method.parameterTypes.forEach { sink("param", it) }
            sink("return", method.returnType)
        }
        // 泛型实参层：按 signature 类型名核对（渠道中立 List<ChatHistoryPage?> 合法；LarkTarget/CardSpec 命中即红）
        for (method in surface.methods.filter { it.declaringClass == surface }) {
            method.genericParameterTypes.forEach { gt ->
                val tn = gt.typeName
                val hit = FORBIDDEN_SIMPLE_NAMES.firstOrNull { f ->
                    Regex("[.<,]\\s*com\\.jiaoay\\.lark\\.gateway\\.$f\\b").containsMatchIn(tn) ||
                        Regex("^com\\.jiaoay\\.lark\\.gateway\\.$f\\b").containsMatchIn(tn) ||
                        tn.endsWith(".$f")
                }
                if (hit != null) forbiddenHits.add("${method.name} param(泛型) → $hit")
            }
            val rtn = method.genericReturnType.typeName
            val rHit = FORBIDDEN_SIMPLE_NAMES.firstOrNull { f ->
                Regex("[.<,]\\s*com\\.jiaoay\\.lark\\.gateway\\.$f\\b").containsMatchIn(rtn) ||
                    Regex("^com\\.jiaoay\\.lark\\.gateway\\.$f\\b").containsMatchIn(rtn) ||
                    rtn.endsWith(".$f")
            }
            if (rHit != null) forbiddenHits.add("${method.name} return(泛型) → $rHit")
        }
        // 接口自身可测性：方法数下限（当前 5 个抽象方法；跌破 = 接口被动过，守护面漂移）
        val abstractCount = surface.methods.count { it.declaringClass == surface }
        assertTrue(
            abstractCount >= MIN_SURFACE_METHODS,
            "NativeChatSurface 方法数异常（$abstractCount < $MIN_SURFACE_METHODS）——接口定位/反射失效",
        )
        assertTrue(
            forbiddenHits.isEmpty(),
            buildString {
                appendLine("NativeChatSurface 出现飞书专属类型：")
                forbiddenHits.forEach { appendLine("  - $it") }
                appendLine("渠道中立红线（KDoc 钉死「本接口不出现任何飞书类型」）：LarkTarget/CardSpec/CardJson")
                appendLine("不得出现在签名；跨会话值类型用批 A 渠道中立命名族（ChatInfo/ChatHistoryPage/…）。")
            },
        )
    }

    // ── 实现与常量 ──

    /** pipeline/ + handler/ 两个守护目录（lark-bridge 生产源集） */
    private fun scanDirs(root: File): List<File> =
        listOf("pipeline", "handler").map { File(root, "$LARK_BRIDGE_MAIN/kotlin/com/jiaoay/lark/bridge/$it") }

    private fun File.walkKtFiles(): List<File> =
        walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    /**
     * 工具 Args 反射：经 `Companion.serializer()` 的 descriptor 读序列化字段名。
     * 🔴 经 Companion.serializer() Java 反射取 KSerializer（探针已实证）——
     *    kotlinx 顶层 serializerOrNull(KClass) 是 @InternalSerializationApi 且 K2 下 KClass 值实参
     *    不参与重载解析（候选不适用），不可用。Companion.serializer() 是编译器插件生成的稳定静态面。
     *    探针正确性由 D-3 已知字段名样本锁死。
     */
    private fun serialFieldNames(loader: ClassLoader, toolFqcn: String): List<String> {
        val argsClass = Class.forName("$toolFqcn\$Args", false, loader)
        val companion = Class.forName("$toolFqcn\$Args\$Companion", false, loader)
        val companionInstance = argsClass.getDeclaredFields()
            .firstOrNull { it.type == companion }
            ?.apply { isAccessible = true }
            ?.get(null)
            ?: error("$toolFqcn.Args 的 Companion 实例不可达——类形态漂移，守护探针失效")
        val serializer = companion.getMethod("serializer").invoke(companionInstance)
            as kotlinx.serialization.KSerializer<*>
        val descriptor = serializer.descriptor
        return (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
    }

    /** 返回类型/参数类型的「飞书专属简单名」命中（词边界；渠道中立类型不命中）。 */
    private fun forbiddenSimpleNameOf(type: Class<*>): String? {
        val n = type.simpleName
        return FORBIDDEN_SIMPLE_NAMES.firstOrNull { f ->
            n == f || n.startsWith("${f}\$") || n.contains("$f\$") || n.contains("$f$")
        }
    }

    /** 工具类反射的 ClassLoader（当前线程 context loader 即 gradle test worker 的全量 classpath）。 */
    private fun toolClassLoader(): ClassLoader = Thread.currentThread().contextClassLoader


    /**
     * 归一化：剥离行注释 / 块注释（含 KDoc）/ 字符串字面量内容，保留换行。
     * 🔴 复制自 [LarkSdkIsolationTest] 的同名 private 成员（批 D 纪律 6：倾向复制——
     * 提取共享 util 会改既有守护文件，侵入面更大；两份实现各自由其 B-2/D-3 样本测试锁死）。
     */
    private fun normalizeKotlinSource(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
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
        const val LARK_BRIDGE_MAIN = "lark-bridge/src/main"
        const val NATIVE_SURFACE_FQCN = "com.jiaoay.lark.bridge.pipeline.NativeChatSurface"

        /** chat_id 的归一化比对 token（小写 + 去下划线后全等） */
        const val CHAT_ID_TOKEN = "chatid"

        /** pipeline/handler 扫描面下限（当前 28 个 .kt，60% 余量） */
        const val MIN_SCAN_FILES = 16

        /** NativeChatSurface 抽象方法数下限（当前 5：fetchChatHistory/chatInfoWithMembers/createGroupChat/lookupUser/readWikiDocument） */
        const val MIN_SURFACE_METHODS = 5

        /**
         * 白名单基线（🔴 从当前 HEAD 实况 grep 得出，非抄任务单）：
         * pipeline/ 内 CardSpec( 构造点恰 4 处、全部收在 CardJson.kt（卡片构造唯一入口）；
         * handler/ 零命中；两目录 LarkTarget.Chat( 零命中（构造只在 BridgeAssembler.kt:804 /
         * Bridge.kt:676 / cli/SendCommand.kt:84——均不在守护面）。
         * 匹配形态 = 「相对路径 ⇢ 归一化命中模式」。
         */
        val PIPELINE_CARD_SPEC_SINKS = setOf(
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/CardJson.kt ⇢ CardSpec(",
        )

        /**
         * 构造点匹配模式（归一化后 contains）：`LarkTarget.Chat(` 直接匹配构造调用；
         * `CardSpec(` 匹配构造调用（数据类无其他同名入口；` CardSpec` 类型声明不带 `(` 不命中）。
         */
        val CONSTRUCTOR_PATTERNS = listOf(
            "LarkTarget.Chat(",
            "CardSpec(",
        )

        /** L2 五工具（能力一级化批 C）——Args 无 chat_id */
        val L2_TOOL_CLASSES = listOf(
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.FetchChatHistoryTool" to "L2 拉历史（chatId 钉死 surface 闭包）",
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.GetChatInfoTool" to "L2 查群信息（chatId 钉死 surface 闭包）",
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.CreateGroupChatTool" to "L2 建群（天然跨会话，成员 open_ids 是内容不是目标会话）",
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.LookupUserTool" to "L2 查用户（open_id 是查询键不是会话）",
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.ReadWikiDocumentTool" to "L2 读文档（URL/token 是内容不是会话）",
        )

        /** L1 三工具（能力一级化批 B）——同形态守扩展 */
        val L1_TOOL_CLASSES = listOf(
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.SendImageTool" to "L1 发图片（出站面 reply.image）",
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.RecallOwnMessageTool" to "L1 撤回（账本双重守护）",
            "com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.ReplyInThreadTool" to "L1 话题回复（载体锚定）",
        )

        /** 飞书专属类型简单名（NativeChatSurface 签名禁入） */
        val FORBIDDEN_SIMPLE_NAMES = listOf("LarkTarget", "CardSpec", "CardJson")

        /**
         * 渠道中立命名族（批 A 命名——E 组的**允许**面，供读者对照禁入名单；判定按 FQCN 词边界
         * 匹配 FORBIDDEN_SIMPLE_NAMES，无需消费本名单）。此名单保留为文档性锚：加新渠道中立类型时
         * 在此登记即自证。
         */
        val ALLOWED_SIMPLE_NAMES = listOf(
            "ChatInfo", "ChatMemberBrief", "ChatMembersPage", "ChatCreation", "ChatMemberOpResult",
            "ChatHistoryItem", "ChatHistoryPage", "ReactionBrief", "ReadReceiptPage", "WikiNodeRef",
            "SenderProfile",
        )
    }
}
