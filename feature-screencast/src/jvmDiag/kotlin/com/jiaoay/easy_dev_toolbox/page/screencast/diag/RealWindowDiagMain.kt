package com.jiaoay.easy_dev_toolbox.page.screencast.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastDetachedWindowController
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastDetachedWindowHost
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastFrame
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastRuntime
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastStatus
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 真窗渲染链端到端诊断（按需诊断通道——恒不进常规 jvmTest/build/CI，需真机在线 +
 * 桌面显示环境，只经 `./gradlew :feature-screencast:realWindowDiag` 显式触发；
 * 任务接线见本模块 build.gradle.kts 的 realWindowDiag JavaExec）。
 *
 * 链路 = 真实引擎会话（ScreencastRuntime.engine → Streaming → 首帧）→ 真产品拓扑
 * 独立窗（application{} 真启动器 + 宿主主窗内容树内组合真 [ScreencastDetachedWindowHost]，
 * 控制器经公开入口 openOrFocus 开窗）→ java.awt.Robot 同客户端自捕窗像素（进程捕自己
 * 的窗不受合成器对外部捕获的钳制；免任何外部工具）→ 进程内像素分析：
 * 帧区行带结构 + 方差块 + 双帧 5 秒间隔 diff。
 *
 * 判定契约：**帧区存在高方差内容块 且 双帧 diff bbox 非空 → PASS（退出码 0）**，
 * 否则 FAIL（退出码 1）。fps 仅记录进报告，不进判据（scrcpy 变化帧语义——静止
 * 画面低 fps 属正常）。Robot 捕获失败（headless 等环境不支持）给显式 FAIL 报告，
 * 不裸抛。
 *
 * 透底探针（顶栏带均亮读数）：开窗前后对宿主主窗/独立窗各取一条顶栏带均亮——
 * 深底内容在铺 ≈20-60、透壁纸 ≈223，判窗面空帧透底与否；附双窗 GraphicsConfiguration
 * 取证（vis=0x72 = 32 位 ARGB 逐像素半透明窗，透底候选的判定锚）。判法与机制真源见
 * 本仓投屏透底根因档案（GC 继承透底机制与 xwininfo 判据）se.md（仓内唯一文件名）。
 *
 * 为什么必须走 application{} 真启动器：手工 new ComposeWindow.setContent 会因
 * androidx.lifecycle 的主线程检查崩溃——Compose Desktop 的 UI 线程与生命周期装配
 * 只由 application 启动器建立（宿主产品 main.kt 同配方）。
 *
 * @param args 可选串号：`--serial=<串号>` 或裸位置参数；缺省取 `adb devices` 首个
 * 在线设备（经 SimpleShell 统一收口执行，禁 ProcessBuilder 直调——AGENTS.md 约定 1）
 */
