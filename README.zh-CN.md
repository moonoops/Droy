# Droy

**运行在 Android 手机上的个人 AI 助理伙伴。**

[English](README.md) | 简体中文

[下载 APK](https://github.com/moonoops/Droy/releases) · [安装指南](INSTALLATION.md) · [问题反馈](https://github.com/moonoops/Droy/issues) · [@moonoops](https://github.com/moonoops)

![Droy](assets/droy-promo-banner.png)

## Droy 是什么

Droy 是基于 DeepSeek Harness（DSH）构建的 Android AI Agent。用自然语言描述需求，连接自己的模型服务，再让 Droy 通过你授权的能力完成手机操作。

Agent 运行环境部署在手机本地，配置完成后可以脱离电脑使用。调用云端模型仍需要网络和你自己的模型配置。

## Droy 怎么工作

DSH 负责规划任务与调用模型；Droy 管理权限、任务状态和数据策略，Android Use 通过当前已授权的通道观察和操作应用。

![Droy 架构](assets/droy-architecture.zh-CN.png)

## 主要能力

| 能力 | 说明 |
| --- | --- |
| 对话 | 流式回复、会话历史、附件和可配置的模型。 |
| 手机操作 | 打开应用、观察页面、截图、点击、滑动和输入。 |
| 语音 | 按住说话、本地语音识别、语音对话和可选回复朗读。 |
| 伙伴形象 | 可配置外观与性格的动画伙伴，展示任务动态。 |
| 通知管理 | 内容规则、模型辅助分析和提醒。 |
| 个人记忆 | 可选的本地记录与检索，分别控制来源和模型处理授权。 |
| 扩展 | 通过 DSH 使用 Skills、插件、MCP 和自动化。 |
| 语言 | 中文和英文。 |

## 开始使用

### 设备与兼容性

**Droy 基于已 Root 的 Redmi K90 Ultra 开发。其他机型、系统版本和未 Root 环境需要分别验证兼容性。**

- Android 11 / API 30 及以上的 ARM64 手机，保持系统 WebView 更新。
- 自己的兼容模型配置；理解截图需要模型支持图像输入。
- 普通对话和基础手机操作不强制要求 Root；个人记忆需要 Root 与执行隔离资格。


遇到兼容性问题，可以让 Agent 协助定位、修改源码并验证，通过 [Issue](https://github.com/moonoops/Droy/issues) 提供复现信息，或提交 [Pull Request](https://github.com/moonoops/Droy/pulls) 分享修复。

### 推荐：通过 USB ADB 配合 Agent 安装

1. 使用 USB 数据线连接手机和电脑。
2. 在手机开发者选项中开启 USB 调试，并授权电脑连接。
3. 在能够执行本地命令的电脑端 Agent 客户端中，例如 Codex、Claude Code 或 DSH，输入：

```text
请按 https://github.com/moonoops/Droy 中的 INSTALLATION.md，
将 Droy 安装到我通过 USB 连接的 Android 手机上。先检查设备和所需工具，
优先使用官方签名 APK 并核对校验值。确认我的功能和权限选择后，
通过 ADB 安装、配置并完成自检。需要手机确认的步骤请提示我，
模型 API Key 由我直接在手机填写。
```

4. 在手机完成模型配置和权限确认，核对自检结果。配置完成后可断开 USB 使用。

安装端支持 macOS、Linux 和 Windows，需要 Python 3.8+ 与 ADB。详细流程见[安装指南](INSTALLATION.md)。也可以直接安装官方 APK，并按手机引导配置。

## 权限与数据

- 任务上下文、页面文字、截图和附件可能发送给当前模型服务，其条款和调用费用由对应服务决定。
- 语音识别在本地运行，识别后的文字提交给对话模型；朗读使用所选语音引擎。
- 通知分析可能发送通知正文，明确的内容规则可在本地执行。
- 个人记忆默认关闭，来源分别开启；模型处理需授权端点、数据类别和用途。

可随时停止任务，在设置中调整能力授权。后台任务依赖 Android 进程可用性。卸载前请正常关闭通知管理，让 Droy 恢复其修改的通知设置。

## 从源码构建

工具链与命令见 [BUILDING.md](BUILDING.md)，检查入口见 [TESTING.md](TESTING.md)。构建与运行版本由 [runtime/release.json](runtime/release.json) 固定。

## 反馈与贡献

通过 [Issues](https://github.com/moonoops/Droy/issues) 反馈问题，通过 [Pull Request](https://github.com/moonoops/Droy/pulls) 提交经过验证的修复。请附上 Droy 版本、机型、Android 版本、Root 状态和复现步骤；分享日志与截图前移除凭据及个人数据。

## 许可

Droy 自有代码采用 **GNU AGPL v3.0 only**（[LICENSE](LICENSE)）。可以按协议使用、修改和商用，并须履行适用的源码提供等义务。

第三方组件继续适用各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 🤝 社区与致谢

- **LINUX DO 社区：** [linux.do](https://linux.do)

## Star 趋势

<a href="https://www.star-history.com/?repos=moonoops%2FDroy&amp;type=date&amp;legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;theme=dark&amp;legend=top-left" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;legend=top-left" />
    <img alt="moonoops/Droy 的 GitHub Star 数量趋势" src="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;legend=top-left" />
  </picture>
</a>
