# HANDOFF · rewrite-lark-bridge 交接清单

> 本文件是 `rewrite-lark-bridge` change 的交接快照（2026-09-09 续接生成）。完整交接文档（含终验数据与 C 档真机结果）在 gitignored 的 `build/handoff-rewrite-lark-bridge.md`。

## 状态：全部里程碑完成（M0~M7，28/28 施工任务），待维护者提交

- M1 Spike S1~S7 全 PASS；M2 拆旧（净删 ~2.3 万行）；M3 网关 + SDK 隔离守护；M4 桥接内核；M5 CLI + T11 真机（A 档 11 项 + B 档消息级 13 项已过）；M6 UI 零白名单；M7 迁移 + 文档收口。
- 终审报告 `build/final-audit-docs-and-deltas.md` 的 6 项修复已于 2026-09-09 逐一核实落实（appId 掩码 / 守护测试归属 / EDT ≤1.5s / 字段数 56 / 幂等标记措辞 / 行数漂移）。
- `spike-s4s6-runbook.md` 已按其 §7 删除清单移除（S4/S6 验证完毕，非交付物）；引用处已改为「已移除」注记。

## 剩余事项

1. git 提交由维护者执行（建议：主体 + 文档两笔，或一笔均可）
2. 提交后走 openspec 归档流程（`/opsx:archive`）合入 delta specs
3. 若真机发现 `sender_stats.jsonl` 的 `userName` 仍空 ⇒ 飞书后台补授 `contact:user.base:readonly`
4. 遗留 backlog（已登记，非回归）：appSecret 待迁 keychain；`LegacyConfigImporter` 1.1.0 后删除；`modules("java.net.http")` 注释理由改写
5. 低优可改善点：audit `command` 字段恒空（是否记录 route id 待定）；五阶段精确计数器无 dump 出口

## 接续指南

- 测试纪律：守护测试验收一律 `--rerun-tasks --no-build-cache`
- T11 runbook：`docs/pages/lark-bridge.md`「T11 可执行 runbook」节
- CLI：`java -jar lark-bridge/build/libs/lark-bridge-all.jar <serve|check|send|handle-event|permission-server>`；配置 `~/.easy_dev_toolbox/lark/bridge-config.json`；`--set` ephemeral 永不落盘
- 敏感红线：appSecret 永不入仓库；`--verbose` 日志外传前必须掩码
