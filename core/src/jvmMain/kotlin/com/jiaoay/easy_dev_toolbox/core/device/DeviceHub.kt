package com.jiaoay.easy_dev_toolbox.core.device

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.AppHost
import com.jiaoay.toolbelt.adb.AdbBinaryReleaser
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbEndpointResolver
import com.jiaoay.toolbelt.device.AdbLog
import com.jiaoay.toolbelt.device.DeviceFileBrowser
import com.jiaoay.toolbelt.device.DeviceLayoutInspector
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DevicePackageBrowser
import com.jiaoay.toolbelt.device.DeviceWindowViewsInspector
import com.jiaoay.toolbelt.device.DeviceWirelessConnector
import com.jiaoay.toolbelt.device.KeyEventInjector
import com.jiaoay.toolbelt.device.PerfSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/**
 * 设备域 App 级单源持有者：全进程唯一的 [DeviceMonitor] 与各能力内核实例。
 *
 * 惰性构造（首个消费方触碰时）：轮询循环随实例启动，但 [DeviceMonitor] 门控默认
 * false——无页面接线期间一拍都不 spawn，与「页面未打开」同频；页面经消费壳的
 * `setPageVisible` 接线组合生命周期。
 *
 * 所有 scope 从 [AppHost.scope] 派生子 scope（SupervisorJob 隔离异常、退出随根
 * cancel 一并收束），不得自建孤儿 scope。
 */
object DeviceHub {

    private val adbLog = AdbLog { tag, message -> JLog.d(tag) { message() } }

    /** 内置 adb 的端点解析器（进程唯一）：只有「随包内置」一支 */
    val endpointResolver: AdbEndpointResolver by lazy {
        AdbEndpointResolver(releaser = AdbBinaryReleaser(adbRootDir = FileUtils.getAdbDir()))
    }

    /**
     * 当前端点（每次调用重解析）。
     *
     * 内置 adb 不可用时返回一个**指向不存在文件**的端点而不是抛异常或返回 null：
     * 各内核的构造缝是 `() -> AdbEndpoint`，让它们去处理可空会把这个判断散布到十几个
     * 地方。指向不存在的路径会让命令以「可执行文件不存在」失败，而真实原因经
     * [endpointUnavailableReason] 单独暴露给状态栏与设置页。
     */
    fun currentEndpoint(): AdbEndpoint {
        val resolved = endpointResolver.resolve()
        if (resolved != null) {
            ShellCommandBuilder.adbServerPort = resolved.serverPort
            return resolved
        }
        ShellCommandBuilder.adbServerPort = null
        return MISSING_ENDPOINT
    }

    /** 内置 adb 不可用的人话原因（null = 正常）；供设备状态栏与设置页呈现 */
    val endpointUnavailableReason: String? get() = endpointResolver.lastIssue

    /**
     * 内置 adb 不可用时各内核拿到的端点：路径指向一个必定不存在的文件。
     *
     * 用它而不是空串，是因为空串会让命令重写器回退到字面 `adb`——那正是被移除的
     * PATH 隐式搜索，行为会变成「取决于用户机器上装了什么」。这里让失败显式发生。
     */
    private val MISSING_ENDPOINT: AdbEndpoint = AdbEndpoint(
        executablePath = "<builtin-adb-unavailable>",
        serverPort = null,
    )

    private fun newScope(): CoroutineScope =
        CoroutineScope(AppHost.scope.coroutineContext + SupervisorJob(AppHost.scope.coroutineContext.job))

    val monitor: DeviceMonitor by lazy {
        DeviceMonitor(
            scope = newScope(),
            runner = SystemAdbProcessRunner,
            endpointReader = ::currentEndpoint,
            log = adbLog,
        )
    }

    /**
     * 设备负载波采样器（App 级单源，惰性构造同 [monitor]）：设备三流消费
     * [monitor]（选定态与检测态同源）。
     */
    val perfSampler: PerfSampler by lazy {
        PerfSampler(
            scope = newScope(),
            devices = monitor,
            runner = SystemAdbProcessRunner,
            endpointReader = ::currentEndpoint,
            log = adbLog,
        )
    }

    /**
     * 按键注入器（App 级单源，惰性构造同 [monitor]）：设备三流消费 [monitor]，
     * 无常驻环零 spawn（命令只在按键点击时执行）。
     */
    val keyInjector: KeyEventInjector by lazy {
        KeyEventInjector(
            scope = newScope(),
            devices = monitor,
            runner = SystemAdbProcessRunner,
            endpointReader = ::currentEndpoint,
            log = adbLog,
        )
    }

    /**
     * 设备文件浏览器（App 级单源）：请求-回执模型无常驻环零轮询——消费方在自己的
     * 作用域直接挂起调用，故无需注入 scope。
     */
    val fileBrowser: DeviceFileBrowser by lazy {
        DeviceFileBrowser(runner = SystemAdbProcessRunner, endpointReader = ::currentEndpoint)
    }

    /** 设备应用浏览器（App 级单源，惰性同 [fileBrowser]）：请求-回执模型 */
    val packageBrowser: DevicePackageBrowser by lazy {
        DevicePackageBrowser(runner = SystemAdbProcessRunner, endpointReader = ::currentEndpoint)
    }

    /** 布局检查器（App 级单源）：单次 dump 请求-回执，无轮询 */
    val layoutInspector: DeviceLayoutInspector by lazy {
        DeviceLayoutInspector(runner = SystemAdbProcessRunner, endpointReader = ::currentEndpoint)
    }

    /** 全窗口视图检查器（App 级单源）：dump → pull → 解码，请求-回执 */
    val windowViewsInspector: DeviceWindowViewsInspector by lazy {
        DeviceWindowViewsInspector(runner = SystemAdbProcessRunner, endpointReader = ::currentEndpoint)
    }

    /**
     * 无线调试连接器（App 级单源，惰性同 [monitor]）：设备三流消费 [monitor]
     * （目标解析同源——选定优先/单设备回退），命令只在动作钮点击时 spawn。
     */
    val wirelessConnector: DeviceWirelessConnector by lazy {
        DeviceWirelessConnector(
            scope = newScope(),
            devices = monitor,
            runner = SystemAdbProcessRunner,
            endpointReader = ::currentEndpoint,
            log = adbLog,
        )
    }
}
