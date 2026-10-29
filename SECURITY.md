# Security Policy

## Supported Versions

仅最新 Release 分支接受安全修复（见 [Releases](https://github.com/shijiayao-main/Toolbelt/releases)）。

## Reporting a Vulnerability

🔴 请**勿**通过公开 issue / PR / 讨论区报告安全漏洞。

请通过 GitHub **私密漏洞报告**（Security → Report a vulnerability）提交，或联系仓库维护者。收到报告后：

- 48 小时内确认收到
- 评估期间可要求补充复现信息
- 修复发布后在 Release Notes 中致谢（除非报告者要求匿名）

## Scope 说明

本项目**随包分发第三方二进制**（内置 adb、scrcpy-server、FFmpeg 原生库、sherpa-onnx），这些组件的 CVE 同样影响用户——报告里注明影响组件即可，无需自行甄别归属。设备观测功能（adb / 投屏 / logcat）按设计在**用户本机与用户自己的设备**间工作，属预期行为而非漏洞面。
