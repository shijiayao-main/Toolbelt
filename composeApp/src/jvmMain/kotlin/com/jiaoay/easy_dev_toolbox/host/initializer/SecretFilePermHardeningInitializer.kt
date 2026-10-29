package com.jiaoay.easy_dev_toolbox.host.initializer

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.SecretFilePerms
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer
import com.jiaoay.easy_dev_toolbox.core.log.JLog

/**
 * 密钥文件权限硬化（启动期兜底重扫）：DataStore `preferences_pb` 是明文密钥的落盘处
 * （llm_api_key / xlog_private_key 等），补 POSIX 600（仅属主可读写）。
 *
 * - 兜底而非唯一防线：会话内写路径已由 LocalDataManager 的 post-edit 硬化钩子收口
 *   （写后即 600）；本扫描覆盖钩子漏斗外的写者——DataStore 原子替换的 .tmp 中间文件、
 *   corruptionHandler 清空重建、legacy rename、直呼 dataStore() 的旁路写、以及上一
 *   会话遗留。每次启动幂等全量重设，钩子不替代扫描，扫描亦不替代钩子。
 * - 判定谓词单源经 SecretFilePerms.isSecretPreferencesFile（文件名含 .preferences_pb
 *   即命中，覆盖 .bak-forensic 等后缀变体），不得在本类私写。
 * - 非 POSIX 文件系统（Windows）整体跳过并 log 留痕（明文密钥按平台默认权限落盘，
 *   属已知边界，不宣称已收口）；单文件失败仅告警（每会话至多一次），恒返回 true
 *   （权限硬化失败不阻断启动）。
 */
class SecretFilePermHardeningInitializer : JInitializer {

    override val isHighLevel: Boolean = true

    override fun level(): Int {
        return 2
    }

    override suspend fun init(): Boolean {
        if (SecretFilePerms.posixSupported.not()) {
            JLog.w(TAG) { "非 POSIX 文件系统，密钥文件权限硬化整体跳过（明文密钥按平台默认权限落盘）" }
            return true
        }
        val dir = FileUtils.getDatastoreDir()
        if (dir.isDirectory.not()) return true
        dir.listFiles { f -> f.isFile && SecretFilePerms.isSecretPreferencesFile(f.name) }.orEmpty().forEach { file ->
            SecretFilePerms.harden600(file)
        }
        return true
    }

    companion object {
        private const val TAG = "SecretFilePermHardening"
    }
}
