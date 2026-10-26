# 构建与检查

从仓库根目录执行，构建依赖见 [README](README.md)。测试使用虚构数据；设备测试请使用专用设备或隔离的测试环境，先停止正在进行的任务。

## 主机检查

```sh
./tools/build.sh
./gradlew --no-daemon :app:lintDebug
```

已有构建依赖时可运行相关模块检查：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py'
node --expose-internals --test runtime/mobile/tests/*.test.mjs
node --test runtime/media-input/tests/*.test.mjs runtime/voice/tests/*.test.mjs
node --test runtime/memory/tests/*.test.mjs runtime/privacy/tests/*.test.mjs runtime/execution/tests/*.test.mjs
node --expose-internals runtime/mobile/tests/profile-smoke.mjs
node --expose-internals runtime/mobile/tests/profile-memory-active-smoke.mjs
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon -I tools/tests/runtime_release_binding.gradle :app:testRuntimeReleaseBinding
```

主机回归使用本地模型与执行器夹具，不需要模型 API Key。源码变化后重新运行 `node tools/prepare_dsh.mjs --refresh-mobile`，再构建并安装对应 APK。

## 设备检查

构建脚本生成 App 和 instrumentation APK。设备连接与安装见 [INSTALLATION.md](INSTALLATION.md)。检查首次配置、模型连接、所选权限、手机动作、停止、撤权、重启与升级后配置保留。

仪器测试入口位于 `app/src/androidTest/`，各类声明了所需的显式 fixture 参数。仅在满足其前置条件时运行；缺少授权、已有私人数据或存在未恢复的 fixture 清单时，不要绕过保护条件。

- 个人记忆与采集测试需要已授权 Root、执行隔离资格和空白测试环境，不得覆盖现有私人记忆库。
- 麦克风、相机、位置与通知测试只使用受控材料；会改变权限或设置的用例结束后核对恢复。
- 声音下载、模型请求等联网测试需事先确认流量、存储及调用费用。
- 日志、录屏、报告、设备编号和测试产物保存在源码仓库之外，不提交真实运行数据。

## 界面变更验收

实际运行受影响入口及其共用容器，检查中文和英文，并按影响覆盖以下条件：

| 条件 | 检查内容 |
| --- | --- |
| 目标手机、320 CSS px 窄屏、横屏 | 文字完整，所有 tab、按钮与滚动末项可到达，无意外横向溢出 |
| 系统大字体与应用最大字号 | 说明和操作不裁切、遮挡；不以缩小必要文字代替修复 |
| 浅色、深色及跟随系统 | 文字、选中状态、弹层与系统栏清楚可辨 |
| 键盘、弹层与系统返回 | 安全区正确，返回逐层关闭，草稿与当前会话按约定保留 |
| 加载、空内容、错误与权限状态 | 提示和恢复入口可用，实际触摸有效 |

审查实际截图并操作边缘按钮、最后一个 tab 与末项控件；DOM 存在或点击回执不能替代界面检查。DSH 升级后逐项到达所有可见入口和设置 tab。验收记录保存在源码仓库之外。
