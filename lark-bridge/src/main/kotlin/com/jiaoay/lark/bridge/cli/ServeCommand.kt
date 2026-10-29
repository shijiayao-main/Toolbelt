// serve 子命令：加载配置 → 装配唯一门面 → start → 阻塞运行 → 优雅停止 → exitProcess。
// 🔴 头号注意事项（gateway-contract.md §16.6）：SdkLarkGateway.close() 单靠反射 shutdown 不能让 JVM 自然退出
//    （残留 OkHttp Dispatcher + main 两个非 daemon 线程）⇒ serve 的停止流程必须在 bridge.close() 返回后由宿主显式 exitProcess。
//    网关内部绝不 exitProcess/System.exit/Runtime.halt——那是宿主的责任，宿主就是本命令。
// 可测性 seam：exit（(Int)->Unit）与 gatewayFactoryOverride（(()->MessageGateway)?）由构造注入，
//    CliExitProcessTest 据此断言「close() 之后调用 exit(0)」且绝不真退出 JVM。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.Context
import com.jiaoay.lark.bridge.Bridge
import com.jiaoay.lark.bridge.BridgeAssembler
import com.jiaoay.lark.bridge.BridgeGateways
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.gateway.MessageGateway
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** serve run() 的三态收尾（在唯一一层 runBlocking 内产出，close()/exit() 一律挪到阻塞世界执行，避免嵌套）。 */
private sealed interface ServePhase {
    /** start 被拒（含 code，供 exitCodeFor）。 */
    data class Refused(val code: BridgeErrorCode?) : ServePhase

    /** --once：已在挂起世界优雅 stop，待 close()+exit(0)。 */
    data object OnceStopped : ServePhase

    /** 长驻：待注册 shutdown hook + latch.await()。 */
    data object Resident : ServePhase
}

/**
 * `serve`：启动桥接服务并阻塞运行（Ctrl+C 优雅停止）。
 *
 * @param gatewayFactoryOverride 测试注入的网关工厂（null ⇒ 按 --dry-run/config.dryRun 选 SyntheticGateway 或真实 SDK 网关）。
 */
