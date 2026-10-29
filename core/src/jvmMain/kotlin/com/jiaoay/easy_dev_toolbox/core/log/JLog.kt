package com.jiaoay.easy_dev_toolbox.core.log

/**
 */
object JLog {
    private var log: Logger = DefaultLogImpl()

    fun init(log: Logger) {
        this.log = log
    }

    fun d(tag: String, msg: () -> String) {
        log.d(tag, msg)
    }

    fun i(tag: String, msg: () -> String) {
        log.i(tag, msg)
    }

    fun w(tag: String, msg: () -> String) {
        log.w(tag, msg)
    }

    fun e(tag: String, msg: () -> String) {
        log.e(tag, msg)
    }

    fun e(tag: String, e: Throwable?, msg: () -> String) {
        if (e == null) {
            log.e(tag, msg)
        } else {
            log.e(tag, e, msg)
        }
    }
}