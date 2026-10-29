package com.jiaoay.easy_dev_toolbox.core.navigation

/**
 * XLog 解密页跳页槽（解压产物「送 XLog 解密」的**多文件**分支承载面）。
 *
 * **中立跳页槽（:core 单点）**：解压页产物目录内 `.xlog`/`.alog` 命中**多文件**
 * 时，单值 palette 参数直填态装不下整批判——经本槽携**目录路径**（非预展开清单）
 * 去工具聚合页 XLog 解密子签批量入任务卡。feature-archive 与 feature-logkit
 * 互不依赖（feature 互不 import 铁律），两个 feature 都只 import 本槽：
 * - **生产侧**（feature-archive 解压页）：产物卡「送 XLog 解密」条目动作的
 *   多文件分支调 [offer]（目录路径 + 跳页回调；false = 宿主未接线，投递被拒绝
 *   且载荷不残留）；
 * - **消费侧双段**（宿主 + feature-logkit）：宿主 wire 的跳页回调只负责切页
 *   （跳「工具」聚合页并预选 XLog 解密子签）；目录路径留在本槽 pending 态，
 *   由 XLog 解密页**组合期** [consumePendingDirectory] 取走（读即清——一次性
 *   载荷，仅首次组合消费；embedded 聚合页子签形态同体消费）后以页内同款
 *   accepts 展开批量入卡。
 *
 * 载荷契约 = 携目录路径而非预展开清单：accepts 过滤语义（`.xlog`/`.alog` 扩展名
 * 判定与上限闸）单源留在 XLog 解密域，生产侧不持第二套消费语义。
 *
 * 槽形态与 [DeepAnalyzeHook] / [TaskPipelineHandoff] 同款（三度先例）：
 * wire/isWired + offer 拒绝未接线 + pending 载荷读即清。
 */
object GoXLogDecryptHook {

    @Volatile
    private var jump: (() -> Unit)? = null

    @Volatile
    private var pendingDirectory: String? = null

    /**
     * 宿主接线（跳页回调；null = 未接线）。生产侧条目渲染与响应的开关真源
     * （[isWired]——解压页「送 XLog 解密」条目的多文件分支门控之一）。
     */
    fun wire(jump: (() -> Unit)?) {
        this.jump = jump
    }

    /** 宿主是否已接线（生产侧多文件分支可用性查询） */
    val isWired: Boolean
        get() = jump != null

    /**
     * 解压页侧投递：目录路径挂 pending + 触发宿主跳页回调。
     *
     * @return false = 宿主未接线（投递被拒绝，载荷不残留——调用方静默跳过多文件分支）
     */
    fun offer(directoryPath: String): Boolean {
        val cb = jump ?: return false
        pendingDirectory = directoryPath
        cb()
        return true
    }

    /**
     * XLog 解密页组合期取载荷（读即清——一次性载荷，仅本次组合消费）。
     * 页面以本页拖放区同款 accepts 展开目录并批量入卡；null = 无待消费跳页。
     */
    fun consumePendingDirectory(): String? {
        val dir = pendingDirectory
        pendingDirectory = null
        return dir
    }

    /** 测试缝：清空接线与残留载荷（单测隔离用） */
    fun clearForTest() {
        jump = null
        pendingDirectory = null
    }
}
