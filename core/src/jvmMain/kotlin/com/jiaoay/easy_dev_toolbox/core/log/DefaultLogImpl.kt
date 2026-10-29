package com.jiaoay.easy_dev_toolbox.core.log

/**
 */
class DefaultLogImpl : Logger {
    override fun d(tag: String, msg: () -> String) {
        println("d: $tag: ${msg.invoke()}")
    }

    override fun i(tag: String, msg: () -> String) {
        println("i: $tag: ${msg.invoke()}")
    }

    override fun w(tag: String, msg: () -> String) {
        println("w: $tag: ${msg.invoke()}")
    }

    override fun e(tag: String, msg: () -> String) {
        println("e: $tag: ${msg.invoke()}")
    }

    override fun e(tag: String, throwable: Throwable, msg: () -> String) {
        println("e: $tag: throwable: ${throwable.message}, ${msg.invoke()}")
    }
}