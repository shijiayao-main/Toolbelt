package com.jiaoay.easy_dev_toolbox.page.timestamp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * XA-13 源码锚守护（重设计批 11 · 2026-10-02）：输入框「Enter 立即求值」接线钉死。
 *
 * docs/ui-style-guide.md:672 Enter 提交矩阵例外表曾登记「时间戳页 300ms debounce +
 * Enter 立即求值」，但页面此前零 key handler（假声明——XA-13 立案事由）。本卡实现侧
 * 修复后，声明成真；本守护防回归——输入框 modifier 被摘除 onEnterKey 时红：
 * 1. 输入框 modifier 必须挂 `.onEnterKey { viewModel.convert(input) }`（SubmitKey
 *    单行语义默认档——页面不得自写 key event 处理，XA-12/RenameDialog 先例）；
 * 2. 零自持 onPreviewKeyEvent/onKeyEvent（Enter 语义单源 = SubmitKey 扩展）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-timestamp:jvmTest --rerun-tasks --no-build-cache`。
 */
class TimestampEnterKeyWiringGuardTest {

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "feature-timestamp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `input field triggers convert immediately on enter`() {
        val text = pageSource()
        // 锚：输入框 modifier 挂 onEnterKey 直调 convert（回调捕获当前 input state——
        // onEnterKey 每次重组重建闭包取现值；只回填不 convert 不合「立即求值」语义）
        val anchor = Regex(
            """\.onEnterKey \{ viewModel\.convert\(input\) \}""",
        )
        assertTrue(
            anchor.containsMatchIn(text),
            "XA-13 守护：时间戳输入框必须挂 .onEnterKey { viewModel.convert(input) }" +
                "（Enter 立即求值——docs/ui-style-guide.md 例外登记语义；摘除即假声明回归）",
        )
        assertTrue(
            "onEnterKey" in text && "import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey" in text,
            "XA-13 守护：onEnterKey 须走 SubmitKey 扩展 import（Enter 语义单源，页面不得自写 key handler）",
        )
    }

    @Test
    fun `page has no self-owned key event handler`() {
        val text = pageSource()
        assertTrue(
            "onPreviewKeyEvent" !in text && "onKeyEvent" !in text,
            "XA-13 守护：页面不得自写 key event 处理（Enter 语义单源 = SubmitKey.onEnterKey 扩展）",
        )
    }
}
