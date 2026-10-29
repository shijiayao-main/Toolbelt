package com.jiaoay.toolbelt.device

/**
 * 目录条目类型（`ls -l` 权限列首字符口径）。
 */
enum class DeviceFileType {
    FILE,
    DIRECTORY,
    SYMLINK,
    ;

    companion object {
        /**
         * 权限串首字符 → 类型。b/c/s/p 等特种文件按 [FILE] 收录（文件面板无特种文件
         * 独立交互，模型收窄为三值；stat 备面对不认识的类型显式失败，不在此猜）。
         */
        internal fun fromPermissionString(perms: String): DeviceFileType = when (perms.firstOrNull()) {
            'd' -> DIRECTORY
            'l' -> SYMLINK
            else -> FILE
        }
    }
}

/**
 * 单条目录条目（`ls -l` 长格式一行）。
 *
 * 契约：
 * - [name] 为设备侧原始名（含空格名经名称列 join 还原）；设备侧 `ls` 对文件名零
 *   转义，名内含换行会打断行边界——该行防御性跳过，属登记残余（见
 *   `DeviceFileParsers` 对象头注释）；
 * - [symlinkTarget] 仅 [DeviceFileType.SYMLINK] 非 null（无 ` -> ` 拆分点的断链
 *   行为空串，保持非 null 不变量）；目标为设备侧原文（可为相对路径，未解析）；
 * - [sizeBytes] 为 ls 尺寸列字节值（目录条目是目录项尺寸，非递归总量）；
 * - [modifiedTime] 为 ls 日期+时间两列原文拼接（展示用；精确纪元秒走
 *   [DeviceFileStat.modifiedEpochSeconds]）。
 */
data class DeviceFileEntry(
    val name: String,
    val type: DeviceFileType,
    val symlinkTarget: String?,
    val sizeBytes: Long,
    val permissions: String,
    val owner: String,
    val group: String,
    val modifiedTime: String,
)

/**
 * 单条目 `stat` 机器可读数据（`stat -c '%F|%s|%Y|%A|%U|%G'` 单行输出）。
 * 与 [DeviceFileEntry] 分工：本模型是详情/校验备面（精确纪元秒），列目录主路径
 * 仍走 ls（一次 exec 取全目录，stat 只对单条目兜底）。
 */
data class DeviceFileStat(
    val type: DeviceFileType,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val permissions: String,
    val owner: String,
    val group: String,
)
