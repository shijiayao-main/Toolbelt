package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.io.File

/**
 * 任务级路径围栏：
 *
 * LLM 的工具调用参数（file_path/dir_path）不可信——两类风险：
 * 1. LLM 幻觉路径（编造系统敏感路径）；
 * 2. 文件内容注入：preview_text 的结果会回流 LLM 上下文，恶意日志文本可诱导
 *    后续工具调用任意路径（间接 prompt injection 的落地面）。
 *
 * 围栏语义：允许集初始 = [initialAllowed]（单文件任务 = {inputFile} 单元素；飞书桥接调度 agent =
 * 本消息引用文件 + 其下载目录产物，多文件场景）；decrypt/extract 的产物自动登记进允许集
 * （解密/解包结果可继续 preview/list）；工具的路径参数必须命中允许集，否则工具返回错误文案
 * （成为 LLM 的工具反馈，LLM 可自行调整，不崩任务）。
 *
 * 允许根目录集合（[allowedRoots]）：
 * - 命中任一允许根（canonical 前缀比对）的路径**直接放行且不走 HITL gate**——信任边界从
 *   「运行时逐路径确认」前移到「配置时一次授权整个目录」（源码目录场景：agent 读全目录
 *   避免逐文件弹窗风暴——几十上百次 read_file 逐个确认不可用）；
 * - 🔴 消费面钉死**只有桌面链**（蒸馏/agent 会话）注入源码根；桥接链恒不注入
 *    （源码目录进桥接允许集 = 用户整个源码项目可被发到飞书群——出站旁路）；
 * - 蓝本 = lark-bridge WorkspacePolicy（前缀比对含分隔符边界 + Windows 大小写归一 +
 *   ~ 展开 + 符号链接 canonical 后落在根外即拒绝的 fail-safe），逻辑平移不引依赖。
 *
 * HITL 接缝（参照 deepagents permissions 的 interrupt 语义）：
 * [escapeGate] 非空时，[authorizeWithGate] 对「未命中允许集」的路径**挂起询问**用户
 * （Compose Dialog / 未来飞书确认卡），批准即临时登记进允许集并放行，拒绝则照旧返回 null
 * → 工具回 DENIED 文案（LLM 可自行调整）。gate 为 null = 既有拒绝语义零变化（桥接默认形态）。
 * 取消任务时 gate 随 Job 取消自动中断（CE 穿透），无泄漏。
 */
