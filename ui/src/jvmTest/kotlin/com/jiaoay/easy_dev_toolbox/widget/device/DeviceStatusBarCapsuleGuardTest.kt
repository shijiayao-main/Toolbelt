package com.jiaoay.easy_dev_toolbox.widget.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SH-05 · 设备状态栏胶囊化 + Single 型号直显 + Multi ≤3 台内联直选
 * 源码锚守护（重设计批 10 · 2026-10-02；随设备域升位迁 :ui 测试面）。
 *
 * 卡面 acceptance 三条：
 * 1. Single 态显示具体设备型号或串号（原「已连接 (1台)」丢弃 model 信息）；
 * 2. Multi ≤3 台时状态栏内直选设备，无对话框（FilterChip 流替换 TextButton +
 *    自动弹窗两跳动线——deviceAutoPromptShown 自动弹窗逻辑整块退役）；
 * 3. 检测失败/未连接两态「去设置配置 adb 路径」入口保留。
 *
 * 形态锚（token 承载；.devbar 逐字）：
 * 容器 AppShapes.capsule + surfaceDeep 实底 + hairline 描边；状态点
 * Dimens.dotIndicatorSize（6dp，DL-M25 对齐——原 10dp 裸值随白名单条目删除，
 * UiArchitectureGuardTest raw-dp 值级白名单 ShellToolsPage.kt 条目同 commit 清理）。
 *
 * 🔴 与 XA-08 五锚（feature-shelltools 的 ShellToolsViewModelDetectingWiringGuardTest
 * 逐字匹配）共存：胶囊化重构保留 detecting 签名/禁用/后缀/contentDisabled/调用点
 * 五锚，本守护只加不删。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :ui:jvmTest --rerun-tasks --no-build-cache`。
 */
class DeviceStatusBarCapsuleGuardTest {

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
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/device/DeviceStatusBar.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    /** DeviceStatusBar 函数区切片（单函数文件——声明起到文件尾，防跨函数误报） */
    private fun statusBarBlock(): String {
        val src = source()
        val start = src.indexOf("fun DeviceStatusBar(")
        assertTrue(start >= 0, "SH-05 守护：DeviceStatusBar 函数缺失（重构后请同步本守护）")
        return src.substring(start)
    }

    @Test
    fun `status bar container uses capsule shape with subtle background`() {
        val block = statusBarBlock()
        assertTrue(
            "AppShapes.capsule" in block,
            "SH-05 守护：设备状态栏容器必须用 AppShapes.capsule 胶囊形态" +
                "（行级状态语言对齐词汇表 .pill）",
        )
        // （.devbar 逐字）：底 = --surface-deep
        // 实底 + hairline 描边（旧 surfaceVariant×bgSubtle alpha 合成底是 DL-F08 定罪路径）
        assertTrue(
            "surfaceColors.surfaceDeep" in block,
            "SH-05 守护：胶囊底色必须走 surfaceDeep 实底（mock-base .devbar " +
                "background: var(--surface-deep) 逐字——旧 bgSubtle 合成底已定罪删除）",
        )
        assertTrue(
            "surfaceColors.borderHairline" in block,
            "SH-05 守护：胶囊必须带 hairline 内描边（.devbar box-shadow inset 1px " +
                "var(--border-hairline) 逐字）",
        )
    }

    @Test
    fun `status dot uses dot indicator size token`() {
        val block = statusBarBlock()
        assertTrue(
            "Dimens.dotIndicatorSize" in block,
            "SH-05 守护：状态点必须用 Dimens.dotIndicatorSize（6dp，DL-M25 对齐）" +
                "（原 10dp 裸值已随 UiGuard raw-dp 白名单条目清理）",
        )
        assertFalse(
            ".size(10.dp)" in block,
            "SH-05 守护：状态点不得回潮 10dp 裸值（dotIndicatorSize token 单一真源）",
        )
    }

    @Test
    fun `single state shows device model with serial fallback`() {
        val block = statusBarBlock()
        assertTrue(
            "已连接 · " in block,
            "SH-05 守护：Single 态主文案必须为「已连接 · <型号>」（型号直显——" +
                "原「已连接 (1台)」对多设备用户无辨识度）",
        )
        assertTrue(
            "model.ifEmpty { single.serial }" in block,
            "SH-05 守护：Single 型号必须 model 优先 serial 兜底" +
                "（DeviceState.Single 无 displayLabel 字段，主行自行派生，" +
                "口径对齐 DeviceInfo.displayLabel）",
        )
    }

    @Test
    fun `multi state up to three devices offers inline chip selection`() {
        val block = statusBarBlock()
        assertTrue(
            "FilterChip(" in block,
            "SH-05 守护：Multi ≤3 台必须状态栏内联 FilterChip 直选（零对话框打断）",
        )
        assertTrue(
            "deviceState.count <= 3" in block,
            "SH-05 守护：内联直选必须以 ≤3 台为门（>3 台降级对话框路径保留）",
        )
        assertTrue(
            "onPickDevice" in block,
            "SH-05 守护：chip 点击必须经 onPickDevice 回调（VM selectDevice 接线）",
        )
    }

    @Test
    fun `auto prompt dialog logic retired`() {
        // 迁移后双文件面：对话框触发态在 ShellToolsPage、渲染件在 :ui widget/device/
        // ——两处任一残留自动弹窗状态即回潮
        listOf(source(), pageSource()).forEach { src ->
            assertFalse(
                "deviceAutoPromptShown" in src,
                "SH-05 守护：deviceAutoPromptShown 自动弹窗逻辑必须退役" +
                    "（内联直选后弹窗打断不再需要；残留 = 死状态 + 意外弹窗回归）",
            )
        }
    }

    @Test
    fun `degraded dialog entry retained for more than three devices`() {
        val block = statusBarBlock()
        assertTrue(
            "选择设备" in block,
            "SH-05 守护：>3 台降级路径必须保留「选择设备」对话框入口",
        )
        // 失败/未连接两态的「去设置配置 adb 路径」入口保留（acceptance 3）
        assertTrue(
            Regex("去设置配置 adb 路径").findAll(block).count() >= 2,
            "SH-05 守护：检测失败与未连接两态的「去设置配置 adb 路径」入口必须保留" +
                "（胶囊化只动容器与连接态，不动排障入口）",
        )
    }
}
