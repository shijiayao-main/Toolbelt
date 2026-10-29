# shrink-progress-card-window

过程卡片滚动窗口：推送语义从增量累积改为全量替换最新窗口快照（卡片恒等于「状态 + 最近 N 条」），删除死配置 patchIntervalMillis（v3→v4 迁移），暴露 entryMaxChars