fun main(args: Array<String>) {
    val exitCode = runCatching { runDiag(args) }.getOrElse { failure ->
        println("[diag] 判定: FAIL —— 异常终止: ${failure.message}")
        failure.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

/** 机制判别克隆窗开关（聚焦宿主后创建的最小窗——判 32 位 GC 继承下最小窗是否即死） */
private val CloneVisible = androidx.compose.runtime.mutableStateOf(false)

/** 诊断结论（pass + 人话摘要；指标明细在产出时已逐行打印） */
private data class DiagOutcome(val pass: Boolean, val summary: String)

/** 引擎会话就绪载荷（真启动器开窗前的预检产物） */
private class SessionReady(val serial: String, val deviceName: String)

private fun runDiag(args: Array<String>): Int {
    when (val preflight = runBlocking { preflightSession(args) }) {
        is DiagOutcome -> {
            println("[diag] 判定: FAIL —— ${preflight.summary}")
            return 1
        }

        is SessionReady -> println("[diag] 预检通过: serial=${preflight.serial} 设备='${preflight.deviceName}'")
    }

    var outcome: DiagOutcome = DiagOutcome(pass = false, summary = "诊断流程未执行")
    // exitProcessOnExit=false：launcher 不得自行 exitProcess(0)——判定报告打印与
    // FAIL=1 退出码契约由本 main 收尾（默认 true 会吞掉 FAIL 退出码并跳过报告）
    application(exitProcessOnExit = false) {
        fun finish(result: DiagOutcome) {
            outcome = result
            exitApplication()
        }

        // 宿主主窗：真产品拓扑里独立窗经本内容树组合（诊断 launcher 专用窗，非产品窗；
        // Window 组合点已在 WindowCompositionAnchorGuardTest 锚登记）
        Window(
            onCloseRequest = { finish(DiagOutcome(pass = false, summary = "宿主主窗在诊断完成前被关闭")) },
            title = "realWindowDiag 宿主主窗（诊断通道）",
            state = rememberWindowState(),
            // 与应用主窗（main.kt）完全同形态：undecorated+transparent 父窗——独立窗
            // 的 GC 继承（lastActiveGC）与透底形态都以此为前置，判法见类头 KDoc
            undecorated = true,
            transparent = true,
        ) {
            // 宿主窗内容复刻产品主窗的绘制面：不透明深底铺满（产品 = AppTheme Surface
            // colorScheme.background 铺面）——顶栏带均亮读数的内容在铺判定基准
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF131319.toInt()))) {
                BasicText("realWindowDiag 运行中——真产品独立窗已在本窗内容树内组合")
            }
            LaunchedEffect(Unit) {
                // runCatching 收口：诊断面任何意外都落显式 FAIL 报告，不裸抛炸组合
                finish(runCatching { diagFlow() }.getOrElse { DiagOutcome(pass = false, summary = "异常终止: ${it.message}") })
            }
        }
        // 机制判别克隆窗（宿主聚焦后经状态翻转才创建——与独立窗同受 lastActiveGC
        // 继承前置，但不含引擎/视频/alwaysOnTop，判最小形态窗在 32 位 GC 下是否即死）
        if (CloneVisible.value) {
            Window(
                onCloseRequest = { finish(DiagOutcome(pass = false, summary = "克隆窗被关闭")) },
                title = "realWindowDiag 克隆判别窗",
                state = rememberWindowState(width = 1141.dp, height = 642.dp),
                undecorated = false,
                transparent = false,
                resizable = true,
            ) {
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF131319.toInt()))) {
                    BasicText("clone visual discriminator")
                }
            }
        }
        // 真产品独立窗（宿主主窗内容树内组合——与宿主 main.kt 产品拓扑同构）
        ScreencastDetachedWindowHost(icon = null)
    }

    println("[diag] 判定: ${if (outcome.pass) "PASS" else "FAIL"} —— ${outcome.summary}")
    return if (outcome.pass) 0 else 1
}

/**
 * 开窗前预检（runBlocking 于主线程、无任何窗口在场）：串号解析 → 引擎真会话启动 →
 * 等 Streaming + 首帧。失败路径先收口引擎再返回 FAIL 结论，不悬挂会话。
 */
private suspend fun preflightSession(args: Array<String>): Any {
    val serial = resolveSerial(args)
        ?: return DiagOutcome(pass = false, summary = "无在线设备——真窗诊断需 adb 设备在线（或经 --serial 指定）")

    val engine = ScreencastRuntime.engine
    if (!engine.start(adbPath = ADB_PATH, serial = serial)) {
        return DiagOutcome(pass = false, summary = "引擎拒绝启动（当前状态非 Idle: ${engine.status.value}）")
    }
    val streaming = withTimeoutOrNull(STREAMING_TIMEOUT_MS) {
        engine.status.first { it is ScreencastStatus.Streaming } as ScreencastStatus.Streaming
    } ?: return stopAndFail("等待 Streaming 超时——当前状态 ${engine.status.value}")
    println("[diag] Streaming: 设备='${streaming.deviceName}' ${streaming.width}x${streaming.height}")

    val firstFrame: ScreencastFrame = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
        engine.frame.first { it != null }
    } ?: return stopAndFail("等待首帧超时——引擎未发布任何帧")
    println("[diag] 首帧已发布（世代 ${firstFrame.generation}）")
    return SessionReady(serial, streaming.deviceName)
}

