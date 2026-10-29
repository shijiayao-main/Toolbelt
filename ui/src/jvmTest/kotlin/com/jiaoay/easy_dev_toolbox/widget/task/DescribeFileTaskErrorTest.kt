package com.jiaoay.easy_dev_toolbox.widget.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解密/解析异常中文分类映射（L7——palette 直执行与任务卡错误面同源文案）：
 * - 密钥缺失哨兵消息 → 指引文案；
 * - hex 解析（NumberFormatException）/ EC 密钥规格 → 私钥格式错误；
 * - 其余 IllegalArgumentException → 日志格式错误（带原文摘要）；
 * - 未识别形态 → null（调用方回退 e.message——映射函数不做兜底捏造）。
 */
class DescribeFileTaskErrorTest {

    @Test
    fun `missing key sentinel maps to key missing guidance`() {
        val e = IllegalArgumentException("the log is encrypted and the private key needs to be entered")
        val text = describeFileTaskError(e)
        assertTrue(text != null && "私钥缺失" in text, "缺失哨兵映射私钥缺失：实际「$text」")
        assertTrue(text!!.contains("64 位 hex"), "文案带私钥形态指引（出路要素）")
    }

    @Test
    fun `hex parse failure maps to key format error`() {
        val text = describeFileTaskError(NumberFormatException('z'.toString()))
        assertTrue(text != null && "私钥格式错误" in text, "hex 解析失败映射私钥格式错误：实际「$text」")
    }

    @Test
    fun `malformed structure maps to log format error with raw message`() {
        val text = describeFileTaskError(IllegalArgumentException("invalid segment magic"))
        assertTrue(text != null && "日志格式错误" in text, "非法参数族映射日志格式错误：实际「$text」")
        assertTrue("invalid segment magic" in text!!, "格式错误文案携带原文摘要（排障线索不丢）")
    }

    @Test
    fun `unrecognized exception returns null for caller fallback`() {
        assertNull(describeFileTaskError(RuntimeException("io broken")), "未识别形态返回 null（调用方回退 e.message）")
        assertNull(describeFileTaskError(IllegalStateException(null as String?)), "无消息异常返回 null")
    }
}
