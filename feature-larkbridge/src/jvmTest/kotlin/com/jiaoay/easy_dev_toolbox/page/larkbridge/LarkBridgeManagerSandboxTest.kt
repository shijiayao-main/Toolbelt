// send_file 沙箱策略的组装决策测试。
//
// 🔴 为什么需要这个文件：`WorkspacePolicy` 本身的语义（canonical 前缀命中）已有测试，
//    但「**根集合取哪几个**」是 App 侧的装配决策：根集合恒为 root ∪ extraRoots。
package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.lark.bridge.config.WorkspaceConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LarkBridgeManagerSandboxTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("sandbox-$tag").toFile().apply { deleteOnExit() }

    @Test
    fun `root 与 extraRoots 并集放行（互不遮蔽）`() {
        val root = tempDir("r")
        val extra = tempDir("e")
        val policy = LarkBridgeManager.sandboxPolicyOf(
            WorkspaceConfig(root = root.absolutePath, extraRoots = listOf(extra.absolutePath)),
        )
        assertNotNull(policy, "配了 root 就不是空配置")
        listOf("r" to root, "e" to extra).forEach { (tag, dir) ->
            val f = File(dir, "artifact-$tag.md").apply { writeText("x") }
            assertTrue(
                runCatching { policy.requireInsideWorkspace(f) }.getOrDefault(false),
                "$tag 根应各自放行（并集，互不遮蔽）",
            )
        }
    }

    @Test
    fun `根全空时返回 null（不设上界，回落精确允许集 fail-safe）`() {
        assertNull(
            LarkBridgeManager.sandboxPolicyOf(WorkspaceConfig(root = "", extraRoots = emptyList())),
            "空工作区 ⇒ null（调用方据此回落精确允许集判据）",
        )
    }

    @Test
    fun `沙箱外路径不放行（白名单语义不因 extraRoots 缺席而放宽）`() {
        val root = tempDir("only-root")
        val policy = LarkBridgeManager.sandboxPolicyOf(
            WorkspaceConfig(root = root.absolutePath),
        )
        assertNotNull(policy, "root 非空 ⇒ 有策略")
        val outside = File(tempDir("outside"), "x.md").apply { writeText("x") }
        assertFalse(
            runCatching { policy.requireInsideWorkspace(outside) }.getOrDefault(false),
            "白名单外路径不得放行",
        )
    }
}
