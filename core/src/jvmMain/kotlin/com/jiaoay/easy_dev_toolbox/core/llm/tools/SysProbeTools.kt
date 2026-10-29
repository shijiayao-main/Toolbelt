package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.ArgvProcessRunner
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.IOException
import kotlinx.serialization.Serializable

/**
 * 只读系统诊断工具域：`sys.*`——LLM 只能选不能造的白名单只读命令目录。
 *
 * ## 形态：编译期 enum 目录（key → 固定 argv）
 * [ProbeKey] 每个条目 = 编译期锁死的 argv 模板；LLM 入参是**目录条目名**（enum name）
 * 而非命令串——白名单从结构上成立（不存在「字符串参数下白名单不成立」的注入面）。
 * 带参槽（服务名/行数/schema.key/扩展 uuid）受白名单/钳制/形态校验，非法值拒收人话
 * （回执列允许集，LLM 可自行调整）。加目录条目走提案流程评审（每条命令须逐条核只读性：
 * 启动/修改类一律不收；journalctl/systemctl 不带 --user 的形态一旦混入即系统级面泄漏）。
 *
 * ## 执行面
 * 经 [ArgvProcessRunner]（argv 纯语义：无 shell 解释器、argv 数组直传；执行件内
 * 提权/包管理黑名单恒拒 + systemctl/journalctl 恒 --user 硬校验、看门狗 + 进程树级联终止）。
 *
 * ## 退出码 = 数据
 * 只读探查命令的非零退出码是诊断答案不是失败（pgrep 无匹配 = 1、systemctl status
 * 单元不存在 = 非零、printenv 部分变量未设置 = 1）——回执原样上报退出码并提示按命令
 * 语义解读；工具层 Err 只用于「未选中目录/参数拒收/命令不可用/超时」。
 *
 * ## 输出上界（真门禁：超限即刷爆 agent 上下文）
 * 回执恒经 [LogStatsDiffTools.capAt] 收口 ≤ [MAX_OUTPUT_CHARS] = 8K 字符（web.fetch
 * 同款配方：结构性上界不依赖输出量推导成立）；回执标记是否截断。
 *
 * ## 输出脱敏与审计
 * 输出恒过 [SysOutputSanitizer]（token/key/password 行级遮蔽——回灌 LLM 后可经
 * 免确认直述面外泄，脱敏是读取-转述通道的收窄）；每次执行（含被拒）落审计
 * （[SysAuditLog]：argv 全文/裁决/退出码/输出摘要，输出全文不落）。
 *
 * ## 域准入（信任用户名单）
 * 执行前必过 [SysSessionGate.requireSessionAuthorization]（名单内放行、名单外进不了域；
 * 门控面缺席即 DENIED，fail-closed——裸执行属装配缺陷）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysSessionGate] 非 null（工厂返 null 不注册）。
 */
object SysProbeTools {

    const val PROBE_NAME = "sys.probe"

    /** sys.* 家族名集——消费方 names 选择集单源引用（[WebTools.WEB_NAMES] 先例） */
    val SYS_NAMES: Set<String> = setOf(
        PROBE_NAME,
        SysReadConfigTools.READ_CONFIG_NAME,
        SysRepairTools.REPAIR_NAME,
        SysWriteTools.WRITE_CONFIG_NAME,
        SysServiceTools.SERVICE_NAME,
        SysVerifyTools.VERIFY_NAME,
        SysHandoffTools.HANDOFF_NAME,
    )

    /** 输出长度结构性上界（字符）——真门禁，见对象 KDoc「输出上界」 */
    internal const val MAX_OUTPUT_CHARS = 8192

    // ── 带参槽 ──

    /**
     * 带参槽种类：每种槽一个 Args 字段 + 一套校验。
     * 槽值只进 argv 数组元素（argv 纯语义无 shell 解释，注入面结构上不存在），
     * 白名单/钳制的意义是**收窄命令语义**（防服务名指向用户可写的自造单元、
     * 防行数拉爆输出），不是防注入。
     */
    enum class ParamKind {
        SERVICE, LINES, SCHEMA_KEY, UUID;

        /** 槽参数名（enum name 的小写投影——与 Args 字段名一致，回执/拒收文案恒用它） */
        val id: String get() = name.lowercase()
    }

