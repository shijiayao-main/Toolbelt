# GEMINI.md

@AGENTS.md

## Gemini CLI 补充约定

- 遵守 [AGENTS.md](AGENTS.md) 的知识库读取策略与关键约定；文档与源码冲突时以源码为准。
- skill 位置：Claude Code 读 `.claude/skills/`，Copilot 读 `.github/skills/`。Gemini CLI 仅从全局目录（`~/.gemini/skills/`）加载 skill，不会读上述仓库内路径；如需使用，可将 skill 手动安装到全局目录，或直接把对应 SKILL.md 当普通说明书阅读后照做：
  - **docs-sync**：触发知识库（docs/）的生成或更新。
  - **user-manual**：生成/更新面向最终用户的《用户手册》。
