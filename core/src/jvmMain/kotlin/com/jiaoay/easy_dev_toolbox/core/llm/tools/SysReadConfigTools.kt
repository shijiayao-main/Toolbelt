package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 窄路径只读配置族工具：`sys.read_config`——LLM 只能选编译期路径族，族内相对路径
 * 必经 [ToolExecutionContext.authorizeWithGate] 围栏 + 族根 canonical 包含性双闸。
 *
 * ## 路径族白名单（编译期锁死，[ConfigFamily]）
 * - `fcitx5_config`：`~/.config/fcitx5` 族内子树单文件只读（LLM 供族内相对路径）；
 * - `xprofile`：`~/.xprofile` 固定单文件只读；
 * - `gnome_extensions_manifest`：`~/.local/share/gnome-shell/extensions/` 的
 *   manifest 摘要（逐扩展抽 metadata.json 的 uuid/name/version/shell-version/
 *   description/url 字段行，不回灌全文）。
 * 整个 `~/.config` 级粗根**永不入族**（内混凭据目录——github-copilot 等 token
 * 载体）；加族 = 改 [ConfigFamily] 枚举并同步单测断言面，走提案评审。
 *
 * ## 双闸语义（缺一即拒）
 * 1. 任务围栏 [ToolExecutionContext.authorizeWithGate]：路径参数不可信（幻觉路径/
 *    文件内容注入），未命中允许集/允许根且 HITL 门拒绝（或无门）即 DENIED；
 * 2. 族根 canonical 包含性：围栏放行（含 HITL 批准）后仍校验 canonical 路径落在
 *    族根内——族内符号链接指向族外（穿越到凭据路径）拒收，fail-safe。
 *
 * ## 读取上界（真门禁）
 * 单文件超 [MAX_FILE_BYTES] = 256KB 整体拒绝、不读头部——半份配置无诊断价值，
 * 且「超界只读头部」是可被反复利用的回读面（大凭据文件头部即泄露）。
 * 回执恒经 [LogStatsDiffTools.capAt] 收口 ≤ [MAX_OUTPUT_CHARS] = 8K 字符。
 *
 * ## 输出脱敏与审计
 * 内容恒过 [SysOutputSanitizer]（token/key/password 行级遮蔽）；每次执行（含被拒）
 * 落审计（[SysAuditLog]，输出全文不落）。
 *
 * ## 信任用户域准入与族根放行
 * 执行前必过 [SysSessionGate.requireSessionAuthorization]（名单内放行、名单外进不了域）；
 * 门控面缺席即 DENIED（fail-closed）。族根放行 = 名单内用户把族根 register 进 sys 专用
 * context 允许集 + 族内相对路径经 [SysSessionGate.familyEscapeGate] 缝按 canonical 包含性
 * 放行（与授权模型解耦）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysSessionGate] 非 null（工厂返 null 不注册）。
 */
object SysReadConfigTools {

    const val READ_CONFIG_NAME = "sys.read_config"

    /** 单文件读取上界（字节）：超界整体拒绝（拒绝只读头部——依据见对象 KDoc「读取上界」） */
    internal const val MAX_FILE_BYTES = 256 * 1024

    /** 回执长度结构性上界（字符）——sys.probe 同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** manifest 摘要里 description 字段的截断上界（字符）——摘要形态不回灌全文 */
    private const val MANIFEST_FIELD_MAX_CHARS = 200

    /**
     * 路径族（编译期锁死）：族根一律 user.home 相对路径——族根由运行时 home 解析，
     * 单测经 user.home 重定向隔离（UserHomeIsolation）。
     *
     * @param rootPath user.home 下的族根相对路径（固定单文件族 = 文件相对路径）
     * @param takesRelative 该族是否接受族内相对路径入参
     */
    enum class ConfigFamily(
        internal val rootPath: String,
        internal val takesRelative: Boolean,
    ) {
        FCITX5_CONFIG(".config/fcitx5", takesRelative = true),
        XPROFILE(".xprofile", takesRelative = false),
        GNOME_EXTENSIONS_MANIFEST(".local/share/gnome-shell/extensions", takesRelative = false),
        ;

        /** LLM 入参形态的族名（enum name 的小写投影） */
        val id: String get() = name.lowercase()

        /** 族根/族固定文件（运行时 user.home 下绝对路径） */
        internal fun root(home: File): File = File(home, rootPath)
    }

