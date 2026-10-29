package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 宿主 xlog 私钥缓存：私钥真源变更流（[keyFlow]）→ [keyRef] 事件化写穿。
 *
 * 契约：
 * - [keyRef] 是回落缝（BridgeAssembler 的 `xlogKeyFallback`）的同步读源——lib 侧闭包
 *   非挂起，读不到 DataStore，私钥必须由本类在变更时**提前写穿**到 ref（collect 事件化：
 *   保存即发射即热更，无轮询陈旧窗口）；
 * - 发射 null（key 未配置/被清空重建）归一为空串——与「未配置」同一判定面，回落富化
 *   判据 `isBlank` 不区分两者；
 * - [seed] 单次挂起读当前值：装配期的同步读契约（collect 首帧异步到达，回落缝第一拍
 *   等不起首帧，必须装配点先种好）；
 * - [watchIn] 在注入作用域 collect；读取异常只 warn 并按 [RETRY_MILLIS] 重试
 *   （对齐旧刷新回路「失败不炸、下拍再试」容错口径），作用域取消即收口（宿主 shutdown
 *   取消网关作用域时一并收口）。
 *
 * @param keyFlow 私钥真源变更流（生产 = `DecryptTools.defaultPrivateKeyFlow`；测试注入
 *   临时实例的 [com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager.valueFlow]）
 */
internal class HostXlogKeyCache(
    private val keyFlow: Flow<String?>,
) {

    private companion object {
        private const val TAG = "HostXlogKeyCache"

        /** collect 异常退避重试间隔（对齐 Koog 端点回路的刷新周期档）。 */
        private const val RETRY_MILLIS = 1_000L
    }

    /** 回落缝同步读源（未刷新前为空串 = 未配置）。 */
    val keyRef = AtomicReference("")

    /** 单次挂起读当前值（装配期种子；读取异常上抛，由装配点 runCatching 容错）。 */
    suspend fun seed() {
        keyRef.set(keyFlow.first().orEmpty())
    }

    /** 在 [scope] collect 变更流（写穿 [keyRef]）；随 scope 取消收口。 */
    fun watchIn(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { keyFlow.collect { keyRef.set(it.orEmpty()) } }
                    .onFailure {
                        JLog.w(TAG) { "宿主 xlog 私钥变更流中断（${RETRY_MILLIS}ms 后重试）：${it.message}" }
                    }
                delay(RETRY_MILLIS)
            }
        }
    }
}
