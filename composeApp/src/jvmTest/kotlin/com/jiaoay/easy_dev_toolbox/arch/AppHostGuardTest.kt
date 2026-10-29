// S2 · AppHost 收口守护：宿主侧 App 级 CoroutineScope 构造点必须收口。
// 🔴 设计（方案 5.1 AppHostGuardTest 裁定）：
//  - A 组扫描面**诚实收窄为宿主侧**（composeApp/feature-*/core 的 src/jvmMain）——lib 模块
//    （lark-bridge/lark-gateway/lark-ws，含 BridgeDispatchers.kt 的 11 处）生命周期由
//    Bridge.stop/close 自管，不是 App 级 scope，刻意不在本守护面内（不做过度承诺）；
//  - 白名单条目各带理由 + 防腐化断言（条目必须仍有真实命中；先例：UiArchitectureGuardTest）；
//  - 🔴 正则排除 `rememberCoroutineScope`（Compose 组合作用域，字面含子串会误命中）。
// 🔴 验收纪律：--rerun-tasks --no-build-cache（AGENTS.md「测试」节）。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AppHostGuardTest {

    /**
     * A：宿主侧生产源（`src/jvmMain`）中，`CoroutineScope(` 构造点必须 ⊆
     * `{AppHost.kt}` ∪ [SCOPE_ALLOWLIST]——**或**为「从 AppHost.scope 派生子 scope」的
     * 合法形态（`CoroutineScope(AppHost.scope.coroutineContext + ...)`）。
     *
     * 白名单三态判定：
     * 1. 落在 AppHost.kt（唯一根 scope 本体）⇒ 合法；
     * 2. 行内含 `AppHost.scope.coroutineContext`（派生子 scope：SupervisorJob 隔离异常、
     *    退出随根 cancel）⇒ 合法（S2 改造后的正当形态）；
     * 3. 在 [SCOPE_ALLOWLIST]（存量自建、生命周期自管或页面作用域，各带理由）⇒ 登记豁免；
     * 4. 其余 ⇒ 违规：App 级后台任务的新 scope 必须挂 AppHost（或从其派生）。
     */
    @Test
    fun `A host-side CoroutineScope constructors are confined to AppHost or registered exemptions`() {
        val root = locateProjectRoot()
        // 评审修复批 2026-09-23：扫描面补齐后建模块（feature-quest/feature-logworkbench/
        // feature-mcp/feature-voice——2026-09-19~20 建仓后未随批扩表，守护盲区）；
        // 列表项 = <module>/src/jvmMain/kotlin 硬编码路径，与 .filter { isDirectory }
        // 机制兼容（目录存在即收录，不存在静默跳过——新增模块须在此追加）。
        val hostMainDirs = listOf(
            "composeApp", "core", "ui", "feature-timestamp", "feature-logkit",
            "feature-shelltools", "feature-logcat", "feature-screencast", "feature-larkbridge", "feature-ai", "feature-archive",
            "feature-quest", "feature-logworkbench", "feature-mcp", "feature-voice",
        )
            .map { File(root, "$it/src/jvmMain/kotlin") }
            .filter { it.isDirectory }
        check(hostMainDirs.size >= MIN_HOST_MODULES) { "宿主侧模块根定位异常（${hostMainDirs.size} 个）" }

        val ktFiles = hostMainDirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.sortedBy { it.path }
        check(ktFiles.size >= MIN_HOST_KT_FILES) {
            "宿主侧 .kt 扫描数异常（${ktFiles.size} < $MIN_HOST_KT_FILES）：守护会静默失效"
        }

        // 🔴 排除 rememberCoroutineScope：字面含 `CoroutineScope(` 子串但语义是 Compose 组合作用域
        val ctorRegex = Regex("""(?<!remember)CoroutineScope\s*\(""")
        val violations = mutableListOf<String>()
        val allowlistHits = mutableSetOf<String>()
        for (file in ktFiles) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().forEachIndexed { idx, raw ->
                // 剥注释：行尾 // 与 KDoc/块注释行（* 或 /* 开头）——构造点不可能写在注释里，
                // 而 KDoc 里的历史文字（如 ShellToolsRuntime 头注释的旧构造形态）会假红。
                val line = raw.substringBefore("//").let { if (it.trimStart().startsWith("*")) "" else it }
                if (!ctorRegex.containsMatchIn(line)) return@forEachIndexed
                val isAppHost = rel == APP_HOST_PATH
                val isDerived = line.contains("AppHost.scope.coroutineContext")
                val exempt = SCOPE_ALLOWLIST.any { (path, _) -> rel == path }
                if (exempt) allowlistHits += rel
                if (!isAppHost && !isDerived && !exempt) {
                    violations += "$rel:${idx + 1}: ${raw.trim()}"
                }
            }
        }

        // 防腐化：白名单条目必须仍有真实命中
        val stale = SCOPE_ALLOWLIST.filter { (path, _) -> path !in allowlistHits }
        if (stale.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("SCOPE_ALLOWLIST 存在已无真实命中的条目（防腐化断言）：")
                    stale.forEach { (path, reason) -> appendLine("  - $path（理由：$reason）") }
                    appendLine("该文件的 CoroutineScope 构造已消失或已改为 AppHost 派生——请同步删除白名单条目。")
                },
            )
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("宿主侧 App 级 scope 收口违例 ${violations.size} 处：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("App 级后台任务的 scope 必须从 AppHost.scope 派生（SupervisorJob 隔离异常、退出随根 cancel），")
                    appendLine("或确属页面/会话作用域时进 SCOPE_ALLOWLIST 并附理由（先例见现有条目）。")
                },
            )
        }
    }

    /**
     * B：`main.kt` 的 `onDispose` 退出清理只经 `AppHost.shutdown(...)` 单点调用
     * （旧三连裸调已收口；退出序由 AppHost 的 SHUTDOWN_ORDER 承载）。
     */
    @Test
    fun `B main kt onDispose cleanup goes through AppHost shutdown only`() {
        val root = locateProjectRoot()
        val mainKt = File(root, MAIN_KT_PATH)
        assertTrue(mainKt.isFile, "$MAIN_KT_PATH 缺失——入口被移动，须同步更新本测试")
        val text = stripLineComments(mainKt.readText())
        val onDisposeBlock = extractOnDisposeBlock(text)
            ?: fail("main.kt 中未找到 onDispose 块——退出清理结构变化，须同步更新本测试")
        // 🔴 B 组语义（精确化）：onDispose 块内必须含 AppHost.shutdown 单点调用，
        //    且桥接退出不得再绕过 AppHost 退出序直调（hostTeardown 的两个收尾调用是
        //    AppHost.shutdown 的参数体，属合法结构——退出序契约由 AppHost.SHUTDOWN_ORDER 承载）。
        assertTrue(
            "AppHost.shutdown(" in onDisposeBlock,
            "onDispose 退出清理必须经 AppHost.shutdown（bridgeExit/hostTeardown 以参数注入退出序），" +
                "实际块体=\n$onDisposeBlock",
        )
        // 违规判定（精确）：`LarkBridgeManager.onAppExit()` 出现在**语句位**（整行剥空白后以它打头）
        // = 顶层直调；作为 `bridgeExit = { ... }` 的 lambda 体出现（缩进在参数内）是合法注入形态。
        val directCall = onDisposeBlock.split('\n').any { line ->
            line.trim().startsWith("LarkBridgeManager.onAppExit()")
        }
        assertFalse(
            directCall,
            "桥接退出不得在 onDispose 顶层直调——必须经 AppHost.shutdown 的 bridgeExit 参数（退出序 ① 桥接先停）",
        )
    }

    /** 自 user.dir 逐级向上探测项目根（对齐 LarkSdkIsolationTest 的定位约定） */

    /** 剥 // 行注释（保留代码；块注释内不再匹配调用——main.kt 无块注释写法，行剥已足） */
    private fun stripLineComments(source: String): String =
        source.split('\n').joinToString("\n") { it.substringBefore("//") }

    /** 提取 main.kt 里 `onDispose {` 到配对 `}` 的块体 */
    private fun extractOnDisposeBlock(source: String): String? {
        val start = source.indexOf("onDispose {")
        if (start < 0) return null
        var depth = 0
        var i = start + "onDispose ".length // 从 '{' 起
        val sb = StringBuilder()
        while (i < source.length) {
            val c = source[i]
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return sb.toString()
            }
            sb.append(c)
            i++
        }
        return null
    }

    private companion object {
        const val APP_HOST_PATH =
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/runtime/AppHost.kt"
        const val MAIN_KT_PATH = "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/main.kt"

        /** 宿主侧模块数下限（评审修复批 2026-09-23 扩容后 13 个 jvmMain 模块） */
        const val MIN_HOST_MODULES = 12

        /** 宿主侧生产源 .kt 下限（扩容后实测 292，留余量） */
        const val MIN_HOST_KT_FILES = 250

        /**
         * 存量自建 scope 白名单（路径 → 理由）。🔴 每条三要素齐备：为何不能上交 AppHost、
         * 生命周期谁管、何时可移除。
         */
        val SCOPE_ALLOWLIST: List<Pair<String, String>> = listOf(
            // 应用退出收口：shutdown() 在 AppHost 根 scope 已进入取消阶段后执行，
            // 从根派生的 scope 会被一并取消、命令根本发不出去，故必须自建独立 scope。
            // 生命周期 = 单条 kill-server 命令执行完，守护线程承载、不阻塞退出路径。
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/device/AdbServerShutdownParticipant.kt" to
                "退出收口：根 scope 已取消，须自建短命 scope 发一次性 kill-server（非 App 级常驻任务）",
            // App.kt 的窗口几何防抖写回：DisposableEffect(hostWindow) 内自建 IO scope，随窗口
            // 析构 onDispose cancel（生命周期 = 窗口，不是 App 级）；EDT 防卡顿的刻意外置。
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/App.kt" to
                "窗口几何防抖写回：DisposableEffect 内自建、随窗口 onDispose cancel（页面/窗口作用域，非 App 级）",
            // ShellDebugViewModel 的两个自建 scope（文件级豁免覆盖同文件两构造点）：
            // ① 交互 shell 会话 scope：每次 startSession 新建、旧会话 cancel
            //   （生命周期 = 会话，开发调试页非生产工具页）。
            // ② Simple 执行孤儿 launcher（SYS-03 · 重设计批 1）：CoroutineScope(SupervisorJob+IO)
            //   fire-and-forget 承载 SimpleShell.execute 的阻塞 waitFor（默认不响应协程取消——
            //   取消中断线程须 runInterruptible，改动超 SYS-03 卡面 scope 故未走）；等待侧只
            //   await CompletableDeferred，cancel/30s 超时即时恢复 UI 不等 OS 进程；孤儿无取消
            //   路径、生命周期 = 单次 OS 进程执行自然退出，进程未死前输出丢弃（诚实边界）。
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugViewModel.kt" to
                "交互 shell 会话 scope（每会话新建/旧会话 cancel）+ Simple 执行孤儿 launcher（SYS-03：fire-and-forget 承载阻塞 waitFor、无取消路径、单次执行生命周期；cancel 只解锁 UI 不杀 OS 进程）（调试页，非 App 级）",
            // ShellHistoryManager.combineScope：排序流 combine 的内部 scope，close 时 cancel
            // （KDoc 明示「与 VM 生命周期一致」）。
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryManager.kt" to
                "排序 combine 内部 scope：随 VM close cancel（VM 生命周期，非 App 级）",
            // LarkBridgeManager.getOrCreate 的网关 scope：真实 SDK 网关的承载 scope，随
            // onAppExit 的 gatewayScopeRef.cancel 收（有取消路径；网关生命周期 ≠ App 级回路）。
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgeManager.kt" to
                "网关承载 scope：随 onAppExit 的 gatewayScopeRef.cancel 收口（网关生命周期自管，非 App 级后台回路）",
            // 🔴 2026-09-17 审计修复（第三批：StreamingShell 读协程移出 coroutineScope）：读流协程
            // 阻塞在不可取消的 reader.readLine() 上，coroutineScope 的取消语义被架空（孙进程持管道
            // 时 executeStreaming 永不返回）。改为方法内短命 scope：finally 里 join(1s) +
            // cancel 收口（生命周期 = 单次命令执行，方法返回前必收；非 App 级后台回路）。
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/StreamingShell.kt" to
                "读流排干 scope：方法内短命、finally join+cancel 收口（生命周期 = 单次命令执行，非 App 级）",
            // ArgvProcessRunner 的输出排干 scope（只读诊断域执行原语）：排干协程阻塞在不可取消的
            // read 上，作为 withContext 子协程会把 run() 的返回挂死（进程树已级联销毁但管道不 EOF
            // 的句柄泄漏场景）——独立短命 scope，finally cancel 收口（生命周期 = 单次探查执行，
            // 方法返回前必收；非 App 级后台回路）。同型配方 = StreamingShell 读流排干先例。
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/system/ArgvProcessRunner.kt" to
                "输出排干 scope：方法内短命、finally cancel 收口（生命周期 = 单次探查执行，非 App 级）",
            // PageHome 的 inspector 持久化写回 scope（调查工作台 S2：底面板 PanelHost 退役后
            // 平移为右缘 inspector 形态持久化——宽度/窄轨态防抖写回）：setter 均为 UI 线程
            // 同步调用面（非挂起），DataStore 写须 launch 下沉；DisposableEffect onDispose
            // 一并 cancel（生命周期 = PageHome 组合，非 App 级；同 App.kt 窗口几何先例）。
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHome.kt" to
                "inspector 持久化写回 scope：PageHome 组合作用域、onDispose cancel（UI 局部写回，非 App 级）",
            // （P5 收编 AppHost · R4 2026-09-25：McpAgentTools 自建 scope 面消失——
            //  宿主 scope 经 McpServerManager.scopeForAgent() 注入，豁免条目删除）
            // scopeForAgent 的未注入退回分支（测试/CLI 形态无宿主 scope）落在
            // McpServerManager——SupervisorJob 临时自建，生产恒走注入路径：
            "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpServerManager.kt" to
                "scopeForAgent 未注入退回：测试/CLI 形态临时自建（生产路径 = AppHost scope 注入，R4 P5 收编）",
        )
    }
}
