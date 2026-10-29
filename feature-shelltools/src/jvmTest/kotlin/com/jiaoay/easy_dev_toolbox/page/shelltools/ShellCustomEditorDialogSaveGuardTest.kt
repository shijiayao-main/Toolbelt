package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 编辑对话框保存置灰带原因源码锚守护。
 *
 * ShellCustomEditorDialog 保存钮不得 `Button(enabled = canSave)` 静默置灰
 * （违 §4.7h「禁止静默置灰」）；守护配方 = AppButton disabledReason when 折入
 * canSave 判断（🔴 AppButton disabledReason 非 null 即置灰——
 * effectiveEnabled = enabled && !busy && disabledReason == null，若保留
 * enabled = canSave 双轨则 disabledReason 恒非空 = 永不可点）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellCustomEditorDialogSaveGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    private fun source(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/custom/ShellCustomEditorDialog.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `save button uses app button with disabled reason`() {
        val src = source()
        assertTrue("AppButton(" in src, "XA-10 守护：保存钮必须走 AppButton（内建 TooltipBox 承载置灰原因）")
        assertTrue("disabledReason" in src, "XA-10 守护：保存钮必须带 disabledReason（禁止静默置灰）")
        assertTrue(
            "先填写命令名称" in src,
            "XA-10 守护：名称为空的置灰原因文案锚缺失",
        )
    }

    @Test
    fun `save reason when folds cansave into branches not dual track`() {
        val src = source()
        // 🔴 双轨回潮锚：enabled = canSave 与 disabledReason 并存 = 永不可点
        // （AppButton disabledReason 非 null 即置灰压制 enabled）
        assertTrue(
            src.lineSequence().none { it.trim().startsWith("enabled = canSave") },
            "XA-10 守护：enabled = canSave 双轨不得回潮（disabledReason 非 null 恒置灰，双轨 = 永不可点；" +
                "canSave 判断必须折进 disabledReason 的 when 分支）",
        )
        // canSave 独立变量不得回潮（代码里断「canSave = name」形态——注释里的引用不算）
        val codeOnly = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "canSave = name" !in codeOnly,
            "XA-10 守护：canSave 独立变量不得回潮（校验应折进 disabledReason when）",
        )
    }

    @Test
    fun `filled state yields null disabled reason so save stays clickable`() {
        val src = source()
        // disabledReason when 必须有 else -> null 分支（填齐 = 可点）
        val whenBlock = src.substringAfter("disabledReason = when {").substringBefore("},\n                onClick")
        assertTrue(
            "else -> null" in whenBlock,
            "XA-10 守护：disabledReason when 必须有 else -> null（填齐后 disabledReason = null 才可点）",
        )
    }

    @Test
    fun `dirty form dismissal goes through confirm`() {
        // 脏态确认锚：onDismissRequest 不得直连 onDismiss（半填表单被 Esc/误点遮罩
        // 静默丢弃）——脏态经 requestDismiss 拦截走 AppConfirmDialog
        val src = source()
        assertFalse(
            Regex("""onDismissRequest = onDismiss""").containsMatchIn(src),
            "XA-10 守护：编辑器 onDismissRequest 直连 onDismiss = 脏表单静默丢弃回潮（须走 requestDismiss 脏态拦截）",
        )
        assertTrue(
            "fun requestDismiss()" in src,
            "XA-10 守护：脏态拦截入口 requestDismiss 缺失",
        )
        assertTrue(
            "放弃未保存的修改" in src && "AppConfirmDialog(" in src,
            "XA-10 守护：脏态确认卡缺失（AppConfirmDialog + 放弃语义文案）",
        )
        assertTrue(
            "nameFocusRequester" in src,
            "XA-10 守护：名称框焦点初始化缺失（对话框打开即聚焦可打字）",
        )
    }
}
