package com.jiaoay.easy_dev_toolbox.page.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 产物 chip 点击动作分流判据单测：[isTextLogArtifact]（判定表单源
 * [TEXT_LOG_ARTIFACT_EXTENSIONS]）——文本日志形态产物进应用内查看器，
 * 其余产物走系统默认程序打开。chip 动作接线面（offer 写槽 + openFile 兜底）
 * 由 PageReportTest 源码锚背书；查看器挂载面（槽 collect → Dialog）由
 * LogViewerPaletteWiringGuardTest 背书。
 */
class PaletteHudArtifactTest {

    @Test
    fun `hud produced text artifact extensions route to in app viewer`() {
        // 现网 HUD 产物形态逐一对表：解密/过滤/分割/时间截取产物（.txt/.log，
        // 解密产物名含源扩展如 *.xlog.txt——判定取最后一段扩展）、日志对比
        // 导出（.diff）、文本报告（.md）
        val viewable = listOf(
            "/tmp/logs/capture_0529.xlog.txt",
            "/tmp/logs/capture_0529.alog.txt",
            "/tmp/logs/app_000.log",
            "/tmp/logs/new.log.diff",
            "/tmp/logs/new.log.filter-time_1300-1430.txt",
            "/tmp/reports/report.md",
        )
        viewable.forEach { path ->
            assertTrue(isTextLogArtifact(path), "HUD 产物必须是文本日志形态（进应用内查看器）：$path")
        }
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertTrue(isTextLogArtifact("/tmp/notes/README.MD"), "扩展名判定必须大小写不敏感（大写 .MD）")
        assertTrue(isTextLogArtifact("/tmp/logs/capture.Txt"), "扩展名判定必须大小写不敏感（混合大小写 .Txt）")
    }

    @Test
    fun `non text artifacts keep system open action`() {
        // 零回归面：非文本产物保留 openFile（解压产物目录无扩展名、压缩包/图片
        // 等二进制不在清单、加密产物 .xlog/.alog 不进查看器——先解密引导由
        // 查看器 Failed 态自带，但路由层就不送）
        val nonText = listOf(
            "/tmp/out/extracted",
            "/tmp/archives/logs.zip",
            "/tmp/screenshots/IMG_001.PNG",
            "/tmp/logs/capture_0529.xlog",
            "/tmp/logs/capture_0529.alog",
            "/tmp/no-extension-file",
        )
        nonText.forEach { path ->
            assertFalse(isTextLogArtifact(path), "非文本产物不得进应用内查看器（仍走系统打开）：$path")
        }
    }

    @Test
    fun `extension table is the single routing judge`() {
        // 判定表 = 分流唯一真源：内容与表逐项对齐（防他处再列第二套扩展名后漂移）
        assertEquals(
            setOf("txt", "log", "md", "diff"),
            TEXT_LOG_ARTIFACT_EXTENSIONS,
            "判定表内容漂移——增删扩展名必须同 commit 更新本断言与表 KDoc 口径",
        )
        // 段级误判防：扩展名前缀相似不放宽（.alog/.xlog 不是 .log；.logx 不是 .log）
        listOf(".alog", ".xlog", ".logx", ".txtx").forEach { ext ->
            assertFalse(
                isTextLogArtifact("/tmp/file$ext"),
                "扩展名判定必须整段相等（前缀相似不放宽）：$ext",
            )
        }
    }

    // ── 产物续跑映射（nextStepOfCommand：palette 直跑产物「送往下一步」桥接面）──

    @Test
    fun `hud next step bridges pipeline commands through the handoff table`() {
        // 解密产物 → Tag 过滤（复用 PIPELINE 表查表桥接——label/目标命令 id/参数槽
        // 与页面产物卡「送往 Tag 过滤」同源，不复制第二张映射表）
        val decrypt = assertNotNull(
            nextStepOfCommand("logkit.xlog-decrypt") as? HudNextStep.Pipeline,
            "解密产物须映射管线衔接（palette 直跑产物不再断头）",
        )
        assertEquals("送往 Tag 过滤", decrypt.target.label, "续跑文案与页面产物卡同源（PIPELINE 表）")
        assertEquals("logkit.tag-filter", decrypt.target.commandId, "目标命令 = 过滤页命令（衔接槽解析锚）")
        assertEquals("file_path", decrypt.target.fileParamId, "产物路径预填槽 = 目标命令文件参数")
        // 时间截取切片（本级产物子集）→ Tag 过滤（读面产物同语义，经 LOG_VIEWER 行查表）
        val slice = assertNotNull(
            nextStepOfCommand("tools.slice-time") as? HudNextStep.Pipeline,
            "时间截取产物须映射管线衔接",
        )
        assertEquals("logkit.tag-filter", slice.target.commandId)
    }

    @Test
    fun `tag filter product maps to deep analyze and unmapped commands have no step`() {
        // Tag 过滤产物主去向 = 深度分析（DeepAnalyzeHook 直投——PIPELINE KDoc 既有条目语义）
        assertEquals(HudNextStep.DeepAnalyze, nextStepOfCommand("logkit.tag-filter"))
        // 未映射命令不虚标续跑钮：分割产物 = 管线终点（无下一级）、buffer 暂存无
        // 产物语义、对比/查看器/暂存未登记映射（与 PIPELINE「未登记来源不出现
        // 条目」同口径——无静默兜底目标）
        assertNull(nextStepOfCommand("logkit.file-split"), "分割产物是管线终点，不得虚标续跑")
        assertNull(nextStepOfCommand("buffer.offer"))
        assertNull(nextStepOfCommand("tools.log-diff"))
        assertNull(nextStepOfCommand("tools.view-log"))
    }

    @Test
    fun `hud next step labels derive from target or fixed deep analyze wording`() {
        val step = assertNotNull(
            nextStepOfCommand("logkit.xlog-decrypt") as? HudNextStep.Pipeline,
            "前置：解密产物映射管线衔接",
        )
        assertEquals("送往 Tag 过滤", hudNextStepLabel(step), "Pipeline 标签 = 目标情境文案单源")
        assertEquals("送工作台分析", hudNextStepLabel(HudNextStep.DeepAnalyze), "DeepAnalyze 标签固定直投语义")
    }
}
