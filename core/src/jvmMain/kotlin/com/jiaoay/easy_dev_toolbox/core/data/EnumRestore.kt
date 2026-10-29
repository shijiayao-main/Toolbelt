package com.jiaoay.easy_dev_toolbox.core.data

/**
 * 按枚举名从持久化存储恢复枚举值，名字非法或缺失时回退默认值。
 * 统一收敛 AppViewModel 等处的 try-catch valueOf 恢复样板。
 */
suspend inline fun <reified T : Enum<T>> loadEnum(name: String, default: T): T {
    if (name.isBlank()) return default
    return try {
        enumValueOf<T>(name)
    } catch (_: Exception) {
        default
    }
}
