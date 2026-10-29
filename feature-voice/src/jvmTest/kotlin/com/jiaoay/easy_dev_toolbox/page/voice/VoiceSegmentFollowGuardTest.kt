package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 语音分段贴底跟随真实现守护：
 *
 * 注释归真契约——KDoc/行注宣称「分段结果区尾部跟随」则实现必须有跟随逻辑
 * （不许注释与实现脱节）。断言面：
 * 1. LazyColumn 带 state（rememberLazyListState）；
 * 2. BottomFollowScrollEffect 接线（ChatMessageList 同款配方——itemCount =
 *    segments.size，分段只增不减）；
 * 3. 零动画铁律：BottomFollowScrollEffect 内部 scrollToItem 非动画（组件侧契约，
 *    此处钉消费面接线存在）。
 */
class VoiceSegmentFollowGuardTest {

    @Test
    fun `分段列表贴底跟随接线完整`() {
        val source = voicePageSource()
        listOf(
            "val segmentsState = rememberLazyListState()" to "分段 LazyColumn 必须持 state",
            "state = segmentsState" to "LazyColumn 必须消费 segmentsState",
            "BottomFollowScrollEffect(" to "贴底跟随 effect 必须接线（ChatMessageList 同款配方）",
            "itemCount = segments.size" to "跟随触发源 = 分段数（分段只增不减）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "VoicePage.kt 丢失跟随要素「$needle」——$why")
        }
        // effect 必须在 LazyColumn 之外（组合面兄弟——ChatMessageList:44 先例）：
        // 接线行须在 itemsIndexed 所在块之后出现（同函数区内 LazyColumn 起点先于 effect）
        val lazyStart = source.indexOf("androidx.compose.foundation.lazy.LazyColumn(")
        val effectAt = source.indexOf("BottomFollowScrollEffect(")
        assertTrue(lazyStart in 0 until effectAt, "LazyColumn 应先于 effect 接线（组合序）")
    }

    // ── 源码锚 ──

    private fun voicePageSource(): String {
        // 向上找 settings.gradle.kts 定项目根（GuardTestSupport/VoiceReviewMarkdownTest 同款配方）
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(
                    dir,
                    "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt",
                )
                assertTrue(file.isFile, "源码锚文件缺失：VoicePage.kt")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:feature-voice:jvmTest 工作目录异常")
    }
}