    /**
     * 用户级服务名白名单：systemd --user 单元名。单元文件可被用户自造
     * （~/.config/systemd/user 内同名单元劫持 status 输出），故服务名不走自由命名——
     * 只收常见桌面会话服务。加条目 = 改这一处常量。
     */
    val SERVICE_WHITELIST: Set<String> = setOf(
        "fcitx5", "ibus", "ibus-daemon",
        "pipewire", "wireplumber", "pulseaudio",
        "xdg-desktop-portal", "gnome-shell",
    )

    /**
     * gsettings schema.key 白名单（"schema.key" 连写形态，argv 拆为两段）：
     * GNOME Shell 扩展/收藏夹与桌面外观键——fcitx5/dock 两类桌面问题的高频只读键。
     * 加键 = 改这一处常量。
     */
    val GSETTINGS_KEYS: Set<String> = setOf(
        "org.gnome.shell.enabled-extensions",
        "org.gnome.shell.disabled-extensions",
        "org.gnome.shell.favorite-apps",
        "org.gnome.desktop.interface.gtk-theme",
        "org.gnome.desktop.interface.color-scheme",
        "org.gnome.desktop.wm.preferences.theme",
    )

    /** journalctl 行数钳制下限（行）：再小没有诊断价值 */
    const val LINES_MIN = 10

    /** journalctl 行数钳制上限（行）：输出上界的语义侧配合（200 行 × 长行仍在 8K capAt 兜底内） */
    const val LINES_MAX = 200

    /** journalctl 行数缺省值（行） */
    const val DEFAULT_LINES = 50

    /**
     * GNOME 扩展 uuid 形态校验（uuid@domain，如 ubuntu-dock@ubuntu.com）：
     * uuid 空间开放（已装扩展由 gnome-extensions list 枚举），编译期无法白名单，
     * 故只做形态校验（形态外的值拒收人话）；argv 纯语义下形态校验非注入防线
     * （见 [ParamKind] KDoc）。
     */
    val EXTENSION_UUID_REGEX: Regex =
        Regex("""^[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.-]*$""")

    // ── 目录（编译期锁死：key → 固定 argv 模板） ──

