# Copilot 编码助手项目说明

仓库根目录的 [AGENTS.md](../AGENTS.md) 已被 Copilot coding agent / CLI 自动读取；IDE chat 场景不会自动加载它，请先用文件工具读取 AGENTS.md，再遵守其知识库读取策略与关键约定。文档与源码冲突时以源码为准。

## Copilot 补充约定

- 本项目 skill 位于 `.github/skills/`（Copilot 原生路径）与 `.claude/skills/`（Claude Code 原生路径，内容同步）：
  - **docs-sync**：触发知识库（docs/）的生成或更新。
  - **user-manual**：生成/更新面向最终用户的《用户手册》。
