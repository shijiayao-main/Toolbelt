package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守护：IA 分区重构后五处文案漂移一次性修正。
 *
 * 五分区 IA 后分区名已变（连接与准入/可观测性……）、路由页签已删除、tier「进阶设置」
 * 已被门控双形态取代——引导文案必须与 [P5FieldGroups.ZONES] title 逐字一致，否则用户按文案
 * 操作找不到落点。
 *
 * 锚面 = 四源文件归一化（剥注释——注释面「路由」残留是历史记述不在此列：
 * LarkBridgePage.kt 与 lark-bridge Routes.kt 的注释提及不在本验收口径内，
 * 验收口径 = **用户可见字符串字面量**零残留）：
 * 1. 零「『路由』页签」字面量（已改退役文案）；
 * 2. 零「『连接』分组」（旧分组名）/「下方『工作区』分组」（旧分组名）/
 *    「进阶设置里重新开启」（旧 tier 命名）；
 * 3. 新文案锚：「连接与准入」「可观测性」、退役说明。
 */
class LbGuardCopyDriftTest {

    @Test
    fun `lb05 no visible route-tab literal remains in user facing strings`() {
        val code = listOf(VM, TASK_ERROR_HINT, LOG_CONSOLE, SECURITY_LOGIC)
            .joinToString("\n") { normalizedCode(it) }
        // 旧文案路径：到「路由」页签……（用户可见字面量零残留——验收口径限字面量，注释不动）
        assertFalse(
            "「路由」页签" in code,
            "用户可见字符串不得再含「路由」页签（路由页签已删）",
        )
    }

    @Test
    fun `lb05 five copy sites point at current zone titles`() {
        val vm = normalizedCode(VM)
        // App ID 未填写引导 →「连接与准入」分区
        assertTrue(
            "在「连接与准入」分区填写飞书应用凭据" in vm,
            "VM App ID 引导须指向「连接与准入」分区（与 P5FieldGroups.ZONES title 逐字）",
        )
        assertFalse(
            "在「连接」分组" in vm,
            "VM 不得再指向旧「连接」分组（防回潮）",
        )

        val security = normalizedCode(SECURITY_LOGIC)
        // 工作区根目录引导 →「连接与准入」分区
        assertTrue(
            "在「配置」页签「连接与准入」分区选择 agent 的根目录" in security,
            "安全卡工作区引导须指向「连接与准入」分区",
        )
        assertFalse(
            "「工作区」分组" in security,
            "安全卡不得再指向旧「工作区」分组（防回潮）",
        )

        val hint = normalizedCode(TASK_ERROR_HINT)
        // 工作区缺失修复建议 →「连接与准入」→ 工作区根目录
        assertTrue(
            "「连接与准入」→ 工作区根目录" in hint,
            "工作区缺失修复建议须按「配置」页签 →「连接与准入」→ 工作区根目录 路径表述",
        )
        // 退役文案
        assertTrue(
            "快捷指令模板已退役——该指令由内置指令集接管，请检查指令名" in hint,
            "COMMAND_TEMPLATE_EMPTY 须用退役文案",
        )

        val console = normalizedCode(LOG_CONSOLE)
        // 日志台关闭空态 →「可观测性」分区
        assertTrue(
            "可在「配置」页签「可观测性」分区重新开启" in console,
            "日志台空态须指向「可观测性」分区（与 P5FieldGroups.ZONES title 逐字）",
        )
        assertFalse(
            "进阶设置里重新开启" in console,
            "日志台空态不得再用旧 tier 命名「进阶设置」（防回潮）",
        )
    }

    // ── 基建 ──

    /** 归一化：剥行注释 + 剥块注释 + 压空白——锚只对用户可见代码字面。 */
    private fun normalizedCode(relPath: String): String =
        file(relPath).readText()
            .replace(Regex("/\\*+[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun file(relPath: String): File {
        val f = File(projectRoot(), relPath)
        assertTrue(f.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return f
    }

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val PKG = "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge"
        const val VM = "$PKG/LarkBridgeViewModel.kt"
        const val TASK_ERROR_HINT = "$PKG/TaskErrorHint.kt"
        const val LOG_CONSOLE = "$PKG/log/LogConsolePanel.kt"
        const val SECURITY_LOGIC = "$PKG/security/BridgeSecurityCardLogic.kt"
    }
}
