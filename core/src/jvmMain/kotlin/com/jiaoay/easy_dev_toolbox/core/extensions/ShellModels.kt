package com.jiaoay.easy_dev_toolbox.core.extensions

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

enum class ShellOutputSource {
    STDOUT,
    STDERR,
}

data class ShellOutput(
    val line: String,
    val source: ShellOutputSource,
)