class ServeCommand(
    out: PrintStream = System.out,
    exit: (Int) -> Unit = { exitProcess(it) },
    private val gatewayFactoryOverride: (() -> MessageGateway)? = null,
) : BridgeConfigCommand("serve", out, exit) {

    override fun help(context: Context) =
        "启动桥接服务并阻塞运行；Ctrl+C 优雅停止后由宿主 exitProcess（网关内部绝不退出 JVM，§16.6）\n" +
            "serve 为无 agent 应答形态：消息收发/文件登记/固定指令照常；agent 类消息将明确报「未装配应答引擎」。\n" +
            "迁移提示：旧版 --app-id / --app-secret 等 30 个业务字段旗标已移除；改用 --set <点路径>=<值>" +
            "（ephemeral，永不落盘，只影响本次进程）覆写单个字段，或直接编辑 --config 指向的 JSON 文档；" +
            "全字段点路径表见 check 子命令输出。"

    override fun run() {
        val resolution = resolveConfig()
        if (resolution is ConfigResolution.Err) {
            emit("配置错误 [${resolution.code}]：${resolution.message}")
            exit(exitCodeFor(resolution.code))
            return
        }
        val ok = resolution as ConfigResolution.Ok
        val config = ok.config
        val dataRoot = resolveDataRoot()
        emit("数据根：$dataRoot")
        emit("配置来源：${ok.source}")

        // agent 硬依赖预检随 CLI 委托后端退役：serve 恒注入 NoAgentRunner（无应答引擎形态），
        //    预检语义退役——消息收发/文件登记/固定指令照常，agent 类消息由 NoAgentRunner 显式报错。
        // 网关 scope（真实 SDK 网关需要；SyntheticGateway 用不到，但仍统一创建/取消，无副作用）。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // G-F：网关工厂唯一化——CLI 与 App 都调 BridgeGateways.resolve（不再各自拼 SdkLarkGateway）。
        //    dryRun 旗标与 config.connection.dryRun 取或 ⇒ SyntheticGateway 替身（本地联调不出网）。
        val factory: () -> MessageGateway = gatewayFactoryOverride
            ?: { BridgeGateways.resolve(config, scope, dryRunOverride = dryRun) }

        // 唯一装配点（App 与 CLI 共用）：defaultConfig 传生效配置，磁盘缺失时即用它；随后 applyConfig 把覆写落进 configRef。
        val bridge = BridgeAssembler.assemble(
            dataRoot = dataRoot,
            gatewayFactory = factory,
            defaultConfig = config,
            parentJob = null, // CLI serve 独立根
            consoleLog = true,
            // --verbose ⇒ 桥接侧 console 下限降到 DEBUG，放出既有拒绝诊断；不加持仍 INFO（行为不变）。
            consoleMinLevel = consoleLevelFor(verbose),
        )

        // 🔴 run() 边界**恰好一层** runBlocking（CLI 是阻塞入口，runBlocking 唯一正当位置；Bridge.start/applyConfig/stop 均已 suspend）。
        //    块内只做挂起调用，**绝不**调用 bridge.close()——close() 自带唯一一层 runBlocking，在块内调用即嵌套（严禁）。
        //    close()/exit() 一律挪到 runBlocking 返回后的阻塞世界执行。
        val phase = runBlocking {
            // 🔴 --set ephemeral：persist=false，覆写只在本进程内存生效，**任何情况都不写 bridge-config.json**
            //    （见 BridgeConfigCommand.resolveConfig KDoc；stop()⑧ flushNow 因无 pending draft 天然 no-op）。
            bridge.applyConfig(config, persist = false)
            when (val started = bridge.start()) {
                BridgeStartResult.Started -> {
                    emit("桥接已启动：${bridge.status.value.connection}")
                    if (once) {
                        bridge.stop(Bridge.GRACE_MILLIS)
                        ServePhase.OnceStopped
                    } else {
                        ServePhase.Resident
                    }
                }
                BridgeStartResult.AlreadyRunning -> {
                    emit("桥接已在运行")
                    if (once) {
                        bridge.stop(Bridge.GRACE_MILLIS)
                        ServePhase.OnceStopped
                    } else {
                        ServePhase.Resident
                    }
                }
                is BridgeStartResult.Refused -> {
                    // 缺 appId / 锁被占 / 连接失败：明确文案 + 非 0 退出，不抛栈。
                    emit("启动被拒 [${started.code}]：${started.reason}")
                    if (started.code == BridgeErrorCode.BlockedByPeerInstance) {
                        emit("提示：飞书集群模式不广播，同 App ID 双开必随机丢消息——请先停止另一实例。")
                    }
                    ServePhase.Refused(started.code)
                }
            }
        }

        when (phase) {
            is ServePhase.Refused -> {
                bridge.close()
                scope.cancel()
                exit(exitCodeFor(phase.code))
                return
            }
            ServePhase.OnceStopped -> {
                bridge.close()
                scope.cancel()
                emit("已按 --once 优雅停止")
                // 🔴 gateway-contract.md §16.6：close() 返回后由宿主显式 exitProcess（正常退出 0）。
                exit(EXIT_OK)
                return
            }
            ServePhase.Resident -> {
                // 长驻：SIGINT/停止信号 → shutdown hook 内 stop+close（🔴 hook 是独立线程，其内 close() 的 runBlocking 不与主线程嵌套；
                // 且不在 hook 内 exitProcess，避免与 JVM 关闭流程死锁）→ 主线程 exit(0)。
                val latch = CountDownLatch(1)
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        runCatching { bridge.close() }
                        runCatching { scope.cancel() }
                        latch.countDown()
                    },
                )
                emit("桥接长驻运行中，Ctrl+C 停止…")
                latch.await()
                // 🔴 gateway-contract.md §16.6：close() 已在 hook 内完成，主线程显式退出（残留非 daemon 线程由 JVM halt 一并回收）。
                exit(EXIT_OK)
            }
        }
    }
}
