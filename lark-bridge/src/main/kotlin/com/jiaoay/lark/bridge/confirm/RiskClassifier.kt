// 高危操作分类器。
// WorkspacePolicy 从 exec 包取（lib 侧统一坐标，`isEmpty` / `requireInsideWorkspace` 同名同义）。
// 🔴 规则表字面值（FORBIDDEN 5 条 / REMOTE_WRITE 5 条 / DELETE_TOOL_NAMES 6 个 / DELETE_INPUT_PATTERNS 12 条 /
//    ARCHIVE_CREATE 5 条 / WRITE_TOOL_NAMES 7 个）
//    与判定顺序（FORBIDDEN+REMOTE_WRITE → DELETE_TOOL → DELETE_INPUT → ARCHIVE_CREATE → 越界+WRITE → WRITE → LOW）勿随手改。
package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.exec.WorkspacePolicy
import java.io.File

/**
 * 高危操作分类器：对 MCP tools/call 的 toolName + input 做规则匹配，输出 [RiskLevel]。
 *
 * 规则设计原则：
 * - 删除类模式（rm/rmdir/unlink/Delete/shutil.rmtree/git clean 等）→ HIGH 或 FORBIDDEN
 * - 远端写（git push / git remote 写形态 / gh pr|release create / git svn dcommit）→ FORBIDDEN
 *   （deny 不可确认通过——push/remote 外发同时踩「偷代码+上传 git」双红线，本地 commit 放行）
 * - 归档/打包创建（tar/zip/7z/gzip）→ HIGH（强制确认——[产物] 标记链单跳批量外发的最短形态）
 * - 越出白名单的路径操作 → HIGH（强制确认）
 * - 普通读取 → LOW（无需确认）
 * - 规则表集中、可扩展、可被 skill 追加
 *
 * 判定结果可写审计（上层调用方负责）。
 *
 * 本 object 体内**只有不可变常量**（Regex/Set/List），无任何可变全局状态；
 * 唯一的外部依赖 [WorkspacePolicy] 由调用方传入（构造注入语义）。
 */
object RiskClassifier {

    /** 风险等级 */
    enum class RiskLevel {
        /** 低风险：只读操作，无需确认 */
        LOW,
        /** 中风险：写入操作（编辑/创建文件），可选择性确认 */
        MEDIUM,
        /** 高风险：删除/破坏性操作，强制飞书确认 */
        HIGH,
        /** 禁止：直接 deny，不允许确认通过（越界删除等极端操作） */
        FORBIDDEN,
    }

    /** 分类结果（附带匹配的规则描述，审计可用） */
    data class Classification(
        val level: RiskLevel,
        /** 命中的规则描述（审计日志用） */
        val rule: String = "",
    )

    // ── 删除类高危模式（toolName 或 input 命中即 HIGH） ──

    /** 删除类工具名模式（精确匹配 toolName） */
    private val DELETE_TOOL_NAMES = setOf(
        "Delete", "Remove", "DeleteFile", "RemoveFile",
        "delete_file", "remove_file",
    )

    /** 删除类命令模式（input 内容匹配，不区分大小写） */
    private val DELETE_INPUT_PATTERNS = listOf(
        Regex("""(?i)\brm\s+(-[rfiv]+\s+)*"""),
        Regex("""(?i)\brmdir\b"""),
        Regex("""(?i)\bunlink\b"""),
        Regex("""(?i)\bshutil\.rmtree\b"""),
        Regex("""(?i)\bgit\s+clean\b"""),
        Regex("""(?i)\bfind\b.*-delete\b"""),
        Regex("""(?i)\bRemove-Item\b"""),
        Regex("""(?i)\bdel\s+/[sfq]"""),
        // 收窄为磁盘格式化语义（`format C:` / `format /dev/sda`），
        // 避免误判含 format 的正常文本（如 `format json`、`formatted output`、代码里的 format() 调用）
        Regex("""(?i)\bformat\s+([a-z]:|/dev/)"""),
        Regex("""(?i)\bmkfs\b"""),
        Regex("""(?i)\bdd\s+if=.*of=/dev/"""),
    )

    // ── 破坏性 shell 命令模式 ──

