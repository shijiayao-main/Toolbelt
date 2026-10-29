package com.jiaoay.toolbelt.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备文件解析层守护（纯函数离线测试，零真子进程）。fixture 按真机实采
 * （toybox 0.8.4）实测基线形态：`ls -la` 八列长格式（权限/硬链数/属主/属组/
 * 尺寸/YYYY-MM-DD HH:MM/名称）、`stat -c '%F|%s|%Y|%A|%U|%G'` 管道分隔单行。
 *
 * 守护面：
 * - 双格式分支（带/无硬链列）全矩阵；
 * - 名称列 join 防御（含空格名/含特殊字符名）+ symlink ` -> ` 拆分；
 * - 坏输出显式失败（整体格式不符抛异常，禁静默空列表）+ 坏行跳过不拖垮好行
 *  （换行名断口残余防御）；
 * - `.`/`..` 与 total 摘要行剔除；空目录 = 空列表（合法读数不失败）；
 * - stat 单行：三类型映射 + 列数/类型/数字列不符显式失败。
 */
class DeviceFileParsersTest {

    /** 真机 toybox 0.8.4 八列长格式（带硬链列）基线样例 */
    private val lsLinksFormat = """
        total 24
        drwxrwx--x 2 root root 4096 2026-10-11 14:23 Movies
        drwxrwx--x 4 root root 4096 2026-10-10 09:00 DCIM
        -rw-rw---- 1 root root 512 2026-10-11 08:01 build.log
        lrwxrwxrwx 1 root root 12 2026-09-30 18:00 sdcard -> /storage/emulated/0
    """.trimIndent()

    @Test
    fun `parseLsEntries parses links-format rows preserving ls output order`() {
        val entries = DeviceFileParsers.parseLsEntries(lsLinksFormat)

        assertEquals(listOf("Movies", "DCIM", "build.log", "sdcard"), entries.map { it.name }, "条目序 = ls 输出序")
        val movies = entries[0]
        assertEquals(DeviceFileType.DIRECTORY, movies.type)
        assertEquals(4096L, movies.sizeBytes)
        assertEquals("drwxrwx--x", movies.permissions)
        assertEquals("root", movies.owner)
        assertEquals("root", movies.group)
        assertEquals("2026-10-11 14:23", movies.modifiedTime, "日期+时间两列原文拼接")
        assertNull(movies.symlinkTarget, "非 symlink 条目 target 恒 null")

        assertEquals(DeviceFileType.FILE, entries[2].type)
        val link = entries[3]
        assertEquals(DeviceFileType.SYMLINK, link.type)
        assertEquals("/storage/emulated/0", link.symlinkTarget, "symlink 按 ' -> ' 拆名与目标")
        assertEquals(12L, link.sizeBytes)
    }

    @Test
    fun `parseLsEntries parses links-less format with toLongOrNull probing`() {
        // 无硬链列七列形态（老 ls 变体）：第 2 列非数字 → 属主列起
        val output = """
            total 8
            drwxrwx--x root root 4096 2026-10-11 14:23 Movies
            -rw-rw---- root root 512 2026-10-11 08:01 build.log
        """.trimIndent()

        val entries = DeviceFileParsers.parseLsEntries(output)

        assertEquals(listOf("Movies", "build.log"), entries.map { it.name })
        assertEquals("root", entries[0].owner)
        assertEquals("root", entries[0].group)
        assertEquals(4096L, entries[0].sizeBytes)
        assertEquals(512L, entries[1].sizeBytes)
    }

    @Test
    fun `parseLsEntries joins name column to restore space-containing names`() {
        // 名称列 join：含空格名在两种格式下都必须整名还原
        val linksFormat = "-rw-rw---- 1 root root 512 2026-10-11 08:01 my trace log.txt"
        val linksLessFormat = "-rw-rw---- root root 512 2026-10-11 08:01 my trace log.txt"

        assertEquals(
            "my trace log.txt",
            DeviceFileParsers.parseLsEntries(linksFormat).single().name,
            "带硬链列格式：名称列 join 还原",
        )
        assertEquals(
            "my trace log.txt",
            DeviceFileParsers.parseLsEntries(linksLessFormat).single().name,
            "无硬链列格式：名称列 join 还原",
        )
    }

    @Test
    fun `parseLsEntries keeps special-character names intact without escape`() {
        // toybox 对特殊字符名零转义：$ 与反引号等原文收录（转义是命令构造层的责任）
        val output = "-rw-rw---- 1 root root 512 2026-10-11 08:01 a\$b`c*d.log"
        assertEquals("a\$b`c*d.log", DeviceFileParsers.parseLsEntries(output).single().name)
    }

    @Test
    fun `parseLsEntries splits symlink with space-containing target`() {
        val output = "lrwxrwxrwx 1 root root 12 2026-09-30 18:00 my link -> /storage/emulated/0/My Folder"
        val entry = DeviceFileParsers.parseLsEntries(output).single()
        assertEquals("my link", entry.name, "symlink 名含空格整名还原")
        assertEquals("/storage/emulated/0/My Folder", entry.symlinkTarget, "目标含空格整段还原")
    }

    @Test
    fun `parseLsEntries drops total summary and dot entries`() {
        val entries = DeviceFileParsers.parseLsEntries(lsLinksFormat + "\ndrwxrwx--x 2 root root 4096 2026-10-11 14:23 .\ndrwxrwx--x 2 root root 4096 2026-10-11 14:23 ..")
        assertTrue(entries.none { it.name == "." || it.name == ".." }, "`.`/`..` 条目不进结果")
        assertEquals(4, entries.size)
    }

