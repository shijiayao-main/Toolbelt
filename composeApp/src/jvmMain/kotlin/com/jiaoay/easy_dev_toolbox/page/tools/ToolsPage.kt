package com.jiaoay.easy_dev_toolbox.page.tools

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.page.archive.ArchivePage
import com.jiaoay.easy_dev_toolbox.page.filesplit.FileSplitterPage
import com.jiaoay.easy_dev_toolbox.page.logtag.LogTagFilterPage
import com.jiaoay.easy_dev_toolbox.page.timestamp.TimestampPageContent
import com.jiaoay.easy_dev_toolbox.page.xlog.XLogDecoderPage
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffPageContent
import com.jiaoay.easy_dev_toolbox.page.tools.base64.Base64ExtractorPanel
import com.jiaoay.easy_dev_toolbox.page.tools.color.ColorConverterPanel
import com.jiaoay.easy_dev_toolbox.page.tools.hash.HashChecksumPanel
import com.jiaoay.easy_dev_toolbox.page.tools.json.JsonToolPanel
import com.jiaoay.easy_dev_toolbox.page.tools.jwt.JwtDecoderPanel
import com.jiaoay.easy_dev_toolbox.page.tools.regex.RegexTesterPanel
import com.jiaoay.easy_dev_toolbox.page.tools.ulid.UlidPanel
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.currentWindowSizeClass
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * 工具聚合页：十三签（XLog 解密 / Tag 过滤 / 文件分割 / 时间戳 / 解压 /
 * 日志对比 / 正则测试 / JSON 工具 / Base64 提取 / 哈希校验 / 颜色转换 /
 * JWT 解析 / ULID 工具）收拢为页内 [SegmentedControl] 子签——dock 第 5 图标常驻直达。
 *
 * ## 动机
 * 五件套 navItem 退役、「palette 承接一切」后——「解压」这类零门槛高频工具
 * 对新用户完全不可发现（只能 Ctrl+K），XLog 私钥引导文案还指向一个导航上不存在的
 * 页面。本页补上**常驻可达面**，不回到 14 页等权导航的老路：palette 深入模式与
 * 带参命令全部照旧，五件套自身 navItem 仍退役。「日志对比」「正则测试」「JSON
 * 工具」「Base64 提取」「哈希校验」「颜色转换」「JWT 解析」「ULID 工具」为聚合页原生签
 * （双文件 diff、沙盒、格式化与行内嗅探、哈希校验和、颜色三向互转、JWT 明文
 * 展开、ULID 生成与时间戳反解，无退役散页、无 palette 深入模式候选——可达面
 * 先保本页子签）。
 *
 * ## 结构（AgentCenterPage 聚合先例同配方）
 * - [PageScaffold] 吸顶头 + [SegmentedControl] 十三签 + when 分发；
 * - 子页渲染四胞胎 **embedded 无头形态**（TaskPageTemplate 直出内容段）与时间戳
 *   内容体（[TimestampPageContent]）；子签选中态初值 =「预选槽 → 持久值」合成
 *   （见函数内注释）、切签零动画（PageHomeContent 同口径）；
 * - 滚动责任在本页 content 容器（verticalScroll 包装）——embedded 四胞胎内部
 *   LazyColumn 以 heightIn 有界、嵌套安全；
 * - 🔴 AnimatedContent 单根纪律：每分支包单根 Column（content lambda 多根发射
 *   会被 CMP 1.12 叠放同位——「工具内部重叠」根因）。
 */
