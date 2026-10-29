package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GLB-15 守护（终验蜂群修复批 7.5 · 2026-10-02 维护者拍板）：状态栏桥接段
 * Verified 点色 success（绿）。
 *
 * 收口依据：`.dotok { background: var(--success) }`——「桥接在线」
 * 状态点语义 = 连接健康（绿），原 primary（紫）与壳层「Running=primary」活动态
 * 语言冲突（健康读数误着活动色）；维护者拍板点色走 success 语义消解冲突。
 *
 * 分治终态：
 * - Verified（在线）= **success 绿**（连接健康）；
 * - Degraded（降级）= tertiary 不动（警示非故障）；
 * - PermissionRequired = error 不动（需行动异常态）；
 * - Unavailable（未配置）= onSurfaceMid 中性灰（中性态非故障——常驻红点红字
 *   与「故障=红」语义混载成告警疲劳，全项目修复批 H3 预授权改色）。
 *
 * 守护面（防回潮）：StatusGatewaySegment 的 Verified 分支必须以
 * `LocalEasyToolboxColors.current.success` 为原色（昼战经 dayBattleTint 坐
 * successContainer 档——电光紫 主题 §5.3 呈现路径，非昼战原值直出）；primary 点色回潮即红。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class GatewayStatusDotColorGuardTest {

    @Test
    fun `verified gateway dot renders success green not primary`() {
        val bar = statusBarFile()
        val text = bar.readText()

        // ① Verified 分支 = success（连接健康=绿语义——GLB-15 acceptance；
        //    昼战承载 = dayBattleTint(success, successContainer)，非昼战直出 success）
        val segmentBlock = text.substringAfter("private fun StatusGatewaySegment")
            .substringBefore("\n/**")
        assertTrue(
            "dayBattleTint(toolboxColors.success, toolboxColors.successContainer) to \"桥接在线\"" in segmentBlock,
            "状态栏桥接段 Verified 点色必须为 colors.success（GLB-15：连接健康=绿，" +
                "稿面 .dotok 同构；昼战经 dayBattleTint 坐 successContainer 档）——primary 紫点回潮即红。",
        )

        // ② Verified 分支不得再走 primary（活动态语言不分治即红）
        assertFalse(
            "CapabilityState.Verified -> MaterialTheme.colorScheme.primary" in segmentBlock,
            "桥接段 Verified 分支不得再点 primary（GLB-15：与「Running=primary」活动态" +
                "语言的冲突点已由 success 消解）。",
        )

        // ③ 其余三态语义保留不动（Degraded=tertiary / 异常态=error——B6 修复契约；
        //    异常态昼战经 dayBattleTint 坐 errorContainerFixed 档，原色仍 error）
        assertTrue(
            "MaterialTheme.colorScheme.tertiary to \"桥接降级\"" in segmentBlock,
            "Degraded 点色保持 tertiary（降级但可用——警示非故障；GLB-15 不动项）。",
        )
        assertTrue(
            "dayBattleTint(MaterialTheme.colorScheme.error, toolboxColors.errorContainerFixed) to \"桥接待授权\"" in segmentBlock,
            "PermissionRequired 点色保持 error（需行动异常态——B6 修复契约，GLB-15 不动项；" +
                "昼战经 dayBattleTint 坐 errorContainerFixed 档）。",
        )
    }

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun statusBarFile(): File {
        val file = File(locateProjectRoot(), APP_STATUS_BAR_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$APP_STATUS_BAR_PATH（重命名/移动后请同步本守护）")
        return file
    }

    private companion object {
        const val APP_STATUS_BAR_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/AppStatusBar.kt"
    }
}
