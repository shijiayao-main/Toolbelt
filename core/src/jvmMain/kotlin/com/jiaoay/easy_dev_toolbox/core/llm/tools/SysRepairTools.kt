package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.IOException
import kotlinx.serialization.Serializable

/**
 * 白名单修复动作工具：`sys.repair`——LLM 只能选不能造的编译期动作目录（固定 argv 动词），
 * 是「写系统状态但非配置文件写」类动作的唯一容器（im-config 写 ~/.xinputrc、gsettings 写
 * dconf 库，均不落 sys.read_config 路径族也不属服务管理）。
 *
 * ## 形态：编译期 enum 目录（key → 固定 argv + 回滚配方）
 * [RepairKey] 每条 = 语义化动作名 + 固定 argv 模板 + 语义说明 + 风险徽标 + 回滚配方 +
 * 执行后验证提示。入参是目录条目名与受槽校验的参数（uuid 形态 / schema.key 白名单 /
 * 值形态按 schema 类型），非法值拒收人话。破坏面纪律：删除类/磁盘/网络/持久化面
 * （cron/自启动/systemd 单元/登录启动脚本自由写）永不入目录——目录由静态断言守护
 * （可执行文件白名单 + 逐字符集断言），加条目走提案评审并同步断言面。
 *
 * ## 回滚保障（先快照后执行）
 * 每动作执行前先跑只读快照命令捕获原值（[RepairKey.preReadArgv]）；回滚依赖原值的动作
 * （im_config/gsettings）快照失败即拒执——「无回滚保障的写动作不执行」；回滚配方连同
 * 原值进确认卡、回执与审计（[SysAuditRecord.rollback]）。kill/卸载等回滚面不在目录内时，
 * 配方明示由用户在终端执行，不代执行。
 *
 * ## 授权（信任用户免卡）
 * 执行前必过 [SysRepairGate.requireSessionAuthorization]（信任用户名单判定：名单内免卡直执，
 * 名单外进不了域；门控面缺席即 DENIED fail-closed）。[SysRepairGate.requireActionApproval]
 * 对信任用户直接放行；非信任用户到不了本卡，两卡路径（说明卡 + 确认卡）仅作装配兜底；
 * 卡面数据经 [RepairActionCard] 单源传给装配链实现。
 *
 * ## 执行与回执
 * 经 [SysProbeTools.ProbeExecutor]（sys 家族共用 argv 纯语义执行缝，执行件内黑名单恒拒）；
 * 回执恒过 [SysOutputSanitizer] + 8K capAt 收口；写动作非零退出码 = 未生效（与只读探查的
 * 「非零=数据」语义不同，回执明示），提示用 sys.verify 复跑验证（未装配时 sys.probe 复查）。
 * 每次执行（含被拒）落审计（[SysAuditLog]，含回滚信息，输出全文不落）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysRepairGate] 非 null（工厂返 null 不注册），
 * 恒不入常备清单。
 */
object SysRepairTools {

    const val REPAIR_NAME = "sys.repair"

    /** 回执长度结构性上界（字符）——sys.probe 同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 回滚信息长度上界（字符）：确认卡/回执/审计三面同用（超界截断，防长数组刷卡） */
    internal const val ROLLBACK_MAX_CHARS = 500

    /**
     * 回执中 pre-state 快照块长度上界（字符）：原值快照进回执供 sys.verify 的 before
     * 原样回传（快照命令输出本就短小——im-config -m 三行 / gsettings get 单行）。
     */
    internal const val PRE_STATE_MAX_CHARS = 500

    /**
     * 修复动作可执行文件白名单（结构性放行面）：目录条目的 argv[0] 只能出自本集合——
     * 静态断言钉死，删除类/磁盘/网络/包管理/提权命令结构性无法入目录。
     */
    val REPAIR_ALLOWED_EXECUTABLES: Set<String> = setOf(
        "im-config", "fcitx5", "gnome-extensions", "gsettings",
    )

