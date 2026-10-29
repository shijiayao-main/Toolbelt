# govern-split-packages

架构分析 P0-4：split package 治理。feature-shelltools 的 core.data/core.shelltools 包（真 split，与 :core/composeApp 同名包，含跨模块同包免 import 的危险形态）与 feature-larkbridge 的 core/larkbridge 包（命名冒充）统一归入各自 page.* 包树。约 26 文件 git mv（用户执行）+ package/import 修正（AI 执行）；BridgeAssemblerParityTest:601 路径断言与 LarkBridgeManager 移动同 commit 强制。零行为变化。
