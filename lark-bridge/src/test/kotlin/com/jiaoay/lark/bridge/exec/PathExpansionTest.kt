package com.jiaoay.lark.bridge.exec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BC · PathExpansionTest 平移] `~` 展开与平台语义绝对路径判定（对象改名 [Paths]，语义逐字保持）。
 * 🔴 平移旧 `util/PathExpansionTest.kt` 8 条断言，零回归。
 */
class PathExpansionTest {

    private val home: String = System.getProperty("user.home")

    @Test
    fun `裸 tilde 展开为主目录`() {
        assertEquals(home, Paths.expandTilde("~"))
    }

    @Test
    fun `正斜杠 tilde 前缀展开为主目录子路径（POSIX 惯例）`() {
        assertEquals("$home/work", Paths.expandTilde("~/work"))
    }

    @Test
    fun `反斜杠 tilde 前缀同样展开（Windows 分隔符习惯）`() {
        assertEquals("$home\\work", Paths.expandTilde("~\\work"))
    }

    @Test
    fun `无前缀路径原样返回`() {
        assertEquals("/opt/data", Paths.expandTilde("/opt/data"))
        assertEquals("relative/path", Paths.expandTilde("relative/path"))
        assertEquals("C:\\work", Paths.expandTilde("C:\\work"))
    }

    @Test
    fun `tilde-user 形态不展开（不解析他人主目录）`() {
        assertEquals("~root/home", Paths.expandTilde("~root/home"))
    }

    @Test
    fun `Unix 绝对路径判定`() {
        assertTrue(Paths.isAbsolute("/opt/data"))
        assertFalse(Paths.isAbsolute("relative/path"))
        assertFalse(Paths.isAbsolute("C:\\work"))
    }

    @Test
    fun `当前平台上判定与 java io File 一致`() {
        listOf("/opt/data", "relative/path", "C:\\work", "~/work").forEach { p ->
            assertEquals(
                File(p).isAbsolute,
                Paths.isAbsolute(p),
                "路径 $p 的判定应与 File.isAbsolute 一致",
            )
        }
    }
}
