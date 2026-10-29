package com.jiaoay.lark.bridge.exec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BC · WorkspacePolicyTest 平移] 多目录白名单沙箱校验（平移旧 `lark/WorkspacePolicyTest.kt`，行为逐字保持）。
 */
class WorkspacePolicyTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "wp-test-$name-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    @Test
    fun `多根命中任一放行`() {
        val rootA = tempDir("a")
        val rootB = tempDir("b")
        val policy = WorkspacePolicy(listOf(rootA, rootB))

        val subA = File(rootA, "project").apply { mkdirs(); deleteOnExit() }
        val subB = File(rootB, "data").apply { mkdirs(); deleteOnExit() }

        assertTrue(policy.requireInsideWorkspace(subA))
        assertTrue(policy.requireInsideWorkspace(subB))
        assertTrue(policy.requireInsideWorkspace(rootA))
        assertTrue(policy.requireInsideWorkspace(rootB))
    }

    @Test
    fun `空 roots fail-safe 拒绝所有`() {
        val policy = WorkspacePolicy(emptyList())

        assertTrue(policy.isEmpty)
        assertFalse(policy.requireInsideWorkspace(File("/tmp")))
        assertFalse(policy.requireInsideWorkspace(File("/")))
    }

    @Test
    fun `分隔符边界防误放行（前缀相似目录不放行）`() {
        val root = tempDir("boundary")
        val policy = WorkspacePolicy(listOf(root))

        val sibling = File(root.parentFile, root.name + "x").apply { mkdirs(); deleteOnExit() }

        assertTrue(policy.requireInsideWorkspace(root))
        assertFalse(policy.requireInsideWorkspace(sibling))
    }

    @Test
    fun `越界路径被拒绝`() {
        val root = tempDir("inside")
        val policy = WorkspacePolicy(listOf(root))

        assertFalse(policy.requireInsideWorkspace(File("/etc")))
        assertFalse(policy.requireInsideWorkspace(File("/")))
    }

    @Test
    fun `fromConfig 取并集去空白`() {
        val rootA = tempDir("cfg-a")
        val rootB = tempDir("cfg-b")
        val policy = WorkspacePolicy.fromConfig(
            workspaceDir = rootA.path,
            workspaceWhitelist = listOf(rootB.path, "", "  "),
        )

        assertEquals(2, policy.canonicalRoots.size)
        assertTrue(policy.requireInsideWorkspace(rootA))
        assertTrue(policy.requireInsideWorkspace(rootB))
    }

    @Test
    fun `fromConfig 全空时 isEmpty`() {
        val policy = WorkspacePolicy.fromConfig(workspaceDir = "", workspaceWhitelist = emptyList())

        assertTrue(policy.isEmpty)
        assertFalse(policy.requireInsideWorkspace(File("/tmp")))
    }

    @Test
    fun `fromConfig workspaceDir 与 whitelist 重复时去重`() {
        val root = tempDir("dup")
        val policy = WorkspacePolicy.fromConfig(
            workspaceDir = root.path,
            workspaceWhitelist = listOf(root.path),
        )

        assertEquals(1, policy.canonicalRoots.size)
    }

    @Test
    fun `denyDelete 标记透传`() {
        val root = tempDir("deny")
        assertTrue(WorkspacePolicy(listOf(root), denyDelete = true).denyDelete)
        assertFalse(WorkspacePolicy(listOf(root), denyDelete = false).denyDelete)
    }

    // ==================== 🔴 2026-09-17 审计修复回归（~ 展开） ====================

    /**
     * 审计 §五①：workspace.root 不展开 ~ 但 agentWorkDir 会——同域两套语义，填 ~/work 时
     * 沙箱根是字面 "~" 目录、agent 工作目录展开后反而在白名单外（沙箱失配）。
     * 修复 = fromConfig 对 root / extraRoots 统一展开（与 resolveWorkingDir 同语义）。
     */
    @Test
    fun `fromConfig 展开 root 与 extraRoots 的 tilde 前缀`() {
        val home = System.getProperty("user.home")
        // 字面 ~/dir 形态：展开后应命中真实主目录下的目录
        val policy = WorkspacePolicy.fromConfig(
            workspaceDir = "~/",
            workspaceWhitelist = emptyList(),
        )
        assertEquals(1, policy.canonicalRoots.size, "字面 ~ 不应产生畸形的 canonical")
        assertTrue(
            policy.canonicalRoots.first().endsWith(java.io.File(home).name) ||
                policy.canonicalRoots.first() == java.io.File(home).canonicalPath,
            "展开后沙箱根应在真实主目录内：${policy.canonicalRoots.first()}",
        )
    }

    @Test
    fun `fromConfig tilde root 与展开后的 agentWorkDir 同域`() {
        val policy = WorkspacePolicy.fromConfig(
            workspaceDir = "~/audit-fix-home",
            workspaceWhitelist = emptyList(),
        )
        // 沙箱失配回归：resolveWorkingDir 会展开 agentWorkDir，白名单（policy）此前不展开——
        // 展开后 agentWorkDir 应能落在白名单内（这里用等价形态验证：expandTilde(~/x) == expandTilde(expandTilde(~/x))，幂等）
        val expandedOnce = com.jiaoay.lark.bridge.exec.Paths.expandTilde("~/audit-fix-home/sub")
        val policyRoot = policy.canonicalRoots.first()
        assertTrue(
            java.io.File(expandedOnce).canonicalPath.startsWith(policyRoot),
            "展开后的 agentWorkDir 应落在展开后的沙箱根内：workDir=$expandedOnce root=$policyRoot",
        )
    }
}
