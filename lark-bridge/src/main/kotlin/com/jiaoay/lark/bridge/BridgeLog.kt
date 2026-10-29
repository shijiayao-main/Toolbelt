package com.jiaoay.lark.bridge

/**
 * lib 侧自建日志接口（🔴 不引用 :core 的 JLog——jvmTarget 方向约束）。
 *
 * 构造注入（无全局单例）；禁止 object/companion/顶层 @Volatile var 全局可变状态。
 */
interface BridgeLog {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable? = null)

    companion object {
        /** 静默实现：测试与 dry-run 场景使用（无副作用、无全局状态）。 */
        val Silent: BridgeLog = object : BridgeLog {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, error: Throwable?) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}

/**
 * 最小实现：写到注入的 [out]（默认 System.err）。
 * 🔴 secret 掩码由调用方负责（BridgeField.secret / LarkCredential.masked()），本实现不做内容改写。
 */
class PrintStreamBridgeLog(
    private val out: java.io.PrintStream = System.err,
    private val minLevel: Level = Level.INFO,
) : BridgeLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private fun log(level: Level, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        out.println("[$level] $message")
        error?.printStackTrace(out)
    }

    override fun debug(message: String) = log(Level.DEBUG, message, null)
    override fun info(message: String) = log(Level.INFO, message, null)
    override fun warn(message: String, error: Throwable?) = log(Level.WARN, message, error)
    override fun error(message: String, error: Throwable?) = log(Level.ERROR, message, error)
}