    /**
     * gsettings 写键白名单（"schema.key" 连写形态）→ 值类型：首批只收 GNOME Shell
     * 扩展启停相关键（dock 修复路径），值形态按 schema 实际类型校验（非法拒收）。
     * 类型实测锚：`gsettings range <schema> <key>` → as/as/b。加键 = 改本常量并同步
     * 静态断言面。
     */
    val GSETTINGS_WRITE_KEYS: Map<String, GsettingsValueType> = mapOf(
        "org.gnome.shell.enabled-extensions" to GsettingsValueType.STRING_ARRAY,
        "org.gnome.shell.disabled-extensions" to GsettingsValueType.STRING_ARRAY,
        "org.gnome.shell.disable-user-extensions" to GsettingsValueType.BOOL,
    )

    /** gsettings 白名单键的值类型（与 `gsettings range` 实测类型一一对应） */
    enum class GsettingsValueType {
        /** 布尔：字面 true/false */
        BOOL,

        /** 字符串数组：GVariant 文本形态 ['a@b.c', 'd@e.f']（元素须是扩展 uuid 形态）或 [] */
        STRING_ARRAY,
    }

    // ── 槽（修复动作参数面：uuid / schema.key / 值；无路径槽） ──

    /** 修复动作槽种类：槽值只进 argv 数组元素（argv 纯语义无 shell 解释） */
    enum class RepairSlot {
        UUID, SCHEMA_KEY, VALUE;

        /** 槽参数名（enum name 的小写投影——与 Args 字段名一致） */
        val id: String get() = name.lowercase()
    }

    /**
     * 值形态校验：schema.key 在白名单内且值形态匹配该键 schema 类型才放行。
     * 字符串数组元素逐个过扩展 uuid 形态（扩展启停键的元素就是 uuid——比「任意字符串」
     * 强一档的形态闸）。
     */
    internal fun checkSchemaValue(schemaKey: String, rawValue: String): SysProbeTools.SlotCheck {
        val value = rawValue.trim()
        val type = GSETTINGS_WRITE_KEYS[schemaKey]
            ?: return SysProbeTools.SlotCheck.Rejected(
                "schema.key「$schemaKey」不在修复白名单内" +
                    "（允许：${GSETTINGS_WRITE_KEYS.keys.sorted().joinToString("、")}）——" +
                    "写键白名单防自由键名写入；需要新键：修改 core/llm/tools/SysRepairTools.kt 的" +
                    "GSETTINGS_WRITE_KEYS 常量（编译期单源）",
            )
        return when (type) {
            GsettingsValueType.BOOL -> if (value == "true" || value == "false") {
                SysProbeTools.SlotCheck.Accepted(value)
            } else {
                SysProbeTools.SlotCheck.Rejected(
                    "值「$value」形态非法——该键是布尔类型（gsettings range: type b），只收字面 true / false",
                )
            }
            GsettingsValueType.STRING_ARRAY -> checkStringArrayValue(value)
        }
    }

    /** 字符串数组值形态：GVariant 文本形态 [] 或 ['uuid', 'uuid']（元素须过扩展 uuid 形态） */
    private fun checkStringArrayValue(value: String): SysProbeTools.SlotCheck {
        val isEmptyArray = Regex("""^\[\s*]$""").matches(value)
        val filledArray = Regex("""^\[\s*'[^']*'(?:\s*,\s*'[^']*')*\s*]$""").matches(value)
        if (!isEmptyArray && !filledArray) {
            return SysProbeTools.SlotCheck.Rejected(
                "值「${value.take(80)}」形态非法——该键是字符串数组（gsettings range: type as），" +
                    "只收 GVariant 文本形态 [] 或 ['uuid1', 'uuid2']（单引号包裹、逗号分隔）",
            )
        }
        val elements = Regex("'([^']*)'").findAll(value).map { it.groupValues[1] }.toList()
        val bad = elements.filter { !SysProbeTools.EXTENSION_UUID_REGEX.matches(it) }
        return if (bad.isEmpty()) {
            SysProbeTools.SlotCheck.Accepted(value)
        } else {
            SysProbeTools.SlotCheck.Rejected(
                "数组元素「${bad.joinToString("、")}」不是合法扩展 uuid 形态（uuid@domain，" +
                    "如 ubuntu-dock@ubuntu.com）——可用 sys.probe 的 gnome_extensions_list 条目枚举已装扩展",
            )
        }
    }