    /**
     * 破坏性操作（直接 FORBIDDEN）。
     *
     * `rm -rf /` 不用 `$` 锚定：input 常是 JSON 包裹（`{"command":"rm -rf /"}`）或带尾部空白/分号，
     * 锚定会被绕过。改为后跟空白/引号/命令分隔符/串尾，既覆盖 JSON 包裹与命令拼接，
     * 又不误伤 `rm -rf /workspace/build`（`/` 后接路径字符）。
     */
    private val FORBIDDEN_PATTERNS = listOf(
        Regex("""(?i)\brm\s+-rf\s+/(\s|"|'|;|&|\||$)"""),  // rm -rf /（含 JSON 包裹与命令拼接）
        Regex("""(?i)\brm\s+-rf\s+/\*"""),             // rm -rf /*
        Regex("""(?i)\brm\s+-rf\s+~"""),               // rm -rf ~
        Regex("""(?i):\(\)\{.*\|.*&\s*\}"""),           // fork bomb
        Regex("""(?i)\bchmod\s+-R\s+777\s+/"""),       // chmod -R 777 /
    )

    // ── 远端写模式组（红线④封死：push/外发到远端 → FORBIDDEN，deny 不可确认通过） ──

    /**
     * 远端写模式组：git push / git remote 指向远端的写形态 / gh pr|release create / git svn dcommit。
     * 本地 git 读写（commit / log / status / diff / remote -v / config）**放行**——禁区只圈「外发到远端」。
     *
     * 旗标与 `-C <路径>` 变体：`git\s+(?:<全局选项>\s+)*<子命令>` 骨架里全局选项取
     * `-{1,2}名字[= 值]` 两型（`-C /repo` / `--git-dir=/x` / `-c a=b` 单 token）。
     * 已知残余（如实登记）：git alias 展开（`git config alias.p 'push'` 后 `git p`）与
     * `sh -c` 拼串内的间接构造不在本表覆盖面——别名配置本身属工作区写操作，逐命令确认链（workspace
     * 档 Bash 不预批）与确认卡兜底。
     */
    private val REMOTE_WRITE_PATTERNS = listOf(
        // git push（含 -C / --git-dir / -c 等全局选项变体与 JSON 包裹；git config push.default 不在此形态——子命令位是 config）
        Regex("""(?i)\bgit\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ](?!push\b)\S+)?\s+)*push\b"""),
        // git remote 指向远端的写形态（add/set-url/remove/rm——远端端点配置写 = 外发通道持久化；-v/get-url 只读放行）
        Regex("""(?i)\bgit\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*remote\s+(?:add|set-url|remove|rm)\b"""),
        // gh pr create（旗标变体：gh --repo x pr create / gh pr --repo x create）
        Regex("""(?i)\bgh\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*pr\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*create\b"""),
        // gh release create（PR 外第二条内容外发面：release 资产/正文直接上传远端）
        Regex("""(?i)\bgh\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*release\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*create\b"""),
        // git svn dcommit（推送到 SVN 远端的 dcommit 推送类）
        Regex("""(?i)\bgit\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*svn\s+(?:-{1,2}[A-Za-z][\w.-]*(?:[= ]\S+)?\s+)*dcommit\b"""),
    )

    // ── 归档/打包创建命令（[产物] 标记链单跳批量外发的最短形态 → HIGH 强制确认） ──

    /**
     * 归档/打包创建命令：一次 `tar czf` 即可把整棵工作区打成单文件、一条 `[产物]` 标记行
     * 整仓出机器——与删除类同判 HIGH（可确认），出站侧另有 Artifacts 归档确认卡双闸。
     *
     * tar 短旗标**区分大小写**（-c create / -C 换目录）：`-C /tmp -xf` 是解包不是打包，
     * 大小写不敏感会把 `-C` 误判成 create；长旗标 --create 与无横线连写（czf）另列。
     * 已知残余：文件名恰为 `c` 开头 ≤4 字母的解包目标（`tar xf cf.tar`）可能误升 HIGH
     * （多一次确认卡，fail-safe 方向，接受）。
     */
    private val ARCHIVE_CREATE_PATTERNS = listOf(
        // tar 创建形态：--create / -c/-czf（短旗标大小写敏感）/ czf 无横线连写（旗标 token ≤5 字符，防误伤文件名）
        Regex("""\btar\s+(?:\S+\s+)*(?:--create|-c[a-z]{0,4}|c[a-z]{0,4})(?=\s|$)"""),
        // zip 创建：负向后顾 (?<![\w.]) 把命中收窄到命令位（unzip 天然无边界；
        // `out.zip -d` 类文件名后缀不构成命令位——解包场景不得误升 HIGH）
        Regex("""(?i)(?<![\w.])zip\s+-?\S"""),
        // 7z 添加（7z a / 7za a——a 是 7z 的 add 子命令；负向后顾排除 `x.7z a` 类文件名形态）
        Regex("""(?i)(?<![\w.])7za?\s+(?:\S+\s+)*a(?=\s|$)"""),
        // gzip 单文件压缩（tar.gz 链的另一半；gunzip 不含 \bgzip 边界）
        Regex("""(?i)\bgzip\b"""),
    )

