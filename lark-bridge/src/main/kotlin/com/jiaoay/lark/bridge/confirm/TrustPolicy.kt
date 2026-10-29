package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.config.AccessConfig

/**
 * 信任用户判定：高风险操作「免逐动作确认」的唯一授权真源。
 *
 * ## 为什么它是独立一层
 * 桥接改造后 `access` 段只剩 [AccessConfig.trustedUsers]，而入站准入恒放行
 * （[com.jiaoay.lark.bridge.pipeline.AccessControl]）。若不做信任分级，防替答机制
 * （只有发起者本人能裁决确认卡）等价于**让任意陌生人自行批准自己的破坏性操作**——
 * 确认卡从「保护」退化成「仪式」。
 *
 * ## 判定面只有一条
 * [isTrusted]：消息发送者是否在名单内。调用方据此二选一：
 *
 * | 场景 | 信任用户 | 非信任用户 |
 * |---|---|---|
 * | sys.* / dev.* / ws.* 域准入（`requireSessionAuthorization`） | 放行 | **拒绝**（进不了域） |
 * | 写 / 修复 / 服务 / 跑任务（`requireActionApproval`） | 免卡直接执行 | 逐动作确认卡 |
 *
 * 非信任用户被挡在域外 ⇒ 「越界 + 非信任 = 硬拒绝」天然成立，不需要额外的分级裁决函数。
 * `RiskClassifier` 的 FORBIDDEN 档（`rm -rf /` 等）由 MCP 路径与工具本体各自兜底，
 * 与本类无关。
 *
 * ## 纯函数零状态
 * 无缓存、无 IO、无挂起——配置每调用现读（HOT：名单变更下一条消息即生效）。
 */
object TrustPolicy {

    /**
     * sender 是否在信任名单内。
     *
     * 名单项与 sender 双方去空白后精确比较（与准入层同一 `sanitize` 口径：
     * 空白项不构成授权）。空 openId 恒不信任（防「匿名发送者命中空串项」的意外放行）。
     */
    fun isTrusted(access: AccessConfig, senderOpenId: String): Boolean {
        val sender = senderOpenId.trim()
        if (sender.isEmpty()) return false
        return access.trustedUsers.any { it.trim() == sender }
    }
}
