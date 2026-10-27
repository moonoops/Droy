# Android Use

Android Use 提供经过产品授权与 Android 权限检查的手机观察、动作、取消和回执。工具名称与参数以[能力目录](../runtime/mobile/assets/droy-capabilities.json)为准。

## 手机操作

执行器按实际授权和动作支持选择无障碍、Shizuku 或 Root；指定 `channel` 只提供可用时的偏好。节点输入、自然滑动和多指手势使用无障碍。所有通道遵循相同的能力开关与停止控制。

- `android_observe`、`android_screenshot` 返回当前页面及观察依据；坐标动作必须使用有效的 `snapshotId` 和允许范围。
- `android_tap`、`android_input_text`、`android_node_action`、`android_swipe`、`android_gesture` 执行动作并返回新观察。多指手势支持 2–5 条同步轨迹。
- `android_wait_for` 等待明确条件。动作可用 `afterCondition` 核对后续页面；超时不代表动作未发生。
- `android_sequence` 串行执行 2–6 个明确步骤，目标歧义、页面变化或结果未知时停止并返回已完成部分。
- `android_global_action` 和 `android_open_settings` 使用系统支持的导航与设置入口。
- `android_verification` 管理有限尝试与人工接管；用户回答后仍需重新观察，不能把回答当作验证通过证据。

动作已提交与业务完成分别判断。未知结果先重新观察，不自动重放已派发的操作；停止或撤权后不继续执行。

## 传感器、附近设备与网络

这些工具由 `droy_load_tools` 的 `hardware` 工具组提供。先查询 `android_capabilities.hardware` 与各工具的 `operations`，再按任务调用。新增能力默认关闭，可在“权限设置”分别允许；系统授权与产品选择独立。普通查询不需要 Root。活动、身体传感器与普通环境传感器分别授权。

| 工具 | 操作与数据 |
| --- | --- |
| `android_sensor` | 枚举传感器、读取与最多十秒采样；保留原始值、单位、坐标轴、精度及实际时间 |
| `android_location` | 定位状态、当前位置和短时更新；返回来源、年龄、精度及坐标基准，与记忆的位置记录独立 |
| `android_infrared` | 查询发射器与载波范围，发送微秒波形或有限重复序列；单段波形短于两秒 |
| `android_bluetooth` | BLE/经典扫描、配对、SDP、BLE GATT 与 RFCOMM 通信、通知/指示及事件分页 |
| `android_nfc` | 前台贴卡、技术识别、NDEF、认证、页/块读写、格式化及原始字节交换 |
| `android_nfc_emulation` | 保存、查询与激活本地 APDU/状态应答配置；读取最近交互记录 |
| `android_wifi` | 连接信息、带原始信标 IE 的扫描、指标采样，以及本应用的网络连接请求 |
| `android_cellular` | 订阅、注册、LTE/NR 等信号、小区及其时间和缺失状态 |
| `android_network_probe` | 绑定指定网络的 DNS/TCP 测量或最多 1 MiB 的 HTTP 下载测试 |
| `android_device_data` | 无损数据的本地保存、分页读取、删除及选定文件/目录的导入导出 |
| `android_device_shortcut` | 创建、列出和停用设备数据、固定红外指令与 HCE 配置的桌面快捷方式 |

传感器、定位和网络值只描述系统实际提供的数据。Wi-Fi 链路速率不是实际吞吐，同信道接入点数量不能证明空口干扰；蜂窝 CQI、QCI 和 5QI 不能当作通用套餐 VIP 等级。小区和扫描结果可能来自缓存，需检查返回时间。显式选择 Wi-Fi 或蜂窝的探测不会自动回退到其他传输。

Wi-Fi `connect` 使用 `NetworkSpecifier` 请求 Droy 自己的网络，由系统决定是否确认；它不保证改变全局默认网络。受保护网络使用手机本地输入后返回的 `credentialRef`，工具不接收或回显密码。蓝牙按本次发现返回的 `deviceRef`、`sessionId`、`attributeRef` 通信；UUID 可以重复。写入类型与协商 MTU 明确返回，不自动拆分应用协议。通知留在本地，按游标读取并检查丢失范围。

外设会话在任务结束、取消、停止或相应权限撤销时释放；持续连接使用有停止入口的前台生命周期。全局暂停同时停用 HCE；普通引擎闲置回收保留用户显式启用的模拟配置。已有系统限制或权限拒绝会直接返回，不自动改走 Root 或修改无线开关。

## NFC 数据、认证与模拟

