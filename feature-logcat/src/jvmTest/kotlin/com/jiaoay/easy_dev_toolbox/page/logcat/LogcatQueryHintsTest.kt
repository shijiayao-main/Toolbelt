package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 查询输入提示纯函数单测：触发（空输入概览 / 尾随待操作数算子值域）/ 消失（完整与
 * 裸输入零打扰）/ 文案口径（key 面与级别值域从解析器常量与 [LogcatLevel] 派生，
 * 不另立第二套口径）。错误回执优先占 supportingText 槽属渲染面接线，VM 错误通道
 * 零改动由既有 LogcatViewModelTest 钉死，本文件只钉提示纯函数。
 */
class LogcatQueryHintsTest {

    @Test
    fun `blank query shows operator overview derived from parser grammar`() {
        listOf("", "   ", "\t").forEach { q ->
            val hint = assertNotNull(logcatQueryHint(q), "空白输入应给算子集概览（q=$q）")
            assertTrue(hint.contains("${LogcatQueryParser.QUERY_KEY_TAG}:"), "概览须含 tag 算子（q=$q）")
            assertTrue(hint.contains("${LogcatQueryParser.QUERY_KEY_LEVEL}:"), "概览须含 level 算子（q=$q）")
            assertTrue(hint.contains("${LogcatQueryParser.QUERY_KEY_MSG}:"), "概览须含 msg 算子（q=$q）")
            assertTrue(hint.contains("~/"), "概览须含正则修饰符形态（q=$q）")
            assertTrue(hint.contains("-"), "概览须含排除前缀（q=$q）")
        }
    }

    @Test
    fun `trailing tag operator hints its value domain`() {
        listOf(
            "tag:",
            "TAG:",
            "tag: ",
            "level:E tag:",
            "(tag:",
            "-tag:",
        ).forEach { q ->
            val hint = assertNotNull(logcatQueryHint(q), "尾随 tag: 算子应给值域提示（q=$q）")
            assertTrue(hint.contains(LogcatQueryParser.QUERY_KEY_TAG), "提示须以 tag 算子为口径（q=$q）")
            assertTrue(hint.contains("~/"), "tag 提示须含正则修饰符通道（q=$q）")
            assertTrue(hint.contains("(?i)"), "tag 提示须含忽略大小写指引（q=$q）")
        }
    }

    @Test
    fun `trailing level operator hints all level letters`() {
        listOf(
            "level:",
            "LEVEL:",
            "level:  ",
            "tag:foo & level:",
        ).forEach { q ->
            val hint = assertNotNull(logcatQueryHint(q), "尾随 level: 算子应给值域提示（q=$q）")
            LogcatLevel.entries.forEach { level ->
                assertTrue(
                    hint.contains(level.letter),
                    "级别提示须含全档单字母 ${level.letter}（q=$q）",
                )
            }
        }
    }

    @Test
    fun `trailing msg operator hints its value domain`() {
        listOf(
            "msg:",
            "MSG:",
            "msg: ",
            "tag:foo & msg:",
            "(msg:",
            "-msg:",
        ).forEach { q ->
            val hint = assertNotNull(logcatQueryHint(q), "尾随 msg: 算子应给值域提示（q=$q）")
            assertTrue(hint.contains(LogcatQueryParser.QUERY_KEY_MSG), "提示须以 msg 算子为口径（q=$q）")
            assertTrue(hint.contains("~/"), "msg 提示须含正则修饰符通道（q=$q）")
            assertTrue(hint.contains("(?i)"), "msg 提示须含忽略大小写指引（q=$q）")
        }
    }

    @Test
    fun `complete or plain queries give no hint`() {
        listOf(
            "camera", // 裸词（有效查询，无提示）
            "tag:camera", // 完整 tag 算子
            "level:E", // 完整 level 算子
            "msg:camera", // 完整 msg 算子
            "tag:foo & -level:W", // 完整组合
            "tag:~/cam/", // 闭合正则
            "stag:", // 前缀粘连的未知 key——不触发（交错误回执单独指路）
            "smsg:", // 前缀粘连的 msg 形态同口径不触发
            "x:level:", // level: 前是 ':'——非 token 边界，不触发
            "level:foo", // 有操作数（值非法由错误回执指路，提示不抢）
            "tag:level:", // level 作为 tag 操作数中缀出现，非尾随算子
        ).forEach { q ->
            assertNull(logcatQueryHint(q), "完整/裸/粘连输入不给提示（q=$q）——提示只服务待输入态")
        }
    }
}
