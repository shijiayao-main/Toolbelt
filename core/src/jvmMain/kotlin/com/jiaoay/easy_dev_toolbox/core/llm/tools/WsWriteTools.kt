package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 工作区写文件工具：`ws.write_file`——LLM 只收**工作区根内相对路径**（不收绝对路径），
 * 写入内容全量经 diff 确认卡人审 + .bak 快照回滚保障。
 *
 * ## 三闸配方（复用 [SysWriteTools] 的 L1a 配方，写域从 fcitx5_config 换成工作区根）
 * 1. 信任用户域准入（[WsSessionGate]，名单外进不了域）+ 逐写确认面（[WsWriteGate]：
 *    信任用户免卡直写；非信任用户到不了本面）；
 * 2. 任务围栏（authorizeWithGate——批准即 register）+ 工作区根 canonical 包含性双闸
 *    （工作区根严禁进 [ToolExecutionContext.allowedRoots]——allowedRoots = 免 gate
 *    直接放行 + 出站旁路，工作区根进 allowedRoots 会绕过 send_file 的授权语义）；
 * 3. 可完整展示性（复用 [SysWriteTools.diffLines] / MAX_FILE_BYTES / MAX_DIFF_LINES /
 *    DIFF_MAX_CHARS 预算）——不可完整展示的写入不确认，整体拒执。
 *
 * ## 回滚保障（先快照后确认后写入）
 * 旧文件存在时写前落 .bak 快照（[SysWriteTools.collisionSafeBackup]）；快照失败即拒执。
 *
 * ## 与 send_file 出站链衔接
 * 写成功即 context.register 登记进本消息围栏——产物可直接 send_file 交付；
 * 出站侧敏感排除表（SensitiveFilePolicy）对 send_file 恒一致拦截，写文件本身不出站。
 *
 * ## 目录语义
 * 拒绝代建目录（父目录必须已存在，[SysWriteConfigTool] 同款）。
 */
object WsWriteTools {

    const val WRITE_FILE_NAME = "ws.write_file"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    // ── 确认卡数据与授权面契约（装配链实现承担发卡/防替答/超时=拒绝） ──

    /**
     * 工作区写确认卡数据（卡面单源）：完整 diff（已脱敏）+ 目标路径 + .bak 快照路径 +
     * 回滚配方。完整内容由实现经说明卡承载（SysWriteTools.WriteActionCard 两卡形态同款）。
     */
    data class WsWriteActionCard(
        val targetPath: String,
        val backupPath: String?,
        val diff: String,
        val rollback: String,
    )

    /**
     * ws.write_file 逐写授权面（装配链侧契约）。[requireSessionAuthorization] = 域准入
     * （信任用户名单判定）；[requireActionApproval] 对信任用户免卡直接放行；非信任用户被
     * 域准入拒绝、到不了本卡，两卡路径仅作装配兜底。
     */
    interface WsWriteGate : WsSessionGate {

        /**
         * 要求本次写入的用户确认（挂起直到裁决/超时）。
         *
         * @return true = 用户批准本次写入；false = 拒绝/超时/说明卡发送失败（fail-closed）
         */
        suspend fun requireActionApproval(card: WsWriteActionCard): Boolean
    }

    // ── 工具：ws.write_file ──

