package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * palette「查看日志文件」命令的宿主挂载接线源码锚——PageHome.kt 根叠层
 * （LogViewerRequest 槽 collect → LogViewerDialog 挂载）。
 *
 * 失效形态：接线被删后命令**静默 no-op**——performArgs 返回成功文案、HUD 正常，
 * 但查看器永不出现（槽无人 collect；编译期不可见、UI 层不可测，
 * ReportGoWorkbenchWiringGuardTest 同款「漏 wire = 静默 no-op」失效形态），必须有源码锚。
 *
 * 钉死锚（PageHome.kt）：
 * 1. `LogViewerRequest.pending.collectAsState()`（槽 collect——各生产面产出请求的唯一消费面）；
 * 2. `LogViewerDialog(`（查看器 Dialog 挂载形态——行号/时间戳/级别跳转 + 文本
 *    搜索 + 选中复制 + 送 Tag 过滤/深度分析衔接的查看器共享消费面）；
 * 3. `LogViewerRequest.consume()`（onDismiss 清槽——关闭后命令可再次打开）；
 * 4. `LogViewerRequest.markHostMounted()`（挂载信号置位——生产面据此降级，漏置 =
 *    各生产动作恒走降级分支，必须钉死）；
 * 5. `bookmarkRunId = request.runId`（run 级书签锚透传——漏传 = run 产物打开后退回
 *    会话级书签，run 级书签持久化整链静默失效）。
 *
 * 源码锚断言形态（ReportGoWorkbenchWiringGuardTest 同族——接线面经源码切片钉死，
 * 槽本体行为在 ParamCommandCatalogTest 背书）。
 */
class LogViewerPaletteWiringGuardTest {

    @Test
    fun `page home collects viewer request slot and mounts log viewer dialog`() {
        val source = pageHomeSource()
        assertTrue(
            "LogViewerRequest.pending.collectAsState()" in source,
            "PageHome 必须 collect 查看器请求槽（漏接 = palette「查看日志文件」静默 no-op——" +
                "命令执行成功但查看器永不出现）",
        )
        assertTrue(
            "LogViewerDialog(" in source,
            "PageHome 必须挂载 LogViewerDialog（共享消费面——任务卡「查看」/logcat 回执行同件）",
        )
        assertTrue(
            "LogViewerRequest.consume()" in source,
            "Dialog onDismiss 必须 consume 清槽（漏清 = 关闭后再次执行命令槽值未变、Dialog 不复现）",
        )
        assertTrue(
            "LogViewerRequest.markHostMounted()" in source,
            "宿主必须置位挂载信号（漏置 = 生产面 hostMounted 恒 false、查看动作恒走降级分支）",
        )
        assertTrue(
            "initialTimestamp = request.initialTimestamp" in source,
            "宿主必须透传请求的 initialTimestamp（漏传 = 工作台超窗点击的带时间戳直达请求" +
                "打开后不定位，用户仍要手动输一遍时间戳）",
        )
        assertTrue(
            "bookmarkRunId = request.runId" in source,
            "宿主必须透传请求的 bookmarkRunId（漏传 = run 级书签持久化整链静默失效——" +
                "工作台 run 产物打开后退回会话级书签，关闭即丢）",
        )
    }

    private fun pageHomeSource(): String {
        val file = File(
            locateProjectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHome.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