private suspend fun stopAndFail(reason: String): DiagOutcome {
    ScreencastRuntime.engine.stop()
    return DiagOutcome(pass = false, summary = reason)
}

/** 双形态串号入参（--serial=x / 裸位置参数）；缺省取 `adb devices` 首个在线设备 */
private suspend fun resolveSerial(args: Array<String>): String? {
    args.firstOrNull { it.startsWith(SERIAL_ARG_PREFIX) }?.let { return it.removePrefix(SERIAL_ARG_PREFIX) }
    args.firstOrNull { it.isNotEmpty() && !it.startsWith("-") }?.let { return it }
    val result = SimpleShell.execute(AdbCommandRewriter.rewrite("adb devices", ADB_PATH, serial = null))
    val stdout = result.getOrNull()?.stdout ?: run {
        println("[diag] adb devices 执行失败（区别于无设备在线）: ${result.exceptionOrNull()?.message}")
        return null
    }
    return stdout.lineSequence()
        .filter { "\tdevice" in it }
        .map { it.substringBefore('\t').trim() }
        .firstOrNull()
}

/** 诊断主流程（组合内 Main 协程）：透底探针 → 稳定期 → 双帧捕获 → 像素分析 → 判定 */
private suspend fun diagFlow(): DiagOutcome {
    val engine = ScreencastRuntime.engine
    try {
        // 宿主主窗基线（开独立窗前——顶栏带均亮判绘制在铺与否；壁纸 ≈223 vs 深底内容 ≈20-60）
        val host = awaitHostWindow()
        if (host == null) {
            println("[diag] 宿主主窗轮询超时未定位")
        } else {
            println("[diag] 宿主主窗已定位: '${host.title}' bounds=${host.bounds}")
            // 产品流对位：主窗在开投屏时是聚焦的（用户刚点完按钮）——复刻聚焦前置
            host.requestFocus()
            println("[diag] 宿主主窗聚焦请求已发（focused=${host.isFocused} active=${host.isActive}）")
            delay(HOST_PAINT_SETTLE_MS)
            captureStrip(host, "宿主窗·开投屏前")
            // 机制判别：宿主聚焦后先创建最小克隆窗（同受 lastActiveGC 继承），探其
            // 存活——判「32 位 GC 继承即死」vs「引擎视频才死」（最小窗即死 = 前者）
            CloneVisible.value = true
            delay(HOST_PAINT_SETTLE_MS)
            java.awt.Window.getWindows()
                .filterIsInstance<java.awt.Frame>()
                .firstOrNull { it.isShowing && it.title.contains("克隆判别窗") }
                ?.let { clone ->
                    println("[diag] 克隆窗 GC=${clone.graphicsConfiguration}")
                    captureStrip(clone, "克隆窗·聚焦后创建")
                } ?: println("[diag] 克隆窗未定位")
        }

        // 真产品独立窗开窗（控制器公开入口；visible=false 起步下与播种 visible=true 等价）
        ScreencastDetachedWindowController.openOrFocus()
        val frame = awaitDetachedWindow()
            ?: return DiagOutcome(pass = false, summary = "等待独立窗挂载超时——真产品独立窗未出现")
        println("[diag] 独立窗已挂载: '${frame.title}' bounds=${frame.bounds}")
        delay(HOST_PAINT_SETTLE_MS)
        captureStrip(frame, "独立窗·开窗后")

        // 宿主主窗复探 + 双窗 GC 取证（GC visual 即透底候选判定锚：vis=0x72 = 32 位 ARGB）
        if (host != null) {
            println("[diag] 宿主主窗焦点态: focused=${host.isFocused} active=${host.isActive}")
            println("[diag] 独立窗 GC=${frame.graphicsConfiguration}")
            println("[diag] 宿主窗 GC=${host.graphicsConfiguration}")
            captureStrip(host, "宿主窗·开投屏后")
        }

        delay(SETTLE_MS)
        val shotA = robotCapture(frame)
            ?: return DiagOutcome(pass = false, summary = "Robot 像素捕获失败（帧 A）——环境不支持自捕本进程窗（headless/无显示）")
        // 双帧 diff 采样：5 秒间隔一次为 PASS 路径；间隔内无变化像素时最多加采
        // （scrcpy 变化帧语义——设备画面完全静止则不产出帧，单窗采样对静止画面
        // 是盲区，加采只扩观察窗，不改判据）
        var shotB: BufferedImage? = null
        var diffAttempts = 0
        var diffPixels = 0
        while (diffAttempts < MAX_DIFF_ATTEMPTS) {
            diffAttempts++
            delay(DIFF_GAP_MS)
            val candidate = robotCapture(frame)
                ?: return DiagOutcome(pass = false, summary = "Robot 像素捕获失败（帧 B 第 $diffAttempts 次采样）——环境不支持自捕本进程窗（headless/无显示）")
            if (candidate.width != shotA.width || candidate.height != shotA.height) {
                return DiagOutcome(pass = false, summary = "双帧几何漂移（${shotA.width}x${shotA.height} → ${candidate.width}x${candidate.height}）——窗口在诊断中被移动/缩放")
            }
            shotB = candidate
            diffPixels = countDiffPixels(shotA, shotB)
            if (diffPixels >= MIN_DIFF_PIXELS) break
            println("[diag] 双帧 diff（第 $diffAttempts 次采样）变化像素 $diffPixels 低于阈值——扩大观察窗重采")
        }
        val evidenceB = shotB ?: return DiagOutcome(pass = false, summary = "双帧采样未执行——诊断流程异常")

        saveEvidence(shotA, evidenceB)
        return analyzeAndJudge(shotA, evidenceB, diffAttempts = diffAttempts, fps = engine.fps.value, frameLive = engine.frame.value != null)
    } finally {
        // 收尾契约（顺序即语义）：先收回独立窗（recall 汇点即停投——窗关即停投），再停
        // 引擎会话（幂等兜底）
        ScreencastDetachedWindowController.recall()
        engine.stop()
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
            engine.status.first { it !is ScreencastStatus.Connecting && it !is ScreencastStatus.Streaming }
        } ?: println("[diag] 会话停止等待超时（收尾仍在途，不阻断判定报告）")
    }
}

