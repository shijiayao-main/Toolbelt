package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 纯数据配置写工具：`sys.write_config`——LLM 只能选编译期写路径族（LL1a 级），
 * 族内相对路径必经 [ToolExecutionContext.authorizeWithGate] 围栏 + 族根 canonical
 * 包含性双闸，写入内容经逐写确认面人审（信任用户免卡直写）。
 *
 * ## 写路径族（编译期锁死）
 * 只收 `fcitx5_config`（`~/.config/fcitx5` 族内单文件写）。登录启动脚本类文件
 * （`~/.xprofile` 等）**永不入写族**——它们是每次登录被执行的持久化面，自由写等价于
 * 给自己造持久化后门（负面清单条目）；GNOME 扩展 manifest 亦不写（只读清单族）。
 * 加写族 = 改 [WriteFamily] 枚举并同步单测断言面，走提案评审。
 *
 * ## 写前三闸（缺一即拒，fail-closed）
 * 1. 信任用户域准入（名单外进不了域）+ 逐写确认面（[SysWriteGate]：信任用户免卡直写；
 *    非信任用户到不了本面）；
 * 2. 任务围栏 + 族根 canonical 包含性（族内符号链接穿越拒收）；
 * 3. **可完整展示性**：diff 超卡面展示预算（[DIFF_MAX_CHARS]）或任一侧超行/字节上界
 *    即拒绝执行——「注入载荷可藏尾部」的结构性消灭：不可完整展示的写入不确认，
 *    而非截断后展示。
 *
 * ## 回滚保障（先快照后确认后写入）
 * 旧文件存在时写前先落 `.bak-<时间戳>` 同目录快照；快照失败即拒执——
 * 「无回滚保障的写入不执行」。回滚配方（用户终端执行 cp 恢复，agent 不代执行）
 * 连同快照路径进确认卡、回执与审计（[SysAuditRecord.rollback]）。
 *
 * ## 输出脱敏与审计
 * diff 与回执恒过 [SysOutputSanitizer]（写入内容可能含凭据形态——确认卡是群内
 * 可见面，凭据形态不得经卡外泄）；每次执行（含被拒）落审计（[SysAuditLog]，
 * 含 .bak 路径，输出全文不落）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysWriteGate] 非 null（工厂返 null 不注册），
 * 恒不入常备清单。
 */
object SysWriteTools {

    const val WRITE_CONFIG_NAME = "sys.write_config"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 单文件上界（字节）：旧文件超界拒写、新内容超界拒写——超界内容无法完整展示 */
    internal const val MAX_FILE_BYTES = 256 * 1024

    /** diff 生成行数上界（任一侧）：纯数据配置写没有千行级文件，超界=非合理写入形态 */
    internal const val MAX_DIFF_LINES = 1000

    /**
     * diff 卡面展示预算（字符，脱敏后）：确认卡必须无截断地展示完整 diff——
     * 超预算即拒执（不可完整展示的写入不确认），不做「展示前 N 行」式截断。
     */
    internal const val DIFF_MAX_CHARS = 4096

    /**
     * 写路径族（编译期锁死）：[WRITE_FAMILY] 是唯一成员——族根一律 user.home 相对
     * 路径，族根由运行时 home 解析（单测经 user.home 重定向隔离）。
     */
    enum class WriteFamily(internal val rootPath: String) {
        FCITX5_CONFIG(".config/fcitx5"),
        ;

        /** LLM 入参形态的族名（enum name 的小写投影） */
        val id: String get() = name.lowercase()

        /** 族根（运行时 user.home 下绝对路径） */
        internal fun root(home: File): File = File(home, rootPath)
    }

    /** 唯一写族（sys.read_config 的 fcitx5_config 族同根；读族扩而写族不扩时读面更宽是有意分层） */
    val WRITE_FAMILY: WriteFamily = WriteFamily.FCITX5_CONFIG

