// :lark-gateway 测试（M3-T3，§11.10 GatewayCloseReleasesThreadsTest，决策 13③ 报警点）。
// 🔴 §11.10 ⑧：本文件**不用虚拟时间**——真实 Dispatchers.IO + 真实线程。
// 🔴 下线批（2026-09-12）后的可兑现性分工：
//   ④（close 幂等）⑥（connectionState 归位）⑦（网关内零 exitProcess/System.exit）= SDK-free 兑现（不变）。
//   原 ①②③⑤（反射 shutdown 的实测数值与失效报警）已随反射路径删除**整体退役**——入站恒 webhook +
//   自研 ws（SDK 侧 rawWsClient 恒 null，无 ws 线程池存在），反射无对象可关。close 的收口结构
//   （步 1.5 WsClientTransport 关闭 = scope.cancel 全回收）由下述 grep 断言锁定；
//   真机线程回落数值归 T11 重测（OkHttp Dispatcher 残留契约不变）。
package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * §11.10：`close()` 幂等 + 状态归位 + 网关内零进程级 API + 自研 ws 收口的结构前提。
 */
class GatewayCloseReleasesThreadsTest {

    // ④ close() 幂等：未连接网关连调 3 次不抛（CAS 保证只有首次执行关闭步骤，§3.11 / [BC:1257]）
    @Test
    fun `close is idempotent and never throws on an unconnected gateway`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gw = SdkLarkGateway(netDispatcher = Dispatchers.IO, scope = scope, wsInboundFactory = NoopWsInboundFactory)
            gw.close()
            gw.close()
            gw.close()   // 连调 3 次不抛（closed.compareAndSet 幂等）
        } finally {
            scope.cancel()
        }
    }

    // ⑥ close() 后 connectionState 归位：close 前为 Idle ⇒ 保持 Idle（§9.3 步 6 / §2.7 状态机）
    @Test
    fun `close keeps an idle gateway idle instead of forcing disconnected`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gw = SdkLarkGateway(netDispatcher = Dispatchers.IO, scope = scope, wsInboundFactory = NoopWsInboundFactory)
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value, "新建网关初始态应为 Idle")
            gw.close()
            // 🔴 §9.3 步 6：若原为 Idle 则保持 Idle（不误报 Disconnected），仅非 Idle 才归位 Disconnected(Unknown,"closed")
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value, "close 前 Idle ⇒ close 后保持 Idle")
        } finally {
            scope.cancel()
        }
    }

    // ⑦ 网关内零 exitProcess / System.exit（§9.4：杀进程是宿主的权力，库模块调进程级 API 会让单测终止 JVM）
    @Test
    fun `gateway main sources contain no process level exit`() {
        val mainDir = File(repoRoot(), "lark-gateway/src/main/kotlin")
        assertTrue(mainDir.isDirectory, "定位不到 lark-gateway/src/main/kotlin：${mainDir.absolutePath}")
        val ktFiles = mainDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(ktFiles.isNotEmpty(), "lark-gateway/src/main/kotlin 下应有 .kt 源文件")
        ktFiles.forEach { f ->
            // 🔴 归一化：SdkLarkGateway.kt 抬头注释写「绝不出现 exitProcess/System.exit」、log.error 文案写「依赖宿主 exitProcess 兜底」，
            //    都是对禁用 API 的**提及**而非调用；未归一化会假红。归一化后只留真实调用。
            val text = stripCommentsAndStrings(f.readText())
            assertFalse("exitProcess" in text, "${f.name} 禁止 exitProcess（§9.4）")
            assertFalse("System.exit" in text, "${f.name} 禁止 System.exit（§9.4）")
            assertFalse("Runtime.getRuntime().halt" in text, "${f.name} 禁止 Runtime.halt（§9.4）")
        }
    }

    // ①⑤ 的结构前提（下线批改写）：close 收口 = WsClientTransport 关闭（scope.cancel 全回收）+ 反射路径不存在
    @Test
    fun `close collapses ws transport via scope cancel and has no reflective path`() {
        val src = File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt").readText()
        val closeBody = src.substringAfter("override fun close()").substringBefore("private fun buildOptions(")
        assertTrue(closeBody.isNotEmpty(), "定位不到 close() 函数体")
        // CAS 幂等
        assertTrue("closed.compareAndSet(false, true)" in closeBody, "close 须以 CAS 保证幂等（§3.11）")
        // 步 1.5：自研入站传输收口（WsClient.close = scope.cancel，心跳/清扫/分发协程一次全回收——零反射）
        assertTrue("wsInboundTransportRef.getAndSet(null)?.close()" in closeBody, "close 须关闭自研入站传输（步 1.5）")
        // 原「步 4 反射 shutdown」已随下线批删除：入站恒 webhook + 自研 ws ⇒ rawWsClient 恒 null，无池可关。
        // 🔴 墓碑注释（解释为何删除）含函数名字样 ⇒ 必须归一化后扫描，否则误红
        val codeOnly = stripCommentsAndStrings(src)
        assertFalse("reflectivelyShutdownWsExecutor" in codeOnly, "反射 shutdown 路径已删（下线批 2026-09-12）")
        assertFalse("wsExecutorReleased" in codeOnly, "wsExecutorReleased 字段已删")
        // 🔴 归一化：close 的 KDoc 写有「依赖宿主 exitProcess 兜底」，是对禁用 API 的提及而非调用
        val closeNorm = stripCommentsAndStrings(closeBody)
        assertFalse("exitProcess" in closeNorm || "System.exit" in closeNorm, "close 内绝不 exitProcess（宿主权力，§9.4）")
    }

    // ②③ 的契约定稿（下线批改写）：close() KDoc 必须写清「反射已删 + OkHttp 残留 + exitProcess 契约」
    @Test
    fun `close KDoc records post-removal contract and exitProcess requirement`() {
        val src = File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt").readText()
        val kdoc = src.substringAfter("线程收口契约").substringBefore("override fun close()")
        assertTrue(kdoc.isNotEmpty(), "定位不到 close() 的线程收口 KDoc")
        // 🔴 下线批后契约：反射路径已删（零反射收口），出站仍走 SDK Client ⇒ OkHttp Dispatcher 残留
        assertTrue("零反射" in kdoc, "KDoc 须写明反射路径已删（WsClient.close = scope.cancel 收口）")
        assertTrue("OkHttp Dispatcher" in kdoc, "KDoc 须写明残留非 daemon 线程 = OkHttp Dispatcher + main")
        // 🔴 头号契约：close() 不能让 JVM 自然退出，必须配合宿主显式 exitProcess（给 M5-T10 CLI serve / M6-T12 onAppExit）
        assertTrue("exitProcess" in kdoc, "KDoc 须写明 close() 必须配合宿主显式 exitProcess（决策 13②）")
        assertTrue("容忍残留非 daemon" in kdoc, "KDoc 须写明线程阈值容忍残留非 daemon，不能要求归零")
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }
}
