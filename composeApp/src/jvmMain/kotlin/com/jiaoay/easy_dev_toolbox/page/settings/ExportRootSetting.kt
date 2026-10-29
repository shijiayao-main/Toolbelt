package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.CommitTextField
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.pickDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 产物目录子卡：设置页「高级」分类产物目录组的表单行（路径编辑 + 目录选择器）+
 * 当前生效根只读展示（AdbPathSetting 同款 frow 三段配方）。
 *
 * 生效语义：失焦/选目录即落盘 + 同步写 FileUtils 进程单源——改后即时生效无需重启；
 * 留空回默认根（~/Documents/Toolbelt）。可再生产物不迁移不回读：旧根下的存量
 * 产物原样保留，用户可手动搬。
 */
@Composable
internal fun ExportRootSetting(
    appViewModel: AppViewModel,
) {
    val configuredRoot by appViewModel.visibleExportRoot.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 展示面跟随配置态重算（configuredRoot 变化 ⇒ 重组 remember key 失效重读单源）
    val effectiveRoot = remember(configuredRoot) { FileUtils.getVisibleExportRoot().absolutePath }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        CommitTextField(
            label = null,
            value = configuredRoot,
            onCommit = { appViewModel.setVisibleExportRoot(it) },
            labelInline = false,
            modifier = Modifier.width(Dimens.fieldControlMinWidth),
        )
        OutlinedButton(
            onClick = {
                // 目录选择器模态阻塞，放 IO 协程避免冻结 Compose UI 线程（pickDirectory 线程契约）
                scope.launch(Dispatchers.IO) {
                    pickDirectory(title = "选择产物目录")?.let { dir ->
                        appViewModel.setVisibleExportRoot(dir.absolutePath)
                    }
                }
            },
        ) { Text("浏览") }
    }

    // 当前生效根独立行——mono 数据字 + CopyIconButton 一键复制（日志目录行同款配方）；
    // 配置为空时展示的就是默认根，无需另设「恢复默认」按钮
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier.padding(top = Dimens.space4),
    ) {
        Text(
            text = effectiveRoot,
            style = DesktopTypography.mono,
            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
            modifier = Modifier.weight(1f),
        )
        CopyIconButton(
            textProvider = { effectiveRoot },
            contentDescription = "复制产物目录",
        )
    }
}
