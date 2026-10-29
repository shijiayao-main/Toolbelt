package com.jiaoay.easy_dev_toolbox.core.device

import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置 adb server 的退出收口：应用退出时结束自己那个专用端口上的 server。
 *
 * ## 为什么值得做
 *
 * 内置 server 在独立端口常驻，会持续持有 USB 设备的独占访问。应用退出后若它不走，
 * 用户接着打开 IDE 或其他 adb 工具会发现设备「看不见」——那正是为了隔离本机生态而
 * 引入的端口，退出时不清理就把隔离代价转嫁给了用户。
 *
 * ## 只杀自己的端口
 *
 * [killCommand] 显式带 `-P <port>`：只结束本应用分配的端口，用户默认端口上的 server
 * （IDE、终端）一根手指都不碰。端口为空（用户配了自定义 adb，或内置 adb 从未就绪）
 * 时整段跳过，零 spawn。
 *
 * ## 时序契约
 *
 * 注册必须晚于投屏/抓取等会话参与者：那些收尾要用 server（隧道清理、显示触点恢复），
 * server 先死会让它们以失败告终。[shutdown] 非阻塞——派发一个守护线程跑一次性命令后
 * 立即返回；结束失败只留下一个空闲 server，不影响数据正确性。
 */
class AdbServerShutdownParticipant : AppLifecycleParticipant {

    override val id: String = ID

    override suspend fun start(scope: CoroutineScope) {
        // 无启动动作：server 由首次 adb 命令按需拉起
    }

    override fun shutdown() {
        val port = ShellCommandBuilder.adbServerPort ?: return
        runCatching {
            Thread {
                runCatching {
                    // 一次性命令走 :core 既有 shell 原语（不新增 ProcessBuilder 合法点）。
                    // 此处需要独立 scope 而非 AppHost 派生：AppHost 的根 scope 在退出序里
                    // 已进入取消阶段，派生 scope 会随根一起被取消、命令根本发不出去。
                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                        SimpleShell.execute(killCommand(port))
                            .onFailure { JLog.d(TAG) { "内置 adb server 收口失败：${it.message}" } }
                    }
                }
            }.apply { isDaemon = true; name = "adb-server-shutdown" }.start()
        }.onFailure { JLog.d(TAG) { "内置 adb server 收口派发失败：${it.message}" } }
    }

    companion object {
        const val ID = "adb-server-shutdown"
        private const val TAG = "AdbServerShutdown"

        /** 收口命令：显式端口，只影响本应用那个 server */
        internal fun killCommand(port: Int): String = "adb -P $port kill-server"
    }
}
