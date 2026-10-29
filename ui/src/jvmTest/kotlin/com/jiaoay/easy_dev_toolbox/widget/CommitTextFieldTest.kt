package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CommitTextField trailingIcon 槽守护：
 *
 * 本件是失焦提交输入框的跨页通用件——API Key 明文切换（AiSettingsPage）需要
 * 字段级行内动作槽，向 OutlinedTextField 同名装饰槽透传。
 *
 * 验收面：
 * 1. **编译可达**：本文件以 trailingIcon 实参引用 [CommitTextField]——槽删除或
 *    签名改写即 :ui:jvmTest 编译失败（API Key 消费点同步红，双向钉死）；
 * 2. **配方锚**：CommitTextField.kt 必须把 trailingIcon 透传到 OutlinedTextField
 *    同名槽（防槽加而未接线）；
 * 3. **默认零破坏**：参数声明带默认值 null（全既有消费方零回归——不带默认值
 *    则 30+ 调用点编译红）；
 * 4. **提交语义零变化**：trailingIcon 槽内容不得触碰失焦提交状态机
 *    （isFocused/hasBeenFocused 只在 onFocusChanged 内变更）。
 */
class CommitTextFieldTest {

    /** 编译可达探针：trailingIcon 槽以实参方式引用（签名不兼容即编译失败） */
    @Composable
    private fun compileReachabilityProbe() {
        CommitTextField(
            label = "探针",
            value = "probe",
            onCommit = {},
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = { Text("eye") },
        )
    }

    @Test
    fun `trailingIcon slot is wired through to outlined text field`() {
        val source = commitTextFieldSource()
        listOf(
            "trailingIcon: (@Composable () -> Unit)? = null" to
                "trailingIcon 参数必须带默认值 null（全既有消费方零回归——不带默认值则全部调用点编译红）",
            "trailingIcon = trailingIcon" to
                "trailingIcon 必须透传到 OutlinedTextField 同名槽（槽加而未接线 = 消费点恒空槽）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "CommitTextField.kt 丢失要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `trailingIcon slot does not touch commit state machine`() {
        // trailingIcon 是纯装饰槽——槽内容与失焦提交状态机零耦合（失焦提交语义
        // 零变化）。只扫 CommitTextField 函数区间（CommitNumberField
        // 同款状态机不在本守护面）；状态机字段在该区间内只允许 onFocusChanged 块
        // 的单处赋值模式（if 聚焦置 true / else 复位 false 各一次）。
        val source = stripComments(commitTextFieldFunctionBody())
        val focusedWrites = source.split("isFocused = ").size - 1
        assertTrue(focusedWrites == 1, "CommitTextField 的 isFocused 只允许 onFocusChanged 内单处赋值（实际 $focusedWrites 处）")
        val beenFocusedTrue = source.split("hasBeenFocused = true").size - 1
        val beenFocusedFalse = source.split("hasBeenFocused = false").size - 1
        assertTrue(
            beenFocusedTrue == 1 && beenFocusedFalse == 1,
            "CommitTextField 的 hasBeenFocused 只允许 onFocusChanged 内置位/复位各一次" +
                "（实际 true=$beenFocusedTrue false=$beenFocusedFalse）——trailingIcon 槽不得触碰状态机",
        )
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

    /** 截取 CommitTextField 函数体区间（到 CommitNumberField 定义为止——同文件双件分区） */
    private fun commitTextFieldFunctionBody(): String {
        val source = commitTextFieldSource()
        val start = source.indexOf("fun CommitTextField(")
        assertTrue(start >= 0, "CommitTextField 函数缺失")
        val end = source.indexOf("fun CommitNumberField(")
        assertTrue(end > start, "CommitNumberField 函数缺失（同文件分区锚漂移）")
        return source.substring(start, end)
    }

    private fun commitTextFieldSource(): String {
        val root = locateProjectRoot()
        val file = File(root, COMMIT_TEXT_FIELD_PATH)
        assertTrue(file.isFile, "$COMMIT_TEXT_FIELD_PATH 不存在——SYS-16 扩参件被移动须同步更新本测试路径")
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
        const val COMMIT_TEXT_FIELD_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/CommitTextField.kt"
    }
}
