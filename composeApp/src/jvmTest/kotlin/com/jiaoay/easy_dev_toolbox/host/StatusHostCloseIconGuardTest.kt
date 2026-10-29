package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * toast 关闭钮统一 CLOSE 字形守护。
 *
 * 收口依据：图标经 AppSymbolIcon 唯一出口、禁同语义混用——
 * 「关闭」语义统一走 `AppSymbolIcon(icon = MaterialSymbols.CLOSE)`（tab/输入条等处
 * 同语义已一致）。本守护锚死该形态，防回潮：
 * 1. 关闭钮内容 = `AppSymbolIcon(icon = MaterialSymbols.CLOSE, ...)`（字形经唯一出口）；
 * 2. contentDescription 带中文「关闭提示」（无障碍语义）；
 * 3. 字面量 `Text("×")` 零残留（改回字形 × 即红）。
 *
 * 范围钉死：本守护只锚关闭钮内容形态，不约束 TextButton 载体（命中区保持
 * TextButton 默认，载体更换不碰本断言）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class StatusHostCloseIconGuardTest {

    @Test
    fun `toast close button renders CLOSE glyph via AppSymbolIcon with chinese description`() {
        val host = anchorFile()
        val text = host.readText()

        // ① CLOSE 字形经 AppSymbolIcon 唯一出口
        assertTrue(
            "MaterialSymbols.CLOSE" in text,
            "StatusHost 关闭钮必须渲染 MaterialSymbols.CLOSE（GLB-09：字形经 AppSymbolIcon 唯一出口），" +
                "实际源码未命中——改回字面量/漏换即红。",
        )
        assertTrue(
            "AppSymbolIcon(" in text,
            "StatusHost 关闭钮必须经 AppSymbolIcon 渲染图标（唯一出口件），不得绕出口自绘字形。",
        )

        // ② 中文 contentDescription（无障碍语义）
        assertTrue(
            """contentDescription = "关闭提示"""" in text,
            "StatusHost 关闭钮 contentDescription 须为「关闭提示」（无障碍语义锚），实际未命中。",
        )

        // ③ 字面量 Text("×") 零残留（禁同语义混用——回潮即红）
        assertFalse(
            """Text("×")""" in text,
            "StatusHost 不得残留字面量 Text(\"×\")——「关闭」语义统一 CLOSE 图标（GLB-09）。",
        )
    }

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun anchorFile(): File {
        val file = File(locateProjectRoot(), STATUS_HOST_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$STATUS_HOST_PATH（重命名/移动后请同步本守护）")
        return file
    }

    private companion object {
        const val STATUS_HOST_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/StatusHost.kt"
    }
}