    // ── 目录（编译期锁死：key → 固定 argv + 回滚配方） ──

    /**
     * 修复动作目录条目。LLM 入参 = [id]（enum name 的小写下划线形态）。
     *
     * @param label 语义化动作名（确认卡标题；不含插值——具体目标看 argv）
     * @param semantics 语义说明（确认卡正文：做什么、副作用落点）
     * @param riskBadge 风险徽标（确认卡风险行；取 RiskLevel 语义档名 MEDIUM/HIGH）
     * @param required 必填槽；缺槽 BAD_ARGS 人话拒收
     * @param buildArgv argv 模板（纯函数：已过校验的槽值 → argv 数组）
     * @param preReadArgv 执行前的只读原值快照命令（null = 无快照步）
     * @param preReadRequired true = 快照失败即拒执（回滚依赖原值——无回滚保障的写动作不执行）
     * @param rollbackFor 回滚配方（槽值 + 快照输出 → 配方一行；快照未捕获传空串）
     * @param verifyHint 执行后的验证提示（sys.verify 复跑对应探查——诊断-修复-验证-外委回环；
     *   sys.verify 未装配时回退 sys.probe 复查，两条路径同写一句）
     */
    enum class RepairKey(
        internal val label: String,
        internal val semantics: String,
        internal val riskBadge: String,
        internal val required: Set<RepairSlot> = emptySet(),
        internal val buildArgv: (Map<RepairSlot, String>) -> List<String>,
        internal val preReadArgv: (Map<RepairSlot, String>) -> List<String>? = { null },
        internal val preReadRequired: Boolean = false,
        internal val rollbackFor: (slots: Map<RepairSlot, String>, preState: String) -> String,
        internal val verifyHint: String,
    ) {
        /**
         * im-config 用户级输入法框架写入：受控间接触碰 ~/.xinputrc（登录启动脚本类文件的
         * 唯一合法写路径——经 vetted 工具的固定单参形态，非 agent 自由写内容）。
         */
        IM_CONFIG_SET_FCITX5(
            label = "设置用户级输入法框架为 fcitx5",
            semantics = "经 im-config 受控写入用户级输入法配置（~/.xinputrc 的 run_im 行，" +
                "im-config 的既有语义）——此后登录会话拉起 fcitx5；本动作不直接改写该文件内容",
            riskBadge = "MEDIUM",
            buildArgv = { listOf("im-config", "-n", "fcitx5") },
            preReadArgv = { listOf("im-config", "-m") },
            preReadRequired = true,
            rollbackFor = { _, pre ->
                val current = imConfigActiveLine(pre)
                if (current.isNullOrBlank()) {
                    "回滚：原值未捕获（im-config -m 第 2 行为空 = 原本无用户级覆盖）——" +
                        "回滚请用户在终端执行 im-config 交互清空用户配置，不代执行"
                } else {
                    "回滚：im-config -n $current（原值=$current，捕获自执行前 im-config -m 第 2 行）"
                }
            },
            verifyHint = "验证：sys.verify（key=im_config_set_fcitx5，before=回执上方 pre-state）" +
                "复跑 im_config_mode 探查判定是否生效；sys.verify 未装配时用 sys.probe 的 " +
                "im_config_mode 条目复查",
        ),

        /**
         * fcitx5 守护后台拉起：严格只收 `fcitx5 -d` 单参形态（无任何自由参数——-r/--enable
         * 等旗标永不入目录）。已在运行时第二实例注册会话总线名失败退出，不重复拉起。
         */
        FCITX5_RESTART_DAEMON(
            label = "后台拉起 fcitx5 输入法守护",
            semantics = "以守护形态启动 fcitx5（fcitx5 -d）；fcitx5 已在运行时新实例注册总线名" +
                "失败退出，不会重复拉起。严格单参形态，无任何自由参数",
            riskBadge = "HIGH",
            buildArgv = { listOf("fcitx5", "-d") },
            preReadArgv = { listOf("pgrep", "-a", "fcitx5") },
            preReadRequired = false,
            rollbackFor = { _, pre ->
                val runningBefore = pre.lineSequence().any { it.isNotBlank() }
                (if (runningBefore) {
                    val firstPid = pre.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.substringBefore(' ')
                    "此前已有 fcitx5 进程在跑（PID $firstPid 等）——"
                } else {
                    "此前无 fcitx5 进程在跑（本动作拉起的即新进程）——"
                }) + "回滚：由用户在终端执行 pkill -TERM fcitx5 停止守护（kill 面不在修复动作目录内，不代执行）"
            },
            verifyHint = "验证：sys.verify（key=fcitx5_restart_daemon，before=回执上方 pre-state）" +
                "复跑 fcitx5_processes 探查判定守护是否拉起；sys.verify 未装配时用 sys.probe 的 " +
                "fcitx5_processes 条目复查（pgrep 无匹配 = 未拉起）",
        ),

        /** GNOME Shell 扩展启用：uuid 走 sys.probe 同款形态校验；回滚 = disable 同 uuid */
        GNOME_EXTENSION_ENABLE(
            label = "启用 GNOME Shell 扩展",
            semantics = "启用已安装的 GNOME Shell 用户扩展（gnome-extensions enable）——" +
                "立即生效，无需重启 shell；仅此单动词，卸载/重置永不入目录",
            riskBadge = "MEDIUM",
            required = setOf(RepairSlot.UUID),
            buildArgv = { p -> listOf("gnome-extensions", "enable", p.getValue(RepairSlot.UUID)) },
            preReadArgv = { listOf("gsettings", "get", "org.gnome.shell", "enabled-extensions") },
            preReadRequired = false,
            rollbackFor = { slots, pre ->
                val uuid = slots.getValue(RepairSlot.UUID)
                if ("'$uuid'" in pre) {
                    "回滚：该扩展此前已在 enabled-extensions 清单内（本动作为幂等重申）——" +
                        "如需撤销请用户确认后再执行 gnome-extensions disable $uuid"
                } else {
                    "回滚：gnome-extensions disable $uuid（同 uuid 反向动作）"
                }
            },
            verifyHint = "验证：sys.verify（key=gnome_extension_enable，uuid=同值，" +
                "before=回执上方 pre-state）复跑 gnome_extensions_info 判定扩展是否已启用；" +
                "sys.verify 未装配时用 sys.probe 的 gnome_extensions_info 条目复查",
        ),

        /** GNOME gsettings 白名单键写入：值形态按 schema 类型校验；回滚 = 写回原值 */
        GSETTINGS_SET(
            label = "设置 GNOME 配置键（白名单）",
            semantics = "写 GNOME gsettings 键（dconf 库，非配置文件写）——仅白名单内的" +
                "GNOME Shell 扩展启停相关键，值形态按 schema 实际类型校验",
            riskBadge = "MEDIUM",
            required = setOf(RepairSlot.SCHEMA_KEY, RepairSlot.VALUE),
            buildArgv = { p ->
                val schemaKey = p.getValue(RepairSlot.SCHEMA_KEY)
                listOf(
                    "gsettings", "set",
                    schemaKey.substringBeforeLast('.'), schemaKey.substringAfterLast('.'),
                    p.getValue(RepairSlot.VALUE),
                )
            },
            preReadArgv = { p ->
                val schemaKey = p.getValue(RepairSlot.SCHEMA_KEY)
                listOf("gsettings", "get", schemaKey.substringBeforeLast('.'), schemaKey.substringAfterLast('.'))
            },
            preReadRequired = true,
            rollbackFor = { slots, pre ->
                val schemaKey = slots.getValue(RepairSlot.SCHEMA_KEY)
                val original = pre.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val schema = schemaKey.substringBeforeLast('.')
                val key = schemaKey.substringAfterLast('.')
                if (original.isBlank()) {
                    "回滚：原值未捕获（执行前 gsettings get 无输出）——回滚请用户在终端执行 " +
                        "gsettings reset $schema $key，不代执行"
                } else {
                    "回滚：gsettings set $schema $key $original（原值捕获自执行前 gsettings get）"
                }
            },
            verifyHint = "验证：sys.verify（key=gsettings_set，schema_key=同值，value=同值，" +
                "before=回执上方 pre-state）复跑 gsettings_get 判定键值是否已写入；" +
                "sys.verify 未装配时用 sys.probe 的 gsettings_get 条目复查",
        ),
        ;

        /** LLM 入参形态的条目名（enum name 的小写投影） */
        val id: String get() = name.lowercase()

        /** 回滚配方长度收口（确认卡/回执/审计三面同源截断） */
        fun rollbackOf(slots: Map<RepairSlot, String>, preState: String): String =
            rollbackFor(slots, preState).take(ROLLBACK_MAX_CHARS)
    }