    @Test
    fun `parseLsEntries treats empty directory as legal empty result`() {
        // -la 形态：空目录仍有 `./..` 行（可识别，零保留条目）；-l 形态：仅 total 行
        val emptyWithDots = """
            total 8
            drwxrwx--x 2 root root 4096 2026-10-11 14:23 .
            drwxrwx--x 2 root root 4096 2026-10-11 14:23 ..
        """.trimIndent()
        assertEquals(emptyList(), DeviceFileParsers.parseLsEntries(emptyWithDots), "空目录 -la = 空列表非失败")
        assertEquals(emptyList(), DeviceFileParsers.parseLsEntries("total 0"), "空目录 -l = 空列表非失败")
        assertEquals(emptyList(), DeviceFileParsers.parseLsEntries(""), "空输出 = 空列表非失败")
    }

    @Test
    fun `parseLsEntries fails explicitly on unrecognized output instead of silent empty`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceFileParsers.parseLsEntries("Permission denied\nsome random text")
        }
        assertTrue(e.message!!.contains("格式不符"), "失败 message 须人话指明格式不符（实际：${e.message}）")
        assertTrue(e.message!!.contains("Permission denied"), "失败 message 带首条不可识别行样例")
    }

    @Test
    fun `parseLsEntries skips bad rows without dragging down good rows`() {
        // 换行名断口残余防御：含换行的文件名把行打断，断口尾行防御性跳过，好行不拖垮
        val output = listOf(
            "-rw-rw---- 1 root root 512 2026-10-11 08:01 good.log",
            "broken tail fragment",
            "-rw-rw---- 1 root root 128 2026-10-11 08:02 another.log",
        ).joinToString("\n")

        val entries = DeviceFileParsers.parseLsEntries(output)

        assertEquals(listOf("good.log", "another.log"), entries.map { it.name }, "坏行跳过，好行保留")
    }

    @Test
    fun `parseLsEntries strips trailing cr from pty output`() {
        val crlf = lsLinksFormat.replace("\n", "\r\n")
        assertEquals(4, DeviceFileParsers.parseLsEntries(crlf).size, "adb shell pty 行尾 CR 统一剥除")
    }

    @Test
    fun `parseLsEntries accepts month-name date variants across ROMs`() {
        // 月名形态（GNU/POSIX ls）日期段为 3 token：月缩写 + 日 + 时间或 4 位年
        val monthDayTime = "-rw-rw---- 1 root root 512 Oct 11 14:23 build.log"
        val monthDayYear = "-rw-rw---- 1 root root 512 Oct 11 2025 build.log"

        val timeEntry = DeviceFileParsers.parseLsEntries(monthDayTime).single()
        assertEquals("build.log", timeEntry.name, "3 token 日期段后名称列起点正确")
        assertEquals("Oct 11 14:23", timeEntry.modifiedTime)
        assertEquals(512L, timeEntry.sizeBytes)

        val yearEntry = DeviceFileParsers.parseLsEntries(monthDayYear).single()
        assertEquals("build.log", yearEntry.name)
        assertEquals("Oct 11 2025", yearEntry.modifiedTime)
    }

    @Test
    fun `parseLsEntries rejects unestablished date shapes as unrecognized rows`() {
        // 日期段不满足 ISO/月名两已定形态 → 行不可识别；整输出零可识别行 = 显式失败
        // （固定列位吃未知日期形态会静默错切名称列，宁可防御）
        assertFailsWith<IllegalArgumentException> {
            DeviceFileParsers.parseLsEntries("-rw-rw---- 1 root root 512 11/10/2026 14:23 build.log")
        }
        // 好行不受坏行拖累：未知日期形态的行跳过，ISO 行保留
        val mixed = listOf(
            "-rw-rw---- 1 root root 512 11/10/2026 14:23 weird-rom.log",
            "-rw-rw---- 1 root root 512 2026-10-11 08:01 build.log",
        ).joinToString("\n")
        assertEquals(listOf("build.log"), DeviceFileParsers.parseLsEntries(mixed).map { it.name })
    }

    @Test
    fun `parseStatLine maps the three known type words`() {
        val stat = DeviceFileParsers.parseStatLine("regular file|1234|1728636000|-rw-rw-r--|root|root")
        assertEquals(DeviceFileType.FILE, stat.type)
        assertEquals(1234L, stat.sizeBytes)
        assertEquals(1728636000L, stat.modifiedEpochSeconds)
        assertEquals("-rw-rw-r--", stat.permissions)
        assertEquals("root", stat.owner)
        assertEquals("root", stat.group)

        assertEquals(DeviceFileType.DIRECTORY, DeviceFileParsers.parseStatLine("directory|4096|1728636000|drwxrwx--x|root|root").type)
        assertEquals(DeviceFileType.SYMLINK, DeviceFileParsers.parseStatLine("symbolic link|12|1728636000|lrwxrwxrwx|root|root").type)
    }

    @Test
    fun `parseStatLine fails explicitly on malformed output`() {
        val wrongColumns = assertFailsWith<IllegalArgumentException> {
            DeviceFileParsers.parseStatLine("regular file|1234|-rw-rw-r--|root|root")
        }
        assertTrue(wrongColumns.message!!.contains("非六列"), "列数不符须显式失败（实际：${wrongColumns.message}）")

        val unknownType = assertFailsWith<IllegalArgumentException> {
            DeviceFileParsers.parseStatLine("character special file|1|1728636000|crw-rw-rw-|root|root")
        }
        assertTrue(unknownType.message!!.contains("类型列不认识"), "未知类型须显式失败不猜（实际：${unknownType.message}）")

        val badSize = assertFailsWith<IllegalArgumentException> {
            DeviceFileParsers.parseStatLine("regular file|abc|1728636000|-rw-rw-r--|root|root")
        }
        assertTrue(badSize.message!!.contains("尺寸列非数字"), "数字列不符须显式失败（实际：${badSize.message}）")
    }
}
