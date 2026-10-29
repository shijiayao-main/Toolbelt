package com.jiaoay.easy_dev_toolbox.core.extensions

/**
 * Shell 命令使用的登录 shell 类型。
 *
 */
enum class ShellType(val displayName: String, val executableName: String) {
    ZSH("Zsh", "zsh"),
    BASH("Bash", "bash"),
    SH("Sh", "sh"),
}
