package com.jiaoay.easy_dev_toolbox.core.log

import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer

/**
 * 日志引导（level 1，高优先级）：仅装配日志后端与 JLog 门面。
 *
 * 文件日志恢复依赖宿主持久化配置（KEY_LOG_FILE_ENABLED 属宿主设置域），
 * 由宿主注册的 LogFileRestoreInitializer 在更高 level 执行——:core 不依赖宿主配置。
 */
class JLogInitializer : JInitializer {

    override val isHighLevel: Boolean = true

    override fun level(): Int {
        return 1
    }

    override suspend fun init(): Boolean {
        JLog.d(TAG) { "init: start" }
        LogbackHelper.init()
        val logImpl = KotlinLoggingLogImpl()
        JLog.init(logImpl)
        JLog.i(TAG) { "init: 日志初始化完成" }
        return true
    }

    companion object {
        private const val TAG = "LogbackInitializer"
    }
}
