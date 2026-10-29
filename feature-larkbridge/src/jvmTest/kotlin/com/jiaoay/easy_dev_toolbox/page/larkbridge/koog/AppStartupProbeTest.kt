package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.StartupProbeOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 启动自检缝 App 适配的分类投影守护：Normal 静默；异常与探查失败都原样上送模板文本。 */
class AppStartupProbeTest {

    @Test
    fun `Normal 投影 null（正常态静默零消息）`() {
        assertNull(AppStartupProbe.messageOf(StartupProbeOutcome.Normal))
    }

    @Test
    fun `Anomaly 与 ProbeFailed 投影模板文本原样（探查失败如实不静默）`() {
        val anomalyText = "【Toolbelt 启动自检】发现 1 项机器健康异常"
        val failureText = "【Toolbelt 启动自检】探查未能完成（如实报告）"
        assertEquals(
            anomalyText,
            AppStartupProbe.messageOf(StartupProbeOutcome.Anomaly(anomalyText)),
            "异常模板文本不追加不裁剪",
        )
        assertEquals(
            failureText,
            AppStartupProbe.messageOf(StartupProbeOutcome.ProbeFailed(failureText)),
            "探查失败模板文本原样上送",
        )
    }
}
