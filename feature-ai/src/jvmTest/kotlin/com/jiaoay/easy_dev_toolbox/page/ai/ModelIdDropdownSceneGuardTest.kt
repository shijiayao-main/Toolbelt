package com.jiaoay.easy_dev_toolbox.page.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodeBounds
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.theme.LocalWindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 模型下拉菜单场景守护（Loaded 清单展开态）。
 *
 * 🔴 回归锚——菜单容器禁用 Lazy 系（SubcomposeLayout 系）：M3 ExposedDropdownMenu
 * 展开时按锚点宽度做固有尺寸测量，固有测量链触达 Lazy 布局即 IllegalStateException
 * 抛在渲染循环（未捕获 = 整进程退出）。本场景在容器回归 Lazy 时必红——
 * [SceneGuardHandle.renderPixels] 把 measure 异常直达断言面，修复态必须绿且
 * 兜底动作项「手动输入模型 ID」在菜单内可见。
 */
class ModelIdDropdownSceneGuardTest {

    private fun hasText(t: String): (androidx.compose.ui.semantics.SemanticsNode) -> Boolean = { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true
    }

    @Test
    fun `loaded catalog expanded menu renders without intrinsic crash and manual entry visible`() {
        SceneGuardHandle(width = 1280, height = 860).use { handle ->
            handle.setContent {
                CompositionLocalProvider(LocalWindowSizeClass provides WindowSizeClass.Medium) {
                    ModelIdDropdown(
                        config = LlmEndpointConfig(
                            baseUrl = "https://api.example.com/v1",
                            apiKey = "sk-test",
                            modelId = "model-a",
                            timeoutSeconds = 60,
                        ),
                        catalog = ModelCatalog.Loaded(listOf("model-a", "model-b", "model-c")),
                        expanded = true,
                        onExpandedChange = {},
                        onPick = {},
                        onFetch = {},
                        onManualInput = {},
                    )
                }
            }
            // 展开态菜单测量：Lazy 容器回归在此抛 IllegalStateException
            handle.renderPixels()

            val manualEntry = handle.semanticsNodeBounds(matcher = hasText("手动输入模型 ID"))
            assertTrue(
                manualEntry.right > 0f && manualEntry.bottom > 0f,
                "菜单内兜底动作项「手动输入模型 ID」必须可见（bounds=$manualEntry）",
            )
        }
    }
}
