// :lark-gateway —— 全仓唯一允许 import com.lark.oapi.* 的模块（AGENTS.md 第 8 条）。
// 本文件：扫码创建自建应用（gateway-contract.md §8）。第 3 个也是最后一个允许 import SDK 的文件。
// 本文件是 object 声明，但体内零 @Volatile var（registerDispatcher 是 val）⇒ 不触发静态可变状态守护。
// 不暴露为 public（internal），:lark-bridge 经 MessageGateway.registerApp 契约成员访问。
package com.jiaoay.lark.gateway

import com.lark.oapi.scene.registration.RegisterApp
import com.lark.oapi.scene.registration.RegisterAppOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫码建应用流程。
 *
 * 三要点：
 *  1. 专用单线程 dispatcher `bridge-register`：`register()` 阻塞最长约 600s，绝不占 commonPool、绝不占 `net` 的 8 个槽。
 *     `limitedParallelism` 返回的是 `Dispatchers.IO` 的**视图**，不持有线程 ⇒ 无需 shutdown、不构成泄漏。
 *  2. `onQRCode` 回调在 SDK 线程上执行，只做转发（不 IO / 不挂起）；跨线程改 Compose 状态由 UI 侧负责。
 *  3. **不可中断**：外层取消不会中断 SDK 内部轮询（SDK 无 cancel 钩子）。宿主侧用 `Job.isActive`
 *     忽略后续回调，把流程协程挂在 `LarkBridgeManager` 作用域而非页面作用域，暴露 `StateFlow<RegisterAppState>`。
 */
internal object RegisterAppFlow {

    /** 网关私有 dispatcher：`IO.limitedParallelism(1, "bridge-register")`，同时只允许一个扫码流。 */
    private val registerDispatcher = Dispatchers.IO.limitedParallelism(1, "bridge-register")

    /**
     * @param regionHint `RegisterApp` 不返回 region，由 [SdkLarkGateway] 从当前 [LarkCredential.region] 透传（gateway-contract.md §8.3 规格 6）。
     * @param onQr 二维码回调 `(qrUrl, expireInSec)`：qrUrl = `QRCodeInfo.getUrl()`（二维码图片 URL，UI 侧渲染），
     *             expireInSec = `QRCodeInfo.getExpireIn()`（有效期秒，UI 侧倒计时）。
     * @return 新凭据：appId = `getClientId()`（飞书把 client_id 等同 app_id）、appSecret = `getClientSecret()`（toString 已掩码）。
     * @throws GatewayException 扫码失败（12 成员里唯一允许抛异常的路径，gateway-contract.md §8.3 规格 4）。
     */
    suspend fun run(regionHint: String, onQr: (String, Int) -> Unit): LarkCredential =
        withContext(registerDispatcher) {
            val result = runCatching {
                RegisterApp.register(
                    RegisterAppOptions.newBuilder()
                        // 🔴 Consumer<QRCodeInfo> SAM；回调内只转发（gateway-contract.md §8.3 规格 2）——SDK 线程上做重活会卡死注册轮询。
                        .onQRCode { info -> onQr(info.url, info.expireIn) }
                        .build()
                )
            }
            result.fold(
                onSuccess = { LarkCredential(appId = it.clientId, appSecret = it.clientSecret, region = regionHint) },
                onFailure = { throw mapRegisterFailure(it) },
            )
        }

    /**
     * 异常按类名 endsWith 映射（gateway-contract.md §6.3.1 纪律：不 import SDK 异常类型，用反射类名匹配容忍 SDK 升级）。
     * 🔴 `ExpiredException extends RegisterAppException`，故必须先判 Expired 再判 RegisterApp——判序颠倒则二维码过期被误报为普通失败。
     */
    private fun mapRegisterFailure(t: Throwable): GatewayException = when {
        t::class.java.name.endsWith("ExpiredException") ->
            GatewayException(GatewayErrorCode.RegisterQrExpired, "二维码已过期，请重新发起", t)
        t::class.java.name.endsWith("RegisterAppException") ->
            GatewayException(
                if (t.message?.contains("INVALID_ARGUMENT") == true) GatewayErrorCode.RegisterInvalidArgument
                else GatewayErrorCode.RegisterFailed,
                t.message ?: "扫码创建应用失败",
                t,
            )
        else -> GatewayException(GatewayErrorCode.RegisterFailed, t.message ?: "unknown", t)
    }
}
