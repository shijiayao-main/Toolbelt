package com.jiaoay.lark.bridge.config

/**
 * 默认配置常量（[CDS §7]）。
 *
 * 设计纪律：本 object 只持有「字面量常量」（DEFAULT_ENV_ALLOWLIST / DEFAULT_ROUTES），
 * **不得**声明 `val DEFAULT = BridgeConfig()` 字段——否则 BridgeConfig() 的默认参数引用本 object 常量时，
 * 会在 object 初始化途中重入 BridgeConfig() 构造，触发 Kotlin object 部分初始化的隐患。
 * 需要默认实例时调用 [default]（函数体在 object 完成初始化后才执行，无重入）。
 */
object DefaultBridgeConfig {

    /**
     * workspace.envAllowlist 默认值（[CDS §3.2.3]）。
     * 逐字来自旧 SandboxedProcessFactory.BASE_ENV_KEYS 的 11 键（含 http_proxy/https_proxy/no_proxy 三个小写代理变体）。
     * 🔴 不是方案的 8 键——照方案实现会导致 Linux 下 agent 子进程静默失去代理能力。
     */
    val DEFAULT_ENV_ALLOWLIST: List<String> = listOf(
        "PATH", "HOME", "LANG", "LC_ALL", "TZ",
        "http_proxy", "https_proxy", "no_proxy",
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
    )

    /**
     * 默认 routes（SYSTEM + FILE 两槽 + AgentRoute 空槽载体）。
     *
     * 各条目去向：
     * - /help 固定回复：**已删除**（REPLY 槽整槽退役）——SystemHandler 内置化；
     * - AgentRoute：**保留为空槽载体**——匹配键（triggers/resetCommand/stopCommand）已退役
     *   （真源 = EngineReservedWords 常量 + retiredV8 旧词，AgentHandler 不再读 route 字段），
     *   但槽位本身是 CLI 形态 `ai` 前缀直跑的装配前提——CLI 用户零自定义
     *   routes 时靠默认槽命中（App 形态该槽被调度层恒短路，消息恒归调度 agent）；
     * - SystemRoute / FileRoute 保留（系统指令集 / 文件登记总开关的 enable 载体）。
     *
     * 产品约定延续：通用桥接底座不得内置业务专属指令；
     * `BridgeConfigDefaultsTest` 负向守护断言钉死业务字样不得回流。
     */
    val DEFAULT_ROUTES: List<RouteRule> = listOf(
        SystemRoute(id = "builtin", enabled = true),
        AgentRoute(id = "default", enabled = true),
        FileRoute(id = "file-analysis", enabled = true),
    )

    /** 默认配置实例（schemaVersion=CURRENT_VERSION，全字段取默认值，含 3 条默认 routes）。 */
    fun default(): BridgeConfig = BridgeConfig()
}
