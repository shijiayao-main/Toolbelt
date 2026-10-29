package com.jiaoay.toolbelt.device

/**
 * 单条设备应用条目（`pm list packages --show-versioncode -U` 一行输出）。
 *
 * 全部包视角契约：条目集 = 设备上**全部**已安装包，不做「第三方包」假设——全预装
 * 形态下 `pm list packages -3` 实测为 0 条（「第三方」标记恒假无意义），
 * 预装/后装的判别由 [isSystem]（`-s` 系统包集合成员标记）承载。
 *
 * - [versionCode] 取 `versionCode:` 段（`--show-versioncode` 旗标输出），32 位
 *   平台常量值按 Long 收录（超 Int 范围的 versionCode 不截断）；
 * - [uid] 取 `uid:` 段（`-U` 旗标输出），Linux uid 数值（共享 uid 形态下多个
 *   包同 uid 是合法读数，如 system uid=1000）；
 * - [isSystem] 为系统包标记（`pm list packages -s` 集合成员），非系统 = 后装
 *   用户包；标记由 [DevicePackageBrowser] 以两次列举集合差写入，本模型只承载
 *   结果不做集合运算。
 */
data class DevicePackageEntry(
    val packageName: String,
    val versionCode: Long,
    val uid: Long,
    val isSystem: Boolean,
)

/**
 * 单包详情（`dumpsys package <pkg>` 的 Packages 记录最小字段面）。
 *
 * 契约：
 * - [versionName] 为 manifest 未设置时的 null 语义（设备侧打印 `versionName=null`
 *   → 本模型 null，不猜不编默认值）；
 * - [firstInstallTime]/[lastUpdateTime] 为设备侧本地时区原文
 *   （`YYYY-MM-DD HH:MM:SS` 形态），不做时区换算——回执展示的是设备自己的表，
 *   换算反而失真；
 * - [codePath] 为 APK 所在设备侧路径（`/system/...` 形态即预装系统分区的旁证）；
 * - [requestedPermissionsCount] 为 manifest 声明权限计数（`requested
 *   permissions:` 子段行数；无该子段的零声明包计 0，是合法读数非解析失败）；
 * - 字段是**最小面**刻意收窄：单包 dumpsys 全量输出约 100KB，只解析详情卡所需
 *   字段，其余段（Dexopt/Queries 等）不进模型——扩字段须先有消费面。
 */
data class DevicePackageDetails(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val uid: Long,
    val codePath: String,
    val firstInstallTime: String,
    val lastUpdateTime: String,
    val requestedPermissionsCount: Int,
)
