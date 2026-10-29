package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.page.larkbridge.GrantProbe
import com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.BridgeToolRegistry
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.rememberCopyFeedbackState
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.NativeToolGrant

/**
 * 机器人主卡（agent-first 身份面；结构真源登记文件见 docs/architecture.md「注释治理」节）。
 */
@Composable
fun BotIdentityCard(
    config: BridgeConfig,
    issues: Map<String, String>,
    onCommit: (BridgeField<Any?>, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BotCardContainer(modifier = modifier) {
        // 头部行：标题 + 右上角语义 pill（同 EvidenceTimeline 紫块头的 aiAccent 配方）
        BotCardHeader(
            title = "机器人主卡",
            pillText = "本地 AI · agent-first",
            pillContainer = LocalEasyToolboxColors.current.aiAccentContainer,
            pillContent = LocalEasyToolboxColors.current.aiAccent,
        )
        // 身份字段：P5FieldGroups.BOT_IDENTITY 三个（复用配置编辑器的 FieldRow 共用渲染器——
        // 同包 internal 直用；字段行配方与 FieldColumn 完全一致：space8 列 + issues 回显，
        // modified 判定同源 P5FieldGroups.isModified）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            P5FieldGroups.BOT_IDENTITY_FIELDS.forEach { field ->
                @Suppress("UNCHECKED_CAST")
                val typed = field as BridgeField<Any?>
                BotFieldRow(
                    field = typed,
                    config = config,
                    issue = issues[field.id],
                    onCommit = { value -> onCommit(typed, value) },
                )
            }
        }
    }
}

/**
 * 能力清单区：小节标题（含工具计数）+ 静态工具名 pill 流 + 跨会话 scope 验证区块 + 一句作用域说明。
 * scope 验证区块结构：六行 ScopeVerifyRow（勾选面已退役，只剩 scope 验证 + 后台三件套）。
 * 零白名单：间距 [Dimens]、圆角 [AppShapes]、透明度 [AppAlpha]、无动画（展开为条件渲染）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotCapabilityCard(
    modifier: Modifier = Modifier,
    /** 配置草稿（testChatId/region/appId 的数据源；探测与深链从此现读）。 */
    config: BridgeConfig,
    /**
     * scope 三态验证状态（grant → state；运行时观察值，VM/页面侧 remember 持有，不落盘）。
     * 缺项 = [GrantProbe.VerifyState.NotVerified]。
     */
    verifyStates: Map<NativeToolGrant, GrantProbe.VerifyState>,
    /** 点「验证」按钮：页面侧发探测（VM 经 store.probeNativeGrant 窄口），结果回写 verifyStates。第二参 = 读文档两项的探测链接（用户粘贴，其余 grant 忽略）。 */
    onVerify: (NativeToolGrant, String) -> Unit,
    /** 桥接是否在跑（false = 验证按钮置灰 + 「桥接未运行」提示；红线 2）。 */
    bridgeRunning: Boolean,
) {
    BotCardContainer(modifier = modifier) {
        CapabilityPills(
            config = config,
            verifyStates = verifyStates,
            onVerify = onVerify,
            bridgeRunning = bridgeRunning,
        )
    }
}

/** 主卡容器：tonal 卡直用 TonalCard（头部行自绘以容纳 pill 标签，不需要 SectionCard 的 title 槽）。 */
@Composable
private fun BotCardContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    com.jiaoay.easy_dev_toolbox.widget.TonalCard(
        modifier = modifier.fillMaxWidth(),
        contentSpacing = Dimens.space12,
    ) {
        content()
    }
}


/**
 * 头部行：标题 + 右上角语义 pill（AppShapes.capsule）。
 *
 * (title, pillText) 两参参数化：姊妹卡 BotCapabilityCard 复用同配方头部（防第二份头部漂移）。
 *
 * 色对同样由调用方指定：机器人主卡 aiAccent 紫（AI 身份面），能力中心中性灰
 * （.pill.mute 恒中性灰）——共享头不挪用 aiAccent 槽，两卡可靠色区分语义。
 */
