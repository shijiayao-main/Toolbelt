package com.jiaoay.toolbelt.device

/**
 * 设备信息（`adb devices -l` 输出解析：串号 + 型号，型号缺失为空串）
 */
data class DeviceInfo(
    val serial: String,
    val model: String,
) {
    /** 展示标识：型号优先，缺失回退串号 */
    val displayLabel: String get() = model.ifEmpty { serial }
}

/**
 * 设备连接状态
 */
sealed class DeviceState {
    data object None : DeviceState()
    data class Single(val serial: String, val model: String) : DeviceState()
    data class Multi(val devices: List<DeviceInfo>) : DeviceState() {
        val count: Int get() = devices.size
    }
}
