package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 🔴 桥接链与源码目录永久隔离守护（P4-B 蒸馏批任务 1.4——出站旁路防线）。
 *
 * 风险：源码目录 allowlist（`llm_agent_source_roots`）若进桥接链 agent 的路径允许集，
 * 等于把用户整个源码项目变成「可发到飞书群」的面（SensitiveFilePolicy 只拦凭据族文件名，
 * 拦不住业务源码）。本守护三面：
 * 1. **装配面**：BridgeToolRegistry.build 构造的 ToolExecutionContext 不带允许根——
 *    无论 DataStore 里 source roots 配了什么，桥接 context 的 allowedRootsCount 恒 0
 *    （观测断言不可达则退化为装配点源码扫描）；
 * 2. **配置消费面**：桥接链源码（feature-larkbridge + lark-bridge）零引用
 *    LlmConfigStore.getSourceRoots / SourceRootsMap——扫盘断言（构建即跑，不依赖运行时）；
 * 3. **出站判据面**：send_file 的放行判据 = 允许集 ∨ 沙箱，两者均与 source roots 无关。
 */
class BridgeSourceRootsIsolationTest {

    /** 仓根探测（测试进程 CWD = 模块根；向上找 settings.gradle.kts 锚定） */
    private fun repoRoot(): java.io.File {
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (java.io.File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("仓根探测失败（settings.gradle.kts 未找到）——测试运行目录异常")
    }

    @Test
    fun `bridge chain source never consumes source roots config`() {
        // 扫盘：桥接两模块源码树零命中 source-roots 消费符号。
        // 🔴 守护配对（防误报）：白名单只有本测试文件自身（test 树不在扫描范围）。
        val repo = repoRoot()
        val roots = listOf(
            java.io.File(repo, "feature-larkbridge/src/jvmMain"),
            java.io.File(repo, "lark-bridge/src/main"),
        )
        val forbidden = listOf(
            "LlmConfigStore.getSourceRoots",
            "SourceRootsMap",
            "withAllowedRoots",
            "KEY_LLM_SOURCE_ROOTS",
            "llm_agent_source_roots",
        )
        val hits = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { f ->
                    val text = f.readText()
                    forbidden.filter { sym -> text.contains(sym) }
                        .map { sym -> "${f.path}: [$sym]" }
                }
        }
        assertTrue(
            hits.isEmpty(),
            "🔴 桥接链不得消费源码目录配置（出站旁路）：命中 ${hits.joinToString("; ")}",
        )
    }

    @Test
    fun `bridge context construction sites use collection constructor only`() {
        // 装配点形状守护：桥接链构造 ToolExecutionContext 的调用形态只有
        // invoke(Collection)（精确允许集）——withAllowedRoots 工厂不在桥接域出现。
        // （正面覆盖由扫盘第一断言承担：withAllowedRoots 零命中。）
        // 路径锚随 BridgeToolRegistry 文件化迁移：Collection 单参构造唯一落点在该 object 的 build 区段。
        val text = java.io.File(
            repoRoot(),
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/koog/BridgeToolRegistry.kt",
        ).readText()
        assertTrue(
            "ToolExecutionContext(it.allowedFiles)" in text,
            "桥接围栏构造形态保持 Collection 单参（精确允许集）——改形态须同步本守护与隔离设计",
        )
    }
}
