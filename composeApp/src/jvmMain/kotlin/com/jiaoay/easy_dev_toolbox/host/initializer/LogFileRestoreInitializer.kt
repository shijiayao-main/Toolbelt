package com.jiaoay.easy_dev_toolbox.host.initializer

import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.log.LogbackHelper

/**
 * 文件日志恢复（宿主注册，level 2 高优）：读设置页持久化开关
 * （KEY_LOG_FILE_ENABLED 属宿主设置域），恢复文件 appender。
 *
 * 与 :core 的 JLogInitializer（level 1 日志引导）分离——:core 不依赖宿主配置；
 * 时序与拆分前一致（原逻辑内联在 level 1 日志引导尾部，恢复不阻断启动、失败仅告警）。
 */
class LogFileRestoreInitializer : JInitializer {

    override val isHighLevel: Boolean = true

    override fun level(): Int {
        return 2
    }

    override suspend fun init(): Boolean {
        // 恢复文件日志开关（设置页"开启日志存储"持久化值）
        if (EasyDataManager.getLogFileEnabled()) {
            LogbackHelper.enableFileLog()
            if (LogbackHelper.enableWriteFile.value.not()) {
                // appender 创建失败（如日志目录不可写）：不阻断启动，仅告警
                JLog.w(TAG) { "init: 文件日志恢复失败，运行态与持久化开关不一致" }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "LogbackInitializer"
    }
}
