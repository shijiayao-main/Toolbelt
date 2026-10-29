package com.jiaoay.lark.bridge.exec

import java.io.File

/**
 * 敏感出站判据表：文件出站通道与文本出站面共用的判据真源——
 * 纯函数、零 IO、可直测。
 *
 * ## 为什么需要这一层
 * 放宽前 `send_file` 的放行面 = 本消息引用链（很窄）；放宽到工作区后，出现一条不对称：
 * 八件**读**类工具（probe_file / preview_text / …）仍走严格允许集，**读不了**工作区内文件的字节，
 * 而 `send_file` 只校验「存在 + 30MB」，**能把整个文件外发到飞书会话**。
 * 攻击链：引用文件里的恶意文本 → 间接 prompt injection → 诱导 LLM 调 `send_file`
 * 发走工作区内的敏感文件。若 `workspace.root` 配成家目录或 `~/.toolbelt`（常见），
 * 桥接配置文档（**明文 appSecret**）与宿主 DataStore（**明文 LLM API Key**）都在射程内。
 *
 * ## 判据（任一命中即拒）
 * 1. **宿主机密根内**（调用方传入的实际路径）——本判据覆盖 `~/.toolbelt` 整棵子树（文本面兼旧根 `~/.easy_dev_toolbox` 双形态），
 *    因而同时包含 ① `lark/`（桥接配置/审计/统计，含明文 appSecret）、
 *    ② `.datastore/`（宿主 DataStore——**明文 LLM API Key** 与 xlog 私钥在此）与
 *    ③ `audit/`（sys.* 只读诊断域审计——防擦痕迹式回读：审计外发即可在群内确认
 *    哪些操作未被追踪）。
 *    🔴 取**宿主应用数据根**而非 `lark/` 子目录：两者要防的是同一批机密，只差一级目录，
 *    只防 `lark/` 会在同级目录留缺口（对抗验证实测该缺口真实存在）。
 *    判据用 **canonicalPath** 比较：抗符号链接穿越、`./` 与 `a/../b` 变体。
 * 2. **文件名形态命中凭据族**（对 raw name 与 **canonical name 双重比对**，OR 语义）：
 *    `.env` 族、`id_rsa` 族私钥、`*.pem`/`*.key`/`*.p12`/`*.jks` 证书与密钥、
 *    桥接附件元数据 `.meta.json`、`credentials`/`secrets` 命名（含 stem 形态）。
 *    🔴 canonical 侧不可省：CLI 子进程能建软链（`ln -s ~/.ssh/id_rsa report.md`），
 *       只看 raw name 会被 `report.md` 骗过而放行私钥（对抗验证实测 ALLOW）。
 *
 * 🔴 方向是 **fail-closed**：无法解析的路径、可疑命中一律拒（误杀代价 = 用户收到「目标不可交付」
 *    而非静默泄漏；且这些文件名本就极少是用户的正常交付物）。
 * 本表**只约束出站交付**（`send_file`/`send_image`、`[产物]` 标记链与 reply 文本出站面），不改 [WorkspacePolicy] 的沙箱语义
 *    （CLI 子进程读写不受影响——它本就有等价读写能力；本表管的是「往外发」这一步）。
 * 🔴 **文件两通道 + 文本出站面三处必须共用本表判据**：漏装一处 = 同宽旁路（漏装的面即泄漏面，
 *    数据泄漏级门禁——文本面入口为 [sanitizeOutboundText]）。
 *
 * ## 有意误杀清单（fail-closed 的代价，显式登记）
 * - `.env.example` / `.env.template` 等模板（通常只有占位符）——仍拒，难与真凭据区分；
 * - `sample.key` / `keyboard.key` 一类**配置示例**——被扩展名族拒，属有意保守
 *   （`id_rsa.pub` 公钥**不拒**：不在名表，非误杀）。
 */
object SensitiveFilePolicy {

