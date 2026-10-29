package com.jiaoay.easy_dev_toolbox.page.archive

import com.jiaoay.easy_dev_toolbox.core.navigation.GoXLogDecryptHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解压产物「送 XLog 解密」三分支分流单测（[sendArchiveProductToXLogDecrypt]
 * 真实临时目录，无 GUI）。
 *
 * 钉死两分支契约的三条分流路径（TaskPipelineHandoff ARCHIVE 行）：
 * 1. 目录内恰 1 个 `.xlog`/`.alog`（ignoreCase，混入非匹配文件不计）→ 单文件
 *    直投 palette：seed.args[file_path] = 该文件路径，跳页槽零载荷；
 * 2. 目录内 ≥2 个（含嵌套子目录递归）→ 跳页槽携**目录路径**（非预展开清单——
 *    accepts 过滤语义单源留在 XLog 解密域），palette 零载荷；
 * 3. 零匹配 → 不投递（双槽零载荷），toast 回执原因（拒绝必带原因）。
 *
 * object 单例槽（palette/跳页/toast）每用例 clearForTest 隔离。
 */
class ArchiveProductHandoffTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("archive-handoff-test").toFile()
        TaskPipelineHandoff.clearForTest()
        GoXLogDecryptHook.clearForTest()
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
        TaskPipelineHandoff.clearForTest()
        GoXLogDecryptHook.clearForTest()
        StatusSignals.clearForTest()
    }

    private fun archiveTarget(): TaskPipelineHandoff.NextToolTarget =
        TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.ARCHIVE)!!

    @Test
    fun `single xlog match goes straight to palette seed`() {
        val dir = Files.createTempDirectory(tmpDir.toPath(), "demo_extracted").toFile()
        val xlog = File(dir, "a.XLOG").apply { writeText("fake") }
        // 混入非匹配文件：不计入命中（单文件分支仍成立）
        File(dir, "readme.txt").writeText("not a log")

        TaskPipelineHandoff.wire { }
        GoXLogDecryptHook.wire { }

        sendArchiveProductToXLogDecrypt(dir.absolutePath, archiveTarget())

        val seed = TaskPipelineHandoff.consumeSeed()
        assertTrue(seed != null, "单文件分支须直投 palette（参数直填态）")
        seed!!
        assertEquals("logkit.xlog-decrypt", seed.commandId, "目标命令 = ARCHIVE 表行（XLog 解密）")
        assertEquals(
            xlog.absolutePath,
            seed.args["file_path"],
            "产物路径落点 = 命中的唯一 .xlog（大写扩展名不拒、非匹配文件不计）",
        )
        assertNull(
            GoXLogDecryptHook.consumePendingDirectory(),
            "单文件分支不占跳页槽（多文件分支专属承载面）",
        )
        assertTrue(
            StatusSignals.toast.value.isEmpty(),
            "命中分支不弹零匹配回执：${StatusSignals.toast.value}",
        )
    }

    @Test
    fun `multiple matches carry directory path via jump slot`() {
        val dir = Files.createTempDirectory(tmpDir.toPath(), "demo_extracted").toFile()
        File(dir, "a.xlog").writeText("fake")
        File(dir, "b.alog").writeText("fake")
        // 嵌套子目录内的命中同样计入批量（展开递归语义）
        val nested = File(dir, "sub").apply { mkdirs() }
        File(nested, "c.xlog").writeText("fake")

        TaskPipelineHandoff.wire { }
        GoXLogDecryptHook.wire { }

        sendArchiveProductToXLogDecrypt(dir.absolutePath, archiveTarget())

        assertNull(
            TaskPipelineHandoff.consumeSeed(),
            "多文件分支不走 palette（单值参数直填态装不下整批判）",
        )
        assertEquals(
            dir.absolutePath,
            GoXLogDecryptHook.consumePendingDirectory(),
            "跳页槽载荷 = 目录路径（非预展开清单——accepts 展开单源留在 XLog 解密页）",
        )
        assertTrue(
            StatusSignals.toast.value.isEmpty(),
            "命中分支不弹零匹配回执：${StatusSignals.toast.value}",
        )
    }

    @Test
    fun `zero match toasts reason and offers nothing`() {
        val dir = Files.createTempDirectory(tmpDir.toPath(), "demo_extracted").toFile()
        File(dir, "readme.txt").writeText("not a log")

        TaskPipelineHandoff.wire { }
        GoXLogDecryptHook.wire { }

        sendArchiveProductToXLogDecrypt(dir.absolutePath, archiveTarget())

        assertNull(TaskPipelineHandoff.consumeSeed(), "零匹配不投递（palette 零载荷）")
        assertNull(GoXLogDecryptHook.consumePendingDirectory(), "零匹配不投递（跳页槽零载荷）")
        assertTrue(
            StatusSignals.toast.value.any { "没有可处理" in it.text },
            "零匹配须就地回执原因（拒绝必带原因）：${StatusSignals.toast.value}",
        )
        assertTrue(
            StatusSignals.toast.value.any { "Tag 过滤" in it.text },
            "零匹配回执须含替代去向指引（纯文本日志包走 Tag 过滤页拖目录，回执指路免出应用回找）：${StatusSignals.toast.value}",
        )
    }
}
