package com.jiaoay.lark.bridge.config

import com.jiaoay.lark.bridge.config.AgentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CDS §13.3 / §16.5] ConfigValidator 三类检查（数值区间 coerceIn 提示不产 issue / 必填非空白条件性 / 枚举合法性）
 * + 跨字段规则（预处理命令 {input} 占位符、L2 grants 值域）。
 * 🔴 不做磁盘 stat、不做格式正则（§16.5：格式错误不产生 issue、workspace.root 空串校验通过）。
 */
class ConfigValidatorTest {

    @Test
    fun `全默认文档校验通过 issues 为空`() {
        assertTrue(ConfigValidator.validate(DefaultBridgeConfig.default()).isEmpty())
    }

    @Test
    fun `数值区间不产 issue（confirm-timeoutSeconds 小于 10 走运行时钳制，BC-476）`() {
        val c = DefaultBridgeConfig.default().copy(
            agent = AgentConfig(confirm = ConfirmConfig(timeoutSeconds = 3)),
        )
        // 数值区间是 coerceIn 提示语义，不阻断落盘、不产 issue
        assertTrue(ConfigValidator.validate(c).none { it.fieldId == "agent.confirm.timeoutSeconds" })
    }

    @Test
    fun `凭据全空不产 issue（全新用户合法中间态）`() {
        // 🔴 凭据全空 = 尚未开始配置，不触发必填（由 Bridge.start 的启动拒绝点兜底），
        //    否则向导流程会被「校验失败不落盘」卡死。
        val c = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(appId = "", appSecret = ""),
        )
        assertTrue(ConfigValidator.validate(c).none { it.fieldId == "connection.appId" })
        assertTrue(ConfigValidator.validate(c).none { it.fieldId == "connection.appSecret" })
    }

    @Test
    fun `凭据部分填写时另一项必填`() {
        val onlyId = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(appId = "cli_x", appSecret = ""),
        )
        val issues = ConfigValidator.validate(onlyId)
        assertTrue(issues.none { it.fieldId == "connection.appId" }, "已填的 appId 不产 issue")
        assertTrue(issues.any { it.fieldId == "connection.appSecret" }, "appId 已填 ⇒ secret 必填")
    }

    @Test
    fun `appId-appSecret 空白且 autoStart 时产生 issue`() {
        val c = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(appId = "  ", appSecret = "", autoStart = true),
        )
        val issues = ConfigValidator.validate(c)
        assertTrue(issues.any { it.fieldId == "connection.appId" })
        assertTrue(issues.any { it.fieldId == "connection.appSecret" })
    }

    @Test
    fun `凭据全空且非 autoStart 时 appId 空白不产 issue`() {
        val c = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(appId = "", appSecret = "", autoStart = false),
        )
        assertTrue(ConfigValidator.validate(c).none { it.fieldId == "connection.appId" })
    }

    @Test
    fun `格式错误不产生 issue（§16-5 已删正则与长度校验）`() {
        // appId 非 cli_ 前缀、appSecret 短、含空格等「格式可疑」值均不应被拒
        val c = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(appId = "whatever-format", appSecret = "x"),
        )
        val issues = ConfigValidator.validate(c)
        // 只可能因非空白通过；绝不因「格式」产 issue
        assertTrue(issues.none { it.fieldId == "connection.appId" })
        assertTrue(issues.none { it.fieldId == "connection.appSecret" })
    }

    @Test
    fun `workspace-root 空串校验通过（不报缺失）`() {
        val c = DefaultBridgeConfig.default().copy(workspace = WorkspaceConfig(root = ""))
        assertTrue(ConfigValidator.validate(c).none { it.fieldId == "workspace.root" })
    }

    @Test
    fun `枚举非法值产生 issue（region 不在 options）`() {
        val c = DefaultBridgeConfig.default().copy(connection = ConnectionConfig(region = "mars"))
        val issues = ConfigValidator.validate(c)
        assertTrue(issues.any { it.fieldId == "connection.region" })
    }

    @Test
    fun `枚举合法值不产 issue`() {
        listOf("feishu", "lark").forEach { region ->
            val c = DefaultBridgeConfig.default().copy(connection = ConnectionConfig(region = region))
            assertTrue(ConfigValidator.validate(c).none { it.fieldId == "connection.region" })
        }
    }

    @Test
    fun `路径存在性不在 validate 检查（不存在的合法格式路径不产 issue）`() {
        val c = DefaultBridgeConfig.default().copy(
            workspace = WorkspaceConfig(root = "/this/path/does/not/exist"),
        )
        assertEquals(0, ConfigValidator.validate(c).count { it.fieldId == "workspace.root" })
    }

    // ==================================================================================
    // 🔴 R2 第二道防线：routes 匹配键重复检测（只产 issue、不阻断落盘）
    // ==================================================================================

    @Test
    fun `R2 routes 内两条 CommandRoute 别名重叠产生 issue`() {
        val c = DefaultBridgeConfig.default().copy(
            routes = listOf(
                CommandRoute(id = "log-analysis", aliases = listOf("/log", "/log-helper")),
                CommandRoute(id = "my-skill", aliases = listOf("/log", "/custom")),
            ),
        )
        val issues = ConfigValidator.validate(c)
        assertTrue(issues.any { it.fieldId == "routes" }, "别名重叠须产 routes issue")
    }

    @Test
    fun `R2 routes 内无匹配键重复不产 issue`() {
        val c = DefaultBridgeConfig.default().copy(
            routes = listOf(
                SystemRoute(id = "builtin"),
                CommandRoute(id = "log-analysis", aliases = listOf("/log")),
                CommandRoute(id = "my-skill", aliases = listOf("/custom")),
            ),
        )
        val issues = ConfigValidator.validate(c)
        assertTrue(issues.none { it.fieldId == "routes" }, "无重复不应产 routes issue；实际=$issues")
    }

    @Test
    fun `R2 默认 routes 不产 routes issue（各 kind 单例无冲突）`() {
        val c = DefaultBridgeConfig.default()
        assertTrue(ConfigValidator.validate(c).none { it.fieldId == "routes" })
    }
}
