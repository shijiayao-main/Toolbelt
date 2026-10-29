package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlin.system.exitProcess

/**
 * kb-import CLI 入口：`./gradlew :core:runKbImport --args="--kb <目录>"`
 * 或打包产物 `java -cp <runtime> ...core.data.db.KbImportMainKt --kb <目录>`。
 *
 * 退出码由 [KbImportCli.run] 决定（0 成功 / 1 用法或校验失败），此处只做进程退出。
 */
fun main(args: Array<String>) {
    exitProcess(KbImportCli.run(args))
}
