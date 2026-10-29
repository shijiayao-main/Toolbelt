package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TaskErrorHint] 翻译表契约：任务级错误码 → 人话 + 修复建议 + 配置字段落点。
 *
 * 🔴 翻译重点来自真实使用数据（2026-09-09）：`agent_timeout` 连败 8 次无解释、
 * `agent_workspace_missing`、`agent_exit_N` 为高频失败。兜底语义：未知码不崩、不吞。
 */
class TaskErrorHintTest {

    /** 超时类：真实数据最高频（连败 8 次的元凶），须给到「调哪个字段」的可执行建议。 */
    @Test
    fun taskTimeoutAndAgentTimeoutHintFields() {
        val hint = TaskErrorHint.of(TaskErrorCodes.TASK_TIMEOUT)
        assertTrue(hint.plain.contains("超时"), "人话须含超时")
        assertTrue(hint.fix.contains("任务超时"), "修复建议须指向任务超时字段")
        assertEquals("runtime.taskTimeoutSeconds", hint.fieldId)

        val alias = TaskErrorHint.of(TaskErrorCodes.AGENT_TIMEOUT_ALIAS)
        assertTrue(alias.plain.contains("超时"), "agent_timeout 别名同走超时翻译")
        assertEquals("runtime.taskTimeoutSeconds", alias.fieldId)
    }

    /** agent_exit_N 前缀形态：退出码拼进人话，落点指向后端与日志。 */
    @Test
    fun agentExitPrefixIncludesCode() {
        val hint = TaskErrorHint.of(TaskErrorCodes.agentExit(1))
        assertTrue(hint.plain.contains("退出码 1"), "须含具体退出码")
        assertTrue(hint.fix.contains("日志"), "须指引用户看日志页签")
    }

    /** 工作区缺失：须指向 workspace.root。 */
    @Test
    fun workspaceMissingPointsToRootField() {
        val hint = TaskErrorHint.of(TaskErrorCodes.AGENT_WORKSPACE_MISSING)
        assertEquals("workspace.root", hint.fieldId)
        assertTrue(hint.fix.contains("工作区"), "修复建议须指向工作区设置")
        // LB-05（重设计批 9）：分区名与 P5FieldGroups.ZONES title 逐字一致——
        // workspace.root 现归「连接与准入」分区，文案不再指向已不存在的「工作区」分组路径
        assertTrue(hint.fix.contains("连接与准入"), "修复建议须指向「连接与准入」分区（LB-05 文案对齐分区表）")
        assertFalse(hint.fix.contains("「工作区」分组"), "不得再指向旧「工作区」分组（LB-05：IA 分区重构后路径已变）")
    }

    /** LB-05（重设计批 9）：快捷指令模板已退役——文案不再指向已删除的「路由」页签。 */
    @Test
    fun commandTemplateEmptyHintsRetiredRoutes() {
        val hint = TaskErrorHint.of(TaskErrorCodes.COMMAND_TEMPLATE_EMPTY)
        assertTrue(hint.fix.contains("退役"), "修复建议须说明模板配置面已退役（LB-05）")
        assertTrue(hint.fix.contains("内置指令集"), "修复建议须指出由内置指令集接管（LB-05）")
        assertFalse(hint.fix.contains("路由"), "不得再指向已删除的「路由」页签（LB-05 验收：grep 无路由页签残留）")
    }

    /** 兜底：未知码不崩、保留原始码；null/空码也不崩。 */
    @Test
    fun unknownCodeFallsBackToRawCode() {
        val unknown = TaskErrorHint.of("weird_new_code")
        assertTrue(unknown.plain.contains("weird_new_code"), "兜底须保留原始码")
        assertTrue(unknown.fix.isNotEmpty(), "兜底也要给修复建议")

        val nullCode = TaskErrorHint.of(null)
        assertTrue(nullCode.plain.contains("未知错误"), "null 码不崩")
        assertNotNull(TaskErrorHint.of(""))
    }

    /**
     * 零生产者码白名单（结构性守卫的豁免面）：字面值仍在 [TaskErrorCodes.ALL] 供存量 audit
     * 分桶，但生产源码零产出路径——进白名单前须全仓 grep 亲验并逐码登记理由，且与
     * [TaskErrorHint] 的零生产者注释逐字对齐：
     * - [TaskErrorCodes.FILE_AGENT_DISABLED]：agent 硬依赖化后「执行器未启用」语义不复存在；
     * - [TaskErrorCodes.COMMAND_NOT_FOUND]：指令发现已内置化；
     * - [TaskErrorCodes.FILE_EXTENSION_REJECTED]：allowedExtensions 配置面已退役；
     * - [TaskErrorCodes.FILE_WORKSPACE_MISSING]：引用流与 agent 管线合流后工作区缺失统一落
     *   AGENT_WORKSPACE_MISSING，本码从未接线。
     */
    private val zeroProducerCodes = setOf(
        TaskErrorCodes.FILE_AGENT_DISABLED,
        TaskErrorCodes.COMMAND_NOT_FOUND,
        TaskErrorCodes.FILE_EXTENSION_REJECTED,
        TaskErrorCodes.FILE_WORKSPACE_MISSING,
    )