    /**
     * im-config -m 输出的第 2 行 = 用户级 active IM（~/.xinputrc 的 run_im 解析值；
     * 第 1 行是系统级、第 3 行起是自动选择/最高优先级——均非 -n 写入的回滚对象）。
     * 行值形态闸：只放行 [A-Za-z0-9_-]+（default/auto/none/输入法名），异常形态按未捕获处理。
     */
    internal fun imConfigActiveLine(preState: String): String? {
        val line = preState.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()
        return line.takeIf { it.isNotEmpty() && Regex("""^[A-Za-z0-9_-]+$""").matches(it) }
    }

    // ── 确认卡数据与授权面契约（装配链实现承担发卡/防替答/超时=拒绝） ──

    /**
     * 修复动作确认卡数据（卡面单源）：完整 argv + 语义化动作名 + 风险徽标 + 回滚配方。
     * 确认卡 inputSummary 被服务侧截断装不下全文——完整内容由实现经说明卡承载
     * （说明卡 + 确认卡两卡形态，仅作装配兜底：信任用户走不到这里）。
     */
    data class RepairActionCard(
        val actionKey: String,
        val label: String,
        val argv: List<String>,
        val riskBadge: String,
        val semantics: String,
        val rollback: String,
    )

    /**
     * sys.repair 授权面（装配链侧契约）。
     * [requireSessionAuthorization] 是唯一的真实门禁：信任用户名单判定，名单外进不了域；
     * [requireActionApproval] 对信任用户直接放行（免卡直执），非信任用户到不了本卡——
     * 两卡路径仅作装配兜底。
     */
    interface SysRepairGate : SysSessionGate {

