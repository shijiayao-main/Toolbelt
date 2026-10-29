package com.jiaoay.easy_dev_toolbox.core.data

/**
 * 路径显示名单源（仅显示层用）：从路径串提取文件名。
 *
 * 先剥 Unix `/` 再剥 Windows `\`——`java.io.File.name` 只按**当前平台**分隔符切分，
 * Linux 上剥不了 Windows 反斜杠路径（日志/回执里记录的对端路径），会整串返回。
 * 显示层统一走本单源；删除/打开等动作仍用原始路径串。
 */
fun pathFileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
