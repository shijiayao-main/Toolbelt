package com.jiaoay.easy_dev_toolbox.page.logkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 目录拖入接线源码锚守护：目录一等输入是 opt-in 链路
 * 「页面 spec → 模板透传 → 组件消费 → 展开内核」，任一环被静默改回都会让
 * 「整目录拖入」无声失效（拖目录又变回「类型不符」跳过）。本测试扫源码把
 * 四环全部钉死，语义与边界由 :ui 的 DropExpansionOutcome 系列单测承担。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-logkit:jvmTest --rerun-tasks --no-build-cache`。
 */
class DirectoryDropWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    /** 剥除 // 行注释与 /* */ 块注释（含 KDoc），字符串内容保留——UiArchitectureGuardTest 同款语义 */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inLine = false
        var inBlock = false
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }

                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i++
                }

                inRawString -> when {
                    c == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                        sb.append("\"\"\"")
                        i += 2
                        inRawString = false
                    }

                    else -> sb.append(c)
                }

                inString -> when {
                    c == '\\' && next != null -> {
                        sb.append(c).append(next)
                        i++
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                    }

                    else -> sb.append(c)
                }

                else -> when {
                    c == '/' && next == '/' -> {
                        inLine = true
                        i++
                    }

                    c == '/' && next == '*' -> {
                        inBlock = true
                        i++
                    }

                    c == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                        sb.append("\"\"\"")
                        i += 2
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                    }

                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun strippedSource(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后请同步本守护）")
        return stripComments(file.readText())
    }

    @Test
    fun `directory drop opt-in chain stays wired end to end`() {
        val missing = buildList {
            // 环 1：两页 spec 开启目录输入（pipeline 主干：解压产物目录 → 解密 → 过滤）
            for (page in listOf(
                "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/xlog/XLogDecoderPage.kt",
                "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterPage.kt",
            )) {
                if (!strippedSource(page).contains("acceptsDirectories = true")) {
                    add("$page 未开启 acceptsDirectories（目录拖入在页面侧被关闭）")
                }
            }
            // 环 2：模板把 spec 字段透传给组件
            if (!strippedSource(
                    "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/TaskPageTemplate.kt",
                ).contains("acceptsDirectories = dropZone.acceptsDirectories")
            ) {
                add("TaskPageTemplate 未把 FileDropZoneSpec.acceptsDirectories 透传给 FileDropZone")
            }
            // 环 3：组件把开关交给展开内核（fallbackRejectText 同环锚——拒绝文案经展开层回退）
            if (!strippedSource(
                    "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/FileDropZone.kt",
                ).contains("expandDirectories = acceptsDirectories")
            ) {
                add("FileDropZone 未把 acceptsDirectories 交给 expandDroppedEntries（开关断链）")
            }
            // 环 4：展开内核声明在位（契约与上限见其 KDoc）
            if (!strippedSource(
                    "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/DropEntryExpansion.kt",
                ).contains("fun expandDroppedEntries")
            ) {
                add("DropEntryExpansion.kt 缺少 expandDroppedEntries 声明（内核文件被移走/改名，请同步本守护）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("目录拖入接线守护：opt-in 链路残缺：\n${missing.joinToString("\n")}")
        }
    }
}