    /** 族内相对路径形态校验：返回 null = 合法；非 null = 人话拒绝理由 */
    internal fun relativeShapeError(raw: String): String? {
        val rel = raw.trim()
        if (rel.isEmpty()) return "族内相对路径不能为空"
        if (rel.startsWith("~")) return "族内相对路径不得以 ~ 开头（家目录展开由族根负责）"
        if (File(rel).isAbsolute) return "族内相对路径不得是绝对路径（族根已定死，只供族内相对路径）"
        val segments = rel.split('/')
        if (segments.any { it == ".." }) return "族内相对路径不得含 .. 段（族根外穿越拒收）"
        if (segments.any { it.isEmpty() }) return "族内相对路径不得含空段（// 或首尾 / 拒收）"
        return null
    }

    /**
     * 全部族根（运行时 user.home 下绝对路径）——会话门控实现的单源：
     * 授权批准后把这些族根 register 进 sys 专用 context 的允许集（族根粒度 = 授权卡声明的族）。
     */
    fun familyRoots(home: File): List<File> = ConfigFamily.entries.map { it.root(home) }

    /**
     * canonical 包含性：candidate 是否落在任一族根内（分隔符边界，含族根自身）——
     * [SysSessionGate.familyEscapeGate] 缝的族内相对路径放行判据。
     */
    fun isInsideAnyFamilyRoot(home: File, candidate: File): Boolean =
        ConfigFamily.entries.any { isInsideRoot(it.root(home), candidate) }

    /** 族展示行（族名 + user.home 相对族根） */
    fun familyLabels(): List<String> = ConfigFamily.entries.map { "${it.id}（~/${it.rootPath}）" }