        /**
         * 要求本动作的用户确认（挂起直到裁决/超时）。
         *
         * @return true = 用户批准本动作；false = 拒绝/超时/说明卡发送失败（fail-closed）
         */
        suspend fun requireActionApproval(card: RepairActionCard): Boolean
    }

    // ── 工具：sys.repair ──

    class SysRepairTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val executor: SysProbeTools.ProbeExecutor = SysProbeTools.ProbeExecutor.DEFAULT,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 逐动作授权面（装配门控；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val repairGate: SysRepairGate? = null,
    ) : StructuredSimpleTool<SysRepairTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = REPAIR_NAME,
        description = "Execute a whitelisted LOCAL SYSTEM REPAIR action for desktop-environment " +
            "fixes (input method config / fcitx5 daemon / GNOME extensions / whitelisted " +
            "gsettings keys). You can ONLY pick a catalog action key, never craft a command. " +
            "Actions: im_config_set_fcitx5 (set user IM framework to fcitx5 via im-config; the " +
            "previous value is captured automatically for rollback), fcitx5_restart_daemon " +
            "(start the fcitx5 daemon, fixed single form, no free params), gnome_extension_enable " +
            "(needs uuid, e.g. ubuntu-dock@ubuntu.com), gsettings_set (needs schema_key from the " +
            "whitelist plus value; bool keys accept literal true/false, extension-list keys accept " +
            "GVariant array form like ['uuid1', 'uuid2']). EVERY action requires the user to " +
            "approve a per-action confirmation card showing the full command and the rollback " +
            "recipe (the session-level grant never covers repair actions). The pre-repair " +
            "snapshot is captured and echoed in the receipt as 'pre-state'; verify afterwards " +
            "with sys.verify (pass that snapshot as before), falling back to sys.probe. " +
            "Deletion/disk/network/persistence/privileged actions are structurally absent from " +
            "this catalog — hand such needs to the user as copy-paste commands instead.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Catalog action key, e.g. im_config_set_fcitx5 / fcitx5_restart_daemon / " +
                    "gnome_extension_enable / gsettings_set. Pick from the catalog only — a " +
                    "command string is never accepted.",
            )
            val key: String,
            @property:LLMDescription(
                "GNOME extension uuid slot (gnome_extension_enable), e.g. ubuntu-dock@ubuntu.com. " +
                    "Enumerate installed extensions via sys.probe gnome_extensions_list first.",
            )
            val uuid: String? = null,
            @property:LLMDescription(
                "gsettings schema.key slot (gsettings_set), e.g. org.gnome.shell.enabled-extensions. " +
                    "Whitelist; the rejection message lists the allowed keys.",
            )
            val schema_key: String? = null,
            @property:LLMDescription(
                "Value slot (gsettings_set). Bool keys: literal true/false. Extension-list keys: " +
                    "GVariant array form like ['ubuntu-dock@ubuntu.com'] or [].",
            )
            val value: String? = null,
        )

        /** 执行包装体：结果 + 审计动作面 + 退出码 + 裁决覆写 + 回滚信息 + 修复前快照 */
        private data class Repaired(
            val result: ToolResult,
            val action: String,
            val exitCode: Int?,
            val verdictOverride: String? = null,
            val rollback: String? = null,
            val preState: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val repaired = executeRepaired(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = REPAIR_NAME,
                    action = repaired.action,
                    verdict = repaired.verdictOverride ?: sysAuditVerdict(repaired.result),
                    exitCode = repaired.exitCode,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(repaired.result),
                    rollback = repaired.rollback,
                ),
            )
            return repaired.result
        }

        private suspend fun executeRepaired(args: Args): Repaired {
            // 域准入（会话档）：门控面缺席 = 装配缺陷，fail-closed 拒绝。
            val gate = repairGate ?: return Repaired(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "sys.repair 未装配逐动作确认门（fail-closed 拒绝）——本链不支持本机修复动作。",
                ),
                action = "key=${args.key}（门控面缺席）",
                exitCode = null,
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(REPAIR_NAME)) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 sys.* 诊断域授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造执行结果。",
                    ),
                    action = "key=${args.key}（会话授权拒绝）",
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val key = RepairKey.entries.firstOrNull { it.id == args.key.trim().lowercase() }
                ?: return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知修复动作「${args.key}」。本工具只能从编译期目录里选动作，不能自造命令。" +
                            "可用动作：${RepairKey.entries.joinToString("、") { it.id }}",
                    ),
                    action = "key=${args.key}（未命中目录）",
                    exitCode = null,
                )

            // 槽纪律：未约定的槽传值 = 拒收（参数面收窄到条目声明的槽）
            val supplied = mapOf(
                RepairSlot.UUID to args.uuid,
                RepairSlot.SCHEMA_KEY to args.schema_key,
                RepairSlot.VALUE to args.value,
            ).filterValues { it != null }.mapValues { it.value as String }
            val unexpected = supplied.keys - key.required
            if (unexpected.isNotEmpty()) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "动作 ${key.id} 不接受参数 ${unexpected.joinToString("、") { it.id }}——" +
                            "该动作只接受 ${key.required.joinToString("、") { it.id }}" +
                            "（无槽动作不接受任何参数）",
                    ),
                    action = "key=${key.id}（槽纪律拒收）",
                    exitCode = null,
                )
            }
            val missing = key.required - supplied.keys
            if (missing.isNotEmpty()) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "动作 ${key.id} 缺必填参数 ${missing.joinToString("、") { it.id }}",
                    ),
                    action = "key=${key.id}（缺必填槽）",
                    exitCode = null,
                )
            }

            // 槽校验：uuid 形态（sys.probe 同款）→ schema.key 白名单 → 值形态（依赖 schema.key）
            val resolved = buildMap {
                for (slot in RepairSlot.entries) {
                    if (slot == RepairSlot.VALUE) continue
                    val raw = supplied[slot] ?: continue
                    val check = when (slot) {
                        RepairSlot.UUID -> SysProbeTools.checkUuid(raw)
                        RepairSlot.SCHEMA_KEY -> checkSchemaKey(raw)
                        RepairSlot.VALUE -> continue
                    }
                    when (check) {
                        is SysProbeTools.SlotCheck.Accepted -> put(slot, check.value)
                        is SysProbeTools.SlotCheck.Rejected -> return badArgs(check.reason, key)
                    }
                }
                supplied[RepairSlot.VALUE]?.let { raw ->
                    when (val check = checkSchemaValue(resolvedSchemaKey(supplied), raw)) {
                        is SysProbeTools.SlotCheck.Accepted -> put(RepairSlot.VALUE, check.value)
                        is SysProbeTools.SlotCheck.Rejected -> return badArgs(check.reason, key)
                    }
                }
            }

            val argv = key.buildArgv(resolved)
            val action = argv.joinToString(" ")

            // 原值快照（先快照后执行——回滚保障）：回滚依赖原值的动作快照失败即拒执
            val preState = capturePreState(key, resolved)
                ?: return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "无法捕获回滚原值（${key.preReadArgv(resolved)?.joinToString(" ")} 执行失败）——" +
                            "拒绝执行无回滚保障的写动作",
                        hint = "可先用 sys.probe 对应只读条目核对该命令在本机是否可用，再重试",
                    ),
                    action = action,
                    exitCode = null,
                )
            val rollback = key.rollbackOf(resolved, preState)

            // 逐动作确认门（信任用户免卡直执）：完整 argv + 语义化动作名 + 风险徽标 + 回滚配方
            val card = RepairActionCard(
                actionKey = key.id,
                label = key.label,
                argv = argv,
                riskBadge = key.riskBadge,
                semantics = key.semantics,
                rollback = rollback,
            )
            if (!gate.requireActionApproval(card)) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "修复动作「${key.label}」未经用户确认（拒绝/超时/说明卡发送失败）——未执行。" +
                            "请如实告知用户可在其本人终端手动执行该命令，禁止未经确认重试。",
                    ),
                    action = action,
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_ACTION_DENIED,
                    rollback = rollback,
                    preState = preState,
                )
            }

            // 失败契约同 ProbeExecutor：只翻译声明的异常（CE 穿透；其余异常上抛不吞）
            val outcome = try {
                executor.run(argv)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        e.message ?: "argv 被执行面策略拒绝",
                    ),
                    action = action,
                    exitCode = null,
                    rollback = rollback,
                )
            } catch (e: IOException) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "命令「${argv.first()}」启动失败（本机未安装或不可执行）：${e.message ?: e.javaClass.simpleName}",
                        hint = "如实告知用户该命令在本机不可用（禁止编造执行结果）",
                    ),
                    action = action,
                    exitCode = null,
                    rollback = rollback,
                )
            }
            if (outcome.timedOut) {
                return Repaired(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "命令「${argv.first()}」超时未完成，已被终止——写动作结果未知，" +
                            "必须先用 sys.probe 对应条目核实实际状态再决定下一步",
                        hint = key.verifyHint,
                    ),
                    action = action,
                    exitCode = null,
                    rollback = rollback,
                    preState = preState,
                )
            }
            return Repaired(
                renderReceipt(key, argv, outcome, rollback, preState),
                action = action,
                exitCode = outcome.exitCode,
                rollback = rollback,
                preState = preState,
            )
        }

        /** 非法槽拒收的统一包装（动作面记 key 形态） */
        private fun badArgs(reason: String, key: RepairKey): Repaired =
            Repaired(
                ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    reason,
                    hint = "按回执内列出的允许值调整后重试；修复目录与白名单均为编译期常量，无运行时配置面",
                ),
                action = "key=${key.id}（槽校验拒收）",
                exitCode = null,
            )

        /** schema.key 槽修复侧白名单（与只读探查的 6 键分开——写面独立收窄） */
        private fun checkSchemaKey(raw: String): SysProbeTools.SlotCheck {
            val name = raw.trim()
            return if (name in GSETTINGS_WRITE_KEYS) {
                SysProbeTools.SlotCheck.Accepted(name)
            } else {
                SysProbeTools.SlotCheck.Rejected(
                    "schema.key「$name」不在修复白名单内" +
                        "（允许：${GSETTINGS_WRITE_KEYS.keys.sorted().joinToString("、")}）——" +
                        "写键白名单防自由键名写入；需要新键：修改 core/llm/tools/SysRepairTools.kt 的" +
                        "GSETTINGS_WRITE_KEYS 常量（编译期单源）",
                )
            }
        }

        /** 值校验依赖的 schema.key（白名单值优先，缺槽/白名单外时原样回传给 checkSchemaValue 拒收） */
        private fun resolvedSchemaKey(supplied: Map<RepairSlot, String>): String {
            val raw = supplied[RepairSlot.SCHEMA_KEY]?.trim().orEmpty()
            return raw.takeIf { it in GSETTINGS_WRITE_KEYS } ?: raw
        }

        /**
         * 原值快照：返回快照输出；快照不可用（命令失败/超时/策略拒绝）时——
         * preReadRequired 条目返回 null（拒执），可选条目返回空串（回滚配方按未捕获降级）。
         */
        private suspend fun capturePreState(key: RepairKey, slots: Map<RepairSlot, String>): String? {
            val preArgv = key.preReadArgv(slots) ?: return ""
            val outcome = try {
                executor.run(preArgv)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            val usableOutcome = outcome?.takeIf { !it.timedOut && it.exitCode == 0 }
            return when {
                usableOutcome != null -> usableOutcome.output
                key.preReadRequired -> null
                else -> ""
            }
        }

        /** 结构化回执：action/argv/exit/rollback/pre-state + 输出（恒脱敏 + 8K capAt 收口）+ 验证提示 */
        private fun renderReceipt(
            key: RepairKey,
            argv: List<String>,
            outcome: ArgvOutcome,
            rollback: String,
            preState: String,
        ): ToolResult {
            val output = SysOutputSanitizer.sanitize(outcome.output).ifBlank { "（无输出）" }
            val body = buildString {
                appendLine("action=${key.id}（${key.label}）")
                appendLine("argv: ${argv.joinToString(" ")}")
                appendLine("exit=${outcome.exitCode}")
                appendLine("rollback: $rollback")
                val preStateBlock = SysOutputSanitizer.sanitize(preState).trim()
                if (preStateBlock.isNotEmpty()) {
                    appendLine("pre-state（修复前快照，调用 sys.verify 时作为 before 原样回传）:")
                    appendLine(preStateBlock.take(PRE_STATE_MAX_CHARS))
                }
                appendLine("--- output ---")
                append(output)
                if (outcome.exitCode != 0) {
                    appendLine()
                    appendLine(
                        "（退出码 ${outcome.exitCode} 非零——写动作语义与只读探查不同：非零 = 未生效或执行失败，" +
                            "不要臆断已生效）",
                    )
                }
                appendLine()
                append(key.verifyHint)
            }
            val capped = capAt(body)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.RepairPayload(
                    key = key.id,
                    exitCode = outcome.exitCode,
                    rollback = rollback,
                    truncated = capped != body || outcome.outputTruncated,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `sys.repair` 目录描述符。risk = DESTRUCTIVE（写系统状态面——权限引擎与外放门控依据）；
     * uiHint = null：纯 agent 工具，不进命令面板。装配门控：config.sysRepairGate 非 null 才
     * 注册（工厂返 null），恒不入常备清单。
     */
    val SYS_REPAIR: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = REPAIR_NAME,
        description = "Execute a whitelisted local system repair action (input method config / " +
            "fcitx5 daemon / GNOME extension enable / whitelisted gsettings keys) — catalog " +
            "keys only, never command strings; every action needs a per-action user confirmation " +
            "card with the full command and rollback recipe; previous value captured before write",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        factory = { _, sink, config ->
            config.sysRepairGate?.let { gate ->
                SysRepairTool(sink, repairGate = gate, trigger = gate.trigger)
            }
        },
    )
}