    // ── 写入类中风险模式 ──

    private val WRITE_TOOL_NAMES = setOf(
        "Edit", "Write", "NotebookEdit",
        "edit", "write",
        "Bash", "shell",
    )

    /**
     * 对工具调用做风险分类。
     *
     * @param toolName MCP tools/call 的工具名
     * @param input 工具入参（JSON 字符串或命令文本）
     * @param policy 工作区策略（可选；提供时校验路径越界）
     * @return [Classification] 含等级与命中规则
     */
    fun classify(toolName: String, input: String, policy: WorkspacePolicy? = null): Classification {
        // 1. FORBIDDEN：破坏性命令直接禁止
        FORBIDDEN_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(input)) {
                return Classification(RiskLevel.FORBIDDEN, "forbidden pattern: ${pattern.pattern}")
            }
        }

        // 1R. FORBIDDEN：远端写（push/外发到远端）——红线④deny，不可确认通过；先于删除类（踩双红线的组合以更严档记录）
        REMOTE_WRITE_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(input)) {
                return Classification(RiskLevel.FORBIDDEN, "remote-write pattern: ${pattern.pattern}")
            }
        }

        // 2. HIGH：删除类工具名精确命中
        if (toolName in DELETE_TOOL_NAMES) {
            return Classification(RiskLevel.HIGH, "delete tool: $toolName")
        }

        // 3. HIGH：input 含删除类命令模式
        DELETE_INPUT_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(input)) {
                return Classification(RiskLevel.HIGH, "delete command pattern: ${pattern.pattern}")
            }
        }

        // 3A. HIGH：归档/打包创建命令（单跳批量外发链的最短形态——tar 一次打包整仓 + 一条标记行出机器）
        ARCHIVE_CREATE_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(input)) {
                return Classification(RiskLevel.HIGH, "archive-create pattern: ${pattern.pattern}")
            }
        }

        // 4. HIGH：路径越出白名单（仅当 policy 可用时校验）
        if (policy != null && !policy.isEmpty) {
            val paths = extractPaths(input)
            val outside = paths.any { path ->
                runCatching { !policy.requireInsideWorkspace(File(path)) }.getOrDefault(true)
            }
            if (outside && toolName in WRITE_TOOL_NAMES) {
                return Classification(RiskLevel.HIGH, "path outside workspace whitelist")
            }
        }

        // 5. MEDIUM：写入类工具
        if (toolName in WRITE_TOOL_NAMES) {
            return Classification(RiskLevel.MEDIUM, "write/shell tool: $toolName")
        }

        // 6. LOW：其余（读取类）
        return Classification(RiskLevel.LOW, "read-only tool: $toolName")
    }

    /** 从 input 中提取可能的文件路径（简单启发式：以 /、~ 或 Windows 盘符开头的 token） */
    private fun extractPaths(input: String): List<String> =
        input.split(Regex("""[\s,;|&()]+"""))
            .filter { it.startsWith("/") || it.startsWith("~") || looksLikeWindowsPath(it) }
            .map { it.trim('"', '\'') }
            .filter { it.length > 1 }

    /** Windows 盘符路径启发式（C:\… 或 C:/…）：Windows 上白名单越界判定同样需要拿到路径 token */
    private fun looksLikeWindowsPath(token: String): Boolean =
        token.length >= 3 && token[0].isLetter() && token[1] == ':' && (token[2] == '\\' || token[2] == '/')
}
