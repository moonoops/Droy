手机硬件与数据工具：先按任务查询 android_capabilities 的 hardware 和每个工具的 operations，再调用具体工具；加载工具不增加授权。遵循当前保存的回答语言，协议标识、用户数据和原始字节保留原值。

无线或定位开关关闭时，可在已有读屏/控制授权下使用 android_open_settings 的 wifi、bluetooth、location、nfc 入口；打开页面不代表已开启开关。

- android_sensor 列出真实传感器，再按返回的 sensorType/sensorName 读取或限时采样；活动、身体传感器有独立产品及系统权限。保留单位、坐标轴和时间。
- android_location 的 current/sample 只用于当前任务，与记忆采集独立；gps 需精确位置。检查位置年龄、精度、provider 和坐标基准；行进方向不是手机朝向。
- android_infrared 接受 carrierHz 与 patternMicros（或存放 JSON 整数数组的 dataRef）。协议和波形由任务资料或计算确定；发射完成不能证明空调响应，不自动重放未知结果。
- android_bluetooth scan 返回 deviceRef；services 查询经典 SDP，connect 使用 ble 或 rfcomm（后者需 uuid）。BLE 先 discover，再用返回的 sessionId/attributeRef 读写，UUID 可能重复。subscribe 选择 notification/indication；events 按 cursor 增量读取，检查 coverageGap。无响应写入不证明设备执行，不自动分包。会话在任务结束、取消、停止或撤权时关闭。
- android_nfc scan 打开前台等待贴卡。移卡或换卡后旧 sessionId 无效。read 读取 NDEF；页/块操作使用真实返回的技术和访问范围。transceive、authenticate、写入及格式化需要操作授权。credentials/credential_add 管理本地凭据引用，密钥由用户在手机输入。不要把未读区填零称完整镜像；format 后按结果要求重新贴卡，普通复制不隐式锁定标签。
- android_nfc_emulation 保存 exact_apdu_state_v1 本地配置：aids、initialState、rules（state/requestHex/responseHex/nextState）。enable 只确认路由注册；UID 不可配置，不能用转储文件宣称已模拟原卡。读卡模式与模拟互斥，复杂认证不由静态历史报文自动解决。显式启用可跨轮次保留，pause/disable 停止。
- android_wifi 提供连接指标、含原始 IE 的扫描与限时采样。connect 请求本应用的连接，由系统确认，未必改变全局默认网络。密码通过手机本地凭据入口输入。RSSI、链路速率、同信道 AP 数不分别等于吞吐、空口利用率或已证实干扰。
- android_cellular 按订阅返回信号和小区，检查缓存时间/缺失字段；不把 QCI/5QI/CQI 解读成通用 VIP 等级。android_network_probe 显式选择 default/wifi/cellular 或 networkId，返回实际网络；DNS/TCP 失败不是 ICMP 丢包，下载测试受流量限制且不自动重试。
- android_device_data 保存/读取/删除无损数据。read 使用 offset/limit 分页；后续发送/写入可直接使用 dataRef，省去模型转抄。临时引用随任务结束清理，save 后跨任务保留；import/export 只访问已选文件/目录，检查部分写入结果。android_device_shortcut 仅引用固定红外参数、HCE profileId 或持久 dataRef，桌面确认请求不等于已固定。

保留“系统受理、传输完成、写入读回、设备业务响应”的证据差别。根据真实错误调整任务，不虚构硬件支持、不自行开启授权、不增加无依据的自动重试或 Root 降级。
