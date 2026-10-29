// check 子命令：按 config-domain-spec 附录 C 打印 BridgeFields.ALL 全字段表 + 校验汇总。
// 这是「配置全部来自单一文档」的自解释出口，也是 slf4j ServiceLoader 的验证载体（fat-jar 跑 check 不得有 NOP 警告）。
// 🔴 secret=true 字段（唯一 connection.appSecret）统一掩码前 4 后 4；CheckCommandTest 断言无明文。
// 🔴 secret 掩码（经 CliSupport.formatFieldValue→maskSecret）是 check 全字段表
//    唯一出口的**强制脱敏**手法——appSecret 绝不明文打印到 stdout/日志；长度 ≤ 8 全掩码，杜绝短密钥被前后 4 位还原。勿放宽。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.Context
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.ConfigValidator
import java.io.PrintStream
import kotlin.system.exitProcess

/** `check`：打印全字段表 + 校验结果（有 issue ⇒ 退出码非 0）。 */
class CheckCommand(
    out: PrintStream = System.out,
    exit: (Int) -> Unit = { exitProcess(it) },
) : BridgeConfigCommand("check", out, exit) {

    override fun help(context: Context) =
        "打印配置全字段表（id/group/label/applyMode/生效值/校验）+ 校验汇总；secret 掩码前 4 后 4；有 issue 退出码非 0"

    override fun run() {
        val resolution = resolveConfig()
        if (resolution is ConfigResolution.Err) {
            emit("配置错误 [${resolution.code}]：${resolution.message}")
            exit(exitCodeFor(resolution.code))
            return
        }
        val ok = resolution as ConfigResolution.Ok
        val config = ok.config
        val issues = ConfigValidator.validate(config)
        val issuesByField = issues.groupBy { it.fieldId }

        emit("Bridge Config: ${ok.source}")
        emit("Schema Version: ${config.schemaVersion}")
        emit(
            if (issues.isEmpty()) {
                "Validation: PASS (0 issues)"
            } else {
                "Validation: FAIL (${issues.size} issues)"
            },
        )
        emit()
        emit("共 ${BridgeFields.ALL.size} 个可编辑字段（secret 字段掩码前 4 后 4）：")
        emit("Field ID | Group | Label | ApplyMode | Value | Issue")

        BridgeFields.ALL.forEach { field ->
            val value = formatFieldValue(field, config)
            val issue = issuesByField[field.id]?.firstOrNull()?.message ?: "-"
            emit("${field.id} | ${field.group.name} | ${field.label} | ${field.applyMode.name} | $value | $issue")
        }

        if (issues.isNotEmpty()) {
            emit()
            emit("Validation Issues (${issues.size}):")
            issues.forEach { emit("  - ${it.fieldId}: ${it.message}") }
            // 有 issue ⇒ 退出码非 0（配置域 → 2）。
            exit(exitCodeFor(BridgeErrorCode.ConfigInvalid))
        }
    }
}
