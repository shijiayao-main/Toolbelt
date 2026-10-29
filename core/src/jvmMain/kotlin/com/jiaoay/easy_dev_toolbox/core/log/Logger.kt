package com.jiaoay.easy_dev_toolbox.core.log

/**
 */
interface Logger {
    fun d(tag: String, msg: () -> String)

    fun i(tag: String, msg: () -> String)

    fun w(tag: String, msg: () -> String)

    fun e(tag: String, msg: () -> String)

    fun e(tag: String, throwable: Throwable, msg: () -> String)
}