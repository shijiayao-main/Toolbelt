package com.jiaoay.easy_dev_toolbox.page.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 样张图案消费位封闭集守护（「图案不上文字承载面」的机械化，StructureBandConsumersGuardTest
 * 同款源码锚风格）：套级图案槽（ThemeSlotOverrides.surfacePattern）的消费/采样调用/绘制
 * 全链只允许出现在设置页样张 base 块所在的 ThemeShowcase.kt——主题卡其余区域、应用页面、
 * 其他模块消费即红（回潮 = 新增消费位未过注册面重裁，且绕开了样张取证面纪律）。
 * 扫描面 = core 以外全部模块生产源（core 是图案单源定义域：枚举/槽/采样/绘制不在封闭集管辖内）。
 * 图案槽申请制注册面（哪套传什么枚举 + 非注册套传参即红）在 :core 的 AppThemeSpecTest。
 */
class SpecimenPatternConsumersGuardTest {

    private companion object {
        /** 消费位白名单（样张 base 块所在文件，相对项目根） */
        val CONSUMERS = setOf(
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/ThemeShowcase.kt",
        )

        /** 图案锚串（消费/选型/采样/绘制任一形态命中即视为图案消费） */
        val PATTERN_ANCHORS = listOf("surfacePattern", "ThemeSurfacePattern", "PatternSample")
    }

    @Test
    fun `specimen pattern consumer is the closed specimen base block set`() {
        CONSUMERS.forEach { rel ->
            val text = pageSource(rel)
            assertTrue(
                "specimenPatternSample" in text,
                "$rel 必须经采样单源取图案（套级选型 + 该套槽色双色的单源入口）。",
            )
            assertTrue(
                "drawSpecimenPatternSample" in text,
                "$rel base 块必须走统一绘制入口（周期/对比密度在绘制位固定）。",
            )
        }
    }

    @Test
    fun `surface pattern never reaches outside the specimen base block`() {
        val offenders = productionSourcesOutsideCore()
            .filter { (rel, text) -> rel !in CONSUMERS && PATTERN_ANCHORS.any { it in text } }
            .map { it.first }
        assertTrue(
            offenders.isEmpty(),
            "图案消费越出样张 base 块封闭集：$offenders——图案不上文字承载面，" +
                "消费位封闭集 = 设置页样张 base 块（新增消费位须先过注册面重裁并同步本白名单）。",
        )
    }

    /** core 以外全部模块生产源（jvmMain 宿主域 + main lib 域），相对项目根路径 → 源码文本 */
    private fun productionSourcesOutsideCore(): List<Pair<String, String>> {
        val roots = mutableListOf<Pair<String, File>>()
        moduleSourceRoots().forEach { dir ->
            for (srcSet in listOf("src/jvmMain/kotlin", "src/main/kotlin")) {
                val root = File(dir, srcSet)
                if (root.isDirectory) roots.add(dir.name to root)
            }
        }
        return roots.flatMap { (module, root) ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(locateProjectRoot()).invariantSeparatorsPath to it.readText() }
                .toList()
        }
    }

    private fun moduleSourceRoots(): List<File> =
        locateProjectRoot().listFiles { f -> f.isDirectory }
            .orEmpty()
            .filter { it.name !in setOf("build", ".git", ".idea", "gradle") }
            .filter { File(it, "src/jvmMain/kotlin").isDirectory || File(it, "src/main/kotlin").isDirectory }
            .filter { it.name != "core" }

    private fun pageSource(rel: String): String {
        val file = File(locateProjectRoot(), rel)
        assertTrue(file.isFile, "源码锚文件缺失：$rel（重命名/移动后请同步本守护）")
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
        error("无法定位项目根（user.dir=${System.getProperty("user.dir")}）")
    }
}
