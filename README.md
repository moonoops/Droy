# Droy

**A personal AI companion on Android.**

English | [简体中文](README.zh-CN.md)

[Download APK](https://github.com/moonoops/Droy/releases) · [Installation](INSTALLATION.md) · [Issues](https://github.com/moonoops/Droy/issues) · [@moonoops](https://github.com/moonoops)

![Droy](assets/droy-promo-banner.png)

## What is Droy?

Droy is an Android AI agent built on DeepSeek Harness (DSH). Describe a task in natural language, connect your own model service, and let Droy interact with your phone through the capabilities you authorize.

The agent runtime runs on the phone and can be used without a connected computer after setup. Cloud models require network access and your own model configuration.

## How Droy works

DSH plans tasks and calls models. Droy manages permissions, task state and data policy; Android Use observes and operates apps through the available authorized channels.

![Droy architecture](assets/droy-architecture.en.png)

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

**Droy is developed on a rooted Redmi K90 Ultra. Other devices, system versions and non-rooted environments need separate verification.**

- ARM64 Android 11 / API 30 or later, with an up-to-date system WebView.
- Your own compatible model configuration. Screenshot understanding requires image input.
- Root is optional for conversations and basic phone interaction. Personal memory requires Root and execution-isolation qualification.


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

## Permissions and data

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

- **LINUX DO Community:** [linux.do](https://linux.do)

## Star history

<a href="https://www.star-history.com/?repos=moonoops%2FDroy&amp;type=date&amp;legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;theme=dark&amp;legend=top-left" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;legend=top-left" />
    <img alt="GitHub star history for moonoops/Droy" src="https://api.star-history.com/chart?repos=moonoops/Droy&amp;type=date&amp;legend=top-left" />
  </picture>
</a>
