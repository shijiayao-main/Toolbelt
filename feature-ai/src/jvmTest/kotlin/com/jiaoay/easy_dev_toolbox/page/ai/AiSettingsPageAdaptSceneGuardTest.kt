package com.jiaoay.easy_dev_toolbox.page.ai

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodeBounds
import com.jiaoay.easy_dev_toolbox.core.theme.LocalWindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.widget.FormRow
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AI 设置页窗口适配场景守护（两锚）：
 * - [FormRow] 三段行 Compact 档回落纵排（窄窗下三段定宽会挤压破版——
 *   lab 190 + ctl 220 + 行尾件把 hint 列挤成一字一行）；
 * - 页面单列档内容恒满宽（「限宽 1200 + 居中」已废除——
 *   label 贴左缘、控件贴右缘；大屏差异化 UI 留待真设计）。
 *
 * 页级场景经 [UserHomeIsolation] 重定向 user.home：VM 配置回填与记忆索引读
 * 全走临时 home 的空 DataStore，不触碰真实用户数据。
 */
class AiSettingsPageAdaptSceneGuardTest {

    private class DiagOwner : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    private val homeIsolation = UserHomeIsolation("edt-ai-adapt-scene")

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
    }

    private fun hasText(t: String): (androidx.compose.ui.semantics.SemanticsNode) -> Boolean = { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true
    }

    @Test
    fun `form row compact stacks label hint and control vertically`() {
        SceneGuardHandle(width = 960, height = 640).use { handle ->
            handle.setContent {
                CompositionLocalProvider(LocalWindowSizeClass provides WindowSizeClass.Compact) {
                    FormRow(label = "LABMARK", hint = "HINTMARK") { Text("CTLMARK") }
                }
            }
            handle.renderPixels()
            val lab = handle.semanticsNodeBounds(matcher = hasText("LABMARK"))
            val hint = handle.semanticsNodeBounds(matcher = hasText("HINTMARK"))
            val ctl = handle.semanticsNodeBounds(matcher = hasText("CTLMARK"))
            assertTrue(
                lab.bottom <= hint.top && hint.bottom <= ctl.top,
                "Compact 档 FormRow 必须纵排（lab→hint→ctl 自上而下），实际 lab=$lab hint=$hint ctl=$ctl",
            )
        }
    }

    @Test
    fun `form row medium keeps three segment horizontal`() {
        SceneGuardHandle(width = 1280, height = 640).use { handle ->
            handle.setContent {
                CompositionLocalProvider(LocalWindowSizeClass provides WindowSizeClass.Medium) {
                    FormRow(label = "LABMARK", hint = "HINTMARK") { Text("CTLMARK") }
                }
            }
            handle.renderPixels()
            val lab = handle.semanticsNodeBounds(matcher = hasText("LABMARK"))
            val ctl = handle.semanticsNodeBounds(matcher = hasText("CTLMARK"))
            assertTrue(
                lab.top < ctl.bottom && ctl.top < lab.bottom && lab.right < ctl.left,
                "Medium 档 FormRow 必须保持三段横排（lab 左、ctl 右且同一行带），实际 lab=$lab ctl=$ctl",
            )
        }
    }

    @Test
    fun `expanded window fills width without centering`() {
        // Wide/Expanded「限宽 + 居中」已废除：大屏差异化 UI 未做
        // 之前，居中悬浮即缺陷——内容区恒满可用宽（label 贴左缘、控件贴右缘）。
        // 旧断言（chip 右缘 1300–1450 居中带、label 左缘 >200）随旧行为一并退役。
        SceneGuardHandle(width = 1600, height = 860).use { handle ->
            handle.setContent {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides WindowSizeClass.Expanded,
                    LocalViewModelStoreOwner provides DiagOwner(),
                ) {
                    AiSettingsPage()
                }
            }
            handle.renderPixels()
            val fetchChip = handle.semanticsNodeBounds(matcher = hasText("拉取列表"))
            val baseUrlLabel = handle.semanticsNodeBounds(matcher = hasText("Base URL"))
            // 满宽行为：chip 钉回 ~1552-24 右缘（>1500），label 贴回左缘（<100）
            assertTrue(
                fetchChip.right > 1500f,
                "Expanded 档单列内容必须满宽（chip 右缘=${fetchChip.right} 应 >1500 贴右缘——居中悬浮已废除）",
            )
            assertTrue(
                baseUrlLabel.left < 100f,
                "Expanded 档单列内容必须贴左（Base URL label 左缘=${baseUrlLabel.left} 应 <100——无居中留白）",
            )
        }
    }
}