@Composable
private fun BotCardHeader(
    title: String,
    pillText: String,
    pillContainer: Color,
    pillContent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        PillLabel(
            text = pillText,
            container = pillContainer,
            contentColor = pillContent,
            // Medium 字重经 style 参数保留（PillLabel 像素级等价——字重不丢）
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun BotFieldRow(
    field: BridgeField<Any?>,
    config: BridgeConfig,
    issue: String?,
    onCommit: (Any?) -> Unit,
) {
    FieldRow(
        field = field,
        config = config,
        issue = issue,
        modified = P5FieldGroups.isModified(field, config),
        onCommit = onCommit,
    )
}
/**
 * 能力清单区：小节标题（含工具计数）+ 静态工具名 pill 流 + 跨会话 scope 验证区块 + 一句作用域说明。
 *
 * scope 验证区块结构：小节标题 + 六行 ScopeVerifyRow +「复制全部所需权限」底部按钮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityPills(
    config: BridgeConfig,
    verifyStates: Map<NativeToolGrant, GrantProbe.VerifyState>,
    onVerify: (NativeToolGrant, String) -> Unit,
    bridgeRunning: Boolean,
) {
    val tools = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS
    // 工具名 pill 灰档底色走 surface slot（.pill.mute：onSurfaceVariant 字 / surfaceDeep 底）
    val surfaceColors = LocalToolboxSurfaceColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        // 头部行进本组合（tools 计数在此层现读）——「能力中心」+ 灰 pill「常备 N · 跨会话 6」，
        // 与姊妹卡 BotIdentityCard 同配方（BotCardHeader 参数化单源），形态一致
        BotCardHeader(
            title = "能力中心",
            pillText = "常备 ${tools.size} · 跨会话 ${NativeToolGrant.entries.size}",
            // 中性灰 .pill.mute，非 aiAccent 紫
            pillContainer = surfaceColors.surfaceDeep,
            pillContent = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "常备工具（${tools.size} 项）",
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            tools.forEach { toolName ->
                PillLabel(
                    text = toolName,
                    container = surfaceColors.surfaceDeep, // 中性灰档 .pill.mute（onSurfaceVariant 字/surfaceDeep 底；bgSegTrack 属分段轨道 token 不得挪用）
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // ── 跨会话能力 scope 验证区块（L1/L2 恒注册；唯一授权闸 = 飞书后台 scope）──
        L2ScopeVerifySection(
            config = config,
            verifyStates = verifyStates,
            onVerify = onVerify,
            bridgeRunning = bridgeRunning,
        )
        Text(
            text = "文件类工具（解密/过滤/分割/取证等）按消息引用的文件动态注册，此处不列。" +
                "本机诊断 / 设备观测 / 工作区能力恒启用——高风险动作按「信任用户」名单放行或走确认卡。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}



/**
 * 跨会话能力 scope 验证区块：小节标题 + 六行 ScopeVerifyRow + 复制全部按钮。
 * 六行 = 六项跨会话能力，每行只有「scope 复制 + 后台三件套 + 验证」——
 * 勾选面（Checkbox/总开关/inline 二选一）已随 `agent.nativeTools` 配置段退役。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun L2ScopeVerifySection(
    config: BridgeConfig,
    verifyStates: Map<NativeToolGrant, GrantProbe.VerifyState>,
    onVerify: (NativeToolGrant, String) -> Unit,
    bridgeRunning: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Text(
            text = "跨会话能力（恒启用——唯一授权闸在飞书后台 scope）",
            style = MaterialTheme.typography.titleSmall,
        )
        NativeToolGrant.entries.forEach { grant ->
            ScopeVerifyRow(
                grant = grant,
                verifyState = verifyStates[grant] ?: GrantProbe.VerifyState.NotVerified,
                config = config,
                onVerify = onVerify,
                bridgeRunning = bridgeRunning,
            )
        }
        // 底部「复制全部所需权限」：六项 scope 去重合并复制
        CopyAllScopesButton(NativeToolGrant.entries.map { it.name }.toSet())
    }
}

/**
 * 单行 scope 验证行（ScopeVerifyRow，六行全文案）：
 * 主行 = 中文名 + 一句人话 + scope hint + 状态 pill；行内展开：
 * 后台三件套（复制 scope / 打开权限页 / 发版提示）+ 探测链接输入（读文档两项）+ 验证按钮 + 引导。
 */
@Composable
private fun ScopeVerifyRow(
    grant: NativeToolGrant,
    verifyState: GrantProbe.VerifyState,
    config: BridgeConfig,
    onVerify: (NativeToolGrant, String) -> Unit,
    bridgeRunning: Boolean,
) {
    // 展开态：点击主行切换（会话级——默认收起，与探到绿/红态无关）
    var expanded by remember(grant) { mutableStateOf(false) }
    // 读文档两项的探测链接（会话级 remember——与 verifyState 同生命周期语义：
    // 一次性探测目标，不进 draft、不落盘）
    var probeDocUrl by remember(grant) { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        HoverableRow(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                ),
        ) {
            Text(
                text = grant.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(Dimens.statusLabelWidth), // .gt width 120px
            )
            Text(
                text = grant.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), // .gd flex:1
            )
            GrantPill(verifyState = verifyState)
        }
        if (expanded) {
            // 展开区首行 =
            // scope 等宽胶囊（.scope mono fs-11 + surface 底 + 发丝内描边）+「⧉ 复制」弱字钮
            // （本行是 scope 的可视化；三件套①的复制钮是动作入口，两者并存）
            ScopeCopyRow(grant = grant)
            GrantTrioRow(grant = grant, config = config)
            // 读文档两项：探测链接输入行（目标由用户粘贴指定——自建应用无保证可读的公共节点）
            if (grant == NativeToolGrant.READ_WIKI || grant == NativeToolGrant.READ_DOC) {
                ProbeDocUrlField(
                    grant = grant,
                    value = probeDocUrl,
                    onValueChange = { probeDocUrl = it },
                )
            }
            // 验证按钮（可探测项常显——主行动作不藏 hover；文字引导不可探测项显示）
            if (GrantProbe.supportsProbe(grant)) {
                VerifyButton(
                    grant = grant,
                    state = verifyState,
                    config = config,
                    bridgeRunning = bridgeRunning,
                    probeDocUrl = probeDocUrl,
                    onVerify = onVerify,
                )
            }
            VerifyGuideRow(
                grant = grant,
                config = config,
                bridgeRunning = bridgeRunning,
            )
        }
    }
}