    /** canonical 包含性（分隔符边界）：candidate 是否落在 root 内（含 root 自身） */
    private fun isInsideRoot(root: File, candidate: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val candidatePath = runCatching { candidate.canonicalPath }.getOrNull() ?: return false
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    /** 族读取的围栏拒绝文案（族域语义——通用 deniedHint 的拖入产物措辞不适用） */
    private val familyDeniedHint: String =
        "sys.read_config 只读编译期路径族内的文件，本次读取未获任务围栏放行。" +
            "请确认族名与族内相对路径，或改用 sys.probe 的只读探查条目"

    class SysReadConfigTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 会话级门控面（P1c 装配门控；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val sysGate: SysSessionGate? = null,
    ) : StructuredSimpleTool<SysReadConfigTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = READ_CONFIG_NAME,
        description = "Read a whitelisted READ-ONLY local config family for desktop-environment " +
            "diagnostics. You can ONLY pick a compile-time family key, never a free path. " +
            "Families: fcitx5_config (needs relative_path inside ~/.config/fcitx5, e.g. " +
            "config / profile / conf/pinyin.conf), xprofile (~/.xprofile), " +
            "gnome_extensions_manifest (digest of installed GNOME extension metadata). " +
            "Relative paths go through the task fence; files larger than 256KB are rejected " +
            "whole; output is secret-masked (token/key/password) and capped at 8K chars. " +
            "A missing file is diagnostic DATA (not configured), not failure.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Config family key: fcitx5_config / xprofile / gnome_extensions_manifest. " +
                    "Pick from the family list only — a free path is never accepted.",
            )
            val family: String,
            @property:LLMDescription(
                "Relative path inside the fcitx5_config family root, e.g. config / profile / " +
                    "conf/pinyin.conf. Only meaningful for fcitx5_config (rejected otherwise).",
            )
            val relative_path: String? = null,
        )

        /** 执行包装体：结果 + 裁决覆写（会话授权拒绝等非执行裁决，审计分类用） */
        private data class Gated(val result: ToolResult, val verdictOverride: String? = null)

        override suspend fun executeTyped(args: Args): ToolResult {
            val gated = executeAndRender(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = READ_CONFIG_NAME,
                    action = "read ${args.family}" + (args.relative_path?.let { ":$it" } ?: ""),
                    verdict = gated.verdictOverride ?: sysAuditVerdict(gated.result),
                    trigger = trigger,
                    outputSummary = sysAuditSummary(gated.result),
                ),
            )
            return gated.result
        }

        private suspend fun executeAndRender(args: Args): Gated {
            // 会话级总授权前置（P1c）：门控面缺席 = 装配缺陷，fail-closed 拒绝（同 sys.probe）。
            val gate = sysGate ?: return Gated(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "sys.* 只读诊断未装配会话门控面（fail-closed 拒绝）——本链不支持本机只读诊断。",
                ),
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(READ_CONFIG_NAME)) {
                return Gated(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 sys.* 只读诊断授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造配置内容。",
                    ),
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val family = ConfigFamily.entries.firstOrNull { it.id == args.family.trim().lowercase() }
                ?: return Gated(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知路径族「${args.family}」。本工具只能从编译期路径族里选，不能自造路径。" +
                            "可用路径族：${ConfigFamily.entries.joinToString("、") { it.id }}",
                    ),
                )
            if (!family.takesRelative && args.relative_path != null) {
                return Gated(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "路径族 ${family.id} 不接受 relative_path（该族读取固定目标/整族清单，无族内路径槽）",
                    ),
                )
            }
            return when (family) {
                ConfigFamily.FCITX5_CONFIG -> Gated(
                    readFamilyFile(
                        family,
                        args.relative_path?.trim().orEmpty(),
                    ),
                )
                ConfigFamily.XPROFILE -> Gated(readFamilyFile(family, ""))
                ConfigFamily.GNOME_EXTENSIONS_MANIFEST -> Gated(readExtensionManifests(family))
            }
        }

        /**
         * 族内单文件读取（fcitx5_config 供相对路径；xprofile 传族固定文件名）。
         * [relative] 为空（fcitx5 缺槽）走 BAD_ARGS。
         */
        private suspend fun readFamilyFile(family: ConfigFamily, relative: String): ToolResult =
            withContext(Dispatchers.IO) {
                if (family.takesRelative && relative.isEmpty()) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "路径族 ${family.id} 需要族内相对路径 relative_path" +
                            "（如 config / profile / conf/pinyin.conf）",
                    )
                }
                if (family.takesRelative) {
                    relativeShapeError(relative)?.let {
                        return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, it)
                    }
                }
                val home = File(System.getProperty("user.home"))
                val root = family.root(home)
                val target = if (family.takesRelative) File(root, relative) else root
                val authorized = context.authorizeWithGate(target.path)
                    ?: return@withContext ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        familyDeniedHint,
                    )
                if (!isInsideRoot(root, authorized)) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "路径「${authorized.path}」解析后落在族根之外（族内符号链接穿越拒收）。" + familyDeniedHint,
                    )
                }
                if (!authorized.isFile) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "「${authorized.path}」不存在（不存在本身是诊断答案：该配置未生成/未配置）",
                        hint = "可改用 sys.probe 探查运行态（如 im_env_vars / im_config_mode 条目），" +
                            "或如实告知用户该配置文件不存在（禁止编造内容）",
                    )
                }
                val size = authorized.length()
                if (size > MAX_FILE_BYTES) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "文件超读取上界（${size}B > ${MAX_FILE_BYTES}B），整体拒绝读取——" +
                            "超界文件不做头部预读（无诊断价值的半份内容 + 可利用的回读面）",
                        hint = "可改用 sys.probe 的运行态探查替代读文件，或如实告知用户该文件过大",
                    )
                }
                val content = authorized.readText()
                renderFileReceipt(family, authorized.path, size, content)
            }

        /**
         * GNOME 扩展清单摘要：逐扩展抽 metadata.json 的摘要字段行（不回灌 JSON 全文）。
         * 围栏授权族根一次；每个 metadata.json 读前做 canonical 包含性与大小校验。
         */
        private suspend fun readExtensionManifests(family: ConfigFamily): ToolResult =
            withContext(Dispatchers.IO) {
                val home = File(System.getProperty("user.home"))
                val root = family.root(home)
                val authorizedRoot = context.authorizeWithGate(root.path)
                    ?: return@withContext ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        familyDeniedHint,
                    )
                if (!isInsideRoot(root, authorizedRoot)) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "扩展目录解析后落在族根之外（符号链接穿越拒收）。" + familyDeniedHint,
                    )
                }
                if (!authorizedRoot.isDirectory) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "GNOME 扩展目录「${authorizedRoot.path}」不存在（未安装任何用户扩展——本身是诊断答案）",
                        hint = "可用 sys.probe 的 gnome_extensions_list 条目核对已装扩展清单（含系统级）",
                    )
                }
                val dirs = authorizedRoot.listFiles { f -> f.isDirectory }
                    ?.sortedBy { it.name }
                    .orEmpty()
                val body = buildString {
                    appendLine("family=${family.id}")
                    appendLine("root=${authorizedRoot.path}")
                    appendLine("extensions=${dirs.size}")
                    appendLine("--- manifests ---")
                    if (dirs.isEmpty()) {
                        appendLine("（扩展目录为空——未安装任何用户扩展，本身是诊断答案）")
                    }
                    for (dir in dirs) {
                        manifestLines(dir, authorizedRoot).forEach { appendLine(it) }
                    }
                }
                val capped = capAt(body)
                ToolResult.Ok(
                    summary = capped.trim(),
                    data = ToolPayload.ReadConfigPayload(
                        family = family.id,
                        path = authorizedRoot.path,
                        truncated = capped != body,
                    ),
                )
            }

        /** 单个扩展的 manifest 摘要行（解析失败/缺失/穿越各有显式行——不静默跳过） */
        private fun manifestLines(dir: File, authorizedRoot: File): List<String> {
            val meta = File(dir, "metadata.json")
            if (!isInsideRoot(authorizedRoot, meta)) {
                return listOf("uuid=${dir.name} 符号链接穿越，拒读")
            }
            if (!meta.isFile) {
                return listOf("uuid=${dir.name} metadata.json 缺失（扩展目录不完整）")
            }
            val size = meta.length()
            if (size > MAX_FILE_BYTES) {
                return listOf("uuid=${dir.name} metadata.json 超上界（${size}B），拒读")
            }
            val fields = runCatching { parseManifestFields(meta, dir.name) }.getOrNull()
                ?: return listOf("uuid=${dir.name} metadata.json 不可解析（非 JSON 形态）")
            return listOf(
                "uuid=${fields.uuid} name=${fields.name} version=${fields.version} " +
                    "shell-version=${fields.shellVersion}",
                "  description=${fields.description}",
                "  url=${fields.url}",
            )
        }

        /** 单文件读取回执：family/path/size 头 + 脱敏后内容（恒 8K capAt 收口） */
        private fun renderFileReceipt(
            family: ConfigFamily,
            path: String,
            size: Long,
            content: String,
        ): ToolResult {
            val sanitized = SysOutputSanitizer.sanitize(content)
            val body = buildString {
                appendLine("family=${family.id}")
                appendLine("path=$path")
                appendLine("size=${size}B")
                appendLine("--- content ---")
                append(sanitized)
            }
            val capped = capAt(body)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.ReadConfigPayload(
                    family = family.id,
                    path = path,
                    truncated = capped != body,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── manifest 解析（纯函数：JsonElement 抽字段，不建 schema 类——字段漂移不炸解析） ──

    /** manifest 摘要字段行（缺失字段的兜底语义见 [parseManifestFields]） */
    private data class ManifestFields(
        val uuid: String,
        val name: String,
        val version: String,
        val shellVersion: String,
        val description: String,
        val url: String,
    )

    /** metadata.json → 摘要字段行；uuid 缺失兜底目录名，其余缺失记为（未知） */
    private fun parseManifestFields(meta: File, dirName: String): ManifestFields {
        val root = Json.parseToJsonElement(meta.readText()).jsonObject
        fun str(field: String): String? =
            root[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val shellVersions = root["shell-version"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        return ManifestFields(
            uuid = str("uuid") ?: dirName,
            name = str("name") ?: "（未知）",
            version = root["version"]?.jsonPrimitive?.let {
                it.intOrNull?.toString() ?: it.contentOrNull
            } ?: "（未知）",
            shellVersion = if (shellVersions.isEmpty()) "（未知）" else shellVersions.joinToString(".."),
            description = str("description")?.take(MANIFEST_FIELD_MAX_CHARS) ?: "（未知）",
            url = str("url") ?: "（未知）",
        )
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `sys.read_config` 目录描述符。uiHint = null：纯 agent 工具（诊断链自用），
     * 不进命令面板。装配门控（P1c）：config.sysSessionGate 非 null 才注册（工厂返 null）；
     * 执行前必过信任用户域准入 + 族根放行（工具内 fail-closed 兜底）。
     */
    val SYS_READ_CONFIG: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = READ_CONFIG_NAME,
        description = "Read a whitelisted read-only local config family (fcitx5 config files / " +
            "~/.xprofile / GNOME extension manifest digest) — family keys only, never free " +
            "paths; 256KB per-file cap; output secret-masked and capped at 8K chars",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { context, sink, config ->
            config.sysSessionGate?.let { gate ->
                SysReadConfigTool(context, sink, sysGate = gate, trigger = gate.trigger)
            }
        },
    )
}
