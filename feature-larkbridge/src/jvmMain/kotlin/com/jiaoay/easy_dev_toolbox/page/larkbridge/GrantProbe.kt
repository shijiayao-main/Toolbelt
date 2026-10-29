package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.NativeToolGrant
import com.jiaoay.lark.bridge.observe.JsonlTailReader
import com.jiaoay.lark.gateway.GatewayConnectionState
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.MessageGateway
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * L2 授权清单的探测验证域（「探测验证」分区）。
 *
 * ## 探测语义（synthesis「二、L2 授权完整走查」第 5-7 条逐条落实）
 * - **只读**：探测调用全部是 read-only API（listMessages pageSize=1 / getChatInfo / resolveSenderProfile），
 *   不发消息、不建群、不写任何飞书侧状态；
 * - **手动触发**：仅用户点「验证」按钮时执行，零自动重试、零轮询；
 * - **失败不重试**：单次调用定生死，失败态落红 pill 等用户再点；
 * - **不阻塞落盘**：探测在页面侧独立协程跑，与 vm.updateField 落盘链零交集；
 * - **结果不落盘**：三态是**运行时观察值**（Map 存 VM 内存 + remember，重启即失）——
 *   「桌面授权（l2Grants）与后台授权（scope+发版）是两道独立闸」，本探测只观察第二道闸的当下状态，
 *   不是配置、不进 bridge-config.json。
 *
 * ## 探测接缝
 * 探测动作收口 lib 门面 [com.jiaoay.lark.bridge.Bridge.probeNativeGrant]——**经运行中网关**的
 * 只读探测（VM 经 `store.probeNativeGrant` 窄口调用，与 sendTest 同门面形态）。
 * 🔴 防回退红线：勿走 [BridgeGateways.resolve] 另造一次性网关并 connect——
 *    `BridgeGateways.sdk()` 恒注入 WsClientTransportFactory ⇒ connect 会开**第二条 :lark-ws
 *    入站长连接**，触发「同 appId 双长连接被服务端互踢」——桥接运行中点「验证」会把自己
 *    踢下线。探测必须走 lib 门面（见上）；桥接未运行时 UI 置灰不探测（红线 2）。
 */
object GrantProbe {

    // ───────────────────────── 三态（运行时观察值，不落盘） ─────────────────────────

    sealed interface VerifyState {
        /** 未验证（含刚勾选）：琥珀 pill「已授权，未验证」。 */
        data object NotVerified : VerifyState

        /** 探测中：pill 显示进行中措辞，验证按钮禁用。 */
        data object Probing : VerifyState

        /** 探测通过：绿 pill「已验证 HH:mm」。 */
        data class Verified(val atMillis: Long) : VerifyState

        /** 探测失败：红 pill「<错误码人话> · 去后台开通」。 */
        data class Failed(val errorText: String) : VerifyState
    }

    /** 需要网络探测的 grant（全部六项：读文档两项经用户粘贴的链接探测——见 [Bridge.probeNativeGrant]）。 */
    val PROBABLE_GRANTS: List<NativeToolGrant> = listOf(
        NativeToolGrant.CHAT_HISTORY,
        NativeToolGrant.CHAT_INFO,
        NativeToolGrant.LOOKUP_USER,
        NativeToolGrant.READ_WIKI,
        NativeToolGrant.READ_DOC,
    )

    /** 该 grant 是否有探测动作（false = UI 显示文字引导，不显示验证按钮）。 */
    fun supportsProbe(grant: NativeToolGrant): Boolean = grant in PROBABLE_GRANTS

    fun latestSenderOpenId(dataRoot: Path, config: BridgeConfig): String? {
        val stats = runCatching {
            JsonlTailReader.readStatsAll(dataRoot.toString()).records
        }.getOrDefault(emptyList())
        stats.lastOrNull { it.type == "MESSAGE" && it.senderOpenId.isNotBlank() }?.let { return it.senderOpenId }
        return config.access.trustedUsers.firstOrNull { it.isNotBlank() }
    }

    /** 按 grant 的默认失败文案（无信号 / 无码场景的兜底人话）。 */
    fun defaultFailText(grant: NativeToolGrant): String = when (grant) {
        NativeToolGrant.CHAT_HISTORY -> "历史消息不可读（scope 未开通或会话不可见）"
        NativeToolGrant.CHAT_INFO, NativeToolGrant.CHAT_MANAGE -> "群信息不可读（scope 未开通或会话不可见）"
        NativeToolGrant.LOOKUP_USER -> "用户不可解析（scope 未开通或不在通讯录授权范围）"
        NativeToolGrant.READ_WIKI -> "wiki 不可读（scope 未开通、发版未生效或节点不在应用可见范围）"
        NativeToolGrant.READ_DOC -> "文档不可读（scope 未开通、发版未生效或文档不在应用可见范围）"
    }

    // ───────────────────────── 错误码人话（GatewayErrorCode 穷尽 when） ─────────────────────────

    /** 单次探测超时窗（connect 与探测调用各一份；够三次网络 RTT，不拖 UI）。 */
    const val PROBE_TIMEOUT_MILLIS: Long = 15_000L

    fun GatewayErrorCode.humanText(): String = when (this) {
        GatewayErrorCode.ConnectFatal -> "连接失败（凭据或发版问题）"
        GatewayErrorCode.ConnectRetryable -> "网络抖动，可再点验证"
        GatewayErrorCode.BlockedByPeerInstance -> "同 App ID 已有实例运行"
        GatewayErrorCode.NotConnected -> "探测连接未就绪"
        GatewayErrorCode.FormatError -> "会话 ID 格式错误"
        GatewayErrorCode.TargetRevoked -> "会话已解散或用户已注销"
        GatewayErrorCode.PermissionDenied -> "权限不足"
        GatewayErrorCode.UploadFailed -> "上传失败"
        GatewayErrorCode.SsrfBlocked -> "地址被安全策略拦截"
        GatewayErrorCode.SendTimeout -> "探测超时"
        GatewayErrorCode.SendFailed -> "请求被拒绝"
        GatewayErrorCode.RateLimited -> "被飞书频控，稍后再试"
        GatewayErrorCode.CardSequenceConflict -> "卡片序号冲突"
        GatewayErrorCode.CardTooLarge -> "卡片超限"
        GatewayErrorCode.CardStreamBusy -> "卡片流忙碌"
        GatewayErrorCode.CardEntityExpired -> "卡片实体过期"
        GatewayErrorCode.CardJsonInvalid -> "请求结构错误"
        GatewayErrorCode.DownloadFailed -> "下载失败"
        GatewayErrorCode.DownloadTooLarge -> "下载超限"
        GatewayErrorCode.ReactionFailed -> "表情回应失败"
        GatewayErrorCode.RegisterQrExpired -> "二维码已过期"
        GatewayErrorCode.RegisterInvalidArgument -> "参数不合法"
        GatewayErrorCode.RegisterFailed -> "建应用失败"
        GatewayErrorCode.Unknown -> "未知错误"
    }
}
