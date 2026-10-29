package com.jiaoay.easy_dev_toolbox.page.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SpaceBar 空态收口 EmptyStateView 守护：
 *
 * ui-style-guide §6.2「空态一律优先 EmptyStateView」——裸 Text 空态（无图标无层次）
 * 收口到 :ui 共享 EmptyStateView（图标 + 文案 + 学习提示四要素）。
 *
 * 验收面（源码锚——composeApp 有 skiko 场景基建但本断言面是配方级，扫描型锚同
 * GhostCardTest 先例；跑测带 --rerun-tasks --no-build-cache 防扫盘假绿）：
 * 1. 空态 item 走 EmptyStateView（组件替换到位——裸「暂无调查」Text 直渲染即红）；
 * 2. text 保留全句「暂无调查——Ctrl+K 召唤工具，或点右上 + 新建空间」——
 *    剥成「暂无调查」会丢动作引导（动作引导是空态可用性要件）；
 * 3. learningHint = SPACE_EMPTY_HINT 常量原样（SpaceBarCopyTest 断言锚零破坏——
 *    常量被内联成字面量即双源漂移）；
 * 4. fillParent = false（窄栏 LazyColumn item 内不撑满，居中布局）。
 */
class SpaceBarEmptyStateGuardTest {

    @Test
    fun `empty state renders EmptyStateView with full sentence and hint constant`() {
        val emptyBlock = emptyStateBlock(stripComments = true)
        assertTrue(
            "EmptyStateView(" in emptyBlock,
            "SpaceBar 空态必须走 EmptyStateView（XA-18②：§6.2 空态唯一语言——裸 Text 收口）",
        )
        assertTrue(
            "暂无调查——Ctrl+K 召唤工具，或点右上 + 新建空间" in emptyBlock,
            "空态主文案必须保留全句（Ctrl+K 召唤 + 右上 + 新建两条动作引导不丢）",
        )
        assertTrue(
            "learningHint = SPACE_EMPTY_HINT" in emptyBlock,
            "学习提示必须挂 SPACE_EMPTY_HINT 常量（SpaceBarCopyTest 断言锚——内联字面量即双源漂移）",
        )
        assertTrue(
            "fillParent = false" in emptyBlock,
            "窄栏空态必须 fillParent = false（LazyColumn item 内不撑满，居中布局）",
        )
    }

    @Test
    fun `bare empty text no longer renders as raw Text`() {
        // 收口彻底性：旧裸 Text 块（bodySmall + onSurfaceFaint 双 Text 直排）不得残留
        val emptyBlock = emptyStateBlock(stripComments = false)
        assertTrue(
            "style = MaterialTheme.typography.bodySmall" !in emptyBlock,
            "空态块不得残留裸 Text 直渲染（bodySmall 直排 = 收口不彻底）",
        )
    }

    /** 空态块切片（if (spaces.isEmpty()) → 归档区注释之间） */
    private fun emptyStateBlock(stripComments: Boolean): String {
        val source = spaceBarSource()
        val block = source
            .substringAfter("if (spaces.isEmpty())")
            .substringBefore("// 归档区")
        if (!stripComments) return block
        // 剥行注释——防 KDoc/行注自身的说明文字背书假绿（GhostCardTest 同款纪律）
        return block.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    private fun spaceBarSource(): String {
        val file = File(locateProjectRoot(), SPACE_BAR_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$SPACE_BAR_PATH（重命名/移动后请同步本守护）")
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
        error("未定位项目根（settings.gradle.kts）——composeApp:jvmTest 工作目录异常")
    }

    private companion object {
        const val SPACE_BAR_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/SpaceBar.kt"
    }
}
