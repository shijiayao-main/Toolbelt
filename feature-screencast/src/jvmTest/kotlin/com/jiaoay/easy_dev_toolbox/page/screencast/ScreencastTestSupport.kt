package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.ShellResult

/**
 * 投屏测试共享桩：恒返回成功 + 空输出的执行通道。
 *
 * 内核同时要求执行通道与专用 gateway，只关心 gateway 行为的用例不必再编一份 fake。
 */
internal val NoopRunner: AdbProcessRunner = AdbProcessRunner { Result.success(ShellResult(0, "", "")) }
