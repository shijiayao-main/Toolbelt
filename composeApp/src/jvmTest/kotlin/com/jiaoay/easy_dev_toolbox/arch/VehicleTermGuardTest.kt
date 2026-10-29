package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 车载域词守护测试（公开面文案约束的防回潮面）。
 *
 * 契约真源 = openspec/specs/copy-rewording/spec.md：产品面向通用设备/嵌入式场景叙事，
 * 用户可见面（UI 文本、手册、README、LLM 提示词、placeholder 示例词）不出现车载域词
 * （`车载|车机|AVM|泊车`）。守护动机：车载语境属产品定位于公开面的剥离项——文案
 * 清洗完成后无机械守护时，后续贡献者/新示例词极易自然写回场景限定词。
 *
 * ## 扫描面
 * 生产源码（各模块 `src/main` 与 `Main` 后缀源集，配方同 [StoragePathLiteralGuardTest]）
 * + `docs/manual` 手册真源 + README.md + AGENTS.md。测试源集不在列——测试语料已同批
 * 通用化，但守护聚焦「用户可见面」这一不可妥协面（全源集扫描会把测试对语料词的合理
 * 使用也拦下，收窄到生产面使信号保持高信噪比）。
 *
 * 🔴 扩展契约：确有合法新增位点（如引用外部产品名）必须先在本测试白名单登记理由，
 * 再落代码。空/缩水扫描 = 永绿后门：扫描文件数跌破下限即红。
 */
class VehicleTermGuardTest {

    private data class Hit(val relativePath: String, val line: Int, val text: String)

    /** 生产源码 + 手册 + 入口文档：行级扫描（车载词在 UI 文案/注释/KDoc 均违规）。 */
    @Test
    fun `vehicle terms are absent from production sources manual and entry docs`() {
        val root = locateProjectRoot()
        val files = collectScannedFiles(root)
        check(files.size >= MIN_SCANNED_FILES) {
            "扫描面文件数异常（${files.size} < $MIN_SCANNED_FILES 下限）：目录定位/剪枝可能出错，守护会静默失效"
        }
        val hits = files.flatMap { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { idx, raw ->
                // FFmpeg 常量 AVMEDIA_*（avutil 域，与车载无关）优先排除——词表「AVM」
                // 是子串匹配，不排则合法常量误伤
                val line = raw.replace("AVMEDIA", "�")
                val term = VEHICLE_TERMS.firstOrNull { it in line }
                    ?: return@mapIndexedNotNull null
                if (isAllowed(rel)) null else Hit(rel, idx + 1, raw.trim())
            }
        }
        if (hits.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("车载域词越权命中（规格：openspec/specs/copy-rewording/spec.md）：")
                    hits.take(30).forEach { appendLine("  - ${it.relativePath}:${it.line}: ${it.text.take(120)}") }
                    if (hits.size > 30) appendLine("  …（共 ${hits.size} 处，仅列前 30）")
                    appendLine("替换语义分流：日志语境→「设备日志」、设备连接→「目标设备」、叙事→「移动/嵌入式设备」、示例→通用功能域。")
                    appendLine("确有合法新增位点：先在本测试 allowedFiles 登记理由，再落代码。")
                },
            )
        }
    }

    /** 白名单自检：条目必须仍指向真实文件（防路径漂移后静默失效）。 */
    @Test
    fun `allowlist entries point to existing files`() {
        val root = locateProjectRoot()
        val dangling = allowedFiles.keys.filterNot { File(root, it).isFile }
        assertTrue(dangling.isEmpty(), "白名单条目指向不存在的文件（路径漂移？须同步更新）：$dangling")
    }

    /** 词表自检：已知车载样本必须命中（防止词面正则/包含判定自身漂移失效）。 */
    @Test
    fun `term patterns catch known vehicle samples`() {
        val samples = mapOf(
            "车载" to "主打车载与移动设备日志分析",
            "车机" to "车机终端连接成功",
            "AVM" to "分析 AVM 影像不显示的原因",
            "泊车" to "泊车页面黑屏无响应",
        )
        samples.forEach { (term, text) ->
            assertEquals(true, VEHICLE_TERMS.any { it in text }, "样本 [$text] 未被词面捕获——词表 $VEHICLE_TERMS 缺陷")
        }
        // 通用替代词不得误伤
        assertEquals(false, VEHICLE_TERMS.any { it in "设置页面黑屏无响应，相机启动失败" }, "通用语料误命中")
    }

    // ------------------------------------------------------------------ internals

    /**
     * 收集扫描面文件：各模块生产源集 .kt + docs/manual 目录下全部 md + README.md + AGENTS.md。
     * openspec/ 归档区与本测试文件按规格允许面排除。
     */
    private fun collectScannedFiles(root: File): List<File> {
        val files = mutableListOf<File>()
        for (module in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            val src = File(module, "src")
            if (!src.isDirectory) continue
            for (sourceSet in src.listFiles { f -> f.isDirectory }.orEmpty()) {
                val isProduction = sourceSet.name == "main" || sourceSet.name.endsWith("Main")
                if (!isProduction) continue
                files += sourceSet
                    .walkTopDown()
                    .onEnter { it.name !in EXCLUDED_DIR_NAMES }
                    .filter { it.isFile && it.extension == "kt" }
            }
        }
        val manualDir = File(root, "docs/manual")
        if (manualDir.isDirectory) {
            files += manualDir.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty()
        }
        listOf("README.md", "AGENTS.md").forEach { name ->
            val f = File(root, name)
            if (f.isFile) files += f
        }
        return files
    }

    private fun isAllowed(relativePath: String): Boolean =
        relativePath in allowedFiles || relativePath.startsWith("openspec/changes/archive/")

    private companion object {

        /** 车载域词面（规格 Requirement 同步）：新词增补须先改规格再改此处 */
        val VEHICLE_TERMS = listOf("车载", "车机", "AVM", "泊车")

        /** 构建产物目录剪枝（同 StoragePathLiteralGuardTest 配方） */
        val EXCLUDED_DIR_NAMES = setOf("build", "out", "node_modules")

        /**
         * 扫描面文件下限：跌破说明扫描根丢失/剪枝出错（当前生产 .kt 约 470+ 手册 26
         * + 入口 2，下限留足余量但保证非空转）。
         */
        const val MIN_SCANNED_FILES = 400

        /**
         * 显式白名单（相对根路径 → 理由）。当前为空——归档区与守护自身经 [isAllowed]
         * 的路径前缀/本文件不在扫描面（collectScannedFiles 不收 composeApp 测试源集）。
         * 新增合法位点在此登记。
         */
        val allowedFiles: Map<String, String> = emptyMap()
    }
}
