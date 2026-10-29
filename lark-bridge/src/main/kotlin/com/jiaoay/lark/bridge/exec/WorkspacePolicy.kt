package com.jiaoay.lark.bridge.exec

import java.io.File
import java.io.IOException

/**
 * 多目录白名单沙箱策略（**平移**旧 `lark/WorkspacePolicy.kt` 66 行，行为逐字保持，[BC WorkspacePolicyTest 平移]）。
 *
 * 构造时一次性 canonical 解析所有根路径并缓存，后续校验为纯字符串前缀比对（O(n) n=根数）。
 * - 空 roots 时 **fail-safe**：拒绝所有路径（无白名单 = 无允许区域）。
 * - 分隔符对齐：校验时拼接 [File.separator] 前缀，防止 `/foo/bar` 误放行 `/foo/barbaz`。
 * - 符号链接穿越：canonicalPath 已解析符号链接，故穿越攻击天然防御。
 */
class WorkspacePolicy(
    roots: List<File>,
    /** 禁删语义标记（上层 RiskClassifier / safety prompt 参考；本类不强制拦截删除）。 */
    val denyDelete: Boolean = true,
) {
    /** 规范化后的允许根路径列表（构造时解析一次；不可解析的根跳过）。 */
    val canonicalRoots: List<String> = roots.mapNotNull { root ->
        runCatching { root.canonicalPath }.getOrNull()
    }.distinct()

    /** 当前平台是否 Windows（前缀比对大小写策略分支用；构造注入面之外的系统属性探测）。 */
    private val windows = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * 校验目标路径是否落在白名单任一根内。
     * @return true = 命中任一根（放行）；false = 未命中（拒绝，含空白名单 fail-safe）
     * @throws IOException target 不可解析时抛出（调用方归入 StartFailed / 拒绝路径）
     */
    fun requireInsideWorkspace(target: File): Boolean {
        if (canonicalRoots.isEmpty()) return false // fail-safe：空白名单拒绝所有
        val candidate = runCatching { target.canonicalPath }.getOrElse { e ->
            throw IOException("路径不可解析：${target.path}（${e.message}）")
        }
        return canonicalRoots.any { root ->
            // 🔴 Windows 前缀比对大小写不敏感——canonicalPath 在 Windows
            //    对**不存在**路径的尾段保留输入拼写（盘符大小写不归一）：白名单 C:\Work 下
            //    c:\work\x 会被误判越界（运行时与 UI 配置大小写一致才放行，太脆）。NTFS 路径
            //    本就 case-insensitive，归一为大小写不敏感比较属平台语义而非放宽。
            if (windows) {
                val prefix = if (root.endsWith(File.separator)) root else root + File.separator
                candidate.equals(root, ignoreCase = true) ||
                    candidate.startsWith(prefix, ignoreCase = true)
            } else {
                val prefix = if (root.endsWith(File.separator)) root else root + File.separator
                candidate == root || candidate.startsWith(prefix)
            }
        }
    }

    /** 白名单是否为空（空 = fail-safe 拒绝所有）。 */
    val isEmpty: Boolean get() = canonicalRoots.isEmpty()

    companion object {
        /**
         * 从配置构建：单目录 [workspaceDir] ∪ 多目录 [workspaceWhitelist] 取并集，去空白、按 path 去重。
         * 对应新配置 `workspace.root` + `workspace.extraRoots`。
         */
        fun fromConfig(
            workspaceDir: String,
            workspaceWhitelist: List<String>,
            denyDelete: Boolean = true,
        ): WorkspacePolicy {
            // 🔴 root / extraRoots 统一 ~ 展开。同域的 agentWorkDir 消费点会展开、白名单却不展——
            //    用户填 ~/work 时沙箱根是字面 "~" 目录、agent 工作目录展开后反而在白名单外（沙箱失配）。
            //    在 fromConfig 单点收口：全部消费点（BridgeGateways / DownloadStore / …）共享。
            val roots = buildList<File> {
                if (workspaceDir.isNotBlank()) add(File(Paths.expandTilde(workspaceDir)))
                workspaceWhitelist.filter { it.isNotBlank() }.forEach { add(File(Paths.expandTilde(it))) }
            }.distinctBy { it.path }
            return WorkspacePolicy(roots, denyDelete)
        }
    }
}