    /**
     * 只读探查命令目录条目。LLM 入参 = [name]（enum name，全小写下划线）。
     *
     * @param required 必填槽；缺槽 BAD_ARGS 人话拒收
     * @param optional 可选槽；不传走缺省值
     * @param buildArgv argv 模板（纯函数：槽值 → argv 数组；槽值已过校验）
     */
    enum class ProbeKey(
        internal val required: Set<ParamKind> = emptySet(),
        internal val optional: Set<ParamKind> = emptySet(),
        internal val buildArgv: (Map<ParamKind, String>) -> List<String>,
    ) {
        /** 输入法框架选择模式（im-config -m：当前生效的输入法框架配置） */
        IM_CONFIG_MODE(buildArgv = { listOf("im-config", "-m") }),

        /** 输入法启动决策（im-launch --print：登录会话将拉起的输入法命令，无输出 = 不拉起） */
        IM_LAUNCH_PRINT(buildArgv = { listOf("im-launch", "--print") }),

        /** fcitx5 官方全量诊断报告（输出量大，回执按 8K 上界截断） */
        FCITX5_DIAGNOSE(buildArgv = { listOf("fcitx5-diagnose") }),

        /** 输入法环境变量（GTK_IM_MODULE/QT_IM_MODULE/XMODIFIERS 等固定枚举，非全量 env） */
        IM_ENV_VARS(buildArgv = {
            listOf(
                "printenv",
                "GTK_IM_MODULE", "QT_IM_MODULE", "XMODIFIERS",
                "SDL_IM_MODULE", "INPUT_METHOD",
            )
        }),

        /** fcitx5 进程在否（pgrep -a：exit=1 = 无进程在跑，是诊断答案） */
        FCITX5_PROCESSES(buildArgv = { listOf("pgrep", "-a", "fcitx5") }),

        /** ibus-daemon 进程在否（pgrep -a：exit=1 = 无进程在跑） */
        IBUS_PROCESSES(buildArgv = { listOf("pgrep", "-a", "ibus-daemon") }),

        /** 桌面会话基本信息（XDG_CURRENT_DESKTOP/XDG_SESSION_TYPE/DESKTOP_SESSION/LANG） */
        DESKTOP_ENV_BASICS(buildArgv = {
            listOf(
                "printenv",
                "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE",
                "DESKTOP_SESSION", "XDG_SESSION_DESKTOP", "LANG",
            )
        }),

        /** 发行版信息（lsb_release -a） */
        OS_RELEASE(buildArgv = { listOf("lsb_release", "-a") }),

        /** 内核版本（uname -r） */
        KERNEL_VERSION(buildArgv = { listOf("uname", "-r") }),

        /** GNOME Shell 已装扩展清单（gnome-extensions list） */
        GNOME_EXTENSIONS_LIST(buildArgv = { listOf("gnome-extensions", "list") }),

        /** GNOME Shell 单个扩展详情（gnome-extensions info <uuid>） */
        GNOME_EXTENSIONS_INFO(
            required = setOf(ParamKind.UUID),
            buildArgv = { p -> listOf("gnome-extensions", "info", p.getValue(ParamKind.UUID)) },
        ),

        /** GNOME 配置键只读查询（gsettings get <schema> <key>；schema.key 走白名单） */
        GSETTINGS_GET(
            required = setOf(ParamKind.SCHEMA_KEY),
            buildArgv = { p ->
                val schemaKey = p.getValue(ParamKind.SCHEMA_KEY)
                listOf("gsettings", "get", schemaKey.substringBeforeLast('.'), schemaKey.substringAfterLast('.'))
            },
        ),

        /** 用户级服务状态（systemctl --user status <service>；服务名走白名单，恒带 --user） */
        SYSTEMD_USER_STATUS(
            required = setOf(ParamKind.SERVICE),
            buildArgv = { p ->
                listOf("systemctl", "--user", "--no-pager", "-n", "40", "status", p.getValue(ParamKind.SERVICE))
            },
        ),

        /** 用户级日志尾部（journalctl --user -n <lines>；恒带 --user，行数钳制 10..200） */
        JOURNALCTL_USER_TAIL(
            optional = setOf(ParamKind.LINES),
            buildArgv = { p ->
                listOf("journalctl", "--user", "--no-pager", "-n", p[ParamKind.LINES] ?: DEFAULT_LINES.toString())
            },
        ),

        /** 指定用户级服务的日志（journalctl --user -u <service> -n <lines>；服务名白名单 + 行数钳制） */
        JOURNALCTL_USER_UNIT(
            required = setOf(ParamKind.SERVICE),
            optional = setOf(ParamKind.LINES),
            buildArgv = { p ->
                listOf(
                    "journalctl", "--user", "--no-pager",
                    "-n", p[ParamKind.LINES] ?: DEFAULT_LINES.toString(),
                    "-u", p.getValue(ParamKind.SERVICE),
                )
            },
        ),
        ;

        /** LLM 入参形态的条目名（enum name 的小写投影——入参匹配与回执恒用它） */
        val id: String get() = name.lowercase()
    }

    // ── 槽校验（非法值拒收人话：回执列允许集/形态，LLM 可自行调整） ──

    /** 槽校验结果：Accepted（归一后的槽值）/ Rejected（人话理由） */
    internal sealed interface SlotCheck {
        data class Accepted(val value: String) : SlotCheck
        data class Rejected(val reason: String) : SlotCheck
    }

    /** 服务名槽：用户级服务白名单（防自造单元劫持，见 [SERVICE_WHITELIST]） */
    internal fun checkService(raw: String): SlotCheck {
        val name = raw.trim()
        return if (name in SERVICE_WHITELIST) {
            SlotCheck.Accepted(name)
        } else {
            SlotCheck.Rejected(
                "服务名「${name}」不在白名单内（允许：${SERVICE_WHITELIST.sorted().joinToString("、")}）——" +
                    "服务名白名单防用户自造单元劫持输出；需要新服务名：修改 core/llm/tools/SysProbeTools.kt 的 SERVICE_WHITELIST 常量（编译期单源）",
            )
        }
    }

    /** 行数槽：整数钳制 [LINES_MIN]..[LINES_MAX]（越界钳到边界，非整数拒收人话） */
    internal fun checkLines(raw: Int): SlotCheck =
        SlotCheck.Accepted(raw.coerceIn(LINES_MIN, LINES_MAX).toString())

    /** schema.key 槽：gsettings 白名单键（见 [GSETTINGS_KEYS]） */
    internal fun checkSchemaKey(raw: String): SlotCheck {
        val name = raw.trim()
        return if (name in GSETTINGS_KEYS) {
            SlotCheck.Accepted(name)
        } else {
            SlotCheck.Rejected(
                "schema.key「${name}」不在白名单内（允许：${GSETTINGS_KEYS.sorted().joinToString("、")}）——" +
                    "gsettings 键白名单防自由键名探测；需要新键：修改 core/llm/tools/SysProbeTools.kt 的 GSETTINGS_KEYS 常量（编译期单源）",
            )
        }
    }