    /**
     * 结构性守卫：「有生产者的码必有专条」——全集单源派生自 [TaskErrorCodes.ALL]（不维护
     * 手工清单，新码上线即被强制翻译），减去零生产者白名单、`agent_exit_` 前缀形态（退出码
     * 形态由专项用例覆盖）与通用兜底码 [TaskErrorCodes.TASK_ERROR]（其语义即「未分类失败」，
     * else 兜底的「原始码 + 看日志」即其正确翻译），其余每码不得落「暂无翻译」else 兜底。
     */
    @Test
    fun everyProducingCodeHasDedicatedHint() {
        // 全集每码（含豁免面）都必须产出非空人话与修复建议——兜底不吞码。
        TaskErrorCodes.ALL.forEach { code ->
            val hint = TaskErrorHint.of(code)
            assertTrue(hint.plain.isNotBlank(), "$code 的人话不得为空")
            assertTrue(hint.fix.isNotBlank(), "$code 的修复建议不得为空")
        }

        val exempt = zeroProducerCodes + setOf(
            TaskErrorCodes.AGENT_EXIT_PREFIX,
            TaskErrorCodes.TASK_ERROR,
        )
        val guarded = TaskErrorCodes.ALL - exempt
        assertTrue(guarded.isNotEmpty(), "全集不应被豁免面吞空——守卫失效信号")
        guarded.forEach { code ->
            val hint = TaskErrorHint.of(code)
            assertFalse(hint.fix.contains("暂无翻译"), "$code 有生产者却落 else 兜底（须补专条翻译）")
        }
    }

    /** 零生产者码无专条：落 else 兜底（原始码 + 看日志），无字段落点。 */
    @Test
    fun zeroProducerCodesFallBackToGenericHint() {
        zeroProducerCodes.forEach { code ->
            val hint = TaskErrorHint.of(code)
            assertTrue(hint.plain.contains(code), "$code 须落兜底并保留原始码（无专条翻译）")
            assertFalse(hint.fix.contains("启用"), "不得再指向已退役的「启用 agent」开关（$code）")
            assertNull(hint.fieldId, "$code 兜底无字段落点")
        }
    }

    /** 语音时长超限：拒收发生在下载前，指引剪短/分段重发（硬上限是常量，无配置落点）。 */
    @Test
    fun audioDurationExceededHintsResendShorter() {
        val hint = TaskErrorHint.of(TaskErrorCodes.AUDIO_DURATION_EXCEEDED)
        assertTrue(hint.plain.contains("10 分钟"), "人话须含硬上限时长")
        assertTrue(hint.fix.contains("剪短"), "修复建议须指引剪短重发")
        assertTrue(hint.fix.contains("分段"), "修复建议须指引分段重发")
        assertFalse(hint.fix.contains("暂无翻译"), "时长超限码不得落兜底")
        assertNull(hint.fieldId, "硬上限是常量而非配置字段，无落点")
    }

    /** 语音转写失败：指引查「AI 设置」模型下载态与「日志」页签详细原因（无配置字段落点）。 */
    @Test
    fun audioTranscribeFailedPointsToModelAndLog() {
        val hint = TaskErrorHint.of(TaskErrorCodes.AUDIO_TRANSCRIBE_FAILED)
        assertTrue(hint.plain.contains("转写失败"), "人话须按转写失败口径")
        assertTrue(hint.fix.contains("AI 设置"), "修复建议须指向 AI 设置页")
        assertTrue(hint.fix.contains("日志"), "修复建议须指引看日志页签")
        assertFalse(hint.fix.contains("暂无翻译"), "转写失败码不得落兜底")
        assertNull(hint.fieldId, "模型下载态不在桥接配置字段，无落点")
    }

    /** COMMAND_AGENT_DISABLED：文案与落点按「执行后端不可用」新语义（不再指向已退役的总闸）。 */
    @Test
    fun commandAgentDisabledPointsToAiSettings() {
        val hint = TaskErrorHint.of(TaskErrorCodes.COMMAND_AGENT_DISABLED)
        assertTrue(hint.plain.contains("后端不可用"), "人话须按运行时不可用口径")
        assertFalse(hint.plain.contains("未启用"), "不得再出现「未启用」措辞（总闸已退役）")
        assertTrue(hint.fix.contains("AI 设置"), "修复建议须指向 AI 设置页（CLI 后端字段已退役）")
        assertEquals(null, hint.fieldId, "CLI 后端配置字段已出表——无深链落点")
    }
}