    /** 私钥/证书族扩展名（小写比较）。 */
    private val DANGEROUS_EXTENSIONS = setOf(
        "pem", "key", "p12", "pfx", "jks", "keystore",
    )

    /**
     * 精确名 / **stem**（去末段扩展名后）匹配表（小写比较）——凭据与密钥的惯用名。
     *
     * 🔴 语义是「`name ∈ 表` ∨ `stem(name) ∈ 表`」而非等值比对：等值比对会放行
     *    `credentials.json`（AWS 惯例）、`secrets.yaml`（k8s 惯例）、`id_rsa.bak`、
     *    `service-account.json` 等**真实凭据载体**（对抗验证实测 ALLOW）。
     *    取 stem 后 `id_rsa.bak` → `id_rsa` 命中，而 `keyboard.log` → `keyboard` 不命中。
     *
     * 🔴 无扩展名的裸名（`key` / `privkey` …）必须在此**逐字列出**：扩展名判据
     *    对无点文件名 `substringAfterLast('.')` 得空串，会被 `ext.isNotEmpty()` 短路。
     */
    private val DANGEROUS_STEMS = setOf(
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "credentials", "secrets", "secret", "passwd", "shadow",
        "key", "privkey", "privatekey", "private_key", "private-key",
        "service-account", "service_account", "aws-credentials",
        "bridge-config.json", // 带点形态：stem 语义下按整名比对亦命中
    )

    /** 双扩展名兜底（`report.md.id_rsa` 这类多段形态）：补中间段命中（末段已由 stem/ext 兜住）。 */
    private val DANGEROUS_INFIXES = listOf(".id_rsa", ".pem", ".key")

    /**
     * **危险名前缀**（`id_rsa` 族与 sys 审计族）：命中条件是「整名 == 前缀」或
     * 「名字以 `前缀.` / `前缀_` / `前缀-` 续写」。
     *
     * 🔴 为什么需要前缀而非仅 stem：`id_rsa_old` / `id_rsa-bak` 这类**无扩展名**的备份变体，
     * stem 判定（去末段扩展名）拿到的就是整个名字，与名表等值比对不中（对抗验证实测 ALLOW）。
     * 限定续写分隔符（`.`/`_`/`-`）是防误杀：普通文件名不会以 `id_rsa` 开头。
     * `sys-audit` 族：sys.* 只读诊断域审计文件（`sys-audit-<日期>.jsonl`）即使判据 1 的
     * 宿主根未知（homeDataRoot 为 null）也不放行——审计外发 = 擦痕迹式回读。
     */
    private val DANGEROUS_PREFIXES = setOf(
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "sys-audit",
    )

    /**
     * 公钥扩展名（显式**放行**）：`id_rsa.pub` / `x.pem.pub` 等。
     * 公钥不是机密，误拒会挡住正常交付；SSH 惯例里 `.pub` 专指公钥。
     */
    private const val PUBLIC_KEY_EXT = "pub"

    /**
     * 判定文件是否**禁止外发**。
     *
     * @param file 目标文件（调用方已确认 `isFile`）
     * @param homeDataRoot 宿主机密根（`~/.toolbelt`，null = 未知，跳过判据 1）
     * @return true = 命中排除表，出站应拒绝
     */
    fun isForbidden(file: File, homeDataRoot: File?): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull()

        // 判据 1：宿主机密根内（canonical 比较——抗软链穿越与路径变体）
        if (homeDataRoot != null) {
            val rootPath = runCatching { homeDataRoot.canonicalPath }.getOrNull()
            if (rootPath != null) {
                val candidates = listOfNotNull(
                    runCatching { file.canonicalPath }.getOrNull(),
                    canonical?.let { runCatching { it.canonicalPath }.getOrNull() },
                )
                if (candidates.any { it == rootPath || it.startsWith(rootPath + File.separator) }) {
                    return true
                }
            }
        }

