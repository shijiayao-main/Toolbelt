package com.jiaoay.log

import java.io.File

sealed class ToolsResult {

    /**
     * @property outputFile 产物文件
     * @property warning 非 null = 引擎形态提示（无时间戳文件的段落切分降级 =
     *   [LogTagFilter.PARAGRAPH_FALLBACK_WARNING]）。产物口径与常规形态不同，
     *   消费方（CLI echo / agent 工具 summary）须原样呈现——静默吞掉会让
     *   调用方按常规块口径误读产物
     */
    data class Success(val outputFile: File, val warning: String? = null) : ToolsResult()

    data class Failure(val reason: String) : ToolsResult()
}