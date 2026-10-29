package com.jiaoay.easy_dev_toolbox.core.initializer

import androidx.annotation.IntRange

interface JInitializer {
    val isHighLevel: Boolean

    /**
     * 高优先级的失败了直接阻断后续进行
     */
    suspend fun init(): Boolean

    @IntRange(from = 1, to = 10)
    fun level(): Int {
        return 7
    }
}
