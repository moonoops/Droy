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