/** 宿主主窗定位（标题 = realWindowDiag 宿主主窗；映射中返回 Frame，否则 null） */
private fun findHostWindow(): java.awt.Frame? =
    java.awt.Window.getWindows()
        .filterIsInstance<java.awt.Frame>()
        .firstOrNull { it.isShowing && it.title.contains("宿主主窗") }

/** 轮询等宿主主窗映射（开窗竞态下组合期 isShowing 尚未置真；超时返回 null） */
private suspend fun awaitHostWindow(): java.awt.Frame? {
    val deadline = System.nanoTime() + DETACHED_WINDOW_TIMEOUT_NS
    while (System.nanoTime() < deadline) {
        findHostWindow()?.let { return it }
        delay(DETACHED_WINDOW_POLL_MS)
    }
    return null
}

/**
 * 顶栏带单捕亮度读数（无背板判法：深底内容 ≈20-60 vs 透壁纸 ≈223）。判绘制在铺
 * 与否的主观测面；PNG 随读数落盘供复核。
 */
private fun captureStrip(frame: java.awt.Frame, label: String) {
    runCatching {
        val shot = robotCapture(frame)
            ?: return println("[diag/strip][$label] Robot 捕获失败")
        val stripHeight = minOf(PROBE_STRIP_HEIGHT, shot.height)
        var sum = 0.0
        var n = 0
        for (y in 0 until stripHeight) {
            for (x in 0 until shot.width) {
                sum += luminance(shot.getRGB(x, y))
                n++
            }
        }
        val mean = sum / n
        val verdict = if (mean < STRIP_PAINTED_MAX) "内容在铺" else "🔴 透底（壁纸/后窗可见）"
        runCatching {
            File(EVIDENCE_DIR).mkdirs()
            ImageIO.write(shot, "png", File(EVIDENCE_DIR, "strip-$label.png"))
        }
        println("[diag/strip][$label] 顶栏带均亮度=${"%.1f".format(mean)} → $verdict")
    }.onFailure { println("[diag/strip][$label] 异常: ${it.message}") }
}

