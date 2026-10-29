package com.jiaoay.easy_dev_toolbox.page.larkbridge

/**
 * 列表型配置的**提交短路**（纯函数）。
 *
 * ## 为什么必须短路
 * 提交列表配置（快捷指令 / 白名单等）时若新值与旧值完全相同也触发 runtime 更新，
 * 会导致**整桥重建断连**（用户改一个无关字段就可能掐断长连接）。
 *
 * ## 语义
 * 相同值 → `return false` 且**零副作用**（[commit] 回调不执行，persist/runtime 计数 == 0）；
 * 变化值 → 执行一次 [commit] 后 `return true`（persist + runtime 各 1 次）。
 * runtime 更新本身已是 `gateway.updatePolicy()` 热更新（不断连），叠加本短路后「相同值零扰动」双重保证。
 *
 * 由 [LarkBridgeViewModel] 的列表提交消费，由 `ListConfigCommitTest` 断言。
 *
 * 零 Compose、零 IO、零 .dp——纯列表相等性判定。
 */
object ListConfigCommit {

    /**
     * 提交列表配置：[current] 与 [next] 相等则短路（`return false`，不调 [commit]）；
     * 否则调一次 [commit] 并 `return true`。
     *
     * @param current 当前列表值
     * @param next 目标列表值
     * @param commit 变化时执行一次的副作用（persist / runtime 更新）
     * @return 是否实际发生了提交（`false` = 相同值短路）
     */
    fun <T> commitListConfig(
        current: List<T>,
        next: List<T>,
        commit: (List<T>) -> Unit,
    ): Boolean {
        if (current == next) return false
        commit(next)
        return true
    }
}
