// M4-T8 第一批 · B 组 confirm 测试：高危操作分类器。
// 来源：legacy `confirm/RiskClassifierTest.kt`（220 行 / 19 条）**逐条移植** + 规则表穷尽断言。
// 🔴 唯一移植差异：`import …lark.WorkspacePolicy` → `import …exec.WorkspacePolicy`（T6 已平移，行为逐字一致）。
package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.exec.WorkspacePolicy
import com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings
import com.jiaoay.lark.bridge.pipeline.testRepoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RiskClassifier]：BC §3 主契约表 `confirm/RiskClassifierTest` 条目的逐条移植
 * + P1c 增量（远端写 FORBIDDEN 模式组 / 归档创建 HIGH 模式组）。
 *
 * 判定顺序（P1c 后）：FORBIDDEN → REMOTE_WRITE(FORBIDDEN) → DELETE_TOOL → DELETE_INPUT →
 * ARCHIVE_CREATE(HIGH) → 越界+WRITE → WRITE → LOW。
 */
class RiskClassifierTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "risk-test-$name-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    // ── FORBIDDEN：破坏性命令直接禁止 ──

    @Test
    fun `rm -rf 根目录识别为 FORBIDDEN`() {
        val result = RiskClassifier.classify("Bash", "rm -rf /")
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, result.level)
        assertTrue(result.rule.contains("forbidden"))
    }

    @Test
    fun `rm -rf 家目录识别为 FORBIDDEN`() {
        val result = RiskClassifier.classify("Bash", "rm -rf ~")
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, result.level)
    }

    /**
     * 🔒 旧 Minor 修复：旧规则 `\brm\s+-rf\s+/$` 用 `$` 锚定，而 MCP 传来的 input 常是
     * JSON 包裹（`{"command":"rm -rf /"}`）或带尾随空白/分号，锚定直接被绕过。
     */
    @Test
    fun `rm -rf 根目录的 JSON 包裹形式仍识别为 FORBIDDEN`() {
        val result = RiskClassifier.classify("Bash", """{"command":"rm -rf /"}""")
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, result.level)
    }

    @Test
    fun `rm -rf 根目录带尾随参数仍识别为 FORBIDDEN`() {
        assertEquals(
            RiskClassifier.RiskLevel.FORBIDDEN,
            RiskClassifier.classify("Bash", "rm -rf / --no-preserve-root").level,
        )
        assertEquals(
            RiskClassifier.RiskLevel.FORBIDDEN,
            RiskClassifier.classify("Bash", "sudo rm -rf /;").level,
        )
        assertEquals(
            RiskClassifier.RiskLevel.FORBIDDEN,
            RiskClassifier.classify("Bash", "rm -rf / ").level,
            "尾部空白不得绕过",
        )
        assertEquals(
            RiskClassifier.RiskLevel.FORBIDDEN,
            RiskClassifier.classify("Bash", "rm -rf / && echo done").level,
            "命令拼接不得绕过",
        )
    }

    @Test
    fun `rm -rf 工作区子目录不因去锚定而误升 FORBIDDEN`() {
        val result = RiskClassifier.classify("Bash", "rm -rf /workspace/project/build")
        assertTrue(
            result.level != RiskClassifier.RiskLevel.FORBIDDEN,
            "子目录删除应为 HIGH（可确认）而非 FORBIDDEN",
        )
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        // 带分号的子目录删除同样不得误升
        assertTrue(
            RiskClassifier.classify("Bash", "rm -rf /workspace/build;").level !=
                RiskClassifier.RiskLevel.FORBIDDEN,
        )
    }

    // 🔒 FORBIDDEN 第 2 条：rm -rf 加通配根（Kotlin 支持嵌套块注释，故此处不用 KDoc 包裹该字面量）
    @Test
    fun `rm -rf 通配根识别为 FORBIDDEN`() {
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, RiskClassifier.classify("Bash", "rm -rf /*").level)
    }

    /** 🔒 FORBIDDEN 第 4 条：fork bomb `:(){ :|:& }` */
    @Test
    fun `fork bomb 识别为 FORBIDDEN`() {
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, RiskClassifier.classify("Bash", ":(){ :|:& };:").level)
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, RiskClassifier.classify("Bash", ":(){:|:&}").level)
    }

    /** 🔒 FORBIDDEN 第 5 条：`chmod -R 777 /` */
    @Test
    fun `chmod -R 777 根目录识别为 FORBIDDEN`() {
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, RiskClassifier.classify("Bash", "chmod -R 777 /").level)
    }

    /**
     * 🔒 旧 Minor 修复：`\bformat\b` 收窄为磁盘格式化语义（`format C:` / `format /dev/sda`），
     * 避免误判含 format 的正常文本（`format json`、`String.format(...)`、`--format=pretty`）。
     */
    @Test
    fun `磁盘格式化命令识别为 HIGH`() {
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "format C:").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "format /dev/sda").level)
    }

    @Test
    fun `含 format 的正常文本不被误判为删除类 HIGH`() {
        listOf(
            "format json",
            "--format=pretty",
            """String.format("%s", value)""",
            "please format the output nicely",
        ).forEach { input ->
            val result = RiskClassifier.classify("Bash", input)
            assertTrue(
                result.level != RiskClassifier.RiskLevel.HIGH,
                "「$input」不应命中删除类规则，实际 ${result.level} / ${result.rule}",
            )
            assertTrue(
                !result.rule.contains("delete"),
                "「$input」不应命中 delete 规则，实际 ${result.rule}",
            )
        }
        // 只读工具 + 含 format 的普通文本 → LOW
        assertEquals(RiskClassifier.RiskLevel.LOW, RiskClassifier.classify("Read", "format json").level)
    }

    // ── FORBIDDEN：远端写模式组（红线④封死——push/外发到远端不可确认通过） ──

    @Test
    fun `git push 识别为 FORBIDDEN（含变体与 JSON 包裹）`() {
        listOf(
            "git push",
            "git push origin main",
            "git push --mirror",
            "git push --tags --force",
            "git -C /repo push origin main",
            "git --git-dir=/repo/.git push",
            "git -c http.sslVerify=false push",
            "GIT PUSH origin master",
            """{"command":"git push origin main"}""",
            "cd /repo && git push",
        ).forEach { input ->
            assertEquals(
                RiskClassifier.RiskLevel.FORBIDDEN,
                RiskClassifier.classify("Bash", input).level,
                "「$input」必须 FORBIDDEN（红线④：上传 git）",
            )
        }
    }

    @Test
    fun `git remote 写形态识别为 FORBIDDEN、只读形态放行`() {
        listOf(
            "git remote add origin https://example.com/x.git",
            "git remote set-url origin https://evil.example.com/x.git",
            "git remote remove origin",
            "git remote rm origin",
        ).forEach { input ->
            assertEquals(
                RiskClassifier.RiskLevel.FORBIDDEN,
                RiskClassifier.classify("Bash", input).level,
                "「$input」必须 FORBIDDEN（远端端点写 = 外发通道持久化）",
            )
        }
        // 只读列表/查询放行（不踩红线④）
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "git remote -v").level)
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "git remote get-url origin").level)
    }

    @Test
    fun `gh pr create 与 gh release create 识别为 FORBIDDEN（含旗标变体）`() {
        listOf(
            "gh pr create --title x --body y",
            "gh --repo owner/x pr create",
            "gh pr --repo owner/x create",
            "gh release create v1 ./artifact.tar.gz",
            """{"command":"gh pr create --fill-first"}""",
        ).forEach { input ->
            assertEquals(
                RiskClassifier.RiskLevel.FORBIDDEN,
                RiskClassifier.classify("Bash", input).level,
                "「$input」必须 FORBIDDEN（PR/release 即内容外发面）",
            )
        }
        // 只读 gh 操作放行
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "gh pr view 12").level)
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "gh pr list").level)
    }

    @Test
    fun `git svn dcommit 识别为 FORBIDDEN`() {
        assertEquals(
            RiskClassifier.RiskLevel.FORBIDDEN,
            RiskClassifier.classify("Bash", "git svn dcommit").level,
        )
    }

    @Test
    fun `本地 git 读写放行（commit、log、status、diff、config 不踩红线④）`() {
        listOf(
            "git commit -m \"wip\"",
            "git commit --amend",
            "git log --oneline -10",
            "git status",
            "git diff HEAD~1",
            "git config push.default simple", // 子命令位是 config，不是 push
            "git stash push -m wip",          // stash push 是本地操作
            "git add -A && git commit -m x",
        ).forEach { input ->
            val result = RiskClassifier.classify("Bash", input)
            assertTrue(
                result.level != RiskClassifier.RiskLevel.FORBIDDEN,
                "本地 git 操作「$input」不得误判 FORBIDDEN（实际 ${result.level} / ${result.rule}）",
            )
        }
    }

    /** 🔴 判定序锚：远端写 FORBIDDEN 先于删除类 HIGH（踩双红线的组合以更严档记录） */
    @Test
    fun `判定顺序 远端写 FORBIDDEN 优先于删除类 HIGH`() {
        val result = RiskClassifier.classify("Bash", "git push origin main && rm -rf /workspace/build")
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, result.level)
        assertTrue(result.rule.startsWith("remote-write"), "rule 应记录先命中的远端写模式：${result.rule}")
    }

    // ── HIGH：归档/打包创建命令（[产物] 标记链单跳批量外发的最短形态） ──

    @Test
    fun `tar 打包形态识别为 HIGH（含旗标与无横线连写变体）`() {
        listOf(
            "tar czf out.tar.gz /workspace",
            "tar -czf out.tar.gz .",
            "tar -c -f out.tar .",
            "tar cvzf out.tgz dir/",
            "tar --create -f out.tar dir",
            "tar -C /tmp -czf out.tar.gz /workspace",
        ).forEach { input ->
            assertEquals(
                RiskClassifier.RiskLevel.HIGH,
                RiskClassifier.classify("Bash", input).level,
                "「$input」必须 HIGH（归档创建 = 单跳批量外发形态）",
            )
        }
    }

    @Test
    fun `tar 解包与目录旗标不误升 HIGH（短旗标大小写敏感）`() {
        listOf(
            "tar -xf archive.tar",
            "tar -xvf archive.tar.gz",
            "tar -C /tmp -xf archive.tar",   // -C 是换目录旗标不是 create
            "tar tf archive.tar",
        ).forEach { input ->
            val result = RiskClassifier.classify("Bash", input)
            assertTrue(
                result.level != RiskClassifier.RiskLevel.HIGH || !result.rule.contains("archive-create"),
                "解包形态「$input」不得命中归档创建规则（实际 ${result.level} / ${result.rule}）",
            )
        }
    }

    @Test
    fun `zip 7z gzip 打包形态识别为 HIGH（unzip、gunzip、bzip2 不误伤）`() {
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "zip -r out.zip /workspace").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "zip out.zip a.txt b.txt").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "7z a out.7z /workspace").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "7za a out.7z .").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "gzip big.log").level)
        // 解压/无关词不误伤
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "unzip archive.zip -d out").level)
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "gunzip a.gz").level)
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "bzip2 -d a.bz2").level)
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Bash", "7z x archive.7z").level)
    }

    /** 🔴 判定序锚：删除类 HIGH 先于归档创建 HIGH（rule 文本可区分命中组） */
    @Test
    fun `判定顺序 删除类优先于归档创建`() {
        val result = RiskClassifier.classify("Bash", "rm -rf build && tar czf out.tar.gz .")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        assertTrue(result.rule.startsWith("delete"), "删除模式先命中：${result.rule}")
    }

    // ── HIGH：删除类操作 ──

    @Test
    fun `rm 命令识别为 HIGH`() {
        val result = RiskClassifier.classify("Bash", "rm -rf /workspace/project/build")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        assertTrue(result.rule.contains("delete"))
    }

    @Test
    fun `rmdir 识别为 HIGH`() {
        val result = RiskClassifier.classify("Bash", "rmdir /tmp/empty")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    @Test
    fun `unlink 识别为 HIGH`() {
        val result = RiskClassifier.classify("Bash", "unlink /workspace/file.txt")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    @Test
    fun `git clean 识别为 HIGH`() {
        val result = RiskClassifier.classify("Bash", "git clean -fd")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    @Test
    fun `shutil rmtree 识别为 HIGH`() {
        val result = RiskClassifier.classify("Bash", "python -c \"import shutil; shutil.rmtree('/tmp/x')\"")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    @Test
    fun `Delete 工具名识别为 HIGH`() {
        val result = RiskClassifier.classify("Delete", "/workspace/file.kt")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        assertTrue(result.rule.contains("delete tool"))
    }

    @Test
    fun `DeleteFile 工具名识别为 HIGH`() {
        val result = RiskClassifier.classify("DeleteFile", "some/path")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    /** 🔒 DELETE_INPUT_PATTERNS 剩余未覆盖项逐条（Remove-Item / del /s|f|q / mkfs / dd if=…of=/dev/…） */
    @Test
    fun `Windows 与裸设备删除命令识别为 HIGH`() {
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "Remove-Item C:\\tmp\\a").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "del /s /q C:\\tmp").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "del /f x.txt").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "mkfs.ext4 /dev/sdb1").level)
        assertEquals(
            RiskClassifier.RiskLevel.HIGH,
            RiskClassifier.classify("Bash", "dd if=/dev/zero of=/dev/sda bs=1M").level,
        )
    }

    /** 🔒 大小写不敏感（每条模式都带 `(?i)`） */
    @Test
    fun `删除类模式大小写不敏感`() {
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "RM -rf /workspace/a").level)
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, RiskClassifier.classify("Bash", "RM -RF /").level)
        assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Bash", "GIT CLEAN -fd").level)
    }

    /** 🔴 判定顺序：FORBIDDEN 先于 DELETE_TOOL（`Delete` + `rm -rf /` ⇒ FORBIDDEN 而非 HIGH） */
    @Test
    fun `判定顺序 FORBIDDEN 优先于删除类工具名`() {
        val result = RiskClassifier.classify("Delete", """{"command":"rm -rf /"}""")
        assertEquals(RiskClassifier.RiskLevel.FORBIDDEN, result.level)
        assertTrue(result.rule.startsWith("forbidden"), "rule 应记录先命中的 FORBIDDEN：${result.rule}")
    }

    /** 🔴 判定顺序：DELETE_TOOL 先于 DELETE_INPUT（rule 文本可区分） */
    @Test
    fun `判定顺序 删除类工具名优先于删除类命令模式`() {
        val result = RiskClassifier.classify("Remove", "rm -rf /workspace/a")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        assertTrue(result.rule.startsWith("delete tool"), "应先命中 toolName：${result.rule}")
    }

    // ── HIGH：越出白名单的路径 ──

    @Test
    fun `越界路径写入识别为 HIGH`() {
        val workspace = tempDir("ws")
        val policy = WorkspacePolicy(listOf(workspace))

        val result = RiskClassifier.classify("Write", "/etc/passwd content", policy)
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        assertTrue(result.rule.contains("outside workspace"))
    }

    @Test
    fun `白名单内路径写入不触发越界 HIGH`() {
        val workspace = tempDir("ws2")
        val policy = WorkspacePolicy(listOf(workspace))
        val insidePath = File(workspace, "file.txt").path

        val result = RiskClassifier.classify("Write", insidePath, policy)
        // 白名单内写入 → MEDIUM（写入类工具），不是 HIGH
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, result.level)
    }

    /** 🔴 越界 HIGH 只对 WRITE_TOOL_NAMES 生效（只读工具越界仍是 LOW） */
    @Test
    fun `越界但非写入类工具不升 HIGH`() {
        val policy = WorkspacePolicy(listOf(tempDir("ws3")))
        assertEquals(RiskClassifier.RiskLevel.LOW, RiskClassifier.classify("Read", "/etc/passwd", policy).level)
    }

    /** 🔴 `policy = null`（未传）时完全跳过越界校验（旧第 4 步的 `policy != null` 守卫） */
    @Test
    fun `未传 policy 时跳过越界校验`() {
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Write", "/etc/passwd content").level)
    }

    /** 🔴 空白名单（`isEmpty`）时跳过越界校验——不是 fail-safe 全 HIGH（旧第 4 步的 `!policy.isEmpty` 守卫） */
    @Test
    fun `空白名单跳过越界校验`() {
        val policy = WorkspacePolicy(emptyList())
        assertTrue(policy.isEmpty)
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, RiskClassifier.classify("Write", "/etc/passwd", policy).level)
    }

    /** 🔴 Windows 盘符路径 token 也能被 extractPaths 抓到（越界判定在 Windows 上同样生效） */
    @Test
    fun `Windows 盘符路径越界识别为 HIGH`() {
        val policy = WorkspacePolicy(listOf(tempDir("ws4")))
        val result = RiskClassifier.classify("Write", """C:\Windows\System32\drivers\etc\hosts""", policy)
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
        assertTrue(result.rule.contains("outside workspace"))
    }

    /** 🔴 `~` 开头的 token 也参与越界判定 */
    @Test
    fun `波浪号路径越界识别为 HIGH`() {
        val policy = WorkspacePolicy(listOf(tempDir("ws5")))
        val result = RiskClassifier.classify("Edit", "~/secret.txt", policy)
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    // ── MEDIUM：写入类工具 ──

    @Test
    fun `Edit 工具识别为 MEDIUM`() {
        val result = RiskClassifier.classify("Edit", "some content")
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, result.level)
    }

    @Test
    fun `Bash 普通命令识别为 MEDIUM`() {
        val result = RiskClassifier.classify("Bash", "ls -la")
        assertEquals(RiskClassifier.RiskLevel.MEDIUM, result.level)
    }

    /** 🔒 WRITE_TOOL_NAMES 全表 7 个逐条（Edit/Write/NotebookEdit/edit/write/Bash/shell） */
    @Test
    fun `写入类工具名全表都判 MEDIUM`() {
        listOf("Edit", "Write", "NotebookEdit", "edit", "write", "Bash", "shell").forEach { tool ->
            val result = RiskClassifier.classify(tool, "harmless input")
            assertEquals(
                RiskClassifier.RiskLevel.MEDIUM,
                result.level,
                "🔒 WRITE_TOOL_NAMES 应含「$tool」，实际 ${result.level} / ${result.rule}",
            )
            assertTrue(result.rule.contains("write/shell tool: $tool"), "rule 应记录工具名：${result.rule}")
        }
    }

    /** 🔒 DELETE_TOOL_NAMES 全表 6 个逐条 */
    @Test
    fun `删除类工具名全表都判 HIGH`() {
        listOf("Delete", "Remove", "DeleteFile", "RemoveFile", "delete_file", "remove_file").forEach { tool ->
            val result = RiskClassifier.classify(tool, "harmless input")
            assertEquals(
                RiskClassifier.RiskLevel.HIGH,
                result.level,
                "🔒 DELETE_TOOL_NAMES 应含「$tool」，实际 ${result.level} / ${result.rule}",
            )
            assertTrue(result.rule.contains("delete tool: $tool"), "rule 应记录工具名：${result.rule}")
        }
    }

    /** 🔴 工具名精确匹配（不做子串/大小写归一）：`MyDeleteHelper` 不在表内 */
    @Test
    fun `工具名精确匹配不做子串命中`() {
        assertEquals(RiskClassifier.RiskLevel.LOW, RiskClassifier.classify("MyDeleteHelper", "x").level)
        assertEquals(RiskClassifier.RiskLevel.LOW, RiskClassifier.classify("delete", "x").level)
        assertEquals(RiskClassifier.RiskLevel.LOW, RiskClassifier.classify("DELETE", "x").level)
    }

    // ── LOW：只读操作 ──

    @Test
    fun `Read 工具识别为 LOW`() {
        val result = RiskClassifier.classify("Read", "/workspace/file.kt")
        assertEquals(RiskClassifier.RiskLevel.LOW, result.level)
    }

    @Test
    fun `Grep 工具识别为 LOW`() {
        val result = RiskClassifier.classify("Grep", "pattern")
        assertEquals(RiskClassifier.RiskLevel.LOW, result.level)
    }

    @Test
    fun `Glob 工具识别为 LOW`() {
        val result = RiskClassifier.classify("Glob", "*.kt")
        assertEquals(RiskClassifier.RiskLevel.LOW, result.level)
    }

    // ── find -delete ──

    @Test
    fun `find -delete 识别为 HIGH`() {
        val result = RiskClassifier.classify("Bash", "find /workspace -name '*.tmp' -delete")
        assertEquals(RiskClassifier.RiskLevel.HIGH, result.level)
    }

    // ── 契约形态 ──

    /** 🔴 RiskLevel 4 值穷尽且顺序不变（UI 与审计按 `name` 字面分桶） */
    @Test
    fun `RiskLevel 四档字面值与顺序锁死`() {
        assertEquals(
            listOf("LOW", "MEDIUM", "HIGH", "FORBIDDEN"),
            RiskClassifier.RiskLevel.entries.map { it.name },
            "🔴 档位顺序即严重度顺序，改名/换序会破坏 UI 与审计分桶",
        )
    }

    /** 🔴 `Classification.rule` 默认空串（旧 data class 同签名） */
    @Test
    fun `Classification 默认 rule 为空串`() {
        val c = RiskClassifier.Classification(RiskClassifier.RiskLevel.LOW)
        assertEquals(RiskClassifier.RiskLevel.LOW, c.level)
        assertEquals("", c.rule)
    }

    /** 🔴 每次 classify 都返回带非空 rule 的结果（LOW 也带 `read-only tool: X`，审计可读） */
    @Test
    fun `所有分支都给出非空 rule`() {
        listOf(
            RiskClassifier.classify("Read", "x"),
            RiskClassifier.classify("Write", "x"),
            RiskClassifier.classify("Delete", "x"),
            RiskClassifier.classify("Bash", "rm -rf /"),
        ).forEach { assertTrue(it.rule.isNotBlank(), "rule 不得为空：$it") }
    }

    /**
     * 🔴 D16：`RiskClassifier` 是 object，但体内**只允许不可变常量**。
     * 守护做法：归一化源码（剥注释与字符串）后扫描 object 体内不得出现 `var `。
     * （`private val DELETE_TOOL_NAMES = setOf(…)` 这类是 val，合规。）
     */
    @Test
    fun `D16 分类器 object 体内零可变状态`() {
        val file = File(testRepoRoot(), "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/confirm/RiskClassifier.kt")
        assertTrue(file.isFile, "被扫描的源文件必须存在（否则本守护是空过）：${file.path}")
        val raw = file.readText()
        assertTrue(raw.lines().size > 50, "疑似扫到了空壳文件：${raw.lines().size} 行")
        val normalized = stripCommentsAndStrings(raw)
        val body = normalized.substringAfter("object RiskClassifier")
        assertFalse(
            Regex("""\bvar\s+\w""").containsMatchIn(body),
            "🔴 D16：RiskClassifier object 体内出现可变状态（var）",
        )
        assertFalse(body.contains("@Volatile"), "🔴 D16：object 体内不得有 @Volatile")
    }

    /** 🔴 classify 是纯函数：同参重复调用结果一致，且不修改传入的 policy */
    @Test
    fun `classify 是纯函数且不改写传入 policy`() {
        val policy = WorkspacePolicy(listOf(tempDir("ws6")))
        val rootsBefore = policy.canonicalRoots
        repeat(3) {
            assertEquals(RiskClassifier.RiskLevel.HIGH, RiskClassifier.classify("Write", "/etc/passwd", policy).level)
        }
        assertEquals(rootsBefore, policy.canonicalRoots, "policy 的根列表不得被改写")
    }
}
