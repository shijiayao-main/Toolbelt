package com.jiaoay.easy_dev_toolbox.page.logkit

import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具链「送往下一步」衔接契约断言（产物 → 下一级带参命令 + 产物路径预填）。
 *
 * 验收面：
 * 1. 解密产物 → 目标 = LogKitContributions 已注册的 Tag 过滤带参命令，
 *    且产物路径落点参数（file_path）在该命令参数表内为 FILE 型——id 字符串锚
 *    与命令参数锚双断言防漂移（改名/退役即红）；
 * 2. logcat 抓取产物 → 同款双断言（映射表新增行不得脱离注册表单测锚）；
 * 2b. 查看器拒收的加密件 → 同款双断言（Failed 态「送 XLog 解密」衔接目标）；
 * 2c. 解压产物 → 同款双断言（目录产物两分支契约的「单文件直投」分支消费面——
 *    表行承载目标与落点，多文件分支经跳页槽不占 palette 参数面）；
 * 2d. 设备文件拉取产物 → 同款双断言（按后缀分立：.txt/无后缀 → Tag 过滤、
 *    .xlog/.alog → XLog 解密——同一拉取面两种产物语义各占一行）；
 * 3. 未知产物类型不映射：分割（管线终点）/过滤（主去向=深度分析既有）/
 *    未登记串一律 null——null 即条目不出现（来源页 null 透传 = 菜单槽缺席）；
 * 4. offer → consumeSeed 载荷语义：file_path = 产物路径、读即清一次性；
 *    宿主未接线时 offer 拒绝且不残留载荷（衔接条目渲染开关 = isWired）；
 * 5. offer 扩参（extraArgs）：额外预填槽与产物路径并进 seed.args 同面消费，
 *    读即清与未接线拒绝语义同 4（用既有管线目标测，不耦合横向目标）；
 * 6. 面板已开新 seed 到达（槽层语义）：pendingSeed 流即时承载 + 订阅面消费读即
 *    清——面板保持打开时新 offer 不滞留迟发。
 */
class TaskPipelineHandoffTest {

    @AfterTest
    fun tearDown() {
        TaskPipelineHandoff.clearForTest()
    }

