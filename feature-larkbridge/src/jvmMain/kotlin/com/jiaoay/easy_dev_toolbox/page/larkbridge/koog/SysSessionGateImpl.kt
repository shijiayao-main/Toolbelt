package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.SysReadConfigTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.SysSessionGate
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.confirm.TrustPolicy
import java.io.File

/**
 * sys.* 门控面（桥接链实现，[SysSessionGate] 契约的装配侧）。
 *
 * ## 授权模型：按用户信任，不按会话
 * `requireSessionAuthorization` 只回答一个问题——**本消息发送者是否在信任名单内**
 * （[TrustPolicy.isTrusted]）：名单内 ⇒ 放行；名单外 ⇒ 拒绝。名单持久化于
 * `bridge-config.json`，判定恒按人、无会话态、无卡面——群聊里任一成员获批不会外溢到
 * 其他发送者，重启也不失效。
 *
 * 高风险动作不受本闸影响：写配置/修复/服务各经 [KoogSysWriteGate] 等逐动作确认卡，
 * 与信任档无关（信任免的是「会话域准入」，不是「每次写系统的批准」）。
 *
 * ## [familyEscapeGate] 保留
 * 族内相对路径的 HITL 缝仍在位：信任用户的可读族根登记由装配侧完成，
 * 本缝只判「该路径是否落在任一允许族根内」——与授权模型解耦，是路径越界的第二道防线。
 *
 * @param trustedUsersProvider 读当前 `access.trustedUsers`（HOT：名单变更下一条消息即生效）
 * @param senderOpenId 本消息发送者（唯一判定维度）
 * @param sysContext sys 专用围栏 context（族根 register 的落点）
 */
internal class KoogSysSessionGate(
    private val trustedUsersProvider: () -> List<String>,
    private val senderOpenId: String,
    /** sys 专用围栏 context（族根 register 进允许集的落点） */
    private val sysContext: ToolExecutionContext,
) : SysSessionGate {

    override val trigger: String = "sender=$senderOpenId"

    /** 本消息发送者是否信任用户（逐动作 Gate 复用——信任免逐动作卡，见 [TrustPolicy]）。 */
    internal fun isTrustedSender(): Boolean =
        TrustPolicy.isTrusted(AccessConfig(trustedUsers = trustedUsersProvider()), senderOpenId)

    /**
     * 族内相对路径的 HITL 缝：只要路径落在任一 sys 路径族根内即放行。
     * 族根清单是编译期单源（[SysReadConfigTools.familyRoots]），不含用户可配面。
     */
    private val familyGate = object : ToolExecutionContext.PathEscapeGate {
        override suspend fun onRequestAccess(path: File): Boolean {
            val home = File(System.getProperty("user.home"))
            return SysReadConfigTools.isInsideAnyFamilyRoot(home, path)
        }
    }

    override val familyEscapeGate: ToolExecutionContext.PathEscapeGate get() = familyGate

    /**
     * 本消息发送者是否可进入 sys.* 域。
     *
     * 已信任 ⇒ 把全部族根 register 进允许集（幂等，重复 register 无副作用）后放行。
     *
     * @param toolName 触发工具名（保留形参以稳调用点与审计面）
     */
    override suspend fun requireSessionAuthorization(
        @Suppress("UNUSED_PARAMETER") toolName: String,
    ): Boolean {
        val access = AccessConfig(trustedUsers = trustedUsersProvider())
        if (!TrustPolicy.isTrusted(access, senderOpenId)) return false
        val home = File(System.getProperty("user.home"))
        SysReadConfigTools.familyRoots(home).forEach { sysContext.register(it) }
        return true
    }
}

/**
 * 族内相对路径的 HITL 缝（sys 专用 context 的 escapeGate，独立形态供装配侧直接使用）。
 */
internal class SysFamilyEscapeGate : ToolExecutionContext.PathEscapeGate {

    override suspend fun onRequestAccess(path: File): Boolean {
        val home = File(System.getProperty("user.home"))
        return SysReadConfigTools.isInsideAnyFamilyRoot(home, path)
    }
}
