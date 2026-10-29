package com.jiaoay.easy_dev_toolbox.page.quest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 抢焦装配守护（源码锚——PathConfirmCard 的抢焦装配上提 :ui 共享件 GateConfirmOverlay
 * 后，quest 侧只守装配点接线）：PathConfirmCard 键盘裁决（Enter 允许/Esc 拒绝）依赖
 * 卡体 focusable——焦点不转移则 onPreviewKeyEvent 永不触达（Enter 被 ChatComposer 吞、
 * Esc 死键）。组合期主动 requestFocus 是共享件契约（工作台/quest 两消费面同一配方），
 * quest 侧装配必须传 focusRequester。
 */
class FlowPageFocusWiringGuardTest {

    private fun repoSource(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$relativePath（重命名/移动后请同步本测试路径）")
        return file.readText()
    }

    private fun flowPageSource(): String =
        repoSource("feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowPage.kt")

    private fun gateOverlaySource(): String =
        repoSource("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/GateConfirmOverlay.kt")

    @Test
    fun `path confirm card wiring carries focus requester and eager requestFocus`() {
        val source = flowPageSource()
        // 配方锚：FocusRequester remember + 传参给共享 overlay（requestFocus 由共享件内聚）
        assertTrue(
            "FocusRequester()" in source,
            "缺 FocusRequester——PathConfirmCard 不挂 focusable，键盘裁决结构性失效",
        )
        assertTrue(
            "focusRequester = gateFocus" in source,
            "GateConfirmOverlay 装配点未传 focusRequester（不传不挂 focusable，键盘裁决结构性失效）",
        )
        // 组合期主动 requestFocus——上提后为共享件契约面
        val overlay = gateOverlaySource()
        assertTrue(
            "runCatching { focusRequester.requestFocus() }" in overlay,
            "共享件缺组合期主动 requestFocus（卡出现即抢焦——不点击直接裁决；runCatching 防组合期挂载未完成抛错）",
        )
    }

    @Test
    fun `stale no-effect comment does not return`() {
        val source = flowPageSource()
        assertTrue(
            "裁决不受影响" !in source,
            "「Enter/Esc 裁决不受影响」假承诺注释回潮——裁决依赖抢焦装配（卡级 onPreviewKeyEvent 焦点在卡外时永不触达）",
        )
    }
}