    class WsWriteFileTool(
        private val context: ToolExecutionContext,
        private val gate: WsWriteGate,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val audit: SysAuditLog = SysAuditLog(),
    ) : StructuredSimpleTool<WsWriteFileTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = WRITE_FILE_NAME,
        description = "Write (create or overwrite) a file INSIDE THE CONFIGURED WORKSPACE. " +
            "You must pass a relative path inside the workspace root (never an absolute path, " +
            "never a path outside it) and the COMPLETE new file content. Before writing, a " +
            "per-write confirmation card carrying the complete sanitized old-vs-new diff is " +
            "required (session grant never covers writes); a timestamped .bak snapshot of the " +
            "old file is created automatically. Writes whose diff cannot be fully displayed " +
            "are rejected whole. Parent directories are never created. After a successful " +
            "write the file is registered in this message's fence and can be delivered " +
            "directly with send_file.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Relative path inside the workspace root (e.g. reports/summary.md). " +
                    "Never absolute, never ../ outside the root. The parent directory must " +
                    "already exist (directories are never created).",
            )
            val relative_path: String,
            @property:LLMDescription(
                "The COMPLETE new file content. The old-vs-new diff is shown to the user " +
                    "verbatim on a confirmation card — never embed credentials here.",
            )
            val content: String,
        )

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
                    tool = WRITE_FILE_NAME,
                    action = written.action,
                    verdict = written.verdictOverride ?: sysAuditVerdict(written.result),
                    exitCode = null,
                    trigger = gate.trigger,
                    outputSummary = sysAuditSummary(written.result),
                    rollback = written.rollback,
                ),
            )
            return written.result
        }

        private suspend fun executeWritten(args: Args): Written {
            if (!gate.requireSessionAuthorization(WRITE_FILE_NAME)) {
                return Written(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获工作区（ws.*）授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造写入结果。",
                    ),
                    action = "ws:${args.relative_path}（会话授权拒绝）",
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val relative = args.relative_path.trim()
            // 相对路径形态校验复用 sys 家族判据（绝对路径 / ~/.. / 空段拒收）
            SysWriteTools.relativeShapeError(relative)?.let {
                return Written(
                    ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, it),
                    action = "ws:$relative（相对路径形态拒收）",
                )
            }
            val newBytes = args.content.toByteArray(Charsets.UTF_8)
            if (newBytes.size > SysWriteTools.MAX_FILE_BYTES) {
                return Written(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "新内容超单文件上界（${newBytes.size}B > ${SysWriteTools.MAX_FILE_BYTES}B），整体拒绝写入——" +
                            "超界内容无法完整展示（不可完整展示的写入不确认）",
                    ),
                    action = "ws:$relative（新内容超上界）",
                )
            }
            return withContext(Dispatchers.IO) {
                val root = gate.wsRoot
                val target = File(root, relative)
                // 围栏双闸之一：任务围栏（批准即 register——HITL 缝按「会话已授权 + 在工作区根内」放行）
                val authorized = context.authorizeWithGate(target.path)
                    ?: return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            "ws.write_file 只写工作区根内的文件，本次写入未获任务围栏放行。" +
                                "请确认传入的是工作区根内相对路径",
                        ),
                        action = "ws:$relative（围栏拒绝）",
                        verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                    )
                // 围栏双闸之二：工作区根 canonical 包含性（工作区内符号链接穿越拒收，fail-safe）
                if (!isInsideWorkspace(root, authorized)) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            "路径「${authorized.path}」解析后落在工作区根之外（符号链接穿越拒收）。",
                        ),
                        action = "ws:$relative（工作区根穿越拒绝）",
                        verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                    )
                }
                if (authorized.isDirectory) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "目标「${authorized.path}」是目录，拒绝以文件写入覆盖",
                        ),
                        action = "ws:$relative（目标是目录）",
                    )
                }
                val parent = authorized.parentFile
                if (parent == null || !parent.isDirectory) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "目标父目录不存在——本工具拒绝代建目录，请只写既有目录内的文件",
                        ),
                        action = "ws:$relative（父目录缺失）",
                    )
                }
                val exists = authorized.isFile
                if (exists && authorized.length() > SysWriteTools.MAX_FILE_BYTES) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "旧文件超单文件上界（${authorized.length()}B > ${SysWriteTools.MAX_FILE_BYTES}B），" +
                                "整体拒绝写入——超界旧文件无法完整 diff",
                        ),
                        action = "ws:$relative（旧文件超上界）",
                    )
                }
                val oldContent = if (exists) authorized.readText() else ""
                if (exists && oldContent.lines().size > SysWriteTools.MAX_DIFF_LINES) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "旧文件超 diff 行数上界（> ${SysWriteTools.MAX_DIFF_LINES} 行），整体拒绝写入——" +
                                "不可完整展示的写入不确认",
                        ),
                        action = "ws:$relative（旧文件超行数上界）",
                    )
                }
                val newLines = args.content.lines()
                if (newLines.size > SysWriteTools.MAX_DIFF_LINES) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "新内容超 diff 行数上界（> ${SysWriteTools.MAX_DIFF_LINES} 行），整体拒绝写入——" +
                                "不可完整展示的写入不确认",
                        ),
                        action = "ws:$relative（新内容超行数上界）",
                    )
                }
                val action = "write ws:$relative"
                if (exists && oldContent == args.content) {
                    return@withContext Written(
                        ToolResult.Ok(
                            summary = "action=$action\npath=${authorized.path}\n" +
                                "内容无变化，未写入（无需确认卡）\n" +
                                "如需交付该文件可直接 send_file（路径已在围栏内）",
                            data = ToolPayload.WriteConfigPayload(
                                family = "workspace",
                                path = authorized.path,
                                backupPath = null,
                                changed = false,
                                truncated = false,
                            ),
                        ),
                        action = action,
                    )
                }

                // diff 生成 → 脱敏 → 预算校验（脱敏先于预算）
                val rawDiff = SysWriteTools.diffLines(oldContent.lines(), newLines)
                val diff = SysOutputSanitizer.sanitize(rawDiff.joinToString("\n"))
                val changedLines = rawDiff.count { it.startsWith("+ ") } to rawDiff.count { it.startsWith("- ") }
                if (diff.length > SysWriteTools.DIFF_MAX_CHARS) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "diff 超确认卡展示预算（${diff.length} > ${SysWriteTools.DIFF_MAX_CHARS} 字符），整体拒绝写入——" +
                                "不可完整展示的写入不确认（防注入载荷藏尾），请分多次小步写入或缩小改动范围",
                        ),
                        action = "$action（diff 超展示预算）",
                    )
                }

                // .bak 快照（先快照后确认后写入——回滚保障）
                val backupPath: String?
                val rollback: String
                if (exists) {
                    val backup = SysWriteTools.collisionSafeBackup(authorized)
                    try {
                        authorized.copyTo(backup, overwrite = false)
                    } catch (e: IOException) {
                        return@withContext Written(
                            ToolResult.Err(
                                ToolResult.ErrCode.FAILED,
                                "无法创建 .bak 快照（${e.message ?: e.javaClass.simpleName}）——拒绝执行无回滚保障的写入",
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

                // 逐写确认卡：完整 diff（已脱敏、无截断）+ 目标 + 快照 + 回滚
                val card = WsWriteActionCard(
                    targetPath = authorized.path,
                    backupPath = backupPath,
                    diff = diff,
                    rollback = rollback,
                )
                if (!gate.requireActionApproval(card)) {
                    return@withContext Written(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            "工作区写入「${authorized.path}」未经用户确认（拒绝/超时/说明卡发送失败）——未写入。" +
                                "请如实告知用户，禁止未经确认重试。",
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
                // 写成功：登记进本消息围栏——send_file 直接可交付
                context.register(authorized)
                Written(
                    renderReceipt(relative, authorized.path, backupPath, changedLines, diff, rollback),
                    action = action,
                    rollback = rollback,
                )
            }
        }

        /** canonical 包含性判据（工作区根穿越拒收；解不出 canonical 按拒处理 fail-safe） */
        private fun isInsideWorkspace(root: File, target: File): Boolean {
            val rootCanon = runCatching { root.canonicalPath }.getOrElse { return false }
            val targetCanon = runCatching { target.canonicalPath }.getOrElse { return false }
            return targetCanon == rootCanon || targetCanon.startsWith(rootCanon + File.separator)
        }

        /** 结构化回执：action/path/backup/changed + diff（恒脱敏 + 8K capAt 收口）+ 交付提示 */
        private fun renderReceipt(
            relative: String,
            path: String,
            backupPath: String?,
            changed: Pair<Int, Int>,
            diff: String,
            rollback: String,
        ): ToolResult {
            val body = buildString {
                appendLine("action=write ws:$relative")
                appendLine("path=$path")
                appendLine("backup=${backupPath ?: "（新建文件，无写前快照）"}")
                appendLine("changed=+${changed.first}/-${changed.second} 行")
                appendLine("rollback: $rollback")
                appendLine("--- diff ---")
                append(diff)
                appendLine()
                append("已写入并登记进本消息围栏——如需交付给用户，直接对该路径调用 send_file")
            }
            val capped = LogStatsDiffTools.capAt(body, MAX_OUTPUT_CHARS)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.WriteConfigPayload(
                    family = "workspace",
                    path = path,
                    backupPath = backupPath,
                    changed = true,
                    truncated = capped != body,
                ),
            )
        }
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `ws.write_file` 目录描述符。risk = DESTRUCTIVE；装配门控：config.wsWriteGate 非 null
     * 才注册（工厂返 null），恒不入常备清单。
     */
    val WS_WRITE_FILE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = WRITE_FILE_NAME,
        description = "Write (create or overwrite) a file inside the configured workspace — " +
            "relative path only, per-write confirmation card with the complete sanitized diff, " +
            "timestamped .bak snapshot, registered into the message fence for send_file delivery",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        factory = { context, sink, config ->
            config.wsWriteGate?.let { gate ->
                WsWriteFileTool(context, gate, sink)
            }
        },
    )
}
