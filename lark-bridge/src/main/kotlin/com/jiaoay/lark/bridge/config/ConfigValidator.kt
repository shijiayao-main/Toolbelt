package com.jiaoay.lark.bridge.config

/**
 * 一条校验问题（[CDS §8.1]）。fieldId 对应 BridgeField.id，UI 用于字段级 isError 标记。
 */
data class ConfigIssue(
    val fieldId: String,
    val message: String,
)

/**
 * 配置校验器（[CDS §8 / config-domain-spec.md §16.5]，纯函数）。
 *
 * **validate 只做三类检查**（config-domain-spec.md §16.5 通则，规范性条款）：
 * 1. 数值区间 —— Editor.Number 的 min/max，用 **coerceIn 语义钳制到边界（不阻断落盘、不产 issue）**，
 *    由 UI 渲染层/运行时钳制（如 confirm.timeoutSeconds 的 MIN_CONFIRM_TIMEOUT_SECONDS=10L）承接；
 * 2. 必填非空白 —— **仅** connection.appId / connection.appSecret，且**仅当** connection.autoStart
 *    或凭据部分填写时必填（凭据全空 = 尚未开始配置的合法中间态）；
 * 3. 枚举值合法性 —— Editor.Choice 的 options 内。
 * 外加一条纯字符串跨字段规则（config-domain-spec.md §8.2）：自定义预处理命令的 {input} 占位符。
 *
 * 🔴 **任何需要磁盘 stat、网络探测、或依赖外部形态假设（正则/长度/前缀格式）的检查一律不进 validate**（config-domain-spec.md §16.5）：
 * appId 正则、appSecret 长度、workspace.root 格式校验已被裁定删除（误拒会把用户彻底锁在门外）。
 */
object ConfigValidator {

    fun validate(config: BridgeConfig): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()

        // 逐字段 validate lambda（当前全为默认 {null}；保留遍历以承接未来新增的单字段校验）
        BridgeFields.ALL.forEach { field ->
            @Suppress("UNCHECKED_CAST")
            val f = field as BridgeField<Any?>
            f.validate(f.get(config))?.let { msg -> issues += ConfigIssue(field.id, msg) }
        }

        // 检查 3：枚举值合法性（Editor.Choice.options）
        BridgeFields.ALL.forEach { field ->
            val editor = field.editor
            if (editor is Editor.Choice) {
                val value = field.get(config) as? String
                if (value != null && value !in editor.options) {
                    issues += ConfigIssue(field.id, "值 \"$value\" 不在允许范围内：${editor.options.joinToString(" / ")}")
                }
            }
        }

        // 检查 2：必填非空白（🔴 不能让「必填」在凭据全空时触发——否则全新用户在向导里
        //    先改任何一个字段都会被「校验失败不落盘」卡死。凭据必填只在「用户真在配凭据
        //    但没配完」（部分填写）或「确实要自动启动」时成立；
        //    凭据全空 = 尚未开始配置，属合法中间态，
        //    由 [Bridge.start] 的启动拒绝点兜底（空 appId ⇒ Refused，文案具体）。）
        val credentialsPartiallyFilled =
            config.connection.appId.isNotBlank() || config.connection.appSecret.isNotBlank()
        if (config.connection.autoStart || credentialsPartiallyFilled) {
            if (config.connection.appId.isBlank()) {
                issues += ConfigIssue("connection.appId", "已开始配置凭据或开启自动启动时 App ID 必填")
            }
            if (config.connection.appSecret.isBlank()) {
                issues += ConfigIssue("connection.appSecret", "已开始配置凭据或开启自动启动时 App Secret 必填")
            }
        }

        // 跨字段（config-domain-spec.md §8.2）：自定义预处理命令非空时须含 {input} 占位符
        //    （否则命令拿不到文件路径，等于配死命令）
        if (config.fileMessage.preprocessCommand.isNotBlank() &&
            !config.fileMessage.preprocessCommand.contains("{input}")
        ) {
            issues += ConfigIssue("fileMessage.preprocessCommand", "自定义预处理命令必须包含 {input} 占位符")
        }

        // 第二道防线：routes 数组内匹配键重复（用户手工编辑也可能造成）——只产 issue、不阻断落盘
        issues += findDuplicateMatchingKeys(config.routes)

        return issues
    }

    /**
     * 检测 routes 数组内**匹配键相同**的条目对（纯函数，不阻断落盘，只产 [ConfigIssue]）。
     *
     * 匹配键定义（与 [LegacyDocumentMapper.mergeRoutes] 一致）：
     * - [CommandRoute] → `aliases` 集合（任一别名重叠即冲突）
     * - [AgentRoute] → 同 kind 单例（匹配键已随 routes 数据退役）
     * - [SystemRoute] / [FileRoute] → 按 kind 单例（同 kind 出现两条即冲突）
     */
    private fun findDuplicateMatchingKeys(routes: List<RouteRule>): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        for (i in routes.indices) {
            for (j in i + 1 until routes.size) {
                val a = routes[i]
                val b = routes[j]
                if (isMatchingKeyDuplicate(a, b)) {
                    issues += ConfigIssue(
                        "routes",
                        "路由 \"${a.id}\"（#${i}）与 \"${b.id}\"（#${j}）匹配键重复，" +
                            "后者永远不会被命中（数组前者胜）",
                    )
                }
            }
        }
        return issues
    }

    /** 判定两条 route 是否匹配键重复（同 kind 才可能；跨 kind 不冲突）。 */
    private fun isMatchingKeyDuplicate(a: RouteRule, b: RouteRule): Boolean {
        if (a::class != b::class) return false
        return when (a) {
            is CommandRoute -> {
                val bb = b as CommandRoute
                a.aliases.any { it in bb.aliases }
            }
            // AgentRoute 的撞名检测已随 routes 匹配键退役删除（triggers 字段不复存在）；
            // 引擎保留词与用户自定义词的并存去重由 EngineReservedWords 承担。
            is AgentRoute -> true
            is SystemRoute -> true
            is FileRoute -> true
        }
    }
}
