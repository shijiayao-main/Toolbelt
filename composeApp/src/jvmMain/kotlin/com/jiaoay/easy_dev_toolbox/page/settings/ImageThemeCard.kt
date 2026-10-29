package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.pickOpenFiles
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.materialkolor.ktx.themeColorOrNull
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.glass.appPanelRimTop
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

/**
 * 图片提取种子色的状态机（图片提取失败反馈闭环）。
 *
 * 与 ConnectivityProbe（AiSettingsViewModel.kt）/ AdbCheckResult 同构的
 * Idle/工作中/成功/失败四面——失败原因对 UI 可见（只进 JLog 的话
 * 用户拖入坏图后 UI 无任何提示，只有静默回弹）。
 */
sealed class ExtractThemeState {
    data object Idle : ExtractThemeState()
    data object Extracting : ExtractThemeState()
    data class Done(val seed: Color) : ExtractThemeState()

    /** 失败原因（面向用户文案：e.message 已在提取层收敛为可读中文）。 */
    data class Failed(val message: String) : ExtractThemeState()
}

/**
 * 「从图片提取配色」入口（`.zone` 单行 zrow 形态）：
 * 上传语义 icon + 题 fs-13/600 + hint 文「拖入图片到此处，或
 * 点击选择」+ 行尾 chipbtn hot「选择图片」（FileDropZone 100dp 竖排 hero 大拖放区
 * 形态退役；FileDropZone 组件本体不动，其他页面继续消费；本卡换实现）。
 *
 * 拖放/点击选择/类型过滤语义保留：拖入经 [acceptsImage] 过滤扩展名，点击走
 * [pickOpenFiles]（FileDropZone 同款共享挂起选择器，filter 同参）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImageThemeCard(
    extracting: Boolean,
    onImageSelected: (java.io.File) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (extracting) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(AppShapes.medium)
                .padding(horizontal = Dimens.space16, vertical = Dimens.space14), // 14dp 归档 space14
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(Dimens.iconLarge), strokeWidth = 2.dp)
            Spacer(Modifier.width(Dimens.space12))
            Text(
                text = "正在从图片提取配色…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    } else {
        val surface = LocalToolboxSurfaceColors.current
        val pickerScope = rememberCoroutineScope()
        // 拒绝态（拖入全军覆没时短促提示）——FileDropZone 同款语义内联（zone 单行无竖排空间）
        var rejected by remember { mutableStateOf(false) }
        LaunchedEffect(rejected) {
            if (rejected) {
                delay(FileDropRejectDismissMs)
                rejected = false
            }
        }
        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) {
                    rejected = false
                }

                override fun onEnded(event: DragAndDropEvent) = Unit

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = (event.dragData() as? DragData.FilesList)
                        ?.readFiles()
                        ?.mapNotNull { runCatching { java.io.File(java.net.URI.create(it)) }.getOrNull() }
                        ?.filter { it.exists() && acceptsImage(it) }
                        .orEmpty()
                    if (files.isNotEmpty()) {
                        files.firstOrNull()?.let(onImageSelected)
                        return true
                    }
                    rejected = true
                    return false
                }
            }
        }
        val foregroundColor = if (rejected) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        // .zone 容器：r-lg 圆角 + surface-raised 实底 +
        // inset 1px hairline + inset 顶缘 panel-rim（appPanelRimTop 单源——TonalCard 同配方）
        // + padding 14×20；内容 = .zrow 单行（gap 10）
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(AppShapes.large)
                .background(surface.surfaceRaised, AppShapes.large)
                .border(Dimens.glassStrokeHairline, surface.borderHairline, AppShapes.large)
                .appPanelRimTop(AppShapes.large)
                .clickable {
                    // zrow cursor:pointer：整行可点 = 点击选择（chipbtn hot 同动作）
                    pickerScope.launchPicker(onImageSelected)
                }
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                    target = dropTarget,
                )
                .padding(horizontal = Dimens.space20, vertical = Dimens.space14),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space10),
            ) {
                // 上传语义（箭头+托盘）→ FILE_UPLOAD 符号名；
                // .ico 18px
                AppSymbolIcon(
                    icon = MaterialSymbols.FILE_UPLOAD,
                    contentDescription = null,
                    size = Dimens.iconMedium,
                    tint = foregroundColor,
                )
                Text(
                    // .zt fs-13/600
                    text = "从图片提取配色",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = foregroundColor,
                )
                Text(
                    // .zn fs-11 on-surface-var
                    text = if (rejected) "不支持的图片类型，已忽略" else "拖入图片到此处，或点击选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rejected) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 行尾 chipbtn hot：primary 字 + primary×hotWash 0.10 洗底
                // + r-pill + padding 6×14 + fs-12/600
                Text(
                    text = "选择图片",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(AppShapes.capsule)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash),
                            AppShapes.capsule,
                        )
                        .clickable { pickerScope.launchPicker(onImageSelected) }
                        .padding(horizontal = Dimens.space14, vertical = Dimens.space6),
                )
            }
        }
    }
}

/** 拒绝态自动消退时长（FileDropZone.REJECT_HINT_DISMISS_MS 同值；本文件私有常量避免扩散组件内部状态） */
private const val FileDropRejectDismissMs = 2_400L