    /** 写族根（运行时 user.home 下绝对路径）——确认卡/测试断言单源 */
    fun writeFamilyRoot(home: File): File = WRITE_FAMILY.root(home)

    /** 相对路径形态校验：复用 sys.read_config 的同款判据（绝对路径/~/.. /空段拒收） */
    internal fun relativeShapeError(raw: String): String? = SysReadConfigTools.relativeShapeError(raw)

    /**
     * 行级 diff（LCS，双侧各 ≤ [MAX_DIFF_LINES] 行）：产出 `- `（旧有新无）与
     * `+ `（新有旧无）行序列，公共行省略（上下文不进卡——卡面只承载变化）。
     */
    internal fun diffLines(old: List<String>, new: List<String>): List<String> {
        val n = old.size
        val m = new.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += "- ${old[i]}"; i++ }
                else -> { out += "+ ${new[j]}"; j++ }
            }
        }
        while (i < n) { out += "- ${old[i]}"; i++ }
        while (j < m) { out += "+ ${new[j]}"; j++ }
        return out
    }

    /** .bak 快照文件名后缀（毫秒级时间戳） */
    internal fun backupSuffix(now: LocalDateTime = LocalDateTime.now()): String =
        now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

    /**
     * 碰撞安全的 .bak 快照落点：毫秒级时间戳后缀在快速连写下可能重复，重名追加
     * 递增序号（`-1`/`-2`…）直到未占用——快照绝不因重名失败（快照失败 = 拒执写动作，
     * 不能让命名碰撞把合法写误判为不可回滚）。
     */
    internal fun collisionSafeBackup(target: File, now: LocalDateTime = LocalDateTime.now()): File {
        var backup = File(target.parentFile, "${target.name}.bak-${backupSuffix(now)}")
        var attempt = 0
        while (backup.exists()) {
            attempt++
            backup = File(target.parentFile, "${target.name}.bak-${backupSuffix(now)}-$attempt")
        }
        return backup
    }

    // ── 确认卡数据与授权面契约（装配链实现承担发卡/防替答/超时=拒绝） ──

    /**
     * 配置写确认面卡数据（卡面单源）：完整 diff（已脱敏）+ 目标路径 + .bak 快照路径 +
     * 回滚配方。确认卡 inputSummary 被服务侧截断装不下 diff——完整内容由实现经说明卡
     * 承载（sys.repair 动作卡同款两卡形态）；diff 超预算在工具层已拒，卡面恒完整。
     */
    data class WriteActionCard(
        val family: String,
        val targetPath: String,
        val backupPath: String?,
        val diff: String,
        val rollback: String,
    )

    /**
     * sys.write_config 逐写授权面（装配链侧契约）。
     * [requireSessionAuthorization] = 域准入（信任用户名单判定，名单外进不了域）；
     * [requireActionApproval] 对信任用户免卡直接放行；非信任用户被域准入拒绝、到不了本卡，
     * 两卡路径仅作装配兜底。
     */
    interface SysWriteGate : SysSessionGate {

        /**
         * 要求本次写入的用户确认（挂起直到裁决/超时）。
         *
         * @return true = 用户批准本次写入；false = 拒绝/超时/说明卡发送失败（fail-closed）
         */
        suspend fun requireActionApproval(card: WriteActionCard): Boolean
    }

    // ── 工具：sys.write_config ──

    class SysWriteConfigTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 逐写授权面（装配门控；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val writeGate: SysWriteGate? = null,
    ) : StructuredSimpleTool<SysWriteConfigTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = WRITE_CONFIG_NAME,
        description = "Overwrite a whitelisted LOCAL CONFIG FILE with new content (fcitx5 config " +
            "family only: files inside ~/.config/fcitx5). You can ONLY pick the compile-time " +
            "family and a relative path inside it, never a free path. Before writing, the " +
            "complete old-vs-new sanitized diff is prepared for the confirmation card; trusted " +
            "senders (access.trustedUsers) run this action without a confirmation card; " +
            "untrusted senders cannot enter this tool's domain. A timestamped .bak " +
            "snapshot is created automatically and the rollback recipe is reported. " +
            "Writes whose diff cannot be fully displayed (over the display budget) are rejected " +
            "whole, never truncated. Login-startup scripts (~/.xprofile etc.) are permanently " +
            "outside this write family. Verify afterwards with sys.read_config.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Config family key. Only fcitx5_config (files inside ~/.config/fcitx5) is " +
                    "writable — login-startup scripts and GNOME manifests are never writable.",
            )
            val family: String,
            @property:LLMDescription(
                "Relative path inside the fcitx5_config family root, e.g. config / profile / " +
                    "conf/pinyin.conf. The parent directory must already exist (directories are " +
                    "never created).",
            )
            val relative_path: String,
            @property:LLMDescription(
                "The COMPLETE new file content (plain text, INI style for fcitx5). The old-vs-new " +
                    "diff is shown to the user verbatim on a confirmation card — never embed " +
                    "credentials here.",
            )
            val content: String,
        )

        /** 执行包装体：结果 + 审计动作面 + 裁决覆写 + 回滚信息（.bak 路径与配方） */
        private data class Written(
            val result: ToolResult,
            val action: String,
            val verdictOverride: String? = null,
            val rollback: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val written = executeWritten(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = WRITE_CONFIG_NAME,
                    action = written.action,
                    verdict = written.verdictOverride ?: sysAuditVerdict(written.result),
                    exitCode = null,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(written.result),
                    rollback = written.rollback,
                ),
            )
            return written.result
        }

        private suspend fun executeWritten(args: Args): Written {
            // 域准入（会话档）：门控面缺席 = 装配缺陷，fail-closed 拒绝。
            val gate = writeGate ?: return Written(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "sys.write_config 未装配逐写确认门（fail-closed 拒绝）——本链不支持本机配置写入。",
                ),
                action = "family=${args.family}（门控面缺席）",
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(WRITE_CONFIG_NAME)) {
                return Written(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 sys.* 诊断域授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造写入结果。",
                    ),
                    action = "family=${args.family}（会话授权拒绝）",
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val family = WriteFamily.entries.firstOrNull { it.id == args.family.trim().lowercase() }
                ?: return Written(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知或不可写路径族「${args.family}」。本工具只能写编译期写路径族：" +
                            "${WRITE_FAMILY.id}（~/${WRITE_FAMILY.rootPath}）。" +
                            "登录启动脚本类文件（~/.xprofile / ~/.xinputrc 等）是每次登录被执行的" +
                            "持久化面，永不进写族——相关修复走 sys.repair 白名单动作或交用户本人执行；" +
                            "GNOME 扩展 manifest 为只读清单族，不可写。",
                    ),
                    action = "family=${args.family}（未命中写族）",
                )
            val relative = args.relative_path.trim()
            relativeShapeError(relative)?.let {
                return Written(
                    ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, it),
                    action = "family=${family.id}:$relative（相对路径形态拒收）",
                )
            }
            val newBytes = args.content.toByteArray(Charsets.UTF_8)
            if (newBytes.size > MAX_FILE_BYTES) {
                return Written(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "新内容超单文件上界（${newBytes.size}B > ${MAX_FILE_BYTES}B），整体拒绝写入——" +
                            "超界内容无法完整展示（不可完整展示的写入不确认）",
                    ),
                    action = "family=${family.id}:$relative（新内容超上界）",
                )
            }
            return withContext(Dispatchers.IO) {
                val home = File(System.getProperty("user.home"))
                val root = family.root(home)
                val target = File(root, relative)
                // 围栏双闸之一：任务围栏（路径参数不可信——幻觉路径/文件内容注入）
                val authorized = context.authorizeWithGate(target.path)
                    ?: return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            "sys.write_config 只写编译期写路径族内的文件，本次写入未获任务围栏放行。" +
                                "请确认族名与族内相对路径",
                        ),
                        action = "family=${family.id}:$relative（围栏拒绝）",
                        verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                    )
                // 围栏双闸之二：族根 canonical 包含性（族内符号链接穿越拒收，fail-safe）
                if (!SysReadConfigTools.isInsideAnyFamilyRoot(home, authorized)) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            "路径「${authorized.path}」解析后落在写族根之外（族内符号链接穿越拒收）。",
                        ),
                        action = "family=${family.id}:$relative（族根穿越拒绝）",
                        verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                    )
                }
                if (authorized.isDirectory) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "目标「${authorized.path}」是目录，拒绝以文件写入覆盖",
                        ),
                        action = "family=${family.id}:$relative（目标是目录）",
                    )
                }
                val parent = authorized.parentFile
                if (parent == null || !parent.isDirectory) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "目标父目录不存在——本工具拒绝代建目录，请只写既有目录内的文件",
                        ),
                        action = "family=${family.id}:$relative（父目录缺失）",
                    )
                }
                val exists = authorized.isFile
                if (exists && authorized.length() > MAX_FILE_BYTES) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "旧文件超单文件上界（${authorized.length()}B > ${MAX_FILE_BYTES}B），" +
                                "整体拒绝写入——超界旧文件无法完整 diff",
                        ),
                        action = "family=${family.id}:$relative（旧文件超上界）",
                    )
                }
                val oldContent = if (exists) authorized.readText() else ""
                if (exists && oldContent.lines().size > MAX_DIFF_LINES) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "旧文件超 diff 行数上界（> $MAX_DIFF_LINES 行），整体拒绝写入——" +
                                "不可完整展示的写入不确认",
                        ),
                        action = "family=${family.id}:$relative（旧文件超行数上界）",
                    )
                }
                val newLines = args.content.lines()
                if (newLines.size > MAX_DIFF_LINES) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "新内容超 diff 行数上界（> $MAX_DIFF_LINES 行），整体拒绝写入——" +
                                "不可完整展示的写入不确认",
                        ),
                        action = "family=${family.id}:$relative（新内容超行数上界）",
                    )
                }
                val action = "write ${family.id}:$relative"
                if (exists && oldContent == args.content) {
                    return@withContext Written(
                        ToolResult.Ok(
                            summary = "action=$action\npath=${authorized.path}\n" +
                                "内容无变化，未写入（无需确认卡）\n" +
                                "验证：sys.read_config（family=${family.id}, relative_path=$relative）复查",
                            data = ToolPayload.WriteConfigPayload(
                                family = family.id,
                                path = authorized.path,
                                backupPath = null,
                                changed = false,
                                truncated = false,
                            ),
                        ),
                        action = action,
                    )
                }

                // diff 生成 → 脱敏 → 预算校验（脱敏先于预算：遮蔽后长度仍超界才拒）
                val rawDiff = diffLines(oldContent.lines(), newLines)
                val diff = SysOutputSanitizer.sanitize(rawDiff.joinToString("\n"))
                val changedLines = rawDiff.count { it.startsWith("+ ") } to rawDiff.count { it.startsWith("- ") }
                if (diff.length > DIFF_MAX_CHARS) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "diff 超确认卡展示预算（${diff.length} > $DIFF_MAX_CHARS 字符），整体拒绝写入——" +
                                "不可完整展示的写入不确认（防注入载荷藏尾），请缩小改动范围或交用户本人执行",
                        ),
                        action = "$action（diff 超展示预算）",
                    )
                }

                // .bak 快照（先快照后确认后写入——回滚保障）：旧文件存在才有快照
                val backupPath: String?
                val rollback: String
                if (exists) {
                    val backup = collisionSafeBackup(authorized)
                    try {
                        authorized.copyTo(backup, overwrite = false)
                    } catch (e: IOException) {
                        return@withContext Written(
                            ToolResult.Err(
                                ToolResult.ErrCode.FAILED,
                                "无法创建 .bak 快照（${e.message ?: e.javaClass.simpleName}）——" +
                                    "拒绝执行无回滚保障的写入",
                                hint = "可先用 sys.read_config 核对该路径可读性后重试",
                            ),
                            action = action,
                        )
                    }
                    backupPath = backup.path
                    rollback = "回滚：由用户在终端执行 cp '${backup.path}' '${authorized.path}' 恢复写前快照" +
                        "（快照已落 ${backup.path}；本工具不代执行回滚）"
                } else {
                    backupPath = null
                    rollback = "回滚：本写入为新建文件——如需撤销由用户在终端删除 '${authorized.path}'" +
                        "（删除面不在本工具目录内，不代执行）"
                }

                // 逐写确认面：完整 diff（已脱敏、无截断）+ 目标 + 快照 + 回滚
                val card = WriteActionCard(
                    family = family.id,
                    targetPath = authorized.path,
                    backupPath = backupPath,
                    diff = diff,
                    rollback = rollback,
                )
                if (!gate.requireActionApproval(card)) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            "配置写入「${authorized.path}」未经用户确认（拒绝/超时/说明卡发送失败）——未写入。" +
                                "请如实告知用户可在其本人终端手动修改该文件，禁止未经确认重试。",
                        ),
                        action = action,
                        verdictOverride = SysAuditLog.VERDICT_ACTION_DENIED,
                        rollback = rollback,
                    )
                }
                try {
                    authorized.writeText(args.content)
                } catch (e: IOException) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "写入失败（${e.message ?: e.javaClass.simpleName}）——旧内容可从快照恢复：" +
                                (backupPath?.let { "cp '$it' '${authorized.path}'" } ?: "本写入为新建文件，无旧内容"),
                            hint = "如实告知用户写入失败与快照位置，禁止编造写入结果",
                        ),
                        action = action,
                        rollback = rollback,
                    )
                }
                Written(renderReceipt(family, relative, authorized.path, backupPath, changedLines, diff, rollback), action = action, rollback = rollback)
            }
        }

        /** 结构化回执：action/path/backup/changed + diff（恒脱敏 + 8K capAt 收口）+ 验证提示 */
        private fun renderReceipt(
            family: WriteFamily,
            relative: String,
            path: String,
            backupPath: String?,
            changed: Pair<Int, Int>,
            diff: String,
            rollback: String,
        ): ToolResult {
            val body = buildString {
                appendLine("action=write ${family.id}:$relative")
                appendLine("path=$path")
                appendLine("backup=${backupPath ?: "（新建文件，无写前快照）"}")
                appendLine("changed=+${changed.first}/-${changed.second} 行")
                appendLine("rollback: $rollback")
                appendLine("--- diff ---")
                append(diff)
                appendLine()
                append("验证：sys.read_config（family=${family.id}, relative_path=$relative）复查写入结果；" +
                    "fcitx5 配置改动需重启输入法生效（sys.repair 的 fcitx5_restart_daemon 或交用户本人）")
            }
            val capped = capAt(body)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.WriteConfigPayload(
                    family = family.id,
                    path = path,
                    backupPath = backupPath,
                    changed = true,
                    truncated = capped != body,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `sys.write_config` 目录描述符。risk = DESTRUCTIVE（写盘面——权限引擎与外放门控依据）；
     * uiHint = null：纯 agent 工具，不进命令面板。装配门控：config.sysWriteGate 非 null 才
     * 注册（工厂返 null），恒不入常备清单。
     */
    val SYS_WRITE_CONFIG: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = WRITE_CONFIG_NAME,
        description = "Overwrite a whitelisted local config file (fcitx5 config family only) — " +
            "family + relative path only, never free paths; trusted senders run this action " +
            "without a confirmation card and untrusted senders cannot enter this tool's domain; " +
            "timestamped .bak snapshot before write; login-startup " +
            "scripts are permanently excluded",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        factory = { context, sink, config ->
            config.sysWriteGate?.let { gate ->
                SysWriteConfigTool(context, sink, writeGate = gate, trigger = gate.trigger)
            }
        },
    )
}
