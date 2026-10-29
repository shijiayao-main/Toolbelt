package com.jiaoay.easy_dev_toolbox.page.larkbridge.log

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.easy_dev_toolbox.core.log.JLog

 /**
  * [BridgeLog] → 宿主 [JLog] 的桥接适配器。
  *
  * 为什么需要它：App 侧桥接运行日志（重连 / 队列丢弃 / 回复失败 / agent stderr）若只进
  *    `RingLogBus`（内存环形缓冲）则重启即丢、不落 app.log，用户报障时零持久凭据。
  *    本适配器经 `BridgeAssembler.assemble(logDelegate = …)` 注入，成为 `ObservingBridgeLog`
  *    的二级输出（双写：日志台照旧 + app.log 落盘）。
 */
object JLogBridgeDelegate : BridgeLog {

    private const val TAG = "LarkBridge"

    override fun debug(message: String) {
        JLog.d(TAG) { message }
    }

    override fun info(message: String) {
        JLog.i(TAG) { message }
    }

    override fun warn(message: String, error: Throwable?) {
        if (error != null) {
            JLog.w(TAG) { "$message | ${error.javaClass.simpleName}: ${error.message.orEmpty()}" }
        } else {
            JLog.w(TAG) { message }
        }
    }

    override fun error(message: String, error: Throwable?) {
        if (error != null) {
            JLog.e(TAG, error) { message }
        } else {
            JLog.e(TAG) { message }
        }
    }
}
