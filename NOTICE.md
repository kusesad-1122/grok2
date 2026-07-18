# 移植出处声明 / Porting Notice

本项目(Grok Build 安卓手机版)**移植自 xAI 开源项目
[grok-build](https://github.com/xai-org/grok-build)**,原项目采用 **Apache License 2.0**。

- 保留原始 `LICENSE`(Apache-2.0)与 `THIRD-PARTY-NOTICES`,未移除任何版权声明。
- App 运行的是由原始 Rust 源码交叉编译得到的**真实 `grok` 二进制**(路线 A),非重写。
- 终端渲染与 PTY 采用 [Termux](https://github.com/termux/termux-app) 的
  `terminal-view` / `terminal-emulator` 模块,同为 **Apache License 2.0**(见 `TERMUX-LICENSE.md`)。

This project is a port of xAI's open-source **grok-build** (Apache-2.0). The original
`LICENSE` and `THIRD-PARTY-NOTICES` are retained. The app runs the real `grok` binary
cross-compiled from the original Rust sources. Terminal rendering/PTY use Termux's
`terminal-view` / `terminal-emulator` modules (Apache-2.0).
