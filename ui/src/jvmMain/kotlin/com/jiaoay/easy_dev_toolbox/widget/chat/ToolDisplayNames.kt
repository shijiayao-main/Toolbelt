package com.jiaoay.easy_dev_toolbox.widget.chat

/**
 * 工具名 → 中文显示名单源。
 */
object ToolDisplayNames {

    private val map: Map<String, String> = mapOf(
        // ── 蒸馏域 ──
        "distill.report_step" to "蒸馏进度上报",
        "distill.propose_knowledge" to "提交知识候选",
        // ── 知识库域 ──
        "knowledge.search" to "知识库检索",
        "knowledge.read" to "读取知识条目",
        "knowledge.lookup_tag" to "按标签查知识",
        // ── 记忆域 ──
        "memory.view" to "查看长期记忆",
        "memory.append" to "追加记忆",
        "memory.replace" to "改写记忆",
        "memory.delete" to "删除记忆",
        "view_memory" to "查看长期记忆",
        "append_memory" to "追加记忆",
        "replace_memory" to "改写记忆",
        "delete_memory" to "删除记忆",
        // ── 用户交互域 ──
        "user.ask_user" to "向用户提问",
        // ── 文件系统域（extended 新链 + 裸名旧链）──
        "fs.extended.read_file" to "分页读文件",
        "fs.extended.grep" to "内容检索",
        "fs.extended.glob" to "文件名匹配",
        "fs.extended.convert_timestamp" to "时间戳换算",
        "fs.extended.hash_file" to "文件校验",
        "fs.extended.hex_dump" to "十六进制转储",
        "fs.extended.strings" to "提取可打印串",
        "read_file" to "分页读文件",
        "grep" to "内容检索",
        "glob" to "文件名匹配",
        "convert_timestamp" to "时间戳换算",
        "hash_file" to "文件校验",
        "hex_dump" to "十六进制转储",
        "strings" to "提取可打印串",
        // ── 文件操作域 ──
        "fs.decrypt_file" to "解密文件",
        "fs.extract_archive" to "解压归档",
        "fs.preview_text" to "预览文本",
        "fs.list_dir" to "列目录",
        "decrypt_file" to "解密文件",
        "extract_archive" to "解压归档",
        "preview_text" to "预览文本",
        "list_dir" to "列目录",
        // ── 日志域 ──
        "log.analyze_log" to "日志研判",
        "log.probe_file" to "探测日志文件",
        "decrypt_log" to "解密 XLog",
        "filter_by_tag" to "按 Tag 过滤",
        "filter_by_time" to "按时间过滤",
        "filter_by_range" to "按时间区间过滤",
        "split_by_size" to "按大小分割",
        "probe_file" to "探测日志文件",
        "analyze_log" to "日志研判",
        "write_todos" to "更新任务清单",
        // ── MCP 外放域 ──
        "workbench_analyze" to "发起日志研判",
        "distill_run" to "发起知识蒸馏",
        "workbench_status" to "查询研判进度",
    )

    /**
     * 查显示名：命中返回中文名，未命中返回 null（消费方兜底原始名——
     * 新工具未登记不阻断渲染，但会在评审中暴露缺登记）。
     */
    fun display(toolName: String): String? = map[toolName]
}
