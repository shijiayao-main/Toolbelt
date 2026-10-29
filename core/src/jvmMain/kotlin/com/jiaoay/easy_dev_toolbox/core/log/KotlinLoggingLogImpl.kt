package com.jiaoay.easy_dev_toolbox.core.log

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level

class KotlinLoggingLogImpl : Logger {

    override fun d(tag: String, msg: () -> String) {
        log(level = Level.DEBUG, tag = tag, msg = msg)
    }

    override fun i(tag: String, msg: () -> String) {
        log(level = Level.INFO, tag = tag, msg = msg)
    }

    override fun w(tag: String, msg: () -> String) {
        log(level = Level.WARN, tag = tag, msg = msg)
    }

    override fun e(tag: String, msg: () -> String) {
        log(level = Level.ERROR, tag = tag, msg = msg)
    }

    override fun e(tag: String, throwable: Throwable, msg: () -> String) {
        val name = getNameByFunction(msg)
        KotlinLogging.logger(name).error(throwable) {
            "$tag: ${msg.invoke()}"
        }
    }

    private fun log(level: Level, tag: String, msg: () -> String) {
        val name = getNameByFunction(msg)
        KotlinLogging.logger(name).at(level) {
            this.message = "$tag: ${msg.invoke()}"
        }
    }

    private fun getNameByFunction(func: () -> String): String {
        val name = func.javaClass.name
        val slicedName = when {
            name.contains("Kt$") -> name.substringBefore("Kt$")
            name.contains("$") -> name.substringBefore("$")
            else -> name
        }
        return slicedName
    }
}