/**
 * 图片扩展名过滤（zone 拖入/点击选择共用；原 FileDropZone accepts 同集合）。
 */
private fun acceptsImage(file: java.io.File): Boolean = file.extension.lowercase() in IMAGE_EXTENSIONS

/**
 * 点击选择图片（pickOpenFiles 共享挂起选择器——原 FileDropZone 点击同类调用）。
 */
private fun kotlinx.coroutines.CoroutineScope.launchPicker(onImageSelected: (java.io.File) -> Unit) {
    launch {
        val files = pickOpenFiles(
            title = "选择图片",
            multiple = false,
            filter = ::acceptsImage,
        )
        files.firstOrNull()?.let(onImageSelected)
    }
}

/**
 * 提取结果确认对话框：种子色 + 深浅两排预览色点 + 应用/取消。
 */
@Composable
fun ImageThemeConfirmDialog(
    seed: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // P0 一致性批修复预览欠账：经主题规格派生（Custom 档 style 与实机一致），不再直调 dynamicColorScheme
    val customSpec = remember(seed) { AppThemeSpec.Custom(seed) }
    val lightScheme = remember(seed) { customSpec.colorSet(dark = false) }
    val darkScheme = remember(seed) { customSpec.colorSet(dark = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("提取到配色方案", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(seed),
                    )
                    Spacer(Modifier.width(Dimens.space12))
                    Column {
                        Text("种子色", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "#%06X".format(seed.toArgb() and 0xFFFFFF),
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PreviewRow("浅色模式", lightScheme)
                PreviewRow("深色模式", darkScheme)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PreviewRow(label: String, scheme: ColorScheme) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 最小宽而非固定宽：CJK 大度量字体（Linux Noto Sans CJK）下「浅色模式」4 字接近 64dp 上限，
            // 固定宽会软换行导致行高跳变；单行省略兜底
            modifier = Modifier.widthIn(min = 64.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space6)) {
            PreviewDot(scheme.primary)
            PreviewDot(scheme.secondary)
            PreviewDot(scheme.tertiary)
        }
    }
}

@Composable
private fun PreviewDot(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 图片文件 → 提取种子色。
 * 流程：ImageIO 解码 → Compose ImageBitmap → MaterialKolor 量化提取主题色。
 * 需在 IO 线程调用（图片解码可能较慢）。
 *
 * 失败原因随 Result 上抛（返回 Color? 只进 JLog 的话——
 * 失败对 UI 不可见）。两条可读失败文案：ImageIO 解不出图 / 量化提不出主色。
 */
internal fun extractSeedFromImage(file: java.io.File): Result<Color> = runCatching {
    val buffered = javax.imageio.ImageIO.read(file) ?: error("无法读取图片: ${file.name}")
    val bitmap = buffered.toComposeImageBitmap()
    bitmap.themeColorOrNull() ?: error("图片无法提取主色（纯色/透明图或色彩过少）: ${file.name}")
}.onFailure { e ->
    JLog.e("ImageTheme", e) { "extract seed failed: ${file.name}" }
}