    @Test
    fun `xlog decrypt product maps to registered tag filter command`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)
        assertTrue(target != null, "解密产物须有下一级目标（管线主干：解密 → Tag 过滤）")
        target!!
        assertEquals("送往 Tag 过滤", target.label, "条目文案 = 动作动词 + 目标工具名")
        assertEquals("logkit.tag-filter", target.commandId)

        // 目标命令必须在 LogKitContributions 注册表内（id 漂移即断链——palette 解析落空）
        val registered = LogKitContributions.registrations
            .flatMap { it.paramCommands }
            .firstOrNull { it.id == target.commandId }
        assertTrue(
            registered != null,
            "映射目标命令 id=${target.commandId} 须在 LogKitContributions 注册表内（改名后同步映射表）",
        )
        registered!!
        // 产物路径落点参数存在且为 FILE 型（衔接预填的表单校验面）
        assertTrue(
            registered.params.any { it.id == target.fileParamId && it.type == ParamType.FILE },
            "目标命令须有 FILE 型参数 ${target.fileParamId}（产物路径落点）",
        )
    }

    @Test
    fun `logcat capture product maps to registered tag filter command`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(
            TaskPipelineHandoff.SourcePageId.LOGCAT_CAPTURE,
        )
        assertTrue(target != null, "logcat 抓取产物须有下一级目标（抓取落点即过滤定位）")
        target!!
        assertEquals("logkit.tag-filter", target.commandId)

        // 目标命令必须在 LogKitContributions 注册表内（id 漂移即断链——palette 解析落空）
        val registered = LogKitContributions.registrations
            .flatMap { it.paramCommands }
            .firstOrNull { it.id == target.commandId }
        assertTrue(
            registered != null,
            "映射目标命令 id=${target.commandId} 须在 LogKitContributions 注册表内（改名后同步映射表）",
        )
        registered!!
        assertTrue(
            registered.params.any { it.id == target.fileParamId && it.type == ParamType.FILE },
            "目标命令须有 FILE 型参数 ${target.fileParamId}（产物路径落点）",
        )
    }

    @Test
    fun `viewer encrypted failure maps to registered xlog decrypt command`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(
            TaskPipelineHandoff.SourcePageId.LOG_VIEWER_ENCRYPTED,
        )
        assertTrue(target != null, "查看器拒收的加密件须有下一级目标（回管线主干前半段：解密）")
        target!!
        assertEquals("送 XLog 解密", target.label, "条目文案 = 动作动词 + 目标工具名")
        assertEquals("logkit.xlog-decrypt", target.commandId)

        // 目标命令必须在 LogKitContributions 注册表内（id 漂移即断链——palette 解析落空）
        val registered = LogKitContributions.registrations
            .flatMap { it.paramCommands }
            .firstOrNull { it.id == target.commandId }
        assertTrue(
            registered != null,
            "映射目标命令 id=${target.commandId} 须在 LogKitContributions 注册表内（改名后同步映射表）",
        )
        registered!!
        assertTrue(
            registered.params.any { it.id == target.fileParamId && it.type == ParamType.FILE },
            "目标命令须有 FILE 型参数 ${target.fileParamId}（加密件路径落点）",
        )
    }

    @Test
    fun `archive product maps to registered xlog decrypt command`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.ARCHIVE)
        assertTrue(target != null, "解压产物须有下一级目标（两分支契约：单文件直投 palette / 多文件跳页）")
        target!!
        assertEquals("送 XLog 解密", target.label, "条目文案 = 动作动词 + 目标工具名")
        assertEquals("logkit.xlog-decrypt", target.commandId)

        // 目标命令必须在 LogKitContributions 注册表内（id 漂移即断链——palette 解析落空）
        val registered = LogKitContributions.registrations
            .flatMap { it.paramCommands }
            .firstOrNull { it.id == target.commandId }
        assertTrue(
            registered != null,
            "映射目标命令 id=${target.commandId} 须在 LogKitContributions 注册表内（改名后同步映射表）",
        )
        registered!!
        assertTrue(
            registered.params.any { it.id == target.fileParamId && it.type == ParamType.FILE },
            "目标命令须有 FILE 型参数 ${target.fileParamId}（单文件分支直投产物路径落点）",
        )
    }

    @Test
    fun `device file pull text product maps to registered tag filter command`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(
            TaskPipelineHandoff.SourcePageId.DEVICE_FILE_PULL,
        )
        assertTrue(target != null, "设备文件拉取的文本产物（.txt/无后缀）须有下一级目标（拉到本地即过滤页输入类型）")
        target!!
        assertEquals("送往 Tag 过滤", target.label, "条目文案 = 动作动词 + 目标工具名")
        assertEquals("logkit.tag-filter", target.commandId)

        // 目标命令必须在 LogKitContributions 注册表内（id 漂移即断链——palette 解析落空）
        val registered = LogKitContributions.registrations
            .flatMap { it.paramCommands }
            .firstOrNull { it.id == target.commandId }
        assertTrue(
            registered != null,
            "映射目标命令 id=${target.commandId} 须在 LogKitContributions 注册表内（改名后同步映射表）",
        )
        registered!!
        assertTrue(
            registered.params.any { it.id == target.fileParamId && it.type == ParamType.FILE },
            "目标命令须有 FILE 型参数 ${target.fileParamId}（拉取产物路径落点）",
        )
    }

    @Test
    fun `device file pull encrypted product maps to registered xlog decrypt command`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(
            TaskPipelineHandoff.SourcePageId.DEVICE_FILE_PULL_ENCRYPTED,
        )
        assertTrue(target != null, "设备文件拉取的加密件（.xlog/.alog）须有下一级目标（拉到本地即解密页输入类型）")
        target!!
        assertEquals("送 XLog 解密", target.label, "条目文案 = 动作动词 + 目标工具名")
        assertEquals("logkit.xlog-decrypt", target.commandId)

        // 目标命令必须在 LogKitContributions 注册表内（id 漂移即断链——palette 解析落空）
        val registered = LogKitContributions.registrations
            .flatMap { it.paramCommands }
            .firstOrNull { it.id == target.commandId }
        assertTrue(
            registered != null,
            "映射目标命令 id=${target.commandId} 须在 LogKitContributions 注册表内（改名后同步映射表）",
        )
        registered!!
        assertTrue(
            registered.params.any { it.id == target.fileParamId && it.type == ParamType.FILE },
            "目标命令须有 FILE 型参数 ${target.fileParamId}（加密件路径落点）",
        )
    }

    @Test
    fun `logcat handoff without host wiring is rejected and leaves no seed`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(
            TaskPipelineHandoff.SourcePageId.LOGCAT_CAPTURE,
        )!!
        TaskPipelineHandoff.clearForTest()
        assertFalse(TaskPipelineHandoff.isWired, "未接线态衔接条目渲染开关须为 false")
        assertFalse(TaskPipelineHandoff.offer(target, "/tmp/logcat-demo.txt"), "未接线 offer 须拒绝")
        assertNull(TaskPipelineHandoff.consumeSeed(), "拒绝投递不得残留载荷")
    }

    @Test
    fun `offer seeds param form with product path as file_path`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)!!
        TaskPipelineHandoff.wire { }
        val productPath = "/tmp/demo.alog.txt"
        assertTrue(TaskPipelineHandoff.offer(target, productPath), "已接线 offer 须成功")

        val seed = TaskPipelineHandoff.consumeSeed()
        assertTrue(seed != null, "offer 后 seed 可取")
        seed!!
        assertEquals(target.commandId, seed.commandId)
        assertEquals(
            productPath,
            seed.args[target.fileParamId],
            "产物路径须落在目标命令的文件参数 ${target.fileParamId} 上",
        )
        // 读即清：一次性载荷（二次取空——不重复进参数直填态）
        assertNull(TaskPipelineHandoff.consumeSeed(), "seed 读即清（一次性载荷）")
    }

    @Test
    fun `new seed offered while palette stays open is carried by the flow and consumed read-once`() {
        // 面板已开场景的槽层语义（palette 消费面订阅 pendingSeed 流的契约前提）：
        // 宿主 open 同值写回 paletteVisible、打开瞬间初值承载不重跑，新 seed 必须
        // 经槽流即时可见（订阅面非空即消费）且读即清——滞留即下次打开迟发旧参数
        // 表单（旧产物路径预填）。
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)!!
        TaskPipelineHandoff.wire { }
        val firstPath = "/tmp/demo.alog.txt"
        assertTrue(TaskPipelineHandoff.offer(target, firstPath), "已接线 offer 须成功")

        // 打开瞬间初值承载消费首枚 seed（关→开常规路径——面板打开前的既有行为）
        val initial = TaskPipelineHandoff.consumeSeed()
        assertEquals(
            firstPath,
            initial?.args?.get(target.fileParamId),
            "打开瞬间承载须取到首枚 seed（产物路径落点不变）",
        )
        assertNull(TaskPipelineHandoff.pendingSeed.value, "初值承载后槽须清空（流初值 null）")

        // 面板保持打开（无关→开重组、初值承载不再跑）：新 offer 的 seed 须经槽流
        // 即时承载——订阅面 collect 非空即收到，不等下次打开
        val secondPath = "/tmp/another.alog.txt"
        assertTrue(TaskPipelineHandoff.offer(target, secondPath), "面板已开时 offer 仍须成功")
        assertEquals(
            secondPath,
            TaskPipelineHandoff.pendingSeed.value?.args?.get(target.fileParamId),
            "面板已开时新 seed 须经槽流即时可见（订阅面消费前提——不可只靠打开瞬间承载）",
        )

        // 订阅面消费 = 读即清：槽被取走后流值归 null，下次打开初值承载不得迟发旧参数
        val consumed = TaskPipelineHandoff.consumeSeed()
        assertEquals(
            secondPath,
            consumed?.args?.get(target.fileParamId),
            "订阅面消费须取到面板已开期间到达的新 seed（非旧首枚载荷）",
        )
        assertNull(TaskPipelineHandoff.pendingSeed.value, "订阅面消费后槽须清空（无迟发残留）")
        assertNull(TaskPipelineHandoff.consumeSeed(), "seed 读即清（一次性载荷——二次取空）")
    }

    @Test
    fun `offer without host wiring is rejected and leaves no seed`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)!!
        TaskPipelineHandoff.clearForTest()
        assertFalse(TaskPipelineHandoff.offer(target, "/tmp/demo.txt"), "未接线 offer 须拒绝")
        assertNull(TaskPipelineHandoff.consumeSeed(), "拒绝投递不得残留载荷")
        assertFalse(TaskPipelineHandoff.isWired, "未接线态 isWired 须为 false")
    }

    @Test
    fun `offer with extra args merges them beside product path into seed args`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)!!
        TaskPipelineHandoff.wire { }
        val productPath = "/tmp/demo.alog.txt"
        val extraArgs = mapOf("start_time" to "05-09 10:00:00.000")
        assertTrue(TaskPipelineHandoff.offer(target, productPath, extraArgs), "已接线 offer（扩参）须成功")

        val seed = TaskPipelineHandoff.consumeSeed()
        assertTrue(seed != null, "offer（扩参）后 seed 可取")
        seed!!
        assertEquals(target.commandId, seed.commandId)
        assertEquals(
            productPath,
            seed.args[target.fileParamId],
            "产物路径仍落目标命令的文件参数 ${target.fileParamId} 上",
        )
        assertEquals(
            "05-09 10:00:00.000",
            seed.args["start_time"],
            "扩参须进 seed.args 与产物路径同面消费（palette 直填态一次取齐）",
        )
        assertEquals(
            2,
            seed.args.size,
            "seed.args = 单路径 map + extraArgs（扩参槽不得挤掉产物路径槽）",
        )
        // 读即清：一次性载荷（二次取空——不重复进参数直填态）
        assertNull(TaskPipelineHandoff.consumeSeed(), "seed 读即清（一次性载荷）")
    }

    @Test
    fun `offer with extra args without wiring is rejected and leaves no seed`() {
        val target = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)!!
        TaskPipelineHandoff.clearForTest()
        assertFalse(
            TaskPipelineHandoff.offer(target, "/tmp/demo.txt", mapOf("start_time" to "05-09 10:00:00.000")),
            "未接线 offer（扩参）须拒绝",
        )
        assertNull(TaskPipelineHandoff.consumeSeed(), "拒绝投递不得残留载荷")
    }

    @Test
    fun `unknown product sources have no next tool target`() {
        assertNull(TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.FILE_SPLITTER))
        assertNull(TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.LOG_TAG_FILTER))
        assertNull(TaskPipelineHandoff.nextToolTargetOf("NoSuchTool"))
    }
}
