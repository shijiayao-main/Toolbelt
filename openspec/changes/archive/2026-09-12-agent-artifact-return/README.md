# agent-artifact-return

agent 产物通用回传：system prompt 注入 [产物] 标记约定，桥接解析末尾标记行 → 沙箱校验 → 两步上传（im:resource → file 消息），失败降级不阻断文字回复；agent.artifacts 4 配置项
