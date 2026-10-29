# CLAUDE.md

@AGENTS.md

## Claude Code 补充约定

- 知识库读取策略、关键约定、常用命令见 [AGENTS.md](AGENTS.md)；文档与源码冲突时以源码为准。
- 🔴 **写注释前先读 AGENTS.md「注释宪法」（关键约定第 9 条）**：注释只写「为什么/契约/指引」三类，禁历史叙事（批次号/日期）/悬空链接/复述；由 `CommentGuardTest` 守护。
- 本项目自定义 skill 位于 `.claude/skills/`：
  - **docs-sync**：新增/修改功能后触发知识库（docs/）的生成或更新。修改页面结构、新增模块、改动约定后建议主动提醒运行（经同意后再执行，不要未经确认自动改 docs/）。
  - **user-manual**：生成/更新面向最终用户的《用户手册》（按功能模块划分）。涉及用户可感知的功能变更后建议主动提醒运行。
- 跑测试的权限：`.claude/settings.local.json` 仅本机生效、不入库；新环境首次执行 gradle 命令会弹权限确认，属正常现象。
- **git 操作权限**：允许 `git add` / `git commit` / `git mv` / `git restore --staged` 等增量与暂存类操作（commit 末尾附 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）；**仍然禁止** `git push` / `git pull` / `git rebase` / `git merge` / 删除类操作（`git rm`、`git clean`、`git checkout -- <file>` 丢弃改动等）——推送与历史改写永远留给维护者，破坏性/网络操作必须先征询。
