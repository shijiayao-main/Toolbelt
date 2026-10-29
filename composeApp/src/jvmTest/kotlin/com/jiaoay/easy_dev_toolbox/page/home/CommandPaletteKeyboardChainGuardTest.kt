package com.jiaoay.easy_dev_toolbox.page.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * H1/H2 palette 键盘链与危险闸守护（源码锚）：
 *
 * - H1 键盘链三件套：进表单态焦点转移至首参数框 / 表单态 Enter 根层收口 /
 *   就绪态可见执行钮；打字毁表单收口（编辑查询不退出表单）；
 * - H2 危险动作 inline 二次确认：isDangerous 首激活置确认态、同条目再激活才执行、
 *   危险确认条在位；CommandActions.isDangerous 契约注释与实现对齐。
 */
class CommandPaletteKeyboardChainGuardTest {

    private fun sourceOf(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val root = dir ?: fail("项目根定位失败")
        val file = File(root, relative)
        assertTrue(file.isFile, "源码锚文件缺失：$relative（重命名/移动后请同步本测试路径）")
        return file.readText()
    }

    private fun paletteSource(): String =
        sourceOf("composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPalette.kt")

    private fun paramFormSource(): String =
        sourceOf("composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteParamForm.kt")

    @Test
    fun `form state enter is collected at root intercept layer`() {
        val source = paletteSource()
        assertTrue(
            "表单态 Enter 上收根 Box" in source,
            "缺根层表单态 Enter 收口——焦点在参数框时搜索框 handler 不可达，Enter 结构性无操作",
        )
    }

    @Test
    fun `focus transfers to first param field on entering form`() {
        val palette = paletteSource()
        val form = paramFormSource()
        assertTrue(
            "paramFocusRequester.requestFocus()" in palette,
            "缺进表单态抢焦（搜索框→首参数框）——打字毁表单 + Enter 不可达的根源",
        )
        assertTrue(
            "focusRequester" in form && "params.firstOrNull()" in form,
            "PaletteParamForm 未给首参数框挂抢焦链",
        )
    }

    @Test
    fun `query editing no longer destroys param form`() {
        val source = paletteSource()
        val anchor = "编辑查询不再退出表单态"
        assertTrue(anchor in source, "打字毁表单收口注释锚丢失")
        // 负向锚：onValueChange 块内不得再置 activeParam = null（打字毁表单回潮即红）
        val start = source.indexOf(anchor)
        val window = source.substring(start, minOf(start + 400, source.length))
        assertFalse(
            "activeParam = null" in window,
            "查询编辑清空表单态回潮（打字毁表单）",
        )
    }

    @Test
    fun `ready param form shows visible execute button`() {
        val source = paletteSource()
        assertTrue(
            "label = \"执行\"" in source,
            "就绪态缺可见执行钮（鼠标零 affordance——原 else-if 让提示就绪瞬间消失）",
        )
    }

    @Test
    fun `dangerous action requires inline second confirm`() {
        val source = paletteSource()
        assertTrue(
            "dangerousPendingId" in source && "dangerousPendingLabel" in source,
            "缺危险动作二次确认态（isDangerous 直执行回潮）",
        )
        assertTrue(
            "再次 Enter 或点击该行执行" in source,
            "缺危险确认条文案（inline 二次确认的可视面）",
        )
        assertTrue(
            "dangerousPendingId = null" in source && source.contains("dangerousPendingId != "),
            "危险闸缺双臂（首触发置位 + 同条目再触发放行）",
        )
    }

    @Test
    fun `escape steps cancel confirm before exiting form and panel`() {
        val source = paletteSource()
        assertTrue(
            "dangerousPendingId != null ->" in source,
            "Esc 三步序缺确认取消步（悬挂确认不可由 Esc 直接穿出面板）",
        )
    }

    @Test
    fun `escape exiting form invalidates in-flight exec ticket`() {
        val source = paletteSource()
        // Esc 退表单/HUD 分支必须与查询编辑同款收口：递增 execTicket 作废在途
        // 门票 + executing 复位。缺收口 = 迟到结果把已退出的列表态突切回 HUD 态
        // （executing 悬挂期间列表态 Enter 全程死键）。
        val escBranch = source.substringAfter("表单/HUD 态：Esc 先退回列表")
            .substringBefore("else ->")
        assertTrue(
            "execTicket += 1" in escBranch && "executing = false" in escBranch,
            "Esc 退表单分支缺在途门票作废收口（execTicket 递增 + executing 复位）" +
                "——迟到结果抢态与 Enter 死键窗回归（U 系列侦察清单 #1 真缺陷）",
        )
        assertTrue(
            "StatusSignals.show" in source.substringAfter("if (ticket != execTicket)"),
            "作废的迟到结果须经全局状态条告知（作废的是回写面板态，不是告知结果）",
        )
    }

    @Test
    fun `command actions contract comment matches confirm behavior`() {
        val source = sourceOf("core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/CommandActions.kt")
        assertTrue(
            "二次确认" in source,
            "isDangerous 契约注释仍写「视觉标注」——与 inline 二次确认实现矛盾（注释宪法）",
        )
    }
}
