package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.ShellResult

/**
 * 设备域测试共享桩。
 *
 * 内核同时要求执行通道与端点读取缝，只关心业务断言（拉取取消、安装回执等）的用例
 * 不必各编一份 fake。
 */
internal val NoopRunner: AdbProcessRunner = AdbProcessRunner { Result.success(ShellResult(0, "", "")) }

/** 恒定端点：路径留空 = 走字面 adb（命令串断言口径与迁移前一致） */
internal fun endpointOf(path: String = ""): AdbEndpoint = AdbEndpoint(path)
