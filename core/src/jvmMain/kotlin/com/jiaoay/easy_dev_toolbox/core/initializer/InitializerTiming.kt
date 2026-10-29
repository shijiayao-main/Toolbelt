package com.jiaoay.easy_dev_toolbox.core.initializer

/**
 * 单个初始化器的启动耗时观测记录。纯观测面：不参与 [InitResult] 的成败语义，
 * 启动失败短路时记录可能不完整（发射契约见 [JInitializerManager.initTimingsFlow]）。
 *
 * @property initializerClass 初始化器实现类（与 [InitResult.Failed] 的 failedList 同一定位口径）
 * @property isHighLevel 所属分组（true = 高优并发组，false = 低优串行错峰组）
 * @property level 组内执行序（1..10，数字越小越早）
 * @property isSuccess init() 返回 true 且未抛异常
 * @property durationNanos init() 本体耗时（System.nanoTime 口径）。
 *   不含低优组相邻级别之间的 1 秒错峰 delay——耗时只度量 init() 自身，
 *   组级总耗时 = 各记录求和即可得（错峰等待是调度开销而非初始化器开销）
 */
data class InitializerTiming(
    val initializerClass: Class<*>,
    val isHighLevel: Boolean,
    val level: Int,
    val isSuccess: Boolean,
    val durationNanos: Long,
)