class ToolExecutionContext private constructor(
    initialAllowed: Collection<File>,
    allowedRoots: Collection<File> = emptyList(),
    private val escapeGate: PathEscapeGate? = null,
) {
    private val allowed = initialAllowed.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toMutableSet()

    /**
     * 允许根目录集合：构造时一次性 ~ 展开 + canonical 解析后缓存，
     * 校验为纯字符串前缀比对（分隔符边界——/foo/bar 不放行 /foo/barbaz）。
     * 不可解析的根静默跳过（与允许集登记同容错语义）。
     */
    private val allowedRootPaths: List<String> = allowedRoots
        .mapNotNull { root ->
            runCatching { File(expandTildePath(root.path)).canonicalPath }.getOrNull()
        }
        .distinct()

    /** 当前平台是否 Windows（前缀比对大小写归一分支；构造一次缓存） */
    private val windows = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * 主输入文件（产物定位读它——兄弟产物 = parentFile 里同前缀新文件）。
     * 集合形态（桥接多文件）时为允许集**首个文件**（桥接工具链不读此属性）。
     * 空初始集（会话形态）时为 null——调用方按需判空。
     */
    val inputFile: File? = initialAllowed.firstOrNull()?.canonicalFile

    /** 路径是否命中任一允许根（canonical 前缀比对；根列表空时恒 false） */
    private fun isInsideAllowedRoot(path: File): Boolean {
        if (allowedRootPaths.isEmpty()) return false
        val candidate = runCatching { path.canonicalPath }.getOrNull() ?: return false
        return allowedRootPaths.any { root ->
            val prefix = if (root.endsWith(File.separator)) root else root + File.separator
            if (windows) {
                // Windows 尾段拼写保留问题（与 WorkspacePolicy 同款坑）：
                //    canonicalPath 对不存在路径尾段保留输入大小写——NTFS case-insensitive，
                //    归一为大小写不敏感比较属平台语义而非放宽。
                candidate.equals(root, ignoreCase = true) || candidate.startsWith(prefix, ignoreCase = true)
            } else {
                candidate == root || candidate.startsWith(prefix)
            }
        }
    }

    /** 路径是否在围栏内（精确允许集 ∨ 允许根前缀；canonical 比较，容忍相对路径与符号链接） */
    fun isAllowed(path: File): Boolean = runCatching {
        val canonical = path.canonicalFile
        canonical in allowed || isInsideAllowedRoot(canonical)
    }.getOrDefault(false)

    /** 产物登记（解密输出文件 / 解包目录与其直接内容），登记后即可被后续工具访问 */
    fun register(file: File) {
        runCatching {
            allowed += file.canonicalFile
        }
    }

    /** 解包目录内容登记：目录与其直接子项全部入允许集（SafeExtract 产物一层） */
    fun registerArchiveContents(destDir: File, extracted: List<File>) {
        register(destDir)
        extracted.forEach { register(it) }
    }

    /** 围栏校验 + 通过即返回 canonical File；未命中返回 null（调用方翻译为工具错误文案） */
    fun authorize(path: String): File? {
        val file = File(path)
        return file.takeIf { isAllowed(it) }?.canonicalFile
    }

    /**
     * 带人工确认门的围栏校验（HITL 接缝）：
     * - 命中允许集**或允许根** → 直接放行（允许根是配置时已授权的目录，无需再询问）；
     * - 未命中且配置了 [escapeGate] → **挂起询问**：批准 = register + 返回 canonical（本次放行，
     *   后续同路径不再询问）；拒绝 = 返回 null（工具照旧回 DENIED 文案）；
     * - 未命中且无 gate → 返回 null（既有拒绝语义零变化）。
     *
     * gate 挂起期间任务取消（CE）会穿透 [kotlinx.coroutines.suspendCancellableCoroutine]
     *    的挂起点自动中断——调用方无需额外清理。
     */
    suspend fun authorizeWithGate(path: String): File? {
        authorize(path)?.let { return it }
        val gate = escapeGate ?: return null
        val file = File(path)
        val approved = gate.onRequestAccess(file)
        if (!approved) return null
        register(file)
        return runCatching { file.canonicalFile }.getOrDefault(file)
    }

    /** 当前允许集大小（观测/测试用——不含允许根目录的树内文件） */
    val allowedCount: Int get() = allowed.size

    /** 允许根目录数（观测/测试/桥接隔离守护用：桥接链构造的 context 恒为 0） */
    val allowedRootsCount: Int get() = allowedRootPaths.size

    /** HITL 确认门：围栏外路径访问请求的用户裁决回调（Compose Dialog / 飞书确认卡的统一接缝） */
    fun interface PathEscapeGate {
        /** 返回 true = 批准本次访问（路径将临时登记进允许集）；false / CE = 拒绝 */
        suspend fun onRequestAccess(path: File): Boolean
    }

    companion object {
        /** 单文件初始允许集（无 gate 拒绝语义）。 */
        operator fun invoke(inputFile: File): ToolExecutionContext = ToolExecutionContext(listOf(inputFile))

        /** 多文件初始允许集（飞书桥接调度 agent：引用文件 path/dir/artifacts 全量）。 */
        operator fun invoke(initialAllowed: Collection<File>): ToolExecutionContext =
            ToolExecutionContext(initialAllowed.toList())

        /**
         * 带 HITL 确认门的构造——
         * agent 请求围栏外路径时挂起弹确认（approve → register；deny → DENIED 文案）。
         */
        operator fun invoke(inputFile: File, escapeGate: PathEscapeGate): ToolExecutionContext =
            ToolExecutionContext(listOf(inputFile), escapeGate = escapeGate)

        /**
         * 空初始集 + 可选 gate（会话形态：会话先建 context，附件到达后 register 进允许集——
         * 对话式任务流「页面即一个会话」的渐进登记语义）。
         */
        operator fun invoke(escapeGate: PathEscapeGate? = null): ToolExecutionContext =
            ToolExecutionContext(emptyList(), escapeGate = escapeGate)

        /**
         * 会话形态 + 允许根目录集合：桌面链装配点注入源码目录——
         * 源码树内路径免逐文件 HITL。🔴 桥接链装配点恒不注入源码根（守护测试钉死）。
         *
         * 🔴 命名工厂而非 operator invoke 重载：单参 List<File> 会与既有的
         *    invoke(Collection<File>) 产生重载歧义（编译器优先选更具体的 List 重载），
         *    桥接调用点 ToolExecutionContext(allowedFiles) 会被静默劫持进本构造——
         *    出站旁路防线不可依赖「调用点写法自觉」。命名工厂消歧，既有调用点零改动。
         */
        fun withAllowedRoots(
            allowedRoots: List<File>,
            escapeGate: PathEscapeGate? = null,
        ): ToolExecutionContext = ToolExecutionContext(emptyList(), allowedRoots, escapeGate)
    }
}

/** `~` 前缀展开（平移 lark-bridge Paths.expandTilde 语义：~、~/x、~\x → 主目录）——顶层私有件供构造期消费 */
private fun expandTildePath(path: String): String {
    val home = System.getProperty("user.home")
    if (path == "~") return home
    val rest = path.takeIf { it.startsWith("~/") || it.startsWith("~\\") }
        ?.let { it.drop(1) }
        ?: return path
    return home + rest
}
