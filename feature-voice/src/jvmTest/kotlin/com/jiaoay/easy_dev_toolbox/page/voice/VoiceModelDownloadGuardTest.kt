package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型下载体验对冲守护：
 *
 * 验收面：
 * 1. 下载中可见速度与剩余时间估计（速度为 0 时该行不炸）——VM 层差分流 + UI 双坑
 *    门控（speed≤0 整行不显 / totalBytes=-1 未知总长无 ETA）；
 * 2. 下载失败/校验失败卡可见手动放置完整路径与「打开模型目录」钮——Failed/Corrupt
 *    两分支同享（ManualPlacementHint）；
 * 3. ModelStoreTest 5 用例不回归——VM 层差分案 State 零改动（ModelStore.kt 零触碰）。
 *
 * openModelDir 直开目录本体：DesktopUtils.openInFolder 开 parentFile（文件语义）
 * ——差一级，VoiceViewModel 私有直开（不越界改 DesktopUtils.kt）。
 */
class VoiceModelDownloadGuardTest {

    // ── etaMinutes 纯函数直测（除零/负值/-1 总长三用例） ──

    @Test
    fun `eta 速度为零不炸`() {
        assertNull(etaMinutes(remainingBytes = 100_000_000, speedBps = 0), "speed=0 必须返回 null（除零坑）")
        assertNull(etaMinutes(remainingBytes = 100_000_000, speedBps = -5), "speed<0 必须返回 null")
    }

    @Test
    fun `eta 未知总长不炸`() {
        assertNull(etaMinutes(remainingBytes = -1, speedBps = 1_000_000), "remaining=-1（totalBytes=-1 初值差积）必须 null")
        assertNull(etaMinutes(remainingBytes = -100, speedBps = 1_000_000), "负剩余必须 null")
    }

    @Test
    fun `eta 正常取整分且至少一分`() {
        assertEquals(1, etaMinutes(remainingBytes = 1, speedBps = 1_000_000), "不足 1 分按 1 分起估")
        assertEquals(1, etaMinutes(remainingBytes = 59_999_999, speedBps = 1_000_000), "约 1 分")
        assertEquals(1, etaMinutes(remainingBytes = 60_000_001, speedBps = 1_000_000), "61 秒仍约 1 分（约数不精确进位）")
        assertEquals(2, etaMinutes(remainingBytes = 120_000_001, speedBps = 1_000_000), "121 秒约 2 分")
        assertEquals(227, etaMinutes(remainingBytes = 227L * 60 * 1_000_000, speedBps = 1_000_000), "227MB @1MB/s=约 227 分")
    }

    // ── 源码锚（消费面接线） ──

    @Test
    fun `下载卡速度ETA行与失败卡手动放置指引接线`() {
        val page = voicePageSource()
        listOf(
            "vm.downloadSpeed.collectAsState()" to "下载卡必须消费 VM 速度差分流（VM 层方案）",
            // 速度行恒显（稿「速度为 0 时只显速度行不炸」逐字）——ETA 门控改内层 speed>0 判定
            "if (speed > 0)" to "ETA 门控（speed≤0 无 ETA 可估——速度行恒显不炸）",
            "MB/s" to "速度行文案",
            "MB/s" to "速度行文案模板（ETA 段 speed>0 内层条件拼接——速度行恒显形态）",
            "ManualPlacementHint(vm)" to "失败/Corrupt 卡手动放置指引消费（count==2 于下行断言）",
            "打开模型目录" to "「打开模型目录」钮（openModelDir 接线）",
            "ModelStore.modelDir.absolutePath" to "手动放置指引必须含完整绝对路径插值",
            "style = DesktopTypography.mono" to "手动放置路径 mono 等宽（PathConfirmCard 式小字）",
        ).forEach { (needle, why) ->
            assertTrue(needle in page, "VoicePage.kt 丢失下载要素「$needle」——$why")
        }
        assertEquals(
            2,
            Regex("ManualPlacementHint\\(vm\\)").findAll(page).count(),
            "Failed 与 Corrupt 两分支各一处 ManualPlacementHint（实测应为 2）",
        )
    }

    @Test
    fun `VM 速度流与直开目录接线`() {
        val vm = voiceViewModelSource()
        listOf(
            "val downloadSpeed: StateFlow<Long>" to "VM 速度差分流暴露（字节/秒口径）",
            "fun openModelDir()" to "VM 直开模型目录动作面",
            "Desktop.getDesktop().open(ModelStore.modelDir)" to "直开目录本体（openInFolder 开 parent 差一级——不走）",
        ).forEach { (needle, why) ->
            assertTrue(needle in vm, "VoiceViewModel.kt 丢失下载要素「$needle」——$why")
        }
        // ModelStore.State 零改动背书（VM 层差分案——不引入 speedBytesPerSecond 字段）
        val store = modelStoreSource()
        assertTrue(
            "speedBytesPerSecond" !in store,
            "ModelStore.State 不增字段（VM 层差分案——ModelStoreTest 5 用例零风险）",
        )
    }

    // ── 源码锚 ──

    private fun voicePageSource(): String = readSource(
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt",
    )

    private fun voiceViewModelSource(): String = readSource(
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceViewModel.kt",
    )

    private fun modelStoreSource(): String = readSource(
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/ModelStore.kt",
    )

    private fun readSource(relativePath: String): String {
        // 向上找 settings.gradle.kts 定项目根（GuardTestSupport/VoiceReviewMarkdownTest 同款配方）
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, relativePath)
                assertTrue(file.isFile, "源码锚文件缺失：$relativePath")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:feature-voice:jvmTest 工作目录异常")
    }
}
