package com.jiaoay.log

import java.time.LocalDateTime

/**
 * 行首时间戳判定契约：裸格式 `MM-dd HH:mm:ss.SSS` 与年份格式
 * `yyyy-MM-dd HH:mm:ss.SSS`（外部解码器产物，如 alog_decoder.py）均识别。
 * 判定/解析逻辑单源在 [LogTimestamp]（isTimeLine 与 LogTimeFilter 共用，防漂移）。
 */
fun String?.isTimeLine(): Boolean {
    val line: String = this ?: return false
    return LogTimestamp.isTimeLine(line)
}

/**
 * 目标时间解析契约：规范格式 `yyyy-MM-dd_HH:mm:ss.SSS`，容忍空格分隔、省略毫秒
 * （自动补 `.000`）与裸日期 `MM-dd HH:mm:ss.SSS`（无年份——以当前年锚定补全）。
 * 不命中返回 null——宁拒不猜，调用方按格式错回执，不得静默
 * 取当前时间补全。解析单源在 [LogTimestamp.parseTarget]（时间过滤与查看器
 * 时间戳跳转共用，防第二套解析口径漂移）。
 */
fun String.parseLogTimestampTarget(): LocalDateTime? = LogTimestamp.parseTarget(this)

/**
 * 行首时间戳解析契约：解析对象是**行首**时间戳（裸格式 `MM-dd` 与年份格式
 * `yyyy-MM-dd` 同识别——与 [isTimeLine] 同一形态契约）。行首无时间戳或数值非法
 * （如 99 月）返回 null——宁拒不猜，调用方按解析失败处理，不得静默补全。
 * 解析单源在 [LogTimestamp.parseLine]（书签行文指纹保存/恢复两侧共用，防第二套
 * 解析口径漂移）。
 *
 * @param fallbackYear 裸格式 `MM-dd` 行的锚定年份（同文件内指纹比对两测同锚，
 *   取值不影响比对结果；跨锚年份变化按指纹漂移拒绝处理）
 */
fun String.parseLogLineTimestamp(fallbackYear: String): LocalDateTime? =
    LogTimestamp.parseLine(this, fallbackYear)