/** 单行状态 pill（未验证琥珀 / 已验证绿 / 失败红 / 探测中）。 */
@Composable
private fun GrantPill(verifyState: GrantProbe.VerifyState) {
    val colors = LocalEasyToolboxColors.current
    val (text, container, content) = when {
        // warningContainer 定义已含 bgSubtle 预洗（EasyToolboxColors），再 copy 属双洗底——直出
        verifyState is GrantProbe.VerifyState.Probing -> Triple("探测中…", colors.warningContainer, colors.warning)
        // success/errorContainer 是实色槽（非预洗），copy(bgSubtle) 是合法的一次洗底——保留不动
        verifyState is GrantProbe.VerifyState.Verified -> Triple("已验证 ${formatTime(verifyState.atMillis)}", colors.successContainer.copy(alpha = AppAlpha.bgSubtle), colors.success)
        verifyState is GrantProbe.VerifyState.Failed -> Triple("${verifyState.errorText} · 去后台开通", MaterialTheme.colorScheme.errorContainer.copy(alpha = AppAlpha.bgSubtle), MaterialTheme.colorScheme.error)
        else -> Triple("已启用，未验证", colors.warningContainer, colors.warning)
    }
    PillLabel(text = text, container = container, contentColor = content)
}

/** epoch millis → HH:mm（CockpitPanel 同款 formatter 先例）。 */
private fun formatTime(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** 「验证」按钮（行内展开区常显）：只读探测入口；置灰时经 tooltip 说明原因（禁止静默置灰）。 */
@Composable
private fun VerifyButton(
    grant: NativeToolGrant,
    state: GrantProbe.VerifyState,
    config: BridgeConfig,
    bridgeRunning: Boolean,
    probeDocUrl: String,
    onVerify: (NativeToolGrant, String) -> Unit,
) {
    val testChatId = config.connection.testChatId
    val probing = state == GrantProbe.VerifyState.Probing
    val isDocGrant = grant == NativeToolGrant.READ_WIKI || grant == NativeToolGrant.READ_DOC
    val docUrlOk = when (grant) {
        NativeToolGrant.READ_WIKI -> probeDocUrl.contains("/wiki/")
        NativeToolGrant.READ_DOC -> probeDocUrl.contains("/docx/")
        else -> true
    }
    val disabledReason = when {
        !bridgeRunning -> "桥接未运行——先到驾驶舱启动桥接再验证"
        isDocGrant && !docUrlOk ->
            if (grant == NativeToolGrant.READ_WIKI) "先在上方粘贴一条 /wiki/ 链接作为探测目标"
            else "先在上方粘贴一条 /docx/ 链接作为探测目标"
        !isDocGrant && testChatId.isBlank() -> "先填测试会话 ID（连接与准入 · 测试 Chat ID）"
        else -> null
    }
    AppButton(
        label = "验证",
        busy = probing,
        busyLabel = "探测中…",
        onClick = { onVerify(grant, probeDocUrl) },
        kind = AppButtonKind.Secondary,
        enabled = disabledReason == null,
        disabledReason = disabledReason,
    )
}

/**
 * 读文档两项的探测链接输入行（`/wiki/` 或 `/docx/` 完整链接）。
 * 会话级输入态（调用方 remember 持有）：探测目标是一次性输入，不进 draft、不落盘。
 */
@Composable
private fun ProbeDocUrlField(
    grant: NativeToolGrant,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = if (grant == NativeToolGrant.READ_WIKI) {
        "wiki 链接（探测目标）"
    } else {
        "docx 链接（探测目标）"
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = {
            Text(
                if (grant == NativeToolGrant.READ_WIKI) {
                    "https://xxx.feishu.cn/wiki/节点token"
                } else {
                    "https://xxx.feishu.cn/docx/文档token"
                },
            )
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/**
 * 勾选行展开区首行：
 * scope 等宽胶囊 +「⧉ 复制」弱字钮。形态：
 * - `.scope` = mono fs-11 + surface 底 + r-pill + 发丝内描边
 *   （inset box-shadow 语义——KbdText 同配方 drawBehind 内缩描边）；
 * - `.copy`（:321）= fs-12 弱化字（opacity .55 → AppAlpha.contentFaint 0.6 最近档），
 *   点击复制 scope（「复制 scope im:chat」tooltip 语义走 contentDescription）。
 * 复制能力与三件套①并存（本行是 scope 的可视化，三件套①是动作钮）。
 */
@Composable
private fun ScopeCopyRow(grant: NativeToolGrant) {
    val surface = LocalToolboxSurfaceColors.current
    val copyState = rememberCopyFeedbackState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space12), // .grow gap sp-12
    ) {
        // .scope：mono fs-11 + surface 底 + r-pill + 3×10 内边距 + 发丝内描边
        Text(
            text = grant.scopeHint,
            style = DesktopTypography.monoTag, // fs-11 mono（Medium 字重=.tag/.scope 同族最近档）
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .background(surface.surface, AppShapes.capsule)
                .drawBehind {
                    // CSS inset box-shadow → 内缩描边（KbdText 同配方）
                    val stroke = Dimens.glassStrokeHairline.toPx()
                    drawRect(
                        color = surface.borderHairline,
                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                    )
                }
                .padding(horizontal = Dimens.space10, vertical = Dimens.pillPaddingVertical), // .scope padding 3px sp-10
        )
        // .copy：fs-12 弱化字钮（contentFaint 0.6）——点击复制 scope
        Text(
            text = if (copyState.isCopied) "已复制" else "⧉ 复制",
            style = MaterialTheme.typography.labelMedium, // fs-12 档
            color = if (copyState.isCopied) LocalEasyToolboxColors.current.success
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.contentFaint),
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { copyState.copy(grant.scopeHint) }
                .semantics { contentDescription = "复制 scope ${grant.scopeHint}" }, // tooltip 语义「复制 scope im:chat」
        )
    }
}

/**
 * 后台三件套行：[复制 scope（primary）] [打开飞书后台权限页 ↗] [发版：后台 → 版本管理与发布 ↗]。
 *
 * 深链 URL **待真机确认**（双域名 open.feishu.cn / open.larksuite.com）：region 字段分流
 * （feishu=国内先行 / lark=海外同形态），URL 形态按国内域名
 * `https://open.feishu.cn/app/{appId}/security`（权限配置页）；真机确认后此处与
 * [backendUrlOf] 一并校正。外链走 java.awt.Desktop.browse（RegisterAppDialog 先例）。
 *
 * ## ②③按钮分化
 * [backendUrlOf] 带 page 参（默认 security）；②③ 曾同落权限页致「创建版本并发布」
 * 承诺与落点不符，故 ③ 走降级文案「发版：后台 → 版本管理与发布 ↗」仍跳权限页
 * （名实相符：告诉用户去后台哪个菜单，不冒充直达）。
 *
 * ## OPEN(待真机确认): 版本页 deep link
 * 1. 版本页 deep link URL 形态（`$host/app/{appId}/appVersion?/v2/distribute` 是否为
 *    真实路由）——真机确认后改传 `backendUrlOf(config, "appVersion?/v2/distribute")`
 *    并还原直达文案（双域名纪律同上）。
 */
@Composable
private fun GrantTrioRow(grant: NativeToolGrant, config: BridgeConfig) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        // ① 复制 scope（primary 语义——实底 CopyButton 形态由 :ui 收敛件承担）
        CopyButton(text = grant.scopeHint)
        // ② 打开飞书后台权限页（外链；deep link 双域名待真机确认——KDoc 已标注）
        AppButton(
            label = "打开飞书后台权限页 ↗",
            busy = false,
            onClick = { openUrl(backendUrlOf(config)) },
            kind = AppButtonKind.Secondary,
            enabled = config.connection.appId.isNotBlank(),
            disabledReason = if (config.connection.appId.isBlank()) "先填 App ID" else null,
        )
        // ③ 发版入口（外链）：版本页 deep link 待真机确认——降级文案仍跳权限页（名实相符）
        AppButton(
            label = "发版：后台 → 版本管理与发布 ↗",
            busy = false,
            onClick = { openUrl(backendUrlOf(config)) },
            kind = AppButtonKind.Secondary,
            enabled = config.connection.appId.isNotBlank(),
            disabledReason = if (config.connection.appId.isBlank()) "先填 App ID" else null,
        )
    }
}

