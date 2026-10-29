// 重设计批 8 · AG-05 会话历史入口脱离 inspector 守护（2026-10-02）：
// ListMode 主区（新建表单态）补「最近会话」卡——历史可达性不再绑死 inspector 开合。
// E 用例 = XA-24 聚合页子页密度档统一（NewFlowForm 顶层 spacedBy space16→space12）。
//
// 卡面 acceptance：
// 1. inspector 收起（或窄轨）时新建表单态可见最近 5 条会话并可点击进入；
// 2. 有活跃会话（ChatMode）时该卡不渲染；
// 3. 滚动契约不破：该卡在 NewFlowForm 自带 verticalScroll 的 Column 内（无 LazyColumn
//    无界风险——🔴 落位 = 卡进 NewFlowForm 内部（方案 A），外包 Column 会动 weight 链。
//
// 扫描口径：stripKotlinComments 剥注释后断言结构子串（行号无关——批 7.5 经验：
// 源码会继续演化，锚子串不锚行号）。
// 🔴 验收纪律：扫盘型守护会被 build cache 假绿（AGENTS.md「测试」节），
// 一律 --rerun-tasks --no-build-cache 验收。
package com.jiaoay.easy_dev_toolbox.page.quest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class FlowRecentCardGuardTest {

    /** A：NewFlowForm 签名含 recentFlows 数据源 + 三动作接线（零 VM 改动的消费面锚） */
    @Test
    fun `A new flow form signature carries recent flows data and actions`() {
        val code = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        listOf(
            "recentFlows: List<QuestStore.FlowSummary>" to "NewFlowForm 必须带 recentFlows 数据源参数（vm.flows 直通零 VM 改动）",
            "onOpenFlow: (String) -> Unit" to "NewFlowForm 必须带 onOpenFlow 动作参数",
            "onRenameFlow: (String, String) -> Unit" to "NewFlowForm 必须带 onRenameFlow 动作参数（id + 新名）",
            "onDeleteFlow: (String) -> Unit" to "NewFlowForm 必须带 onDeleteFlow 动作参数",
        ).forEach { (needle, why) ->
            assertTrue(needle in code, "FlowPage.kt 丢失「$needle」——$why")
        }
        // 调用点接线：FlowPageContent 的 active==null 分支必须真传 vm 数据与动作
        assertTrue("recentFlows = flows" in code, "调用点必须真传 vm.flows（数据面接线——光有参数不算）")
        assertTrue("onOpenFlow = vm::openFlow" in code, "调用点必须真接 vm::openFlow（点击进入会话）")
        assertTrue("onRenameFlow = vm::renameFlow" in code, "调用点必须真接 vm::renameFlow")
        assertTrue("onDeleteFlow = vm::deleteFlow" in code, "调用点必须真接 vm::deleteFlow")
    }

    /** B：RecentFlowsCard 结构锚——SectionCard collapsible 默认收起 + take(5) + 行渲染 + GhostCard 空态 */
    @Test
    fun `B recent flows card structure anchors`() {
        val code = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        listOf(
            "title = \"最近会话\"" to "卡标题「最近会话」",
            "collapsible = true" to "卡必须可折叠（卡面 proposal）",
            "initialExpanded = false" to "卡必须默认收起（不抢新建表单视觉——卡面 proposal）",
            "flows.take(RECENT_FLOWS_VISIBLE)" to "只显最近 5 条（take(5)——vm.flows updated_at 倒序即最近）",
            "HoverableRow(" to "行渲染必须 HoverableRow（hover 动作行配方——FlowSidePanelContent 同款）",
            "GhostCard {" to "空 flows 必须渲染 GhostCard（DS-08 批 3 落件——本卡即首个消费方）",
            "历史会话在右侧栏（inspector）可查全部" to "空态提示文案（引导去侧栏查全量）",
            "vm.openFlow" to "侧栏槽位行点击仍是 vm::openFlow（本卡不动侧栏）",
        ).forEach { (needle, why) ->
            assertTrue(needle in code, "FlowPage.kt 丢失 $why——「$needle」")
        }
    }

    /**
     * C：落位契约——RecentFlowsCard( 调用必须在 NewFlowForm 函数体内（卡进内部 =
     * 卡随表单同滚的滚动契约；外包 Column 动 weight 链即回潮）。
     *
     * 实现方式：断言 NewFlowForm 函数体切片（从签名到下一顶层 `@Composable`）内含
     * `RecentFlowsCard(` 调用；RecentFlowsCard 定义在 NewFlowForm 之后（文件内独立
     * private composable）。
     */
    @Test
    fun `C recent flows card call sits inside new flow form body`() {
        val code = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        val formStart = code.indexOf("private fun NewFlowForm(")
        assertTrue(formStart >= 0, "NewFlowForm 定义缺失（重命名/移动后同步本守护）")
        // NewFlowForm 函数体 = 签名起至下一个顶层 private fun（RecentFlowsCard）声明
        val nextDecl = code.indexOf("private fun RecentFlowsCard(", formStart)
        assertTrue(nextDecl > formStart, "RecentFlowsCard 定义缺失（重命名/移动后同步本守护）")
        val formBody = code.substring(formStart, nextDecl)
        if ("RecentFlowsCard(" !in formBody) {
            fail(
                "RecentFlowsCard( 调用必须在 NewFlowForm 函数体内（卡面原文：该卡在 " +
                    "NewFlowForm 自带 verticalScroll 的 Column 内）——外包 Column 会动 weight 链" +
                    "破坏滚动契约（AG-05 落位回潮）。",
            )
        }
    }

    /**
     * D：ChatMode 不渲染——RecentFlowsCard 只出现在 NewFlowForm 体内（active==null
     * 分支），FlowChat（ChatMode 主区）函数体内零 RecentFlowsCard 引用。
     */
    @Test
    fun `D chat mode body has zero recent flows card`() {
        val code = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        val chatStart = code.indexOf("private fun FlowChat(")
        assertTrue(chatStart >= 0, "FlowChat 定义缺失（重命名/移动后同步本守护）")
        val chatBody = code.substring(chatStart)
        if ("RecentFlowsCard(" in chatBody) {
            fail(
                "FlowChat（ChatMode 主区）体内不得渲染 RecentFlowsCard——活跃会话已占屏，" +
                    "「最近会话」卡只在新建表单态（active==null 分支）呈现（AG-05 acceptance 2）。",
            )
        }
    }

    /**
     * E（XA-24 · 重设计批 8）：NewFlowForm 顶层 spacedBy = space12——聚合页（智能体
     * 中心）子页密度档统一（Distill/Voice 两签已 12；会话签 16 是历史漂移）。
     *
     * 🔴 全文件 grep space16 会命中别处（不存在的别处也已消除）——断言限定 NewFlowForm
     * 函数体切片：「开启新会话」标题所在的顶层 Column spacedBy 值 = space12
     * （侦察报告测试落点原文：荐切片断言）。
     */
    @Test
    fun `E new flow form top level spacing is space12`() {
        val code = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        val formStart = code.indexOf("private fun NewFlowForm(")
        assertTrue(formStart >= 0, "NewFlowForm 定义缺失（重命名/移动后同步本守护）")
        val nextDecl = code.indexOf("private fun RecentFlowsCard(", formStart)
        val formBody = code.substring(formStart, nextDecl)
        if ("Arrangement.spacedBy(Dimens.space12)" !in formBody) {
            fail(
                "NewFlowForm 顶层 Column 必须 spacedBy(Dimens.space12)——聚合页四签密度档" +
                    "统一（XA-24：Distill/Voice 已 12，会话签 16 = 历史漂移回潮）。",
            )
        }
        assertTrue(
            "Arrangement.spacedBy(Dimens.space16)" !in formBody,
            "NewFlowForm 顶层不得残留 space16（XA-24 密度档回潮）",
        )
    }

    // ── 源码锚扫盘基建（FlowNamingGuardTest 同款配方） ──

    private fun sourceFile(relativePath: String): File {
        val file = File(locateProjectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后请同步本测试路径）")
        return file
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    /**
     * 剥除行注释（//）与块注释（/** */、/* */），保留代码与字符串字面量内容——字符串感知
     * （`"..."`/`"""..."""` 各自成态，字符串态内不识别注释起始），配方照抄
     * KnowledgeUriPrefixSingleSourceGuardTest.stripKotlinComments（UiArchitectureGuardTest 同款）。
     */
    private fun stripKotlinComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inRawString -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = false
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                inString -> when {
                    c == '\\' && i + 1 < source.length -> {
                        sb.append(c).append(source[i + 1])
                        i += 2
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    c == '\n' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                else -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                        i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                        while (i < source.length && source[i] != '\n') i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                        i += 2
                        while (i < source.length) {
                            if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                                i += 2
                                break
                            }
                            if (source[i] == '\n') sb.append('\n')
                            i++
                        }
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val FLOW_PAGE =
            "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowPage.kt"
    }
}
