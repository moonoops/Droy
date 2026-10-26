# Droy

**A personal AI companion on Android.**

English | [简体中文](README.zh-CN.md)

[Download APK](https://github.com/moonoops/Droy/releases) · [Installation](INSTALLATION.md) · [Issues](https://github.com/moonoops/Droy/issues) · [@moonoops](https://github.com/moonoops)

![Droy](assets/droy-promo-banner.png)

## What is Droy?

Droy is an Android AI agent built on [DeepSeek Harness (DSH)](https://github.com/deepseek-ai/deepseek-harness). It brings ChatGPT-style conversations and the task execution experience of desktop agents such as Claude Code to your phone. Chat with Droy, or ask it to plan a task and operate your phone through the capabilities you authorize.

For example, ask Droy to install and configure GKD on your phone, or create scheduled tasks to open DingTalk for a daily attendance check-in or claim coupons at a set time. Execution depends on app rules, login state, device permissions and background availability. Login, verification and other steps that need human input are handed back to you.

The agent runtime runs on the phone and can be used without a connected computer after setup. Cloud models require network access and your own model configuration.

## How Droy works

DSH provides task planning, model calls and the conversation engine. On top of it, we built **Android Use**, a toolset similar to Computer Use that lets the agent observe pages, tap, type, swipe and check the results of its actions.

Following DSH's “everything is a plugin” philosophy, mobile adaptation, voice interaction and memory connect through plugins, backed by Android native executors. Droy manages permissions, task state, data policy and stop controls, giving DSH the tools to act in an Android environment.

![Droy architecture](assets/droy-architecture.en.png)

### Interface preview

Captured on a Redmi K90 Ultra.

| Start a conversation | Capabilities and permissions | Local speech |
| :---: | :---: | :---: |
| <img src="assets/screenshots/chat.en.png" width="260" alt="Droy English conversation home" /> | <img src="assets/screenshots/permissions.en.png" width="260" alt="Droy phone capability permissions" /> | <img src="assets/screenshots/voice.en.png" width="260" alt="Droy local speech and readout settings" /> |

### A cat companion on your screen

When needed, Droy appears on your screen as a little cat. Speech bubbles tell you what it is doing or ask you to choose the next step and help. You can view the task, answer a question or stop an operation directly.

| Task progress | Asking for your help |
| :---: | :---: |
| <img src="assets/screenshots/companion-progress.png" width="280" alt="Cat companion reporting that it is checking the changed page" /> | <img src="assets/screenshots/companion-help.png" width="280" alt="Cat companion asking for the next step with view and stop task controls" /> |

Companion examples use existing Android emulator demo captures with Chinese UI.

You can also make Droy your own: give a desktop agent your appearance requirements and the [project URL](https://github.com/moonoops/Droy). Ask it to follow the existing character package format, generate and replace assets, then build and install your customized Droy. Image generation requires an available image generation service.

## Capabilities

| Capability | Description |
| --- | --- |
| Conversations | Streaming replies, history, attachments and configurable models. |
| Phone interaction | Open apps, observe pages, take screenshots, tap, swipe and enter text. |
| Voice | Hold to talk, local speech recognition, voice conversations and optional readout. |
| Companion | An animated character with configurable appearance, personality and task updates. |
| Notifications | Content rules, model-assisted analysis and reminders. |
| Personal memory | Optional local records and retrieval, with separate source and processing controls. |
| Extensions | Skills, plugins, MCP and automations through DSH. |
| Languages | Chinese and English. |

## Get started

### Device and compatibility

**Droy is developed on a rooted Redmi K90 Ultra, with implementation paths for both rooted and non-rooted devices. Other devices, system versions and permission combinations need separate compatibility checks.**

- ARM64 Android 11 / API 30 or later, with an up-to-date system WebView.
- Without Root, accessibility services provide basic phone interaction; authorized Shizuku can enable some additional operations. Root enables enhanced capabilities. Available features depend on the in-app permission and capability checks. Personal memory requires Root and execution-isolation qualification.
- Both vision and text-only models are supported, including compatible services such as DeepSeek Flash. Text-only models operate through page text and control structure. Screenshot understanding requires image input support from the selected model and API, declared in its configuration. Check the [provider's current capabilities](https://api-docs.deepseek.com/guides/vision/) and your actual configuration.

For compatibility problems, an agent can help investigate, modify the source and verify a fix. Share reproduction steps in an [issue](https://github.com/moonoops/Droy/issues) or submit a [pull request](https://github.com/moonoops/Droy/pulls).

### Recommended: USB ADB installation with an agent

1. Connect your phone to a computer using a USB data cable.
2. Enable USB debugging in the phone's developer options and authorize the computer.
3. In a desktop agent client that can run local commands, such as Codex, Claude Code or DSH, enter:

```text
Install Droy on my USB-connected Android phone by following INSTALLATION.md
in https://github.com/moonoops/Droy. Check the device and required tools,
prefer the official signed APK and verify its checksum. Confirm my feature
and permission choices, install through ADB, and run the setup checks.
Prompt me for phone-side confirmations. I will enter my model API key on the phone.
```

4. Complete model setup and permission prompts on the phone, then check the results. You can disconnect USB after setup.

The installer supports macOS, Linux and Windows with Python 3.8+ and ADB. See [installation details](INSTALLATION.md). You can also install the official APK directly and follow the phone setup guide.

After setup, try:

- "Open Calculator and calculate 128 × 24."
- "Summarize the current page in three key points."

## Permissions and data

**Droy is experimental. Despite detailed permission, data authorization and stop controls, model errors, software defects or system differences may still cause unintended actions, data loss or disclosure. Assess the risks, back up important data, grant only necessary permissions and use sensitive accounts with care. The project is provided as is; to the extent permitted by applicable law, its authors and contributors accept no liability for data loss, disclosure or other damage arising from its use.**

- Task context, page text, screenshots and attachments may be sent to your selected model. Provider terms and charges apply.
- Voice recognition runs locally; recognized text goes to the conversation model. Readout uses your selected speech engine.
- Notification analysis can send notification text to the model; explicit content rules can run locally.
- Personal memory is off by default. Sources are enabled separately; model processing requires authorization for the endpoint, data category and purpose.

You can stop tasks and change capabilities in settings. Background work depends on Android process availability. Before uninstalling, turn off notification management normally so it can restore settings it changed.

## Build from source

See [BUILDING.md](BUILDING.md) for the toolchain and build commands, and [TESTING.md](TESTING.md) for checks. Build and runtime versions are pinned in [runtime/release.json](runtime/release.json).

## Feedback and contributions

Use [Issues](https://github.com/moonoops/Droy/issues) for problems and [pull requests](https://github.com/moonoops/Droy/pulls) for verified fixes. Include the Droy version, device, Android version, Root status and reproduction steps. Remove credentials and personal data from shared logs and screenshots.

## License

Droy's own code is licensed under **GNU AGPL v3.0 only** ([LICENSE](LICENSE)). Use, modification and commercial use are permitted under its terms; applicable source-sharing obligations remain in effect.

Third-party components retain their respective licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 🤝 Community & Acknowledgements

- **[DeepSeek Harness (DSH)](https://github.com/deepseek-ai/deepseek-harness):** Droy is built on its agent engine. Thanks to the DeepSeek team and contributors for their open-source work.
- **LINUX DO Community:** [linux.do](https://linux.do)

## Star history

<a href="https://www.star-history.com/?repos=moonoops%2FDroy&amp;type=date&amp;legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;theme=dark&amp;legend=top-left" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;legend=top-left" />
    <img alt="GitHub star history for moonoops/Droy" src="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;legend=top-left" />
  </picture>
</a>
