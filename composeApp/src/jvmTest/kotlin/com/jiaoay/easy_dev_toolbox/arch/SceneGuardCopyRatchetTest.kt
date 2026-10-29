package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 场景守护渲染配方拷贝棘轮：`private fun renderPixels` 私有复刻计数全仓归零——
 * 上限恒空表，任何模块出现即红。
 *
 * 配方真源 = [SceneGuardSupport]（:test-support 模块，对全部模块测试源集可见）——
 * 新增像素级场景守护必须 import 单源消费，不得在测试类内私有复刻；复刻即漂移
 * （泵形态/背景锚/解码路径会静默分叉）。
 *
 * 🔴 验收纪律：扫盘型守护，一律 --rerun-tasks --no-build-cache（build cache 会假绿）。
 */
class SceneGuardCopyRatchetTest {

    @Test
    fun `private renderPixels copies stay at zero across all modules`() {
        val counts = mutableMapOf<String, Int>()
        for (module in locateProjectRoot().listFiles { f -> f.isDirectory }.orEmpty()) {
            var count = 0
            for (srcSet in TEST_SOURCE_SETS) {
                val srcRoot = File(module, srcSet)
                if (!srcRoot.isDirectory) continue
                srcRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    // 本测试源文件豁免：扫描器自身持有标记字面量，不豁免即自计入
                    .filter { it.name != RATCHET_SELF_FILE }
                    .forEach { f -> count += MARKER.toRegex().findAll(f.readText()).count() }
            }
            if (count > 0) counts[module.name] = count
        }
        assertEquals(
            emptyMap(),
            counts,
            "私有 renderPixels 复刻计数须全仓归零（实际 $counts）——" +
                "新增像素级场景守护须 import arch 包 SceneGuardSupport 单源" +
                "（:test-support 模块，jvmTest 声明 testImplementation(project(\":test-support\"))），" +
                "不得在测试类内私有复刻",
        )
    }

    private companion object {
        /** 私有复刻锚：函数级 private renderPixels 声明即计 1（每测试类至多 1 处） */
        private const val MARKER = "private fun renderPixels"

        private const val RATCHET_SELF_FILE = "SceneGuardCopyRatchetTest.kt"

        private val TEST_SOURCE_SETS = listOf("src/jvmTest/kotlin", "src/test/kotlin")
    }
}
