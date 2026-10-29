package com.jiaoay.easy_dev_toolbox.host.initializer

import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer
import com.jiaoay.easy_dev_toolbox.core.log.JLog

/**
 * 内置 adb 预热（宿主注册，低优组）：读配置缓存并在后台释放随包 adb，顺便定下
 * 进程级 server 端口。
 *
 * ## 为何是低优且恒返回 true
 *
 * 释放内置 adb 是**可延迟、可容忍失败**的准备工作：失败时端点解析会回退到系统 PATH
 * 上的 adb，应用仍可正常工作，只是没装 adb 的用户用不了设备功能。因此不该占启动
 * 关键路径。
 *
 * 恒返回 true 不是「不阻断后续」那么轻——初始化器返回 false 或抛异常会让
 * `InitResult.Failed` 落地，而主窗对**任何** Failed（不分高低优）都渲染整窗
 * 「初始化失败 + 退出应用」。释放内置 adb 失败绝不该把整个应用顶掉。
 *
 * ## 时机
 *
 * 首次设备检测远晚于启动（用户打开设备页或手动刷新才触发），本预热在其之前完成
 * 即可；即使竞态到「检测先跑」，`AdbEndpointResolver` 的幂等释放也能兜住。
 */
class BundledAdbWarmupInitializer : JInitializer {

    override val isHighLevel: Boolean = false

    override fun level(): Int {
        return 1
    }

    override suspend fun init(): Boolean {
        runCatching {
            // 解析即释放：这一步把随包 adb 落到数据目录，并把端口写进进程环境注入点
            val endpoint = DeviceHub.currentEndpoint()
            JLog.d(TAG) { "内置 adb 预热完成：path=${endpoint.executablePath}, port=${endpoint.serverPort}" }
            DeviceHub.endpointResolver.lastIssue?.let { issue ->
                JLog.w(TAG) { "内置 adb 不可用，回退系统 PATH：$issue" }
            }
        }.onFailure { JLog.w(TAG) { "内置 adb 预热失败，回退系统 PATH：${it.message}" } }
        return true
    }

    companion object {
        private const val TAG = "BundledAdbWarmup"
    }
}