/**
 * region 分流的飞书后台基础 URL（deep link 形态待真机确认；国内域名先行——
 * 与 SdkLarkGateway.domainOf 同源语义：feishu → open.feishu.cn / lark → open.larksuite.com）。
 *
 * [page] 参承载后台落点页段（默认 security 权限页）——版本页形态
 * 待真机确认后由 GrantTrioRow ③ 钮接线（见其 KDoc 待确认清单）。
 */
private fun backendUrlOf(config: BridgeConfig, page: String = "security"): String {
    val host = if (config.connection.region == "lark") "https://open.larksuite.com" else "https://open.feishu.cn"
    return "$host/app/${config.connection.appId}/$page"
}

/** 外链打开（runCatching 防无桌面环境崩溃；失败静默——按钮自身有 ↗ 提示）。 */
private fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/**
 * 验证引导行（边界态）：
 * - READ_WIKI/READ_DOC：文字引导「发一条 wiki/docx 链接给机器人即可验证」（无验证按钮）；
 * - 可探测项：一句「勾选后点验证——用当前测试会话做一次只读探测，不产生任何写操作」。
 */
@Composable
private fun VerifyGuideRow(grant: NativeToolGrant, config: BridgeConfig, bridgeRunning: Boolean) {
    val text = when (grant) {
        NativeToolGrant.READ_WIKI -> "粘贴一条 /wiki/ 链接点「验证」，或直接发链接给机器人"
        NativeToolGrant.READ_DOC -> "粘贴一条 /docx/ 链接点「验证」，或直接发链接给机器人"
        else -> {
            val testChatId = config.connection.testChatId
            when {
                !bridgeRunning -> "桥接未运行：先到驾驶舱启动桥接再点「验证」"
                testChatId.isBlank() -> "先在「连接与准入」填写测试 Chat ID，再点「验证」"
                else -> "勾选后点「验证」——用当前测试会话做一次只读探测，不产生任何写操作"
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 「全部所需权限：」行（`.frow` 三段）：
 * 「全部所需权限：」mute 文字 + 「复制 <scope 串>」.lnk 文字链；
 * 复制↔已复制反馈经 rememberCopyFeedbackState 保形。
 */
@Composable
private fun CopyAllScopesButton(grantedNames: Set<String>) {
    val scopes = NativeToolGrant.entries
        .filter { it.name in grantedNames }
        .map { it.scopeHint }
        .distinct()
    val copyState = rememberCopyFeedbackState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "全部所需权限：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (copyState.isCopied) "已复制" else "复制 ${scopes.joinToString(" / ")}",
            style = MaterialTheme.typography.labelSmall,
            color = if (copyState.isCopied) {
                LocalEasyToolboxColors.current.success
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = scopes.isNotEmpty(),
                    onClick = { copyState.copy(scopes.joinToString(" / ")) },
                )
                .padding(horizontal = Dimens.space4),
        )
    }
}