    /** 扩展 uuid 槽：形态校验（uuid@domain；见 [EXTENSION_UUID_REGEX]） */
    internal fun checkUuid(raw: String): SlotCheck {
        val value = raw.trim()
        return if (EXTENSION_UUID_REGEX.matches(value)) {
            SlotCheck.Accepted(value)
        } else {
            SlotCheck.Rejected(
                "扩展 uuid「${raw}」形态非法——须是 uuid@domain 形态（如 ubuntu-dock@ubuntu.com）；" +
                    "可用 uuid 先经 gnome_extensions_list 条目枚举",
            )
        }
    }

    // ── 执行缝（测试注入 fake；生产恒走 ArgvProcessRunner） ──

    /**
     * 执行缝：目录条目 argv → 回执（单测注入 fake 断言 argv 组装与回执形态）。
     * 失败契约与 [ArgvProcessRunner.run] 同款（异常而非 Result 返回——suspend 函数返回
     * kotlin.Result 会被协程完成包装机制二次包箱，运行期 ClassCastException）：
     *
     * @throws IllegalArgumentException 执行面策略拒绝
     * @throws IOException 可执行文件不存在/启动失败
     * @throws kotlinx.coroutines.CancellationException 协程取消原样上抛
     */
    fun interface ProbeExecutor {
        suspend fun run(argv: List<String>): ArgvOutcome

        companion object {
            private val DEFAULT_RUNNER = ArgvProcessRunner()

            val DEFAULT: ProbeExecutor = ProbeExecutor { argv -> DEFAULT_RUNNER.run(argv) }
        }
    }

    // ── 工具：sys.probe ──