        // 判据 2：文件名形态。raw 与 canonical **双重**比对（OR 语义）——
        //    canonical 侧兜住「软链伪装」（CLI 可 ln -s ~/.ssh/id_rsa report.md）；
        //    raw 侧兜住 canonical 解析失败或名字本身即危险名的常规情形。
        val names = buildList {
            add(file.name)
            canonical?.name?.takeIf { it != file.name }?.let { add(it) }
        }
        return names.any { nameMatches(it.lowercase()) }
    }

    /** 单名形态判定（判据 2 主体，raw/canonical 两侧共用）。 */
    private fun nameMatches(name: String): Boolean {
        if (name.isEmpty()) return false
        // .env / .env.local / .env.production …（前缀形式）
        if (name == ".env" || name.startsWith(".env.")) return true
        // 桥接附件元数据：精确名 `.meta.json`（与 DownloadStore.META_FILE_NAME 同源）。
        // 🔴 精确名而非 endsWith：`endsWith(".meta.json")` 会误杀 report.meta.json、
        //    dataset.meta.json 这类常见用户产物（对抗验证抓出的过宽误杀）。
        if (name == ".meta.json") return true
        // 公钥显式放行（SSH 惯例：.pub 专指公钥，不是机密）——先于一切拒绝判据
        if (name.substringAfterLast('.', "") == PUBLIC_KEY_EXT) return false
        // 精确名 / stem 命中
        if (name in DANGEROUS_STEMS) return true
        val stem = name.substringBeforeLast('.', missingDelimiterValue = "")
        if (stem.isNotEmpty() && stem in DANGEROUS_STEMS) return true
        // 私钥名前缀续写（id_rsa_old / id_rsa-bak / id_rsa.bak——无扩展名的备份变体）
        if (DANGEROUS_PREFIXES.any { p -> isPrefixedVariant(name, p) }) return true
        // 私钥/证书族扩展名
        val ext = name.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext in DANGEROUS_EXTENSIONS) return true
        // 多段形态（report.md.id_rsa）
        if (DANGEROUS_EXTENSIONS.any { name.endsWith(".$it") }) return true
        if (DANGEROUS_INFIXES.any { name.contains(it) }) return true
        return false
    }

    /** 名字是否为 [prefix] 的续写变体（`p` / `p.x` / `p_x` / `p-x`），限定分隔符防误杀。 */
    private fun isPrefixedVariant(name: String, prefix: String): Boolean =
        name == prefix ||
            name.startsWith("$prefix.") ||
            name.startsWith("${prefix}_") ||
            name.startsWith("$prefix-")

    // =====================================================================================
    // 文本出站面（reply 文本判据入口）。判据常量与文件通道零搬运共用（同 object）；
    // 文本是字符串非文件 ⇒ canonical 解析不可用，机密根改用形态串枚举（设计内上限，非沙箱）。
    // =====================================================================================

    /** 文本打码占位：纯文本安全（全角括号不构成 markdown 语法，代码块内命中不破坏结构）。 */
    const val TEXT_REDACTED_PLACEHOLDER: String = "（敏感内容已打码）"

    /**
     * 打码提示（整条出站文本末尾附一次）。与文件面 [DENIED_HINT] 措辞有意区分：
     * 本句自明「已打码后发送」而非「拒绝交付」，且不回显命中的具体形态（不给区分性信息）。
     */
    const val TEXT_REDACTED_HINT: String = "提示：以上内容检测到敏感信息，已自动打码后发送。"

    /** 整段拒发文案：仅当整段文本即机密根路径形态串时替换原文（不出原文任何片段）。 */
    const val TEXT_REJECTED_NOTICE: String = "该消息内容为桥接敏感数据路径，出于安全考虑未发送原文。"

    /**
     * 出站文本敏感过滤（reply 文本出站面的判据入口，判据与文件通道同源）。
     *
     * ## 机密根形态串比对
     * 判据 1 的机密根在文本面改用形态串枚举：literal `~/.toolbelt` / 旧根 `~/.easy_dev_toolbox`
     * 前缀 + [homeDataRoot] 注入路径串（raw / absolute / canonical 变体）。命中须做**分隔符边界对齐**
     * （命中串后跟 `/` 或串尾，串尾句点剥离），防 `.toolbelt_backup` 类前缀误杀。
     * `~user/`、`$HOME/` 等别名形态不在枚举内——文本面非沙箱，枚举式覆盖是设计上限，
     * 真实机密根变体由宿主注入值保证。
     *
     * ## 凭据名命中边界
     * 凭据名判据以**文件名形态**出现才命中（路径末段 / 独立 token），复用 [nameMatches] 四重判据；
     * `.pub` 放行、`.meta.json` 精确名语义随常量面天然继承。**纯字母裸词**（`secret`/`key` 一类
     * 自然语言普通词）以独立 token 出现时不打码——凭据族宁可漏打码不广打码，普通绝对路径与
     * 日志分析主业文本的误杀面必须为零（机密根形态零漏为准）。
     *
     * ## 处置边界（显式登记规则，放宽/收紧须经提案变更）
     * - 命中段替换为 [TEXT_REDACTED_PLACEHOLDER]（多处命中逐段打码），整条文本末尾附一次
     *   [TEXT_REDACTED_HINT]；打码是内容变换不是交付失败（调用方 `SendResult.ok` 语义不变）。
     * - 仅机密根路径形态**整串**出现（整段文本即机密根形态串）才整段拒发：返回
     *   [TEXT_REJECTED_NOTICE]，不出原文任何片段。
     *
     * @param text 出站文本原文
     * @param homeDataRoot 宿主机密根；null = 未知 ⇒ 机密根形态判据（含 literal 形态）跳过、
     *   凭据名判据仍生效（与 [isForbidden] 的 null 语义同义，fail-closed 精神保留在凭据族）
     * @return 可发出文本：无命中返回原串（引用不变）；命中返回打码后文本；整串机密根返回拒绝文案
     */
    fun sanitizeOutboundText(text: String, homeDataRoot: File?): String {
        if (text.isEmpty()) return text
        val roots = secretRootForms(homeDataRoot)

        // 整段拒发：仅当整段文本（去首尾空白）即一个机密根形态串——
        // 混合文本（路径 + 说明文字）不触发，走打码路径。
        val trimmed = text.trim()
        if (roots.isNotEmpty() &&
            trimmed.isNotEmpty() &&
            trimmed.all { isPathTokenChar(it) } &&
            roots.any { isSecretRootToken(trimmed, it) }
        ) {
            return TEXT_REJECTED_NOTICE
        }

        val ranges = findSensitiveRanges(text, roots)
        if (ranges.isEmpty()) return text

        val sb = StringBuilder(text.length + TEXT_REDACTED_HINT.length + 4)
        var cursor = 0
        for (range in ranges) {
            sb.append(text, cursor, range.first)
            sb.append(TEXT_REDACTED_PLACEHOLDER)
            cursor = range.last + 1
        }
        sb.append(text, cursor, text.length)
        sb.append("\n\n").append(TEXT_REDACTED_HINT)
        return sb.toString()
    }

    /**
     * 机密根 literal 形态（`~/` → 宿主 home 的展开规则）：当前数据根 `~/.toolbelt`，
     * 兼保留旧根形态 `~/.easy_dev_toolbox`（改名迁移前落点）——历史文本/未迁移机器上的
     * 旧串同样必须打码，撤掉即漏杀。真实路径变体由 [homeDataRoot] 注入值补充。
     */
    private const val LITERAL_SECRET_ROOT = "~/.toolbelt"
    private const val LEGACY_LITERAL_SECRET_ROOT = "~/.easy_dev_toolbox"

    /**
     * 机密根形态串集合（null ⇒ 空表 = 机密根判据整体跳过）。
     * literal + raw/absolute/canonical 变体并列：文本里的路径串可能是任一形态的原文回显。
     */
    private fun secretRootForms(homeDataRoot: File?): List<String> {
        if (homeDataRoot == null) return emptyList()
        val forms = linkedSetOf(LITERAL_SECRET_ROOT, LEGACY_LITERAL_SECRET_ROOT)
        forms += homeDataRoot.path
        forms += homeDataRoot.absolutePath
        runCatching { homeDataRoot.canonicalPath }.getOrNull()?.let { forms += it }
        return forms.filter { it.isNotBlank() && it != "/" }
    }

    /** token 是否为机密根形态串：整串等于根，或根 + 分隔符续写（边界对齐防前缀误杀）。 */
    private fun isSecretRootToken(token: String, root: String): Boolean {
        val t = token.trimEnd('.')
        return t == root || t.startsWith("$root/") || t.startsWith("$root${File.separator}")
    }

    /**
     * 扫描文本中需打码的区段（单趟，token 互斥 ⇒ 区间天然有序不重叠）。
     * 机密根命中整 token 打码；凭据名命中只打码文件名段（路径前缀保留——目录结构本身不敏感）。
     */
    private fun findSensitiveRanges(text: String, roots: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isPathTokenChar(text[i])) {
                i++
                continue
            }
            var j = i
            while (j < n && isPathTokenChar(text[j])) j++
            val token = text.substring(i, j)
            if (roots.any { root -> isSecretRootToken(token, root) }) {
                ranges += i until j
            } else {
                credentialNameOffset(token)?.let { offset -> ranges += (i + offset) until j }
            }
            i = j
        }
        return ranges
    }

    /**
     * 凭据名在 token 内的起始偏移（null = 不命中）。
     * 文件名 = 路径**末段**（目录名形态不判——判中段会把 `/data/data/com.android.key` 一类
     * 日志常见路径误打码）；独立 token（无路径分隔符）视为文件名本身。
     * 纯字母裸词以独立 token 出现时按散文普通词放行（带 `.`/`_`/`-` 等文件名形态特征者不受此豁免）。
     */
    private fun credentialNameOffset(token: String): Int? {
        val slash = maxOf(token.lastIndexOf('/'), token.lastIndexOf('\\'))
        val name = if (slash >= 0) token.substring(slash + 1) else token
        if (name.isEmpty()) return null
        val lower = name.lowercase()
        if (slash < 0 && lower in DANGEROUS_STEMS && BARE_PROSE_WORD.matches(lower)) return null
        return if (nameMatches(lower)) slash + 1 else null
    }

    /**
     * token 字符集：ASCII 字母数字 + 文件名/路径惯用符号（`~` 是机密根 literal 形态的组成部分）。
     * CJK 与标点天然断词：中文散文里的嵌入路径即独立 token，句读不打进 token。
     */
    private fun isPathTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '.' || c == '_' || c == '-' || c == '/' || c == '\\' || c == '~'

    /** 纯小写字母串（自然语言普通词形态——`secret`/`key` 一类散文用法的豁免判据）。 */
    private val BARE_PROSE_WORD = Regex("[a-z]+")

    /** 人类可读的拒绝原因（喂 LLM 的工具反馈文案，便于它换目标或如实告知用户）。 */
    const val DENIED_HINT: String =
        "该文件属于敏感/凭据类或桥接自身的配置数据，出于安全考虑不允许外发。请改用其他产物文件。"

    /**
     * 统一对外拒绝文案（收窄存在性 oracle）。
     *
     * 为什么与 [DENIED_HINT] 分开：`send_file` 的判据顺序会经三段文案
     *    （「文件不存在」/DENIED_HINT/「不在围栏内」）**暴露任意路径是否存在**——
     *    LLM 可用它枚举 `~/.ssh/id_rsa`、`.env.prod` 等围栏外路径的存在性。
     *    围栏外路径统一回本句，区分性信息只留给日志与审计。
     */
    const val OPAQUE_DENIED_HINT: String =
        "该路径不可交付（不在本消息的可发送范围内）。请检查路径，或改用文字答复。"
}
