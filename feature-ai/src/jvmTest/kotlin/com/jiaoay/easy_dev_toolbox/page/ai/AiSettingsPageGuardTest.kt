package com.jiaoay.easy_dev_toolbox.page.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AI 设置页源码锚守护（重设计批 6 · A2 簇 · 2026-10-01）：
 *
 * SYS-16 / SYS-21 / SYS-02 / XA-09 / SYS-01（AI 侧）五卡同文件（AiSettingsPage.kt）
 * 的源码锚收口——可断言项（明文切换/画像行/记忆对话框/忙态按钮/双栏挂入的装配要素）
 * 逐卡落锚，逐卡 commit 分批追加（冲突矩阵：同簇测试文件共用，随簇同 agent 串行）。
 *
 * 配方先例 = McpExposePortConfirmTest（源码切片锚）+ PillLabelTest（剥注释防自咬）。
 */
class AiSettingsPageGuardTest {

    // ── SYS-16 · API Key 明文切换 + 复制 ──────────────────────────────────────

    @Test
    fun `sys16 api key field carries visibility toggle and copy`() {
        val text = pageSource()
        listOf(
            "visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation()" to
                "明文/密文必须随 showKey 条件切换（SYS-16 验收：点击眼睛图标在明文/密文间切换）",
            "MaterialSymbols.VISIBILITY_OFF else MaterialSymbols.VISIBILITY" to
                "眼睛图标必须 VISIBILITY/VISIBILITY_OFF 变体对（密文态=显示动作、明文态=隐藏动作）",
            "if (showKey) \"隐藏 API Key\" else \"显示 API Key\"" to
                "眼睛钮 contentDescription 必须中文无障碍描述且随 showKey 双态翻转（DL §4.3；隐藏/显示动作与图标同步）",
            "CopyIconButton(" to
                "API Key 字段必须挂复制钮（SYS-16 验收：复制图标可将 Key 原文复制到剪贴板）",
            "textProvider = { config.apiKey }" to
                "复制内容必须是 Key 原文（config.apiKey 现值——非脱敏串）",
            "trailingIcon = {" to
                "两钮必须进 CommitTextField trailingIcon 槽（字段级行内动作，M3 原生装饰位）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in text,
                "AiSettingsPage 丢失 SYS-16 要素「$needle」——$why",
            )
        }
    }

    // ── SYS-21 · 性格模式档画像行（人话描述 + prompt 折叠） ────────────────────

    @Test
    fun `sys21 style card renders blurb line and folds prompt preview`() {
        val text = pageSource()
        listOf(
            "it.blurb.isNotBlank()" to
                "画像行必须以 blurb 非空为门控（DEFAULT/CUSTOM 空串不渲染画像行）",
            "style = MaterialTheme.typography.bodyMedium" to
                "画像行字档必须 bodyMedium（预览稿 sys-s04 配方：一句话画像非标签非正文）",
            "LocalToolboxSurfaceColors.current.onSurfaceMid" to
                "画像行色必须 onSurfaceMid（预览稿 sys-s04 配方：弱于正文强于辅助字）",
            "if (showPrompt) \"收起提示词\" else \"查看提示词\"" to
                "prompt 预览必须折叠为「查看提示词」钮按需展开（SYS-21 验收：prompt 原文折叠为按需查看；蜂群回归 2026-10-06 P2 对稿 system.html:724 形态 = 行尾 chipbtn 文字钮 + 复制 ic-btn）",
            "if (selectedStyle != null && selectedStyle.prompt.isNotBlank() && showPrompt)" to
                "展开态门控（showPrompt 局部态 toggle——蜂群回归 2026-10-06 P2 对稿：展开块落画像行下方独立块）",
            "CopyIconButton(" to
                "画像行行尾必须挂复制提示词 ic-btn（system.html:724 ctl 内 ic-btn 复制逐字；CopyIconButton 即 ic-btn 配方）",
            "PresetPreview(text = selectedStyle.prompt)" to
                "展开后渲染现 PresetPreview 形态（只读可复制零改动复用）",
            "Dimens.configModifiedBarWidth" to
                "画像行必须带左缘 3px ai 竖条（system.html:724 box-shadow:inset 3px 0 0 0 var(--ai) 逐字；宽走 token）",
            "可参考内置档的画像与写法" to
                "自定义档引导语必须引用默认档画像作参考（SYS-21 ③：引导语引用画像）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in text,
                "AiSettingsPage 丢失 SYS-21 要素「$needle」——$why",
            )
        }
        // 🔴 安全约束卡的 PresetPreview 不在本卡改动面（卡面只点性格档）——
        // 性格卡区恰好一处 PresetPreview（安全卡形态不动）
        val styleCardStart = text.indexOf("title = \"性格模式\"")
        val safetyCardStart = text.indexOf("title = \"安全约束\"")
        assertTrue(styleCardStart in 0 until safetyCardStart, "性格卡/安全卡锚序异常")
        val styleCardBlock = text.substring(styleCardStart, safetyCardStart)
        assertEquals(
            1,
            styleCardBlock.split("PresetPreview(").size - 1,
            "性格卡内 PresetPreview 必须恰好一处（安全卡形态不在本卡改动面）",
        )
    }

    // ── SYS-02 · 长期记忆管理面板化（四动作平铺独立对话框） ────────────────────

    @Test
    fun `sys02 dropdownmenu container path is retired from memory domain`() {
        val text = stripComments(pageSource())
        // 🔴 只扫 AiSettingsPage.kt（ModelIdDropdown.kt 的 ExposedDropdownMenuBox
        // 是另一文件另一语义，不在扫描面）
        listOf(
            "DropdownMenu(" to "记忆域 DropdownMenu 列表容器必须退役（SYS-02 验收：DropdownMenu 代码路径删除）",
            "DropdownMenuItem(" to "记忆域 DropdownMenuItem 同步退役",
            "memoryViewTarget" to "三层藏匿链中间层（查看弹窗）必须删除——查看语义并入对话框右列详情",
        ).forEach { (needle, why) ->
            assertTrue(
                needle !in text,
                "AiSettingsPage 仍持 SYS-02 退役要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `sys02 memory manager dialog carries four actions`() {
        val text = pageSource()
        // 双入口保留改向：页头图标 + 管理钮（+ 卡内「查看」）都开 MemoryManagerDialog
        assertTrue(
            text.split("refreshMemorySnapshot()").size >= 4,
            "记忆面板入口（页头图标/卡内查看/管理全部记忆）必须保留 refreshMemorySnapshot 刷新语义（打开瞬间快照）",
        )
        val dialogAnchor = text.indexOf("private fun MemoryManagerDialog(")
        assertTrue(dialogAnchor >= 0, "MemoryManagerDialog 新件必须落 AiSettingsPage.kt 同文件（卡面 scope 限一文件）")
        val dialogBlock = text.substring(dialogAnchor, text.length)
        listOf(
            "Dimens.memoryDialogHeightMax" to "内容区必须 heightIn(memoryDialogHeightMax) 封顶（旧 DropdownMenu 无上限病根）",
            "MaterialSymbols.DELETE" to "列表行尾必须删除钮直达（蜂群回归 2026-10-06 P2 对稿 system.html:741-743：行尾 ic-btn 恒显）",
            "\"删除记忆\"" to "删除钮 contentDescription 必须中文（DL §4.3）",
            "onViewEdit(entry)" to "右列题行「编辑」chipbtn 直达（编辑弹窗现实现沿用）",
            "onDelete(entry)" to "行尾删除钮与题行「删除」chipbtn 直达",
            "\"清空全部记忆\"" to "清空动作必须沉底对话框内（error 钮；AppConfirmDialog 二次确认在宿主侧）",
            "HoverableRow(" to "列表行壳必须 HoverableRow（行结构沿用原卡内概览）",
            ".borderStart()" to "右列详情必须 borderStart 分隔线（预览稿 sys-s04 双栏配方）",
            "MemoryManager.readEntry(memoryDir, entry.fileName)" to "详情正文必须 readEntry 现读（agent memory.* 同源读写语义不变）",
            "weight(1.35f)" to "双列比例必须 1fr/1.35fr（system.html:37 .mgr grid-template-columns 逐字——右列详情区稿面 1.35 份）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in dialogBlock,
                "MemoryManagerDialog 丢失 SYS-02 要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `sys02 delete and clear confirmations stay`() {
        val text = pageSource()
        // 清空/删除仍各有 AppConfirmDialog 二次确认（卡面验收第三项）
        assertTrue(
            "title = \"删除记忆\"" in text,
            "单条删除必须保留 AppConfirmDialog 二次确认",
        )
        assertTrue(
            "title = \"清空全部记忆\"" in text,
            "清空全部必须保留 AppConfirmDialog 二次确认",
        )
    }

    // ── XA-09（AI 侧）·「测试中」按钮 AppButton 化 ─────────────────────────────

    @Test
    fun `xa09 ai probe button uses AppButton busy pattern`() {
        val text = stripComments(pageSource())
        // AppButton 配方三要素（§4.7h：100ms–1s 唯一形态 = busy 圈 + 进行时措辞 + 置灰）
        assertTrue(
            "busy = probe is ConnectivityProbe.Running" in text,
            "测试连通性钮必须 AppButton(busy = Running)——行内圈 + busy 自动置灰",
        )
        assertTrue(
            "busyLabel = \"测试中…\"" in text,
            "busyLabel 必须「测试中…」单字符省略号（卡面文案修正面：三点变体退役）",
        )
        assertTrue(
            "kind = AppButtonKind.Hot" in text,
            "kind 必须 Hot（蜂群回归 2026-10-06 P2 对稿 system.html:553——「测试连通性」= " +
                "chipbtn hot 洗底胶囊；旧 Secondary 描边与稿不符）",
        )
        // 旧配方回潮即红：裸 OutlinedButton 文字切换 + 三点变体
        assertFalse(
            "\"测试中...\" " in text || "\"测试中...\"" in text,
            "「测试中...」三点变体不得回潮（文案修正面）",
        )
        assertTrue(
            "AppButton(" in text,
            "AI 设置页必须消费 AppButton（busy 态唯一规范件）",
        )
    }

    // ── SYS-01（AI 侧）· AI 设置页双栏化（四分类 + 六卡零删减 + 滚动责任迁移）────

    @Test
    fun `sys01 ai settings page mounts SettingsTwoPane with four categories`() {
        val text = pageSource()
        assertTrue(
            "SettingsTwoPane(sections = sections)" in text,
            "SYS-01：AiSettingsPage 必须挂 SettingsTwoPane（sections 传入——A1 组件本卡消费）。",
        )
        assertTrue(
            "SettingsPaneSection(" in text,
            "SYS-01：AiSettingsPage 必须构造 SettingsPaneSection 分类。",
        )
        // 四分类（sys-s03 设计主张原句：六卡收进四分类）
        listOf(
            "title = \"模型服务\"," to "首分类（进页初值——预览稿 system-d4 ② 演示语义申报）",
            "title = \"智能体\"," to "第二分类",
            "title = \"能力授权\"," to "第三分类",
            "title = \"长期记忆\"," to "第四分类",
        ).forEach { (needle, where) ->
            assertTrue(
                needle in text,
                "SYS-01：AI 设置四分（模型服务/智能体/能力授权/长期记忆）分类构造缺失「$needle」——$where。",
            )
        }
        // 分类图标 Material Symbols 蛇形名（预览稿 sys-s03 navrow SVG 同形直译：
        // 双矩形堆叠=DNS / 圆点网络=HUB / 斜笔+底线=EDIT / 三层同心圆=TARGET）
        listOf(
            "icon = MaterialSymbols.DNS" to "模型服务分类图标必须 DNS（预览稿双矩形堆叠同形）",
            "icon = MaterialSymbols.HUB" to "智能体分类图标必须 HUB（预览稿圆点网络同形）",
            "icon = MaterialSymbols.EDIT" to "能力授权分类图标必须 EDIT（预览稿斜笔+底线同形）",
            "icon = MaterialSymbols.TARGET" to "长期记忆分类图标必须 TARGET（预览稿三层同心圆同形）",
        ).forEach { (needle, why) ->
            assertTrue(needle in text, "SYS-01：$why")
        }
    }

    @Test
    fun `sys01 all six section cards survive inside pane sections`() {
        val text = pageSource()
        // 六卡零删减可达（内容零删减全项清单——分类归属按预览稿 sys-s03 设计主张）
        listOf(
            "title = \"AI 工具\"" to "模型服务分类（与模型服务卡同舱）",
            "title = \"模型服务\"" to "模型服务分类",
            "title = \"性格模式\"" to "智能体分类（与安全约束卡同舱）",
            "title = \"安全约束\"" to "智能体分类",
            "title = \"源码目录\"" to "能力授权分类（单卡）",
            "title = \"长期记忆\"" to "长期记忆分类（单卡）",
        ).forEach { (needle, where) ->
            assertTrue(
                needle in text,
                "SYS-01：六卡零删减——「$needle」缺失（应平移进$where）。",
            )
        }
        // 未配置态 Info 横幅留在 SettingsTwoPane 之外（页级状态非分类内容——预览稿
        // DOM 中 banner 位于 .pane 之前）
        val bannerAnchor = text.indexOf("StatusBannerStatus.Info")
        val paneAnchor = text.indexOf("SettingsTwoPane(sections = sections)")
        assertTrue(
            bannerAnchor in 0 until paneAnchor,
            "SYS-01：Info 横幅必须留在 SettingsTwoPane 之前（页级整行，不进分类）。",
        )
        // 五个对话框浮层不进分类（组合面在 content 尾部——渲染面与组合位置无关）
        assertTrue(
            text.indexOf("MemoryManagerDialog(") > paneAnchor,
            "SYS-01：记忆管理对话框必须在 SettingsTwoPane 之后组合（浮层不进分类）。",
        )
    }

    @Test
    fun `sys01 scroll responsibility migrates with wide gate and narrow fallback keeps page scroll`() {
        val text = pageSource()
        // 滚动责任迁移配方（A1 PageSettings 同款——两页语义对称）
        listOf(
            "headerSticky = wideWindow" to
                "双栏必须 headerSticky=true（content 有界高度，右舱局部滚生效）",
            "modifier = if (wideWindow) Modifier else Modifier.verticalScroll(state = scrollState)" to
                "整页滚动必须窄窗保留（现状整页滚）、Wide 拆除（右舱局部滚接管）",
            "val wideWindow = currentWindowSizeClass() == WindowSizeClass.Wide" to
                "双栏门控必须 Wide 档（>1760dp）——窄窗回落单列",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in text,
                "SYS-01：滚动责任迁移配方丢失「$needle」——$why。",
            )
        }
    }

    @Test
    fun `sys01 raw dp debt is collected into glassStrokeHairline token`() {
        val code = stripComments(pageSource())
        // 批 6 复核欠账收编（SYS-02 随卡带入的裸 1.dp——UiGuard RAW_DP 唯一红点，
        // 本卡顺手收编，零新增白名单）
        assertFalse(
            "1.dp" in code,
            "AiSettingsPage 不得回潮裸 1.dp 字面量（分隔线描边走 Dimens.glassStrokeHairline token）。",
        )
        assertTrue(
            "strokeWidth = Dimens.glassStrokeHairline.toPx()" in code,
            "MemoryManagerDialog 分隔线描边必须消费 glassStrokeHairline（1dp 发丝档，" +
                "ModelIdDropdown BorderStroke 同款先例）。",
        )
    }

    /** 剥注释（防 KDoc 里提及退役要素自咬——配置说明文字保留） */
    private fun stripComments(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') append('\n')
                        i++
                    }
                    i += 2
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    // ── 源码锚工具（缺失即红——重命名后须同步） ──────────────────────────────

    private fun pageSource(): String {
        val root = locateProjectRoot()
        val file = File(root, PAGE_REL_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PAGE_REL_PATH（重命名/移动后请同步本守护）")
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

    private companion object {
        const val PAGE_REL_PATH =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiSettingsPage.kt"
    }
}
