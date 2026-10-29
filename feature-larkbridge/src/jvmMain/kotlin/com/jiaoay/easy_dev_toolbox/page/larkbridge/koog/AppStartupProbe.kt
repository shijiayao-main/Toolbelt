package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.StartupProbeOutcome
import com.jiaoay.easy_dev_toolbox.core.llm.tools.StartupProber
import com.jiaoay.lark.bridge.observe.StartupProbeSeam

/**
 * 启动自检缝的 App 形态装配件：core 探查器（[StartupProber]——复用 sys.probe 编译期目录
 * 固定 argv 子集 + sys-audit 审计落盘）适配 lib 侧 [StartupProbeSeam]。映射 = 纯分类投影：
 * Normal ⇒ null（正常态静默零消息）；Anomaly / ProbeFailed ⇒ 模板消息文本原样上送
 * （异常态与探查失败都出推送——探查失败如实报告不静默），lib 门面经既有出站面发默认
 * 会话（connection.testChatId）。仅 App 进程内使用（CLI 形态不注入 ⇒ 恒不探查）；
 * 单测注入 fake 缝或直测 [messageOf]。
 */
internal object AppStartupProbe {

    /** 唯一探查器实例（无状态：探查数据全在调用栈内，审计器并发追加安全） */
    private val prober = StartupProber()

    /** lib 缝实现（LarkBridgeManager 装配点注入 BridgeAssembler；是否执行只由本缝是否注入 + connection.testChatId 是否配置决定，无配置开关） */
    val seam: StartupProbeSeam = StartupProbeSeam { messageOf(prober.runStartupProbe()) }

    /** 分类投影：正常态 null 静默；异常/探查失败都出模板消息（lib 侧不区分，统一外发） */
    internal fun messageOf(outcome: StartupProbeOutcome): String? = when (outcome) {
        is StartupProbeOutcome.Normal -> null
        is StartupProbeOutcome.Anomaly -> outcome.message
        is StartupProbeOutcome.ProbeFailed -> outcome.message
    }
}