@Composable
fun ToolsPage(
    appViewModel: AppViewModel = AppViewModel.getViewModel(),
) {
    // 子签 index：0=XLog 解密 / 1=Tag 过滤 / 2=文件分割 / 3=时间戳 / 4=解压 / 5=日志对比 / 6=正则测试 / 7=JSON 工具 / 8=Base64 提取 / 9=哈希校验 / 10=颜色转换 / 11=JWT 解析 / 12=ULID 工具
    // rememberSaveable → remember——子签初值改由「预选槽 →
    // 持久值」异步合成（LaunchedEffect 读一次），saveable 的进程内恢复态与合成初值
    // 竞态（saveable 恢复覆盖合成值，预选/持久语义失效）。
    var tab by remember { mutableStateOf(0) }

    // 初值合成（预选槽 + 持久值，两卡同一写入点合成序）：
    // ① 预选槽非空 → 槽值优先（palette 深入模式带子签载荷跳本页）；
    // ② 槽空 → 持久值（跨启动记忆）；坏值（不在 0..[ToolsConfigStore.TAB_MAX_INDEX]，如手工改 13）回 0
    //   ——验收口径「越界回第 1 签」，刻意不用 coerceIn（coerceIn(13)=12 落第 13
    //   签，坏值是脏数据回默认而非就近夹取）。
    // 初帧闪第 1 签一瞬可接受（AppDataStore 是挂起 API，取非阻塞读配方）。
    LaunchedEffect(Unit) {
        val pending = appViewModel.consumePendingToolsTab()
        val initial = when {
            pending != null -> if (pending in 0..ToolsConfigStore.TAB_MAX_INDEX) pending else 0
            else -> ToolsConfigStore.getSelectedTab().let { v ->
                if (v in 0..ToolsConfigStore.TAB_MAX_INDEX) v else 0
            }
        }
        tab = initial
    }

    // 🔴 在页响应缝：palette 是浮层，用户停在工具页时
    //    ToolsPage 保持组合——LaunchedEffect(Unit) 已执行过不会重跑，此时经 palette
    //    深入模式写入的预选槽值将静默失效并滞留（离页返页时被延迟消费成旧载荷误跳、
    //    覆盖用户持久选择）。订阅槽流（StateFlow 非空即消费）保证在页期间也能响应
    //    预选载荷；初值合成序不变（首次仍由上面 LaunchedEffect 承担，本 effect
    //    只接「页面已组合后的新载荷」——槽被初值合成消费后已清空，天然不双发）。
    LaunchedEffect(appViewModel) {
        appViewModel.pendingToolsTab
            .filterNotNull()
            .collect { index ->
                // 读即清（consume 同款语义）：防止离页返页时 LaunchedEffect(Unit)
                // 初值合成重跑读到已消费过的旧载荷。
                appViewModel.consumePendingToolsTab()
                tab = if (index in 0..ToolsConfigStore.TAB_MAX_INDEX) index else 0
            }
    }

    // 持久回写：切签低频直写（无 debounce，last_page 同款口径）——
    // rememberCoroutineScope 承接挂起 IO（onSelect 回调非组合上下文）。
    val scope = rememberCoroutineScope()
    val onSelectTab: (Int) -> Unit = { index ->
        tab = index
        scope.launch { ToolsConfigStore.setSelectedTab(index) }
    }

    PageScaffold(title = "工具", headerSticky = true) {
        // Compact 档（< 1024dp）十三签短名——窄窗等分布局下
        // 全名截断成「XLog 解…」辨识度崩；短名两字等长无截断。
        // 显示层替换（index 序不变、与预选槽/持久值状态面零交集）；短名表收本页
        // 私有常量不进 ToolPageLabels（检索别名与显示名分离——窗宽形态
        // 变体非页名）。
        val tabOptions = if (currentWindowSizeClass() == WindowSizeClass.Compact) {
            TOOLS_TAB_LABELS_COMPACT
        } else {
            TOOLS_TAB_LABELS_FULL
        }
        SegmentedControl(
            options = tabOptions,
            selectedIndex = tab,
            onSelect = onSelectTab,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 子签切换 AnimatedContent（LarkBridgePage 同款——
            // 五件套内容只进不出同帧交接，滚动容器在外层共享不受双帧影响）
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(animationSpec = AppMotion.standard()) +
                        androidx.compose.animation.slideInVertically(animationSpec = AppMotion.standard()) { it / 16 })
                        .togetherWith(androidx.compose.animation.fadeOut(animationSpec = AppMotion.fast()))
                },
                label = "toolsTab",
            ) { currentTab ->
                // 🔴 AnimatedContent 单根纪律（CMP 1.12 AnimatedEnterExitMeasurePolicy
                //    尾行 place(0,0)：content lambda 多根发射全部叠放同位——
                //    「工具内部重叠」真机事故根因）：每分支包单根 Column——子页自身多根
                //    直出（embedded 四胞胎 Body / TimestampPageContent）一律收拢为单根，
                //    根间 breathing 由本 Column spacedBy 提供（12px——Dense 档同值）。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space12),
                ) {
                    when (currentTab) {
                        0 -> XLogDecoderPage(embedded = true)
                        1 -> LogTagFilterPage(embedded = true)
                        2 -> FileSplitterPage(embedded = true)
                        3 -> TimestampPageContent()
                        4 -> ArchivePage(embedded = true)
                        5 -> LogDiffPageContent()
                        6 -> RegexTesterPanel()
                        7 -> JsonToolPanel()
                        8 -> Base64ExtractorPanel()
                        9 -> HashChecksumPanel()
                        10 -> ColorConverterPanel()
                        11 -> JwtDecoderPanel()
                        else -> UlidPanel()
                    }
                }
            }
        }
    }
}

/** 十三签全名表（Medium+ 档；index 序 = 预选槽/持久值状态面锚序，恒不变） */
private val TOOLS_TAB_LABELS_FULL =
    listOf("XLog 解密", "Tag 过滤", "文件分割", "时间戳", "解压", "日志对比", "正则测试", "JSON 工具", "Base64 提取", "哈希校验", "颜色转换", "JWT 解析", "ULID 工具")

/**
 * 十三签短名表（Compact 档 < 1024dp）：中文短名两字等长，「JSON」「Base64」「JWT」
 * 「ULID」为 ASCII 短名（视觉宽约两个/三个/四个汉字）；「时间」短名 vs ToolPageLabels「时间戳」：
 * 检索别名与显示名分离，窗宽形态变体非页名，不进 ToolPageLabels。
 */
private val TOOLS_TAB_LABELS_COMPACT =
    listOf("解密", "过滤", "分割", "时间", "解压", "对比", "正则", "JSON", "Base64", "哈希", "颜色", "JWT", "ULID")
