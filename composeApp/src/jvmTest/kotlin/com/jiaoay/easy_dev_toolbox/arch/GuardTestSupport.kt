package com.jiaoay.easy_dev_toolbox.arch

import java.io.File

/**
 * 扫盘型守护测试共享工具（locateProjectRoot 单源收敛件）。
 *
 * 此前 11 个守护测试各持一份 private locateProjectRoot（逐字相同的上溯找
 * settings.gradle.kts 配方）——收敛为单源。放 `arch` 包（守护测试全在此包/子包，
 * BackButtonAndTransitionGuardTest 在 page.home 但 import 无碍）。
 */
internal fun locateProjectRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    var hops = 0
    while (dir != null && hops < 8) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
        hops++
    }
    error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
}
