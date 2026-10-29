package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodeBounds
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * FieldRow 响应式双形态场景守护（渲染底座单源 [SceneGuardHandle]）：
 * 横排三段（行可用宽 ≥ [Dimens.fieldRowStackBelowWidth]）与窄行纵排（< 阈值）的布局契约锚。
 *
 * 守护动机：hint 在横排 Row 中无权重，限宽（[Dimens.fieldHintMaxWidth]）缺失时超长 hint
 * 软换行会吃满剩余宽、把编辑器 weight 段压到零宽；窄行缺纵排降级时窗口最小尺寸
 * （物理像素钳制，HiDPI 下 dp 可用宽减半）不足以容纳三段刚性下限和。两形态都以
 * **语义树坐标**断言（[semanticsNodeBounds]）——行带像素锚会被 label 档位/多行
 * hint 形高污染，坐标面天然免疫。
 *
 * 锚字段：横排宽度锚 = fileMessage.preprocessCommand（Text 编辑器、无 pill、无内联 chip，
 * hint 超限宽上限——限宽缺失即坍缩到零宽的最灵敏形态）；纵排锚 = workspace.root
 * （Directory 编辑器 + RESTART pill，纵排下编辑器满行宽且「浏览…」chip 仍在行内）。
 */
class FieldRowSceneGuardTest {

    /** 横排宽度锚字段（长 hint 的纯 Text 编辑器——ctl 段无内联控件分宽） */
    private val textField: BridgeField<Any?> =
        @Suppress("UNCHECKED_CAST")
        (BridgeFields.ALL.first { it.id == "fileMessage.preprocessCommand" } as BridgeField<Any?>)

    /** 纵排锚字段（Directory 编辑器 + RESTART pill——降级形态的多控件行代表） */
    private val directoryField: BridgeField<Any?> =
        @Suppress("UNCHECKED_CAST")
        (BridgeFields.ALL.first { it.id == "workspace.root" } as BridgeField<Any?>)

    /** 场景内容：生产宿主形态镜像（Row/Column 先行吸收 Surface min 约束） */
    @androidx.compose.runtime.Composable
    private fun sceneContent(field: BridgeField<Any?>) {
        Column(modifier = Modifier.fillMaxSize().padding(Dimens.space16)) {
            FieldRow(
                field = field,
                config = DefaultBridgeConfig.default(),
                issue = null,
                modified = false,
                onCommit = { },
            )
        }
    }

    /** 编辑器文本框语义命中（SetText 动作 = 可编辑文本框） */
    private fun isEditorNode(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsActions.SetText) != null

    /** lab 段文本语义命中（Text 语义属性含字段 label） */
    private fun isLabelNode(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(label) } == true

    /**
     * 横排形态：超长 hint 不吞编辑器段——Text 编辑器宽 ≥ [Dimens.fieldControlMinWidth]
     * （hint 限宽缺失时该字段编辑器坍缩到零宽，本断言即红）。
     */
    @Test
    fun `横排形态-长hint不吞编辑器段宽度`() {
        SceneGuardHandle(width = 900, height = 200).use { scene ->
            scene.setContent { sceneContent(textField) }
            scene.renderPixels()
            val editor = scene.semanticsNodeBounds(matcher = ::isEditorNode)
            val label = scene.semanticsNodeBounds(matcher = ::isLabelNodeLabelOfTextField)
            assertTrue(
                editor.width >= Dimens.fieldControlMinWidth.value,
                "横排编辑器段宽 ${editor.width}dp < ${Dimens.fieldControlMinWidth.value}dp" +
                    "——hint 限宽缺失，长 hint 吞掉编辑器段",
            )
            assertTrue(
                editor.top < label.bottom,
                "横排形态编辑器须与 lab 段同带（editor.top=${editor.top} ≥ label.bottom=${label.bottom}——误入纵排）",
            )
        }
    }

    /** 纵排形态：行可用宽低于阈值时三段各占整行——编辑器满行宽且在 lab 段之下 */
    @Test
    fun `纵排形态-窄行三段降级各占整行`() {
        SceneGuardHandle(width = 400, height = 320).use { scene ->
            scene.setContent { sceneContent(directoryField) }
            scene.renderPixels()
            val editor = scene.semanticsNodeBounds(matcher = ::isEditorNode)
            val label = scene.semanticsNodeBounds(matcher = ::isLabelNodeLabelOfDirectoryField)
            // 满行宽 = 400 - 外层 padding 32 - Directory 行内「浏览…」chip 与间距 ≈ 290dp；阈值取 270 留 chip 宽度漂移余量
            assertTrue(
                editor.width >= 270,
                "纵排编辑器段须满行宽（实宽 ${editor.width}dp < 270dp——降级缺失，三段仍在横排互挤）",
            )
            assertTrue(
                editor.top > label.bottom,
                "纵排形态编辑器须在 lab 段之下（editor.top=${editor.top} ≤ label.bottom=${label.bottom}——仍与 lab 同带）",
            )
        }
    }

    private fun isLabelNodeLabelOfTextField(node: SemanticsNode): Boolean = isLabelNode(node, textField.label)

    private fun isLabelNodeLabelOfDirectoryField(node: SemanticsNode): Boolean = isLabelNode(node, directoryField.label)
}
