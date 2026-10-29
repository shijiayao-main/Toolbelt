// contact.v3.user.get 失败诊断（猜测性告警升级为携带真实错误码的可判读告警）。
// 全部为顶层 internal **纯函数**（无状态 ⇒ 可直接单测，无需 mock SDK 响应对象），
// 与 SdkLarkGateway 类零状态耦合；调用点在该类的 resolveSenderName / resolveSenderProfile 旁路。
package com.jiaoay.lark.gateway

import java.util.concurrent.atomic.AtomicBoolean

/**
 * openId 掩码：前 6 + `****` + 后 4（与桥接侧 `maskSecret` 前 4 后 4 的风格一致，此处按任务口径取前 6 后 4）。
 * 🔴 长度 <= 10 时前后 6/4 会重叠还原出完整值 ⇒ 退化为全掩码（与 `maskSecret` 的短值全掩码同理）。
 * 隐私硬约束：contact 诊断 WARN 里**不得出现完整 openId**（`ContactDiagnosticsTest` 钉死）。
 */
internal fun maskOpenId(openId: String): String =
    if (openId.length <= 10) "*".repeat(openId.length)
    else openId.take(6) + "****" + openId.takeLast(4)

/**
 * messageId 掩码：前 6 + `****` + 后 4（与 [maskOpenId] 同风格；引用补查 WARN 里的隐私守卫）。
 */
internal fun maskMessageId(messageId: String): String =
    if (messageId.length <= 10) "*".repeat(messageId.length)
    else messageId.take(6) + "****" + messageId.takeLast(4)

/**
 * `resp.success() == false` 分支的诊断文本：飞书返回的 **code/msg 原文** + 四种可能原因的排查清单。
 * 四种原因的处置完全不同（加 scope / 等发版生效 / 改通讯录授权范围 / 重启进程刷 token 缓存），
 *    缺了真实 code/msg 就只能猜——这是诊断盲区本体。
 * @param code `BaseResponse.code`（int 原样，不做任何映射——映射会丢真值）
 * @param msg  `BaseResponse.msg` 原文（可能为 null）
 */
internal fun contactFailureDiagnostics(openId: String, code: Int, msg: String?): String =
    "contact.v3.user.get 失败 sender=${maskOpenId(openId)}：飞书返回 code=$code msg=\"${msg.orEmpty()}\"。" +
        "四种可能原因（按 code/msg 对号入座）：" +
        // 真机实证（code=99991672 的 msg 给出精确名单）：contact:user.base:readonly 已更名——
        //    现取值 contact:contact.base:readonly（任一 readonly 变体均可）；旧名在后台已搜不到，写了会误导排障。
        "① scope contact:contact.base:readonly 未添加（旧名 contact:user.base:readonly 已更名；" +
        "code=99991672 的 msg 原文会给出完整候选名单，任一 readonly 变体即可）；" +
        "② 已添加但应用未发版或发版未生效（有几分钟传播延迟）；" +
        "③ 🔴 scope 已生效但「通讯录授权范围」未覆盖该用户——飞书后台「权限管理 → 通讯录授权范围」需包含该用户或设为全部成员（极常见坑：有 scope 仍返回权限错误）；" +
        "④ tenant_access_token 缓存早于发版（SDK LocalCache 缓存约 2 小时，重启进程可强制刷新）。" +
        "后续同类失败不再重复告警（每进程只 WARN 一次）。senderName 将为空，审计/统计以 open_id 兜底。"

/**
 * 抛异常分支的诊断文本：只取 **class 简单名 + message**。
 * 🔴 不打完整堆栈（会刷屏）、不打 toString（可能含 token 等敏感上下文——toString 泄漏即凭据外泄）。
 */
internal fun contactExceptionDiagnostics(openId: String, t: Throwable): String =
    "contact.v3.user.get 异常 sender=${maskOpenId(openId)}：${t.javaClass.simpleName}: ${t.message.orEmpty()}。" +
        "（异常路径无飞书 code/msg；网络或 SDK 层故障，检查代理与长连接状态。）" +
        "后续同类失败不再重复告警（每进程只 WARN 一次）。senderName 将为空，审计/统计以 open_id 兜底。"

/**
 * 「每进程只 WARN 一次」的限流判据（CAS 语义与原 `contactScopeWarned.compareAndSet(false, true)` 逐字等价，
 * 抽成纯函数只为可单测（真机实证 9 条消息只告警 1 次，该语义不得为诊断信息而破坏）。
 */
internal fun shouldWarnContactScope(warned: AtomicBoolean): Boolean = warned.compareAndSet(false, true)
