package com.jiaoay.easy_dev_toolbox.core.initializer

/**
 */
sealed class InitResult(val isHighLevel: Boolean) {
    override fun toString(): String {
        return when (this) {
            is Success -> {
                "InitResult.Success(isHighLevel=$isHighLevel)"
            }

            is Failed -> {
                "InitResult.Failed(isHighLevel=$isHighLevel, failedList=$failedList)"
            }
        }
    }

    class Success(isHighLevel: Boolean) : InitResult(isHighLevel = isHighLevel)

    class Failed(
        isHighLevel: Boolean,
        val failedList: List<Class<*>>,
    ) : InitResult(isHighLevel = isHighLevel)
}

fun InitResult?.highLevelIsSuccess(): Boolean {
    this ?: return false

    if (this is InitResult.Success) {
        return true
    }

    if (this is InitResult.Failed && isHighLevel) {
        return false
    }
    return true
}
