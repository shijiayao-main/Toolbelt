package com.jiaoay.lark.bridge.observe

/**
 * 启动自检宿主缝：桥接长连接建立后，门面在 start 成功路径调用**一次**
 * （本缝在位 + testChatId 已配置，两闸缺一当次跳过；config 开关已随 v13→v14 退役）。
 *
 * ## 形态（与 AudioTranscriberSeam 同型的宿主缝）
 * 探查本体在宿主侧（sys.probe 编译期 argv 目录与 sys-audit 审计都属宿主域，lib 是
 * jvmTarget 11 纯 JVM 互不可见）——lib 只定义「跑一次、回一段文本」的最小契约，
 * CLI/测试形态不注入（null）恒不探查。
 *
 * ## 实现侧契约
 * - 跑一次确定性机器健康探查（固定 argv 子集，零 LLM 零 agent）并落审计；
 * - 返回 null = 正常态（门面静默零消息）；非 null = 模板消息文本（确定性异常态或
 *   探查失败的如实报告），门面经既有出站面原样发默认会话（不追加不裁剪）；
 * - 业务失败（单项命令缺失/超时等）由实现折叠进返回文本，不抛业务异常；
 * - 协程取消（CancellationException）原样上抛；挂起安全（门面在旁路协程调用，
 *   不阻塞 start 返回）。
 */
fun interface StartupProbeSeam {
    /** 跑一次启动自检；null = 正常态静默，非 null = 模板消息文本 */
    suspend fun runStartupProbe(): String?
}
