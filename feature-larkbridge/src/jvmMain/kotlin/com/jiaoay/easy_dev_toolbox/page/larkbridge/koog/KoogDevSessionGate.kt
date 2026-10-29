package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.confirm.TrustPolicy

/**
 * dev.* 门控面（桥接链实现，[DevTools.DevSessionGate] 契约的装配侧）。
 *
 * ## 授权模型：按用户信任（与 sys 域同构，独立实例）
 * `requireSessionAuthorization` 只判「本消息发送者是否在信任名单内」。
 * dev 域工具全是只读观测（设备清单 / 日志快照，固定 argv 模板 + serial 白名单校验），
 * 无逐动作确认卡——域准入即唯一闸。
 *
 * 原「本会话首次触发发说明卡 + 确认卡」模型已退役（每会话重批、按会话而非按人授权）。
 *
 * @param trustedUsersProvider 读当前 `access.trustedUsers`（HOT）
 * @param senderOpenId 本消息发送者（唯一判定维度）
 */
internal class KoogDevSessionGate(
    private val trustedUsersProvider: () -> List<String>,
    private val senderOpenId: String,
) : DevTools.DevSessionGate {

    override val trigger: String = "sender=$senderOpenId"

    override suspend fun requireSessionAuthorization(
        @Suppress("UNUSED_PARAMETER") toolName: String,
    ): Boolean = TrustPolicy.isTrusted(
        AccessConfig(trustedUsers = trustedUsersProvider()),
        senderOpenId,
    )
}

/**
 * dev.* 设备源缝的 App 形态装配件：设备检测单源（DeviceHub.monitor）适配——每次调用
 * 触发一次新检测（serial 白名单校验对象恒是当前列表），并携带 adb 路径缓存与检测失败态。
 * 仅 App 进程内使用（桥接 CLI 形态无设备域）；单测注入 fake 缝，不触本件。
 */
internal object AppDeviceSource : DevTools.DeviceSource {
    override suspend fun snapshot(): DevTools.DeviceSnapshot {
        val monitor = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub.monitor
        val devices = monitor.detectOnce()
        return DevTools.DeviceSnapshot(
            devices = devices,
            adbExecutablePath = monitor.endpoint.executablePath,
            detectError = monitor.detectError.value,
        )
    }
}
