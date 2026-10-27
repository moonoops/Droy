# 安装 Droy

推荐使用电脑端 Agent，通过 USB ADB 安装官方签名 APK。安装工具支持 macOS、Linux 和 Windows，需要 Python 3.8+ 与 Android Platform Tools（ADB）。安装已构建 APK 不需要完整 Android 构建环境。

## 连接与安装

1. 使用 USB 数据线连接手机和电脑。
2. 在手机开发者选项中开启 USB 调试，并确认电脑调试授权。
3. 将本项目地址交给能读取仓库、执行本地命令的 Agent，要求按本文扫描、安装和自检。
4. 在手机填写模型配置，完成相应权限确认。配置完成后可断开 USB 使用。

APK 已包含核心运行环境，无需在手机另装 Node、npm 或 DSH。也可以从项目 [Releases](https://github.com/moonoops/Droy/releases) 获取 APK，核对 SHA-256 与签名证书指纹后直接安装，按手机引导配置。

## 给安装 Agent

- 先扫描设备、Android 用户、架构、空间、WebView、已有安装与权限；多台设备时由用户选择。
- 优先使用对应版本的官方签名 APK 并核对摘要；无可用发布包时，明确说明并按 [BUILDING.md](BUILDING.md) 构建，不猜测下载地址。
- 确认个性化、功能和权限选择，展示安装计划；批准后按绑定的 `approvalDigest` 执行。
- 仅配置已选择的能力。API Key 由用户直接在手机填写，不进入聊天、方案文件或日志。Root、Shizuku 和系统要求本人确认的操作在手机完成。
- 安装后执行 `verify`，依据模型请求、工具和权限检查说明结果；待手机配置的项目保持 `pending_phone`。
- 中断后重新扫描并继续已授权步骤；不自动卸载已有应用或清空数据。

## 电脑端命令

Windows 使用可用的 `python` 或 `py -3` 替换 `python3`。ADB 从环境变量、常见 SDK 路径或 PATH 查找，也可通过 `--adb` 或 `DROY_ADB` 指定。

```sh
python3 tools/setup.py scan --serial YOUR_DEVICE_SERIAL

python3 tools/setup.py plan --serial YOUR_DEVICE_SERIAL \
  --apk /path/to/Droy.apk --output /path/to/droy-install-plan.json \
  --mode assistant --permissions automatic

python3 tools/setup.py apply --plan /path/to/droy-install-plan.json \
  --approve APPROVAL_DIGEST_FROM_REVIEWED_PLAN

python3 tools/setup.py verify --serial YOUR_DEVICE_SERIAL --complete
```

仅有一台设备时可省略 `--serial`。命令输出 JSON；`plan` 写入新的本地方案文件，`apply` 重新核对设备和 APK 后执行。方案可能含个人配置，保存在源码仓库之外。设备、已有版本或 APK 改变时重新生成计划。

| 选项 | 用途 |
| --- | --- |
| `--mode dialogue` | 仅对话，关闭手机操作能力 |
| `--mode assistant` | 开启推荐的页面读取、界面操作和应用查询 |
| `--mode custom` | 从关闭手机能力开始，按 `--capability` 选择 |
| `--capability allowContacts=true` | 明确修改对应能力，能力名称见 `runtime/mobile/assets/droy-capabilities.json` |
| `--permissions automatic` | 按已选能力配置相应系统权限 |
| `--permissions manual` | 系统权限由用户在手机配置，命令行默认采用此项 |
| `--name`、`--address`、`--personality` | 设置伙伴姓名、对用户的称呼和性格 |

新安装未指定模式时采用推荐手机操作；覆盖升级未指定新能力时保留原选择和暂停状态。无障碍配置保留其他服务，并核验实际连接。

## Shizuku 与 Root

Shizuku 为可选组件。仅在用户选择后，使用官方来源并核对 SHA-256，将其纳入安装计划；已有通道满足功能时无需加装。检测到 Root 不代表已授权，产品 Root 选择与管理器授权需分别完成。

Shizuku 启动和授权说明见[官方指南](https://shizuku.rikka.app/zh-hans/guide/setup/)。非 Root 启动方式可能需要手机重启后重新启动。Android 提示侧载应用“受限设置”时，按系统入口允许后再开启无障碍。

## 持续后台运行

在 Droy“设置 → 通用”开启“持续后台运行”。开启后，退出界面或移除最近任务仍保留引擎和运行通知；关闭后，退到后台且没有任务或其他功能需要引擎时释放引擎。“关于与诊断 → 停止任务和引擎”始终优先，停止后需手动启动。

在手机系统中允许 Droy 的通知，并将电池策略设为“不受限制”或“不限制”。小米手机还可开启“后台自启动”，在最近任务中锁定 Droy，避免一键清理。菜单名称以当前系统为准。正式版与开发版需分别配置。

安装 Agent 可在明确选定设备和包名后，通过 ADB 配置并核验 Android 电池优化豁免。例如，以下命令仅针对开发版：

```sh
adb -s YOUR_DEVICE_SERIAL shell dumpsys deviceidle whitelist +io.droyagent.app.dev
adb -s YOUR_DEVICE_SERIAL shell dumpsys deviceidle whitelist
```

Root 可用于设置厂商的应用级后台策略，但不需要额外常驻守护、持续修改 OOM 分数或关闭全局省电管理。后台策略不能修复应用崩溃；手机重启或系统强行停止应用后，需重新打开 Droy。验证时同时检查运行通知、宿主进程和引擎子进程，不能仅凭设置成功判断引擎仍在运行。

## 升级与个人构建

官方版本使用相同签名覆盖升级，保留模型、会话、个性化和权限选择。签名冲突时先检查旧安装与数据迁移，不自动卸载。

日常开发使用独立的 `io.droyagent.app.dev`（Droy 开发版 / Droy Dev）和本地调试签名，构建、安装命令见 [BUILDING.md](BUILDING.md)。开发版与现有正式版并存，配置、会话和权限独立，不自动复制正式版数据。`tools/install.sh` 与独立权限工具默认针对开发版；本文的 `tools/setup.py` 安装计划仍明确针对正式包名 `io.droyagent.app`。只有用户明确要求发布到 GitHub 时才使用正式签名及其凭据。

自定义桌面名称或图标需要重新构建：

```sh
./tools/build.sh --app-name 我的Droy --app-icon /path/to/icon.png
```

个人构建应自行保管并持续使用同一签名。安装计划添加 `--personal-build --signing-continuity-confirmed`；工具不会生成或替换签名，也不会把个人包标为官方版本。
