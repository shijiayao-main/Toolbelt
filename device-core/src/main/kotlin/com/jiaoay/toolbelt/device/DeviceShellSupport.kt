package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbPlatform

/**
 * 本机执行 shell 的 token 引号：Unix 单引号零展开（`$` / 反引号 / 空白字面化，
 * 内嵌单引号以 `'\''` 重开闭转义）；Windows `cmd.exe` 双引号（payload 内单引号安全；
 * 路径含双引号不支撑）。
 *
 * 引号只防本机执行 shell 二次分词，设备侧参数（adbd 协议 / 设备 shell）不靠本层转义。
 */
internal fun hostShellQuote(token: String, platform: AdbPlatform? = AdbPlatform.current()): String =
    if (platform?.isWindows == true) {
        "\"$token\""
    } else {
        "'" + token.replace("'", "'\\''") + "'"
    }

/**
 * 设备侧错误原文透传：按参数序取首个非空 stderr，缺席回看 stdout（部分 ROM 的
 * pm 错误走 stdout），再缺席返回 [emptyFallback]。
 *
 * 以空串为「无设备侧输出」哨兵的调用方显式传 `emptyFallback = ""` 并自行补人话，
 * 避免空串与兜底文案在调用面混淆。
 */
internal fun shellErrorText(vararg shells: ShellResult?, emptyFallback: String = "无错误输出"): String =
    shells.firstNotNullOfOrNull { it?.stderr?.trim()?.ifBlank { null } }
        ?: shells.firstNotNullOfOrNull { it?.stdout?.trim()?.ifBlank { null } }
        ?: emptyFallback

/** 设备端 payload 的宿主 shell 包裹（语义化命名，规则同 [hostShellQuote]） */
internal object DeviceShellSupport {
    fun quotePayload(payload: String, platform: AdbPlatform? = AdbPlatform.current()): String =
        hostShellQuote(payload, platform)
}
