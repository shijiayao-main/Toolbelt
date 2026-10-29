package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 围栏允许根目录前缀语义（P4-B 蒸馏批任务 1.2，零 LLM/零网络）：
 * - 目录内文件免 gate 放行（信任边界前移到配置时授权——蒸馏场景防弹窗风暴）；
 * - 根外路径原语义（精确集 ∨ gate/拒绝）——源码目录授权不外溢；
 * - 符号链接 canonical 解析后落根外 = 拒绝（fail-safe——间接 prompt injection 落地面）；
 * - 分隔符边界：/foo/bar 不放行 /foo/barbaz（前缀比对必须含分隔符）；
 * - 构造期 ~ 展开（蓝本 WorkspacePolicy 修过的沙箱失配坑）。
 *
 * Windows 大小写归一分支（os.name 探测）在本机（Linux）不可直接驱动——该分支语义
 * 与 WorkspacePolicy.requireInsideWorkspace 逐字平移，由蓝本侧测试背书；此处只钉
 * 非平台相关的围栏行为。
 */
class ToolExecutionContextAllowedRootsTest {

    private fun tempRoot(): java.io.File = Files.createTempDirectory("fence-roots").toFile()

    @Test
    fun `file inside allowed root is allowed without gate`() {
        val root = tempRoot()
        val file = file(root, "src/A.kt")
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(root))

        assertTrue(ctx.isAllowed(file), "允许根内文件必须直接放行（无 HITL）")
        assertEquals(file.canonicalFile, ctx.authorize(file.path), "authorize 返回 canonical File")
    }

    @Test
    fun `root itself is allowed`() {
        val root = tempRoot()
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(root))

        assertTrue(ctx.isAllowed(root), "允许根本身命中（前缀含根等值分支）")
    }

    @Test
    fun `path outside roots keeps original semantics`() {
        val root = tempRoot()
        val outside = Files.createTempDirectory("fence-outside").resolve("secret.txt").toFile().apply { writeText("x") }
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(root))

        assertFalse(ctx.isAllowed(outside), "根外路径不得因允许根配置而放行")
        assertEquals(null, ctx.authorize(outside.path), "无 gate 时根外路径照旧拒绝（null）")
    }

    @Test
    fun `separator boundary prevents sibling-prefix leak`() {
        val root = tempRoot()
        // 同前缀兄弟目录：root=/tmp/fence-roots-abc，sibling=/tmp/fence-roots-abc-evil
        val sibling = java.io.File(root.parentFile, root.name + "-evil").apply { mkdirs() }
        val evilFile = file(sibling, "evil.txt")
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(root))

        assertFalse(ctx.isAllowed(evilFile), "🔴 分隔符边界：兄弟目录前缀撞车不得放行")
    }

    @Test
    fun `symlink escaping root is rejected`() {
        val root = tempRoot()
        val secret = Files.createTempDirectory("fence-secret").resolve("k.txt").toFile().apply { writeText("s") }
        // 树内符号链接指向根外敏感目录
        val link = java.io.File(root, "leak")
        Files.createSymbolicLink(link.toPath(), secret.parentFile.toPath())
        val viaLink = java.io.File(link, "k.txt")
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(root))

        assertFalse(ctx.isAllowed(viaLink), "🔴 符号链接 canonical 后落根外必须拒绝（fail-safe）")
    }

    @Test
    fun `allowed root does not suppress gate for outside paths`() {
        val root = tempRoot()
        var gateAsked = 0
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(root), escapeGate = { _ ->
            gateAsked += 1
            false
        })

        val outside = Files.createTempDirectory("fence-outside2").resolve("b.txt").toFile().apply { writeText("b") }
        // suspend 函数直测：kotlinx runTest
        kotlinx.coroutines.test.runTest {
            assertEquals(null, ctx.authorizeWithGate(outside.path), "根外路径仍走 gate（拒绝语义）")
        }
        assertEquals(1, gateAsked, "根外路径必须触发 gate 询问")
    }

    @Test
    fun `tilde expansion applies to root at construction`() {
        // 根以 ~/x 形态传入：构造期展开到 user.home——蓝本 WorkspacePolicy 修过的坑
        val home = System.getProperty("user.home")
        val ctx = ToolExecutionContext.withAllowedRoots(listOf(java.io.File("~/fence-tilde-check")))
        // canonicalPath 不要求路径存在——不存在目录也成功解析为展开后的绝对路径
        assertEquals(1, ctx.allowedRootsCount, "~ 根构造期展开并 canonical 成功（不要求存在）")
        // 存在性验证：home 下临时建目录再构造
        val realRoot = java.io.File(home, "fence-roots-real").apply { mkdirs() }
        try {
            val ctx2 = ToolExecutionContext.withAllowedRoots(listOf(java.io.File("~/fence-roots-real")))
            assertTrue(ctx2.isAllowed(java.io.File(realRoot, "a.txt").apply { writeText("x") }), "~ 展开后根内文件放行")
        } finally {
            realRoot.deleteRecursively()
        }
    }

    @Test
    fun `existing exact-allow semantics unchanged without roots`() {
        val target = Files.createTempDirectory("fence-exact").resolve("f.txt").toFile().apply { writeText("f") }
        val ctx = ToolExecutionContext(target)
        assertTrue(ctx.isAllowed(target))
        assertEquals(0, ctx.allowedRootsCount, "无 roots 构造的 allowedRootsCount 恒 0")
        val other = Files.createTempDirectory("fence-exact2").resolve("g.txt").toFile().apply { writeText("g") }
        assertFalse(ctx.isAllowed(other), "精确集语义零回归")
    }

    @Test
    fun `bridge isolation observability - collection constructor never injects roots`() {
        // 桥接链既有装配路径（invoke(Collection<File>)）构造的 context 允许根恒空——
        // 守护断言面（feature-larkbridge 侧还有装配点扫描）
        val ctx = ToolExecutionContext(listOf(Files.createTempDirectory("fence-c").toFile()))
        assertEquals(0, ctx.allowedRootsCount, "🔴 桥接链既有构造不得携带允许根（出站旁路防线）")
    }

    private fun file(dir: java.io.File, name: String): java.io.File =
        java.io.File(dir, name).apply {
            parentFile?.mkdirs()
            writeText("content of $name")
        }
}
