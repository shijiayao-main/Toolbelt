package com.jiaoay.toolbelt.adb

/**
 * 内置 adb 的平台族：决定随包资源键、可执行文件名与命令引号语义。
 *
 * 平台判定同时用于两件事，必须同源：资源选取（加载哪个平台的二进制）与命令重写
 * （Windows 走 `cmd.exe` 双引号、Unix 走登录 shell 单引号）。两处各自判定会让
 * 「跑在哪个平台」出现两种答案。
 */
enum class AdbPlatform(
    /** 随包资源目录名与下载资产后缀（`platform-tools_r<版本>-<key>.zip`） */
    val resourceKey: String,
    /** 可执行文件名：Windows 必须带 `.exe`，否则 cmd.exe 解析失败 */
    val executableName: String,
) {
    WINDOWS("windows", "adb.exe"),
    LINUX("linux", "adb"),
    DARWIN("darwin", "adb"),
    ;

    val isWindows: Boolean get() = this == WINDOWS

    companion object {
        /**
         * 按 `os.name` 判定当前平台；不认识的系统返回 null（调用方据此回退 PATH 上的 adb，
         * 不得抛异常——构建与运行都不该因平台未知而中断）。
         *
         * macOS 不区分 CPU 架构：官方 darwin 包是 x86_64+arm64 的 universal 二进制，一个包喂两个目标。
         */
        fun detect(osName: String): AdbPlatform? {
            val name = osName.lowercase()
            return when {
                // mac/darwin 必须先判：`darwin` 字面包含 `win`，顺序颠倒会把 macOS 认成 Windows
                name.contains("mac") || name.contains("darwin") -> DARWIN
                name.contains("win") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> null
            }
        }

        /** 当前进程平台（[detect] 读 `os.name`）；未知系统为 null */
        fun current(): AdbPlatform? = detect(System.getProperty("os.name").orEmpty())
    }
}
