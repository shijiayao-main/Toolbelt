package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody.Input
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody.MultiSelect
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody.Normal
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody.Chain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索过滤扩展命令内容匹配（design D6，task 4.1）：
 * 覆盖四类 ChildBody 的命中/不命中/大小写不敏感，以及空查询恒真。
 */
class ToolNodeSearchFilterTest {

    // ── Normal ──

    @Test
    fun `normal body matches value`() {
        val body = Normal(value = "adb shell dumpsys battery", desc = "查看电池状态")
        assertTrue(bodyContains(body, "dumpsys"), "value 命中")
        assertFalse(bodyContains(body, "logcat"), "value 不命中且 desc 不命中")
    }

    @Test
    fun `normal body matches desc`() {
        val body = Normal(value = "adb shell dumpsys battery", desc = "查看电池状态")
        assertTrue(bodyContains(body, "电池"), "desc 命中")
    }

    @Test
    fun `normal body match is case-insensitive`() {
        val body = Normal(value = "adb shell GetProp", desc = "")
        assertTrue(bodyContains(body, "getprop"), "大小写不敏感命中")
        assertTrue(bodyContains(body, "GETPROP"), "全大写查询同样命中")
    }

    // ── MultiSelect ──

    @Test
    fun `multi select body matches item name value or desc`() {
        val body = MultiSelect(
            items = listOf(
                MultiSelect.MultiSelectItem(
                    name = "CPU 占用",
                    value = "dumpsys cpuinfo",
                    desc = "查看处理器负载",
                ),
            ),
        )
        assertTrue(bodyContains(body, "cpu 占用"), "item.name 命中（忽略大小写）")
        assertTrue(bodyContains(body, "cpuinfo"), "item.value 命中")
        assertTrue(bodyContains(body, "处理器"), "item.desc 命中")
        assertFalse(bodyContains(body, "meminfo"), "无任何字段命中")
    }

    // ── Input ──

    @Test
    fun `input body matches template value`() {
        val body = Input(
            value = "adb shell am broadcast -a {action}",
            params = listOf(Input.InputParam(name = "action", desc = "广播动作名")),
        )
        assertTrue(bodyContains(body, "broadcast"), "模板 value 命中")
        assertTrue(bodyContains(body, "ACTION"), "param.name 命中（忽略大小写）")
        assertTrue(bodyContains(body, "广播"), "param.desc 命中")
        assertFalse(bodyContains(body, "startservice"), "无任何字段命中")
    }

    // ── Chain ──

    @Test
    fun `chain body matches any command`() {
        val body = Chain(
            commands = listOf(
                "adb root",
                "adb shell logcat -c",
            ),
        )
        assertTrue(bodyContains(body, "logcat"), "任一命令命中")
        assertFalse(bodyContains(body, "bugreport"), "所有命令均不命中")
    }

    @Test
    fun `chain body match is case-insensitive`() {
        val body = Chain(commands = listOf("adb shell PM list packages"))
        assertTrue(bodyContains(body, "pm list"), "大小写不敏感命中")
    }

    // ── 空查询 ──

    @Test
    fun `empty query matches any body`() {
        assertTrue(bodyContains(Normal(value = "anything", desc = ""), ""), "空查询恒真")
        assertTrue(bodyContains(Chain(commands = listOf("x")), ""), "空查询恒真（Chain）")
    }
}
