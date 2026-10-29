package com.jiaoay.easy_dev_toolbox.core.data

/**
 * 剥离 UTF-8 BOM（﻿，EF BB BF 的字符形态）。
 *
 * 🔴 Windows 记事本保存的 JSON 恒带 BOM，`parseToJsonElement` /
 * JSON 解析器遇 BOM 直接失败——用户「只是用记事本改了个值」就整个配置读不出来。
 *
 * 同一语义允许两份实现（模块隔离所迫）：`:core`（宿主域，feature-shelltools 消费）与
 * `:lark-bridge`（jvmTarget 11 纯 JVM lib，不依赖 :core）各持一份，由跨模块契约测试
 * `BomStripContractTest` 防漂移。
 */
object BomStripper {

    /** 剥离文首 UTF-8 BOM；无 BOM 原样返回。 */
    fun strip(text: String): String =
        if (text.startsWith("﻿")) text.substring(1) else text

    /** 读文件并剥离 BOM（UTF-8）。 */
    fun readTextWithoutBom(file: java.io.File): String =
        strip(file.readText(Charsets.UTF_8))
}