    class SysProbeTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val executor: ProbeExecutor = ProbeExecutor.DEFAULT,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 会话级门控面（P1c 装配门控；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val sysGate: SysSessionGate? = null,
    ) : StructuredSimpleTool<SysProbeTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = PROBE_NAME,
        description = "Run a whitelisted READ-ONLY local system probe for desktop-environment " +
            "diagnostics (input method / GNOME extensions / user services / user journal / " +
            "desktop basics). You can ONLY pick a catalog key, never craft a command string. " +
            "Keys: im_config_mode, im_launch_print, fcitx5_diagnose, im_env_vars, " +
            "fcitx5_processes, ibus_processes, desktop_env_basics, os_release, kernel_version, " +
            "gnome_extensions_list, gnome_extensions_info (needs uuid), gsettings_get " +
            "(needs schema_key), systemd_user_status (needs service), journalctl_user_tail " +
            "(optional lines 10..200), journalctl_user_unit (needs service, optional lines). " +
            "Slot params are whitelist/clamp validated — invalid values are rejected with the " +
            "allowed set. Non-zero exit code is DATA (pgrep no-match, missing unit), not failure.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Catalog key of the probe to run, e.g. im_config_mode / gnome_extensions_list / " +
                    "journalctl_user_unit. Pick from the catalog only — a command string is never accepted.",
            )
            val key: String,
            @property:LLMDescription(
                "Service name slot (systemd_user_status / journalctl_user_unit). " +
                    "Whitelist; the rejection message lists the allowed values.",
            )
            val service: String? = null,
            @property:LLMDescription(
                "Journal line count slot (journalctl_* keys), 10..200, default 50. Clamped to range.",
            )
            val lines: Int? = null,
            @property:LLMDescription(
                "gsettings schema.key slot (gsettings_get), e.g. org.gnome.shell.enabled-extensions. " +
                    "Whitelist; the rejection message lists the allowed values.",
            )
            val schema_key: String? = null,
            @property:LLMDescription(
                "GNOME extension uuid slot (gnome_extensions_info), e.g. ubuntu-dock@ubuntu.com. " +
                    "Enumerate installed extensions via gnome_extensions_list first.",
            )
            val uuid: String? = null,
        )

        /**
         * 审计包装体：执行结果 + 审计动作面（argv 全文；未成 argv 的拒收记 key 与形态）+
         * 退出码（仅执行面触达后有值）+ 裁决覆写（会话授权拒绝等非执行裁决）。
         */
        private data class Probed(
            val result: ToolResult,
            val action: String,
            val exitCode: Int?,
            val verdictOverride: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val probed = executeProbed(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = PROBE_NAME,
                    action = probed.action,
                    verdict = probed.verdictOverride ?: sysAuditVerdict(probed.result),
                    exitCode = probed.exitCode,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(probed.result),
                ),
            )
            return probed.result
        }

        private suspend fun executeProbed(args: Args): Probed {
            // 会话级总授权前置（P1c）：门控面缺席 = 装配缺陷，fail-closed 拒绝；
            // 授权卡拒绝/超时 = 本会话未授权，如实回 DENIED 让 LLM 告知用户。
            val gate = sysGate ?: return Probed(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "sys.* 只读诊断未装配会话门控面（fail-closed 拒绝）——本链不支持本机只读诊断。",
                ),
                action = "key=${args.key}（门控面缺席）",
                exitCode = null,
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(PROBE_NAME)) {
                return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 sys.* 只读诊断授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造诊断结果。",
                    ),
                    action = "key=${args.key}（会话授权拒绝）",
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val key = ProbeKey.entries.firstOrNull { it.id == args.key.trim().lowercase() }
                ?: return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知探查条目「${args.key}」。本工具只能从编译期目录里选条目，不能自造命令。" +
                            "可用条目：${ProbeKey.entries.joinToString("、") { it.id }}",
                    ),
                    action = "key=${args.key}（未命中目录）",
                    exitCode = null,
                )

            // 槽纪律：未约定的槽传值 = 拒收（参数面收窄到条目声明的槽，防 LLM 跨条目混用）
            val supplied = mapOf(
                ParamKind.SERVICE to args.service,
                ParamKind.LINES to args.lines?.toString(),
                ParamKind.SCHEMA_KEY to args.schema_key,
                ParamKind.UUID to args.uuid,
            ).filterValues { it != null }.mapValues { it.value as String }
            val unexpected = supplied.keys - key.required - key.optional
            if (unexpected.isNotEmpty()) {
                return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "条目 ${key.id} 不接受参数 ${unexpected.joinToString("、") { it.id }}——" +
                            "该条目只接受 ${(key.required + key.optional).joinToString("、") { it.id }}" +
                            "（无槽条目不接受任何参数）",
                    ),
                    action = "key=${key.id}（槽纪律拒收）",
                    exitCode = null,
                )
            }
            val missing = key.required - supplied.keys
            if (missing.isNotEmpty()) {
                return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "条目 ${key.id} 缺必填参数 ${missing.joinToString("、") { it.id }}",
                    ),
                    action = "key=${key.id}（缺必填槽）",
                    exitCode = null,
                )
            }

            // 槽校验：白名单/钳制/形态，非法值拒收人话
            val resolved = buildMap {
                for (kind in supplied.keys) {
                    when (val check = when (kind) {
                        ParamKind.SERVICE -> checkService(supplied.getValue(kind))
                        ParamKind.LINES -> checkLines(supplied.getValue(kind).toIntOrNull() ?: return Probed(
                            ToolResult.Err(
                                ToolResult.ErrCode.BAD_ARGS,
                                "行数参数须是整数（10..${LINES_MAX}），收到：${supplied.getValue(kind)}",
                            ),
                            action = "key=${key.id}（槽校验拒收）",
                            exitCode = null,
                        ))
                        ParamKind.SCHEMA_KEY -> checkSchemaKey(supplied.getValue(kind))
                        ParamKind.UUID -> checkUuid(supplied.getValue(kind))
                    }) {
                        is SlotCheck.Accepted -> put(kind, check.value)
                        is SlotCheck.Rejected -> return Probed(
                            ToolResult.Err(
                                ToolResult.ErrCode.BAD_ARGS,
                                check.reason,
                                hint = "按回执内列出的允许值调整后重试；探查目录与白名单均为编译期常量，无运行时配置面",
                            ),
                            action = "key=${key.id}（槽校验拒收）",
                            exitCode = null,
                        )
                    }
                }
            }

            val argv = key.buildArgv(resolved)
            val action = argv.joinToString(" ")
            // 失败契约见 ProbeExecutor：只翻译声明的异常（CE 穿透；其余异常上抛 = 契约破口，不吞）
            val outcome = try {
                executor.run(argv)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // 执行面策略拒绝（黑名单/缺 --user）：本工具目录结构上不会触发，此分支是防线兜底
                return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        e.message ?: "argv 被执行面策略拒绝",
                    ),
                    action = action,
                    exitCode = null,
                )
            } catch (e: IOException) {
                return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "命令「${argv.first()}」启动失败（本机未安装或不可执行）：${e.message ?: e.javaClass.simpleName}",
                        hint = "可改用其它条目探测，或如实告知用户该命令在本机不可用（禁止编造输出）",
                    ),
                    action = action,
                    exitCode = null,
                )
            }
            if (outcome.timedOut) {
                return Probed(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "命令「${argv.first()}」超时未完成，已被终止（探查命令应在秒级完成）",
                        hint = "可改用更窄的条目（如 journalctl_* 传小行数）重试，或如实告知用户该探查挂起",
                    ),
                    action = action,
                    exitCode = null,
                )
            }
            return Probed(renderReceipt(key, argv, outcome), action = action, exitCode = outcome.exitCode)
        }

        /** 结构化回执：key/argv/exit + 输出（恒脱敏 + 8K capAt 收口） */
        private fun renderReceipt(key: ProbeKey, argv: List<String>, outcome: ArgvOutcome): ToolResult {
            val output = SysOutputSanitizer.sanitize(outcome.output).ifBlank { "（无输出）" }
            val capped = capAt(output)
            val truncated = outcome.outputTruncated || capped != output
            val body = buildString {
                appendLine("key=${key.id}")
                appendLine("argv: ${argv.joinToString(" ")}")
                appendLine("exit=${outcome.exitCode}")
                appendLine("--- output ---")
                append(capped)
                if (outcome.exitCode != 0) {
                    appendLine()
                    appendLine(
                        "（退出码 ${outcome.exitCode} 非零——只读探查常见语义：无匹配进程/单元不存在/部分变量未设置，" +
                            "按命令语义解读，不是工具失败）",
                    )
                }
            }
            // 回执整体恒 8K 收口（头部行 + 非零解读提示一并计入，总长不依赖输出量推导成立）
            val cappedBody = capAt(body)
            return ToolResult.Ok(
                summary = cappedBody.trim(),
                data = ToolPayload.ProbePayload(
                    key = key.id,
                    exitCode = outcome.exitCode,
                    truncated = truncated || cappedBody != body,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `sys.probe` 目录描述符。uiHint = null：纯 agent 工具（诊断链自用），不进命令面板。
     * 装配门控：config.sysSessionGate 非 null 才注册（工厂返 null）；执行前必过
     * 信任用户域准入（工具内 fail-closed 兜底）。
     */
    val SYS_PROBE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = PROBE_NAME,
        description = "Run a whitelisted read-only local system probe (input method / GNOME " +
            "extensions / user services / user journal / desktop basics) — catalog keys only, " +
            "never command strings; output capped at 8K chars; non-zero exit is data",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { _, sink, config ->
            config.sysSessionGate?.let { gate ->
                SysProbeTool(sink, sysGate = gate, trigger = gate.trigger)
            }
        },
    )
}

// ── sys.* 工具族共用审计映射（同包 internal：SysProbeTools / SysReadConfigTools 共用） ──

/**
 * 审计裁决映射：Ok → 会话门控放行（门控面缺席的裸执行已被 fail-closed 拒绝，Ok 只出现在门控后）；
 * Err 按错误码分类（裁决常量语义见 [SysAuditLog] 类 KDoc）。
 */
internal fun sysAuditVerdict(result: ToolResult): String = when (result) {
    is ToolResult.Ok -> SysAuditLog.VERDICT_SESSION_GATED
    is ToolResult.Err -> when (result.code) {
        ToolResult.ErrCode.DENIED -> SysAuditLog.VERDICT_FENCE_DENIED
        ToolResult.ErrCode.BAD_ARGS -> SysAuditLog.VERDICT_BAD_ARGS
        ToolResult.ErrCode.NOT_FOUND -> SysAuditLog.VERDICT_NOT_FOUND
        ToolResult.ErrCode.FAILED -> SysAuditLog.VERDICT_FAILED
    }
}

/** 审计输出摘要：脱敏后回执单行化截 [SysAuditLog.SUMMARY_MAX_CHARS]（输出全文不落审计） */
internal fun sysAuditSummary(result: ToolResult): String =
    result.render().lineSequence().filter { it.isNotBlank() }.joinToString(" ")
        .take(SysAuditLog.SUMMARY_MAX_CHARS)

/** 审计时刻（sys.* 工具族共用入口——记录格式单源） */
internal fun sysAuditTimestamp(): String = SysAuditLog.timestamp()