NFC 读卡通过可见的等待贴卡界面进行。移卡、换卡或离开读卡界面后需重新扫描；读卡与卡模拟互斥。公开技术接口按硬件实际支持开放，MIFARE Classic 并非所有手机都有。手机内置 NFC 不覆盖 125 kHz 门卡。

NDEF 读取、页/块读取返回实际读取范围；未读区域不会填零冒充完整卡片。写入需要匹配目标技术、容量和可写状态，兼容空白标签可显式格式化；普通复制不隐式设置只读锁或更改访问位。认证使用用户在手机本地输入的凭据引用，密钥由 AndroidKeyStore 加密保存。通用 `transceive`、认证、写入及格式化单独要求操作权限；原始指令可能改变卡片状态。

HCE 首版格式为 `exact_apdu_state_v1`，在手机本地匹配完整 APDU 和当前状态，不运行模型生成的脚本。示例仅展示合成协议配置：

```json
{
  "operation": "save",
  "name": "Synthetic local profile",
  "aids": ["F001020304"],
  "initialState": "initial",
  "rules": [{
    "state": "initial",
    "requestHex": "00a4040005f001020304",
    "responseHex": "9000",
    "nextState": "initial"
  }]
}
```

保存后使用返回的 `profileId` 激活。配置与读卡器协议必须匹配；转储、历史报文或 UID 本身不能完成任意原卡模拟。工具不配置原卡 UID，也不向厂商钱包写入安全元件。激活需要设备支持 HCE、NFC 开启、手机解锁且读卡模式已关闭；路由注册不证明读卡器接受。快捷方式只激活已有配置，固定到桌面还需桌面确认。

## 数据引用与保存

较大原始结果使用本地 `dataRef`，可用 `offset`/`limit` 分页读取，或直接作为后续发送、写入的输入。临时数据归当前任务，任务结束后清理；需要后续复用时先执行 `android_device_data.save`。持久记录包含长度、SHA-256、格式和来源/读取范围，保存在 App 私有目录，单项最多 4 MiB。较大数据通过已授权文件导入，避免把整个载荷放进工具调用。

导出只写入用户选定的目录，不覆盖同名文件；中断时可能留下部分文件，需按回执检查，不能直接重试。快捷方式只引用持久数据或有效配置，打开时再次核对当前权限。清理对话历史与删除已保存设备数据分别处理，持久记录通过数据工具显式删除。

## 音频采集与转写

音频能力使用 Android 原生接口。麦克风需要系统录音权限，播放音频需要 MediaProjection 且目标应用允许采集。

| 工具 | 用途 |
| --- | --- |
| `android_audio_start`、`android_audio_status`、`android_audio_stop` | 开始、查询和停止麦克风或播放音频采集 |
| `audio_transcribe` | 使用随 APK 交付的本地识别器转写文件或采集流 |
| `audio_transcript_read`、`audio_transcription_stop` | 增量读取文字或停止转写 |

录音与转写分别管理；录音可跨对话轮次持续，通过系统通知或音频设置停止。保存 WAV 时，48 kHz 单声道 PCM16 每小时约 346 MB。录音保存在 App 私有目录，导出由用户选择位置。语音输入和朗读见[语音说明](../runtime/voice/README.md)。

## 按需应用诊断

`android_app_info`、`android_app_exit_info`、`android_usage_query`、`android_network_usage`、`android_privacy_usage` 和 `android_battery_usage` 返回系统实际可提供的只读记录。部分查询需要 `DUMP`、`PACKAGE_USAGE_STATS` 与使用情况访问授权；工具不会自行提权或开启能力。

结果保留来源、统计范围、缺失与截断状态。耗电是系统估算，权限状态不证明资源被访问，没有记录也不证明未发生。

## 指定应用的进程内存

进程内存能力要求已授权 Root、API 30+、ARM64 宿主和当前 Android 用户下的普通第三方 64 位目标应用。系统、共享 UID、隔离进程和宿主自身不是目标。

| 工具 | 用途 |
| --- | --- |
| `android_process_memory` | 查询进程，建立、查询和关闭会话 |
| `android_memory_scan` | 扫描、筛选和分页读取有限候选 |
| `android_memory_read` | 读取候选或相对区域，生成一次性写入依据 |
| `android_memory_write` | 按预期字节执行一次最多 8 字节的标量写入并读回 |

数值使用字符串，地址与偏移使用十六进制字符串。会话绑定任务、包版本、UID、PID 与启动时间；进程重启后旧引用失效。读写有独立能力开关，写入仅限已确认的私有可写非执行映射。短写和未知结果不自动补写或回滚，写入读回成功也不等于业务目标完成。

扫描与基线保存在手机，仅有限候选进入模型上下文。完整参数和限制以能力目录及原生校验为准。
