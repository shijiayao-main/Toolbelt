package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RenameDialog Enter 提交接线守护：
 *
 * 单行重命名是高频微操作——键盘流不应需要 Tab 找「保存」钮；同族单行输入
 * （AskCard / PathConfirmCard）均已接线，本件是单行对话框族最后缺口。
 *
 * 验收面（双钉）：
 * 1. **Enter 即保存并关闭**：OutlinedTextField modifier 挂 `.onEnterKey { if (canSave)
 *    { onSave(text); onDismiss() } }`——SubmitKey 单行语义默认档（KeyDown + NumPadEnter
 *    覆盖），语义与 confirmButton「保存」逐字一致（canSave 门 = 空标题禁存，Enter 与
 *    保存钮同判）；
 * 2. **Esc 关闭不保存**：不挂任何 Esc/onKeyEvent 处理——AlertDialog 默认 dismiss
 *    行为零改动（挂了 Esc 自持处理即本守护红）；
 * 3. **提交语义单源**：onSave 回调体必须走 [onEnterKey] 扩展（页面不得自写
 *    Enter 处理——SubmitKey KDoc 契约）。
 */
class RenameDialogEnterGuardTest {

    @Test
    fun `enter key submits and closes via SubmitKey single line recipe`() {
        val source = renameDialogSource()
        assertTrue(
            ".onEnterKey { if (canSave) { onSave(text); onDismiss() } }" in source,
            "RenameDialog 输入框必须挂 onEnterKey { if (canSave) { onSave(text); onDismiss() } }" +
                "（XA-12：Enter 即保存并关闭——SubmitKey 单行默认档；canSave 门与保存钮同判）",
        )
    }

    @Test
    fun `escape stays on alert dialog default dismiss`() {
        // Esc 关闭不保存 = AlertDialog 默认行为——自持 Esc 处理即双通道
        //（「AlertDialog 默认行为保持」）。onPreviewKeyEvent/onKeyEvent 直挂即红
        //（onEnterKey 内部用 onPreviewKeyEvent 属 SubmitKey 单源实现，豁免——
        // 剥掉 onEnterKey 行后不得再有 key event 处理）。
        val source = renameDialogSource()
            .lines()
            .filterNot { "onEnterKey" in it }
            .joinToString("\n")
        assertFalse("onPreviewKeyEvent" in source, "RenameDialog 不得自挂 onPreviewKeyEvent（Esc 走 AlertDialog 默认 dismiss，双通道 = 语义漂移）")
        assertFalse("onKeyEvent" in source, "RenameDialog 不得自挂 onKeyEvent（同上）")
    }

    @Test
    fun `enter handler consumes the committed text not stale value`() {
        // onEnterKey 回调闭包读的是 var text（remember 态最新值）——若回调用
        // initial（陈旧值）则保存的永远是原名，重命名失灵。
        val source = renameDialogSource()
        assertTrue(
            "var text by remember" in source,
            "text 必须是 remember 可变态（Enter 回调闭包读取最新输入）",
        )
        assertTrue(
            "onSave(text)" in source,
            "Enter 提交必须携带当前输入 text（携带 initial = 永远保存原名）",
        )
    }

    private fun renameDialogSource(): String {
        val file = File(locateProjectRoot(), RENAME_DIALOG_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$RENAME_DIALOG_PATH（重命名/移动后请同步本守护）")
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
        error("未定位项目根（settings.gradle.kts）——:ui:jvmTest 工作目录异常")
    }

    private companion object {
        const val RENAME_DIALOG_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/RenameDialog.kt"
    }
}
