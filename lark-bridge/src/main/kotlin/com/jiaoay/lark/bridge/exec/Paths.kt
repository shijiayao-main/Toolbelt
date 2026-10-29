package com.jiaoay.lark.bridge.exec

import java.io.File

/**
 * `~` 前缀路径展开与平台绝对路径判定（**平移**旧 `util/PathExpansion.kt` 28 行，行为逐字保持）。
 *
 * 识别三种形态：裸 `~`、`~/`（POSIX 惯例）、`~\`（Windows 分隔符习惯）；
 * 其余（`~user`、`~foo/` 前缀、无前缀）原样返回——不尝试解析他人主目录。
 * 改名 `Paths`（exec 域内聚），语义与旧 `PathExpansion` 完全一致（[behavior-contract.md §4 · PathExpansionTest 平移]）。
 */
object Paths {

    /** `~` 前缀路径展开：`~`、`~/x`、`~\x` → 用户主目录；其余原样返回。 */
    @JvmStatic
    fun expandTilde(path: String): String {
        val home = System.getProperty("user.home")
        if (path == "~") return home
        val rest = path.takeIf { it.startsWith("~/") || it.startsWith("~\\") }
            ?.let { it.drop(1) } // 保留分隔符（/ 或 \），拼主目录后形成 ~/x 或 ~\x
            ?: return path
        return home + rest
    }

    /** 路径是否为绝对路径（平台语义：Windows 盘符/UNC 均绝对，Unix 以 / 开头）。 */
    @JvmStatic
    fun isAbsolute(path: String): Boolean = File(path).isAbsolute
}
