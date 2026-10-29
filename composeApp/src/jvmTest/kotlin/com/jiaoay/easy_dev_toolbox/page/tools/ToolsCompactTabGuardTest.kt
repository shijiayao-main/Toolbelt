package com.jiaoay.easy_dev_toolbox.page.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TL-13 源码锚守护（重设计批 11 · 2026-10-02）：Compact 档子签缩略形态钉死。
 *
 * 卡面 acceptance：「窗口拖窄至 Compact 档，子签显短名且无截断；宽窗维持全名」。
 * 落法锚：
 * 1. 短名表/全名表双常量在 ToolsPage 私有（不进 ToolPageLabels——DL-F15 检索
 *    别名与显示名分离，窗宽形态变体非页名）；
 * 2. currentWindowSizeClass() == Compact 门控切换（PageSettings Wide 门控同款
 *    消费先例）；
 * 3. 两表等长（index 序 = TL-02/TL-08 状态面锚序——短名只是显示层替换，
 *    onSelect 持久回写零状态风险）。第 6 签「日志对比/对比」、第 7 签
 *    「正则测试/正则」、第 8 签「JSON 工具/JSON」、第 9 签「Base64 提取/Base64」、
 *    第 10 签「哈希校验/哈希」、第 11 签「颜色转换/颜色」、第 12 签「JWT 解析/JWT」
 *    与第 13 签「ULID 工具/ULID」加入后两表同步为十三项（JSON/Base64/JWT/ULID
 *    短名是 ASCII——视觉宽约两个/三个/四个汉字，非两字等长形态的已知例外）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache`。
 */
class ToolsCompactTabGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/ToolsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `compact tab labels are short and gated by window size class`() {
        val text = pageSource()
        // 门控锚：Compact 判定在位（core/theme 既有 API——PageSettings 同款）
        assertTrue(
            Regex("""currentWindowSizeClass\(\) == WindowSizeClass\.Compact""").containsMatchIn(text),
            "TL-13 守护：子签 options 须以 currentWindowSizeClass() == WindowSizeClass.Compact 门控" +
                "（窄窗短名、宽窗全名——卡面 acceptance）",
        )
        // 短名表在位（十三签短名——中文九项两字等长 + JSON/Base64/JWT/ULID ASCII 短名）
        val compactAnchor = Regex(
            """TOOLS_TAB_LABELS_COMPACT\s*=\s*listOf\("解密",\s*"过滤",\s*"分割",\s*"时间",\s*"解压",\s*"对比",\s*"正则",\s*"JSON",\s*"Base64",\s*"哈希",\s*"颜色",\s*"JWT",\s*"ULID"\)""",
        )
        assertTrue(
            compactAnchor.containsMatchIn(text),
            "TL-13 守护：Compact 短名表须为「解密/过滤/分割/时间/解压/对比/正则/JSON/Base64/哈希/颜色/JWT/ULID」十三项（中文两字等长，JSON/Base64/JWT/ULID 为 ASCII 短名已知例外）",
        )
        // 全名表在位（宽窗现状维持）
        assertTrue(
            Regex("""TOOLS_TAB_LABELS_FULL\s*=\s*listOf\("XLog 解密",\s*"Tag 过滤",\s*"文件分割",\s*"时间戳",\s*"解压",\s*"日志对比",\s*"正则测试",\s*"JSON 工具",\s*"Base64 提取",\s*"哈希校验",\s*"颜色转换",\s*"JWT 解析",\s*"ULID 工具"\)""")
                .containsMatchIn(text),
            "TL-13 守护：全名表十三项须维持（宽窗现状）——子签全名「XLog 解密/Tag 过滤/文件分割/时间戳/解压/日志对比/正则测试/JSON 工具/Base64 提取/哈希校验/颜色转换/JWT 解析/ULID 工具」",
        )
    }

    @Test
    fun `short label mapping stays page private`() {
        val text = pageSource()
        // 映射表收 ToolsPage 私有（卡面 proposal——不进 ToolPageLabels，DL-F15 口径不破）
        assertTrue(
            "private val TOOLS_TAB_LABELS_COMPACT" in text && "private val TOOLS_TAB_LABELS_FULL" in text,
            "TL-13 守护：短名/全名表须为 ToolsPage 私有常量（窗宽形态变体非页名，" +
                "不进 ToolPageLabels——DL-F15 检索别名与显示名分离）",
        )
        // 不进 ToolPageLabels 核对：labels 真源不得被短名污染
        val labelsFile = File(
            projectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/ToolPageLabels.kt",
        )
        // 硬失败：labels 真源缺失即守护失效（对齐 pageSource() 的 file.isFile 配方）
        assertTrue(labelsFile.isFile, "源码锚文件缺失：${labelsFile.path}（重命名/移动后请同步本守护）")
        val labelsText = labelsFile.readText()
        assertTrue(
            "\"解密\"" !in labelsText && "\"过滤\"" !in labelsText && "\"分割\"" !in labelsText,
            "TL-13 守护：ToolPageLabels 不得收短名（检索别名与显示名分离——DL-F15）",
        )
    }
}