/** 轮询真产品独立窗（标题 = 「<设备名> · 投屏」）挂载与映射；超时返回 null */
private suspend fun awaitDetachedWindow(): java.awt.Frame? {
    val deadline = System.nanoTime() + DETACHED_WINDOW_TIMEOUT_NS
    while (System.nanoTime() < deadline) {
        val frame = java.awt.Window.getWindows()
            .filterIsInstance<java.awt.Frame>()
            .firstOrNull { it.isShowing && it.title.endsWith(DETACHED_TITLE_SUFFIX) && it.width > 0 && it.height > 0 }
        if (frame != null) return frame
        delay(DETACHED_WINDOW_POLL_MS)
    }
    return null
}

/**
 * 同客户端自捕窗像素：捕获自己进程的窗口不受合成器策略限制（X11 客户端间 GetImage
 * 同源机制）。返回 null = 环境不支持（headless / Robot 构造或捕获异常），不裸抛——
 * 由调用方落显式 FAIL 报告。
 */
private fun robotCapture(frame: java.awt.Frame): BufferedImage? = runCatching {
    if (java.awt.GraphicsEnvironment.isHeadless()) error("GraphicsEnvironment 为 headless")
    val robot = Robot()
    val bounds = frame.bounds
    val insets = frame.insets
    // 内容区 = 窗 bounds 剥系统装饰（标题栏）；insets 缺席时退化为全窗
    val content = Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        (bounds.width - insets.left - insets.right).coerceAtLeast(1),
        (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
    robot.createScreenCapture(content)
}.onFailure { println("[diag] Robot 捕获失败: ${it.message}") }.getOrNull()

/** 双帧 PNG 落盘（证据面；写盘失败不阻断判据，如实打印） */
private fun saveEvidence(shotA: BufferedImage, shotB: BufferedImage) {
    val outDir = File(EVIDENCE_DIR)
    runCatching {
        outDir.mkdirs()
        ImageIO.write(shotA, "png", File(outDir, "frame-A.png"))
        ImageIO.write(shotB, "png", File(outDir, "frame-B.png"))
        println("[diag] 双帧取证 PNG: ${File(outDir, "frame-A.png").absolutePath} / ${File(outDir, "frame-B.png").absolutePath}")
    }.onFailure { println("[diag] PNG 取证落盘失败（不阻断判据）: ${it.message}") }
}

/**
 * 像素分析与判定：帧区行带结构 + 方差块 + 双帧 diff（diff 已由调用方采样算得，
 * 此处只算 bbox 展示面），双判据合取定 PASS/FAIL。
 */
private fun analyzeAndJudge(
    shotA: BufferedImage,
    shotB: BufferedImage,
    diffAttempts: Int,
    fps: Int,
    frameLive: Boolean,
): DiagOutcome {
    if (shotA.width != shotB.width || shotA.height != shotB.height) {
        return DiagOutcome(pass = false, summary = "双帧几何漂移（${shotA.width}x${shotA.height} → ${shotB.width}x${shotB.height}）——窗口在诊断中被移动/缩放")
    }
    val width = shotA.width
    val height = shotA.height

    // 帧区行带结构：行亮度方差 → 高方差行的最长连续带即帧区候选
    val rowStdev = DoubleArray(height)
    for (y in 0 until height) {
        var sum = 0.0
        var sumSq = 0.0
        for (x in 0 until width) {
            val lum = luminance(shotA.getRGB(x, y))
            sum += lum
            sumSq += lum * lum
        }
        rowStdev[y] = stdevOf(sum, sumSq, width)
    }
    val bandMask = BooleanArray(height) { rowStdev[it] >= ROW_STDEV_MIN }
    val band = longestBand(bandMask)

    // 方差块：帧区候选内网格分块，高方差内容块计数（平坦黑屏/占位窗在此面露馅）
    var contentBlocks = 0
    var totalBlocks = 0
    if (band != null) {
        val blockW = (width + BLOCK_GRID - 1) / BLOCK_GRID
        val blockH = (band.last - band.first + 1 + BLOCK_GRID - 1) / BLOCK_GRID
        var blockRow = 0
        while (blockRow * blockH <= band.last) {
            var blockCol = 0
            while (blockCol * blockW < width) {
                totalBlocks++
                val x0 = blockCol * blockW
                val x1 = minOf((blockCol + 1) * blockW, width)
                val y0 = band.first + blockRow * blockH
                val y1 = minOf(band.first + (blockRow + 1) * blockH, band.last + 1)
                var sum = 0.0
                var sumSq = 0.0
                var n = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val lum = luminance(shotA.getRGB(x, y))
                        sum += lum
                        sumSq += lum * lum
                        n++
                        x++
                    }
                    y++
                }
                if (n > 0 && stdevOf(sum, sumSq, n) >= BLOCK_STDEV_MIN) contentBlocks++
                blockCol++
            }
            blockRow++
        }
    }

    // 双帧 diff bbox（活视频流判据——UI 骨架静态不贡献 diff，变化像素集中于帧区）
    var diffPixels = 0
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (y in 0 until height) {
        for (x in 0 until width) {
            val a = shotA.getRGB(x, y)
            val b = shotB.getRGB(x, y)
            val delta = pixelDelta(a, b)
            if (delta >= DIFF_PIXEL_ABS_MIN) {
                diffPixels++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    val diffBBox: String? = if (diffPixels > 0) "($minX,$minY,$maxX,$maxY)" else null

    println("[diag] 窗内容区: ${width}x${height}")
    println(
        "[diag] 帧区行带: " + (
            band?.let { "y=[${it.first}, ${it.last}]（${it.last - it.first + 1}px 高，行方差阈值 sd≥$ROW_STDEV_MIN）" }
                ?: "未检出（全窗无高方差行带——画面不可见的直接证据）"
            ),
    )
    println(
        "[diag] 方差块: $contentBlocks/$totalBlocks 个高方差内容块（帧区内 ${BLOCK_GRID}x${BLOCK_GRID} 网格，块方差阈值 sd≥$BLOCK_STDEV_MIN）",
    )
    println("[diag] 双帧 diff（间隔 ${DIFF_GAP_MS / 1000} 秒 × $diffAttempts 次采样）: bbox=$diffBBox 变化像素 $diffPixels 个（阈值 ≥$MIN_DIFF_PIXELS）")
    println("[diag] fps=$fps（记录不进判据）${if (frameLive) "引擎帧在途" else "引擎帧已清空"}")

    val frameVariancePass = band != null &&
        (band.last - band.first + 1) >= FRAME_BAND_MIN_HEIGHT &&
        contentBlocks >= MIN_CONTENT_BLOCKS
    val diffPass = diffPixels >= MIN_DIFF_PIXELS
    val verdict = frameVariancePass && diffPass
    return DiagOutcome(
        pass = verdict,
        summary = "帧区方差块 ${if (frameVariancePass) "达标" else "不达标"} 且 双帧 diff ${if (diffPass) "非空" else "为空"}" +
            "（判据 = 帧区存在高方差内容块 且 双帧 diff bbox 非空）",
    )
}

/** 双帧差异像素计数（采样重试决策用；同尺寸入参契约由调用方保证） */
private fun countDiffPixels(shotA: BufferedImage, shotB: BufferedImage): Int {
    var count = 0
    for (y in 0 until shotA.height) {
        for (x in 0 until shotA.width) {
            if (pixelDelta(shotA.getRGB(x, y), shotB.getRGB(x, y)) >= DIFF_PIXEL_ABS_MIN) count++
        }
    }
    return count
}

/** 双帧单像素差异（RGB 三通道 |Δ| 之和） */
private fun pixelDelta(a: Int, b: Int): Int =
    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
        abs((a and 0xFF) - (b and 0xFF))

/** sRGB → 相对亮度（行带/方差块的统一度量） */
private fun luminance(argb: Int): Double {
    val r = ((argb shr 16) and 0xFF).toDouble()
    val g = ((argb shr 8) and 0xFF).toDouble()
    val b = (argb and 0xFF).toDouble()
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/** 标准差（经累和单遍公式——像素量大，避免双遍扫描） */
private fun stdevOf(sum: Double, sumSq: Double, n: Int): Double {
    val mean = sum / n
    val variance = (sumSq / n) - mean * mean
    return sqrt(variance.coerceAtLeast(0.0))
}

/** true 游程的最长连续段（[first, last] 闭区间；无 true 返回 null） */
private fun longestBand(mask: BooleanArray): IntRange? {
    var bestFirst = -1
    var bestLen = 0
    var curFirst = -1
    for (i in mask.indices) {
        if (mask[i]) {
            if (curFirst < 0) curFirst = i
            val len = i - curFirst + 1
            if (len > bestLen) {
                bestLen = len
                bestFirst = curFirst
            }
        } else {
            curFirst = -1
        }
    }
    return if (bestLen > 0) bestFirst..(bestFirst + bestLen - 1) else null
}

// ── 诊断常量（判据阈值与节拍——改动即改判据契约，须与任务定案口径一致） ────────

/** adb 执行件名（宿主自置 adb 路径走产品设置面，诊断通道恒用裸 adb） */
private const val ADB_PATH = "adb"

/** 串号入参前缀 */
private const val SERIAL_ARG_PREFIX = "--serial="

/** 独立窗标题后缀（真源 = ScreencastDetachedWindowHost 的 "<设备名> · 投屏"） */
private const val DETACHED_TITLE_SUFFIX = "· 投屏"

private const val STREAMING_TIMEOUT_MS = 30_000L
private const val FIRST_FRAME_TIMEOUT_MS = 15_000L
private const val STOP_JOIN_TIMEOUT_MS = 10_000L
private const val DETACHED_WINDOW_TIMEOUT_NS = 20_000_000_000L
private const val DETACHED_WINDOW_POLL_MS = 250L

/** 开窗后帧流渲染稳定窗（秒级——独立窗首组合 + 首帧上屏余量） */
private const val SETTLE_MS = 4_000L

/** 双帧间隔：活视频流 diff 判据的采样窗 */
private const val DIFF_GAP_MS = 5_000L

/** 双帧 diff 采样上限：单窗无变化像素时逐次加采（静止画面盲区的观察窗扩展） */
private const val MAX_DIFF_ATTEMPTS = 3

/** 取证 PNG 落盘目录（JavaExec 工作目录 = 本模块目录） */
private const val EVIDENCE_DIR = "build/realWindowDiag"

/** 行方差阈值：行亮度标准差低于此视为无内容行（UI 空白/纯色） */
private const val ROW_STDEV_MIN = 8.0

/** 帧区行带最低高度（px）：低于此视为未检出有效帧区 */
private const val FRAME_BAND_MIN_HEIGHT = 96

/** 方差块网格边数（帧区候选内等分 n×n 块） */
private const val BLOCK_GRID = 8

/** 块方差阈值：块亮度标准差低于此视为平坦块 */
private const val BLOCK_STDEV_MIN = 6.0

/** 高方差内容块最低计数（n×n 网格内） */
private const val MIN_CONTENT_BLOCKS = 5

/** 双帧单像素差异阈值（RGB 三通道 |Δ| 之和） */
private const val DIFF_PIXEL_ABS_MIN = 30

/** 双帧变化像素最低计数 */
private const val MIN_DIFF_PIXELS = 25

/** 探针骨架带高度（px）：窗口顶栏行采样带，避开帧区 */
private const val PROBE_STRIP_HEIGHT = 48

/** 骨架带「内容在铺」判定阈值：均亮低于此视为深底内容在铺（壁纸 ≈223） */
private const val STRIP_PAINTED_MAX = 90.0

/** 基线探针前宿主窗绘制稳定窗（首帧渲染到位余量） */
private const val HOST_PAINT_SETTLE_MS = 1500L
