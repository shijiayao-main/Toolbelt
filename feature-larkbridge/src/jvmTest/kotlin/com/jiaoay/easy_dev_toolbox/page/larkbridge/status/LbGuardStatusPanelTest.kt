package com.jiaoay.easy_dev_toolbox.page.larkbridge.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LB-08（重设计批 9）守护：驾驶舱状态卡与 hero 去重 + 计数卡分组。
 *
 * 验收面：
 * 1. 运行状态卡不再出现与 hero 重复的「运行」「连接」两行（label = "运行" / "连接"
 *    归一化清零——防回潮）；
 * 2. 计数卡拆三小节 CardHeader（接收/结果/拒绝与丢弃）+ 尾行「日志台行数」；
 * 3. 「存活进程」搬入计数卡「接收」小节（卡面 proposal 明列；搬行后计数卡 12 行——
 *    卡面验收「11 行」系笔误，如实上报）；
 * 4. 12 行 id 全数在文件（零遗漏）；
 * 5. label()/color() internal 共享件零改动（OperationBar 圆点与 hero 仍消费）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LbGuardStatusPanelTest {

    @Test
    fun `runtime card drops hero-duplicate rows and counter card has grouped sections`() {
        val text = source(BRIDGE_STATUS_PANEL)
        // 1. 运行/连接两行清零（StatusRow label 参数位——hero 已承载）
        assertFalse(
            "label = \"运行\"," in text,
            "「运行」行必须删除（LB-08：与 hero 纯重复）",
        )
        assertFalse(
            "label = \"连接\"," in text,
            "「连接」行必须删除（LB-08：与 hero 纯重复）",
        )
        // 2. 三小节 CardHeader 在位
        assertTrue("\"接收\"" in text, "「接收」小节 CardHeader 缺失（LB-08）")
        assertTrue("\"结果\"" in text, "「结果」小节 CardHeader 缺失（LB-08）")
        assertTrue("\"拒绝与丢弃\"" in text, "「拒绝与丢弃」小节 CardHeader 缺失（LB-08）")
        assertTrue("CardHeader(" in text, "小节头必须走 CardHeader 共享件（「任务行为与限额」同款先例）")
        // 3. 存活进程搬入计数卡「接收」小节（切片定位：CardHeader 接收 → 存活进程 在其后、结果节之前）
        val receiveIdx = text.indexOf("title = \"接收\"")
        val resultIdx = text.indexOf("title = \"结果\"")
        val liveIdx = text.indexOf("label = \"存活进程\"")
        assertTrue(
            receiveIdx in 0 until liveIdx && liveIdx < resultIdx,
            "「存活进程」必须搬入计数卡「接收」小节（接收节与结果节之间——LB-08 卡面 proposal）",
        )
        // 4. 12 行 id 全数在文件（原 11 + 搬入的存活进程）
        val rows = listOf(
            "接收消息", "已提交任务", "完成", "失败", "访问拒绝", "未匹配消息",
            "策略拒绝", "去重丢弃", "队列超时", "丢弃合计", "日志台行数", "存活进程",
        )
        rows.forEach { row ->
            assertTrue("label = \"$row\"" in text, "计数行「$row」缺失（LB-08：12 行全数保留零遗漏）")
        }
        // 5. label()/color() internal 共享件零改动（OperationBar 圆点与 hero 仍消费）
        assertTrue("internal fun BridgeConnectionStatus.label()" in text, "label() internal 共享件必须保留（OperationBar/hero 消费）")
        assertTrue("internal fun BridgeConnectionStatus.color()" in text, "color() internal 共享件必须保留（OperationBar/hero 消费）")
        // 增量语义保留：连接自/确认链路/AppId/最近异常/停止原因/Blocked 容器条
        assertTrue("连接自" in text && "确认链路" in text && "最近异常" in text && "停止原因" in text, "运行状态卡增量行必须保留")
        assertTrue("StatusBanner(" in text, "Blocked 容器条必须保留")
    }

    /** 定位源文件（PillLabelGuardTest 同款：user.dir 向上找 settings.gradle.kts 定项目根）。 */
    private fun source(relPath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, relPath)
                assertTrue(file.isFile, "$relPath 不存在——文件被移动须同步更新本测试路径")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val BRIDGE_STATUS_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/BridgeStatusPanel.kt"
    }
}
