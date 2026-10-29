package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PillLabel 胶囊徽标共享件验收断言（DS-11 · 重设计批 3 · 2026-10-01）：
 *
 * 本件是桥接页散写 pill（StatusBadge/RestartPill 等私有件 + BotCapabilityCard/CockpitPanel
 * 手写 pill）的共享化收编——配方源 = ConfigFieldRenderer.StatusBadge（已删私有件）逐行同构。
 * 验收面三钉：
 * 1. **编译可达**：本文件引用 [PillLabel] 可组合项——签名不兼容改写或文件删除会直接
 *    编译失败；icon 参数与 style 参数（labelSmall 默认）同样以引用方式钉进编译面；
 * 2. **值断言**：shape = AppShapes.capsule（percent 全圆）；内边距 space8 × space2、
 *    icon 尺寸 iconSmall——与原 StatusBadge 配方逐值相等（像素级等价的 token 侧锚）；
 * 3. **配方源断言**：PillLabel.kt 必须钉住「background(container, capsule) + padding +
 *    iconSlot + Text」结构——防后续批接线时配方被顺手改动；
 * 4. **反向**：PillLabel 不得自带交互态（DL-M11 微洗底属交互件管辖面）——
 *    clickable/hoverWash/hotWash 字样不得出现在代码区。
 *
 * （消费方源码锚断言在 feature-larkbridge 侧 PillLabelGuardTest——
 * 桥接域 5 处收编 + warningContainer.copy/bgSegTrack 清零 grep 均归那侧。）
 */
class PillLabelTest {

    /** 编译可达探针：签名改写/文件删除即 :ui:jvmTest 编译失败（不执行，只编译） */
    @Composable
    private fun compileReachabilityProbe() {
        PillLabel(
            text = "PillLabel 编译可达探针",
            container = androidx.compose.ui.graphics.Color.Unspecified,
            contentColor = androidx.compose.ui.graphics.Color.Unspecified,
            icon = "check_circle",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }

    @Test
    fun `pill recipe tokens equal status badge legacy values`() {
        // 配方源 = StatusBadge（逐行同构）：capsule 全圆 + space8×space2 + iconSmall
        assertEquals(RoundedCornerShape(50), AppShapes.capsule, "PillLabel shape 档必须 AppShapes.capsule（percent 全圆）")
        assertEquals(8.dp, Dimens.space8, "PillLabel 水平内边距必须 space8（原 StatusBadge 配方值）")
        assertEquals(2.dp, Dimens.space2, "PillLabel 垂直内边距必须 space2（原 StatusBadge 配方值）")
        assertEquals(16.dp, Dimens.iconSmall, "PillLabel icon 槽尺寸必须 iconSmall（原 StatusBadge 配方值）")
    }

    @Test
    fun `pill label recipe stays pinned to capsule background recipe`() {
        val source = pillLabelSource()
        listOf(
            ".background(container, AppShapes.capsule)" to "底色配方必须 background(container, capsule)——container 槽由调用方直出（DL-M10）",
            ".padding(horizontal = Dimens.space8, vertical = Dimens.space2)" to "内边距必须 space8 × space2（配方源 StatusBadge 同值）",
            "size = Dimens.iconSmall" to "icon 槽尺寸必须 iconSmall",
            "style: TextStyle = MaterialTheme.typography.labelSmall" to "文字档默认必须 labelSmall（卡面配方）",
            "tint = contentColor" to "icon tint 必须 contentColor 直通（icon 与文字色同源）",
            "color = contentColor" to "文字色必须 contentColor 直通",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "PillLabel.kt 丢失配方要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `pill label stays non interactive`() {
        // 只查代码区（KDoc 提及 DL-M11 属纪律说明，剥注释防自咬）
        val source = stripComments(pillLabelSource())
        listOf(
            "clickable" to "PillLabel 是静态徽标非交互件（DL-M08：交互微洗底属交互件管辖面）",
            "hoverWash" to "交互微洗底恒交互件自持，本件不持交互态",
            "hotWash" to "同上（DL-M11 三档属交互件）",
            "ElevatedCard" to "PillLabel 必须扁平配方（ELEVATED_CARD 守护红线）",
        ).forEach { (needle, why) ->
            assertFalse(
                needle in source,
                "PillLabel.kt 出现「$needle」——$why",
            )
        }
    }

    /** 剥 KDoc/行注释/块注释（字符串内容保留——本断言面只关心 API 调用） */
    private fun stripComments(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') append('\n')
                        i++
                    }
                    i += 2
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    /** 定位 PillLabel.kt 源文件（自测试工作目录向上找 settings.gradle.kts 定项目根） */
    private fun pillLabelSource(): String {
        val root = locateProjectRoot()
        val file = File(root, PILL_LABEL_PATH)
        assertTrue(file.isFile, "$PILL_LABEL_PATH 不存在——DS-11 新件被移动须同步更新本测试路径")
        return file.readText()
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:ui:jvmTest 工作目录异常")
    }

    private companion object {
        const val PILL_LABEL_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/PillLabel.kt"
    }
}
