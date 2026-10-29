package com.jiaoay.easy_dev_toolbox.host.initializer

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager

/**
 * 产物目录配置恢复（宿主注册，level 2 高优）：读 [EasyDataManager] 的
 * `visible_export_root` 写入 [FileUtils] 进程单源。
 *
 * 为何需要（时序）：可见产物树访问器是同步函数（任意线程直调），DataStore 读是
 * 挂起协程——单源是唯一的桥。启动恢复保证任何用户动作触发的产物写入（导出/
 * 录屏/快照）都落在配置后的根上；产物写入最早发生在用户交互后，level 2（首帧前）
 * 已远早于一切产物写点。
 *
 * 失败不阻断启动：读盘异常时保持单源为 null（默认根 ~/Documents/Toolbelt）。
 */
class VisibleExportRootRestoreInitializer : JInitializer {

    override val isHighLevel: Boolean = true

    override fun level(): Int {
        return 2
    }

    override suspend fun init(): Boolean {
        runCatching {
            FileUtils.applyVisibleExportRoot(EasyDataManager.getVisibleExportRoot())
        }.onFailure { JLog.w(TAG) { "产物目录配置恢复失败，回落默认根：${it.message}" } }
        return true
    }

    companion object {
        private const val TAG = "VisibleExportRootInitializer"
    }
}
