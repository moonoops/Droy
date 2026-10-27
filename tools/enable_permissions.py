#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Enable and verify DroyAgent permissions on one explicitly selected ADB device.

Only this package's permissions are changed. Root-manager policy and Storage
Access Framework selections remain Android-controlled, once-only user choices.
"""

import sys

if sys.version_info < (3, 8):
    print("需要 Python 3.8 或更新版本，请从 https://www.python.org/downloads/ 安装。", file=sys.stderr)
    sys.exit(2)

import argparse
from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
from typing import List, Optional


RELEASE_PACKAGE = "io.droyagent.app"
PACKAGE = RELEASE_PACKAGE + ".dev"
SUPPORTED_PACKAGES = (PACKAGE, RELEASE_PACKAGE)
ACCESSIBILITY = PACKAGE + "/io.droyagent.androiduse.DroyAccessibilityService"
NOTIFICATION = PACKAGE + "/io.droyagent.app.DroyNotificationService"
PROVIDER = "content://" + PACKAGE + ".permissions"
TIMEOUT_SECONDS = 20
NOTIFICATION_READY_SECONDS = 3.0
NOTIFICATION_POLL_SECONDS = 0.1
ACCESSIBILITY_READY_SECONDS = 5.0
ACCESSIBILITY_POLL_SECONDS = 0.2
ACCESSIBILITY_REBIND_SECONDS = 0.5
WORKSPACE_DIR = Path(__file__).resolve().parents[2]
PLATFORM_TOOLS_URL = "https://developer.android.com/tools/releases/platform-tools"
HARDWARE_PERMISSION_LABELS_EN = {
    "大致位置": "Approximate location", "精确位置": "Precise location",
    "蓝牙扫描": "Bluetooth scan", "蓝牙连接": "Bluetooth connection",
    "附近 Wi-Fi 设备": "Nearby Wi-Fi devices", "电话状态": "Phone state",
    "活动识别": "Activity recognition", "心率读取": "Read heart rate", "身体传感器": "Body sensors",
}


class SetupError(Exception):
    """A prerequisite failed before any permission changes."""


class AdbError(Exception):
    """ADB failed; never include command output, which can contain user data."""


class AdbTimeout(AdbError):
    """A bounded ADB read did not finish before its deadline."""


@dataclass
class ItemResult:
    name: str
    status: str
    detail: str


class Adb:
    def __init__(self, executable: str, serial: Optional[str] = None):
        self.executable = executable
        self.serial = serial

    def run(self, *arguments: str, timeout_seconds=TIMEOUT_SECONDS) -> str:
        target = ["-s", self.serial] if self.serial else []
        try:
            result = subprocess.run([self.executable, *target, *arguments],
                                    capture_output=True, text=True,
                                    timeout=timeout_seconds, encoding="utf-8", errors="replace")
        except subprocess.TimeoutExpired as error:
            raise AdbTimeout("ADB 连接超时，请重新连接手机后重试") from error
        except OSError as error:
            raise AdbError("无法启动 ADB，请检查工具路径") from error
        if result.returncode:
            raise AdbError("ADB 执行失败，退出码 " + str(result.returncode))
        return result.stdout.replace("\r", "").strip()

    def shell(self, *arguments: str, timeout_seconds=TIMEOUT_SECONDS) -> str:
        # ADB concatenates shell arguments on the device. Quote there as well as
        # using a subprocess argv on the host, preserving existing service lists.
        return self.run("shell", " ".join(shlex.quote(value) for value in arguments), timeout_seconds=timeout_seconds)


def find_adb(explicit: Optional[str], environment=None, platform_name=None) -> str:
    environment = os.environ if environment is None else environment
    platform_name = sys.platform if platform_name is None else platform_name
    search_path = environment.get("PATH", "")
    candidate = explicit or environment.get("DROY_ADB")
    if candidate:
        executable = shutil.which(candidate, path=search_path)
        if not executable:
            raise SetupError("指定的 ADB 无法执行，请检查 --adb 或 DROY_ADB；不会自动换用其他 ADB。")
        return executable

    adb_name = "adb.exe" if platform_name == "win32" else "adb"
    sdk_dirs = []
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if environment.get(name):
            sdk_dirs.append(Path(environment[name]).expanduser())
    # Finder and Explorer often omit terminal PATH settings. Reuse the build
    # toolchain first, then Android Studio's standard SDK directory.
    sdk_dirs.append(WORKSPACE_DIR / "artifacts" / "toolchains" / "android-sdk")
    home = Path(environment.get("USERPROFILE") or environment.get("HOME") or Path.home())
    if platform_name == "darwin":
        sdk_dirs.append(home / "Library" / "Android" / "sdk")
    elif platform_name == "win32":
        local_data = Path(environment.get("LOCALAPPDATA") or home / "AppData" / "Local")
        sdk_dirs.append(local_data / "Android" / "Sdk")
    else:
        sdk_dirs.append(home / "Android" / "Sdk")
    for sdk in sdk_dirs:
        executable = shutil.which(str(sdk / "platform-tools" / adb_name), path=search_path)
        if executable:
            return executable
    executable = shutil.which(adb_name, path=search_path)
    if executable:
        return executable
    raise SetupError("未找到 ADB。请安装 Android platform-tools，或设置 DROY_ADB：" + PLATFORM_TOOLS_URL)


def choose_device(adb: Adb, explicit: Optional[str]) -> str:
    if explicit:
        serial = explicit
    else:
        listing = adb.run("devices")
        devices = []
        for line in listing.splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[0] != "List" and not fields[0].startswith("*"):
                devices.append(fields[0])
        if not devices:
            raise SetupError("未找到手机，请连接 USB，并在手机上允许这台电脑进行 USB 调试")
        if len(devices) != 1:
            raise SetupError("检测到多个设备，请用 --serial 设备编号选择手机")
        serial = devices[0]
    adb.serial = serial
    if adb.run("get-state") != "device":
        raise SetupError("所选手机尚未连接就绪，请检查 USB 调试授权")
    return serial


def component_key(component: str) -> str:
    package, separator, service = component.partition("/")
    if not separator:
        return component
    return package + "/" + (package + service if service.startswith(".") else service)


def components(raw: str) -> List[str]:
    if raw in ("", "null"):
        return []
    values = raw.split(":")
    # A malformed read must not replace the user's existing accessibility list.
    if any(not re.fullmatch(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+", value) for value in values):
        raise ValueError("无法识别系统服务列表，已保留原列表")
    return values


def permission_is_granted(dump: str, user: str, permission: str) -> bool:
    """Read only the chosen user's block, never another profile's grant."""
    lines = dump.splitlines()
    selected = []
    active = False
    indentation = 0
    for line in lines:
        match = re.match(r"^(\s*)User\s+(\d+):", line)
        if match:
            active = match.group(2) == user
            indentation = len(match.group(1))
            continue
        if active:
            if line.strip() and len(line) - len(line.lstrip()) <= indentation:
                break
            selected.append(line)
    return bool(re.search(r"(?m)^\s*" + re.escape(permission) + r":\s+granted=true(?:[,\s]|$)",
                          "\n".join(selected)))


def development_permission_is_granted(dump: str, permission: str, package_name=PACKAGE) -> bool:
    """Development grants live in the target package's install-permission block."""
    package_indent = None
    permission_indent = None
    grant = re.compile(re.escape(permission) + r":\s+granted=true(?:[,\s]|$)")
    for line in dump.splitlines():
        package = re.match(r"^(\s*)Package \[" + re.escape(package_name) + r"\]", line)
        if package:
            package_indent = len(package.group(1))
            permission_indent = None
            continue
        if package_indent is None or not line.strip():
            continue
        indentation = len(line) - len(line.lstrip())
        if indentation <= package_indent:
            break
        if line.strip() == "install permissions:":
            permission_indent = indentation
            continue
        if permission_indent is not None:
            if indentation <= permission_indent:
                break
            if grant.match(line.strip()):
                return True
    return False


def appop_is_allowed(output: str, operation: str) -> bool:
    modes = re.findall(r"\b" + re.escape(operation) + r":\s*([a-z_]+)", output)
    # Both UID and package modes can be present. An explicit denial must not be
    # hidden by a second line reporting allow.
    return bool(modes) and all(mode == "allow" for mode in modes)


def product_is_enabled(output: str) -> bool:
    pairs = dict(re.findall(r"\b(enabled|stopped|schemaVersion)=(true|false|\d+)\b", output))
    return pairs == {"enabled": "true", "stopped": "false", "schemaVersion": "1"}


def accessibility_is_connected(output: str) -> bool:
    pairs = dict(re.findall(r"\b(accessibilityConnected|schemaVersion)=(true|false|\d+)\b", output))
    if pairs.get("schemaVersion") != "1" or pairs.get("accessibilityConnected") not in ("true", "false"):
        raise ValueError("App 未提供有效的无障碍连接状态，请安装当前版本 APK 后重试")
    return pairs["accessibilityConnected"] == "true"


class PermissionSetup:
    def __init__(self, adb: Adb, user: str, api: int, package_name=PACKAGE):
        if package_name not in SUPPORTED_PACKAGES:
            raise ValueError("Unsupported Droy package / 不支持的 Droy 包名")
        self.package = package_name
        self.accessibility_component = package_name + "/io.droyagent.androiduse.DroyAccessibilityService"
        self.notification = package_name + "/io.droyagent.app.DroyNotificationService"
        self.provider = "content://" + package_name + ".permissions"
        self.activity = package_name + "/io.droyagent.app.MainActivity"
        self.adb = adb
        self.user = user
        self.api = api
        self.results: List[ItemResult] = []
        self.app_started = False

    def item(self, name, action, detail="已开启并确认"):
        try:
            action()
        except (AdbError, ValueError) as error:
            self.results.append(ItemResult(name, "failed", str(error)))
        else:
            self.results.append(ItemResult(name, "verified", detail))

    def settings_get(self, key: str) -> str:
        return self.adb.shell("settings", "--user", self.user, "get", "secure", key)

    def runtime_permission(self, permission: str):
        self.adb.shell("pm", "grant", "--user", self.user, self.package, permission)
        dump = self.adb.shell("dumpsys", "package", self.package)
        if not permission_is_granted(dump, self.user, permission):
            raise ValueError("系统未确认此权限，请检查手机限制后重试")

    def development_permission(self, permission: str):
        self.adb.shell("pm", "grant", "--user", self.user, self.package, permission)
        dump = self.adb.shell("dumpsys", "package", self.package)
        if not development_permission_is_granted(dump, permission, self.package):
            raise ValueError("系统未确认此系统权限，请检查手机限制及 APK 版本后重试")

    def accessibility_recovery(self):
        for name, permission in (("自动恢复写入权限", "android.permission.WRITE_SECURE_SETTINGS"),
                                 ("自动恢复状态读取权限", "android.permission.DUMP")):
            self.item(name, lambda permission=permission: self.development_permission(permission),
                      "已授予并确认；不修改系统无障碍开关")

    def enable_accessibility(self):
        before = components(self.settings_get("enabled_accessibility_services"))
        expected = {component_key(value) for value in before}
        expected.add(component_key(self.accessibility_component))
        if component_key(self.accessibility_component) not in {component_key(value) for value in before}:
            self.adb.shell("settings", "--user", self.user, "put", "secure",
                           "enabled_accessibility_services", ":".join([*before, self.accessibility_component]))
        self.adb.shell("settings", "--user", self.user, "put", "secure", "accessibility_enabled", "1")
        actual = {component_key(value) for value in components(self.settings_get("enabled_accessibility_services"))}
        if not expected.issubset(actual) or self.settings_get("accessibility_enabled") != "1":
            raise ValueError("系统未确认无障碍权限，或其他服务列表发生变化，请检查后重试")

    def wait_for_accessibility(self):
        deadline = time.monotonic() + ACCESSIBILITY_READY_SECONDS
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            try:
                output = self.adb.shell("content", "call", "--user", self.user, "--uri", self.provider,
                                        "--method", "status", timeout_seconds=remaining)
            except AdbTimeout:
                if time.monotonic() >= deadline:
                    return False
                raise
            if accessibility_is_connected(output):
                return True
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(ACCESSIBILITY_POLL_SECONDS, remaining))

    def accessibility(self):
        self.open_app()
        self.enable_accessibility()
        if self.wait_for_accessibility():
            return
        # Updates and instrumentation can leave an enabled service disconnected.
        # Rebind only Droy once, using a fresh list to retain other services.
        current = components(self.settings_get("enabled_accessibility_services"))
        others = [value for value in current if component_key(value) != component_key(self.accessibility_component)]
        try:
            self.adb.shell("settings", "--user", self.user, "put", "secure",
                           "enabled_accessibility_services", ":".join(others))
            actual = {component_key(value) for value in components(self.settings_get("enabled_accessibility_services"))}
            if component_key(self.accessibility_component) in actual or not {component_key(value) for value in others}.issubset(actual):
                raise ValueError("系统未确认无障碍重新绑定，或其他服务列表发生变化，请检查后重试")
            # The settings observer runs asynchronously; allow it to process
            # removal before re-enabling, including an already crashed service.
            time.sleep(ACCESSIBILITY_REBIND_SECONDS)
        finally:
            self.enable_accessibility()
        if not self.wait_for_accessibility():
            raise ValueError("无障碍开关已开启，但服务仍未连接。请在手机系统无障碍设置中关闭再开启 DroyAgent 手机操作，然后重试")

    def appop(self, operation: str):
        self.adb.shell("cmd", "appops", "set", "--user", self.user, self.package, operation, "allow")
        state = self.adb.shell("cmd", "appops", "get", "--user", self.user, self.package, operation)
        if not appop_is_allowed(state, operation):
            raise ValueError("系统未确认此权限，请检查手机限制后重试")

    def usage_access(self):
        # System diagnostic dumps require the install grant as well as the AppOp
        # used by the public usage/network APIs.
        self.development_permission("android.permission.PACKAGE_USAGE_STATS")
        self.appop("GET_USAGE_STATS")

    def notifications(self):
        self.adb.shell("cmd", "notification", "allow_listener", self.notification, self.user)
        # Android may persist listener access after allow_listener returns.
        # Poll only the readback; repeatedly granting can restart the listener.
        deadline = time.monotonic() + NOTIFICATION_READY_SECONDS
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError("系统未确认通知读取权限，请检查后重试")
            raw = self.adb.shell("settings", "--user", self.user, "get", "secure",
                                 "enabled_notification_listeners", timeout_seconds=remaining)
            listeners = components(raw)
            if component_key(self.notification) in {component_key(value) for value in listeners}:
                return
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError("系统未确认通知读取权限，请检查后重试")
            time.sleep(min(NOTIFICATION_POLL_SECONDS, remaining))

    def open_app(self):
        if self.app_started:
            return
        # Some Android vendors refuse cold provider starts from ADB even though
        # the package is installed. A waited, explicit Activity launch also
        # brings force-stopped installations into a usable foreground state.
        # An existing in-app browser can otherwise remain on top of the task.
        # Reordering brings MainActivity forward without clearing that activity.
        launch = self.adb.shell("am", "start", "--user", self.user, "-W",
                                "--activity-reorder-to-front", "-n", self.activity)
        activity = re.search(r"(?m)^Activity:\s*(\S+)\s*$", launch)
        if not re.search(r"(?m)^Status:\s*ok\s*$", launch) or not activity or \
                component_key(activity.group(1)) != component_key(self.activity):
            raise ValueError("系统未确认 DroyAgent 已打开，请检查手机后重试")
        self.app_started = True

    def product(self):
        self.open_app()
        self.adb.shell("content", "call", "--user", self.user, "--uri", self.provider,
                       "--method", "enable-all")
        state = self.adb.shell("content", "call", "--user", self.user, "--uri", self.provider,
                               "--method", "status")
        if not product_is_enabled(state):
            raise ValueError("App 未确认能力已全部开启，请安装当前版本后重试")

    def apply_selected(self, permissions):
        """Grant exactly an approved installation plan's system permissions.

        Product capability choices are applied separately through the setup
        provider; this path never calls the legacy enable-all operation.
        """
        selected = list(dict.fromkeys(permissions))
        allowed = {"accessibility", "calendar", "contacts", "microphone", "notifications",
                   "notificationAccess", "usage", "diagnostics", "brightness", "overlay",
                   "location", "bluetooth", "nearbyWifi", "phoneState", "activityRecognition", "bodySensors"}
        if any(not isinstance(name, str) or name not in allowed for name in selected):
            raise ValueError("安装方案包含不支持的系统权限")
        granted_runtime = set()
        location = (("大致位置", "android.permission.ACCESS_COARSE_LOCATION"),
                    ("精确位置", "android.permission.ACCESS_FINE_LOCATION"))
        hardware = {
            "location": location,
            "bluetooth": (("蓝牙扫描", "android.permission.BLUETOOTH_SCAN"),
                          ("蓝牙连接", "android.permission.BLUETOOTH_CONNECT")) if self.api >= 31 else location,
            "nearbyWifi": (("附近 Wi-Fi 设备", "android.permission.NEARBY_WIFI_DEVICES"),) if self.api >= 33 else location,
            "phoneState": (("电话状态", "android.permission.READ_PHONE_STATE"),),
            "activityRecognition": (("活动识别", "android.permission.ACTIVITY_RECOGNITION"),),
            "bodySensors": (("心率读取", "android.permission.health.READ_HEART_RATE"),) if self.api >= 36
                else (("身体传感器", "android.permission.BODY_SENSORS"),),
        }
        for name in selected:
            if name in hardware:
                for label, permission in hardware[name]:
                    if permission in granted_runtime:
                        continue
                    granted_runtime.add(permission)
                    self.item(label + " / " + HARDWARE_PERMISSION_LABELS_EN[label],
                              lambda permission=permission: self.runtime_permission(permission),
                              detail="已开启并确认 / Enabled and verified")
            elif name == "accessibility":
                self.accessibility_recovery()
                self.item("无障碍操作", self.accessibility)
            elif name in ("calendar", "contacts", "microphone"):
                permission, label = {
                    "calendar": ("android.permission.READ_CALENDAR", "读取日历"),
                    "contacts": ("android.permission.READ_CONTACTS", "读取联系人"),
                    "microphone": ("android.permission.RECORD_AUDIO", "麦克风采集"),
                }[name]
                self.item(label, lambda permission=permission: self.runtime_permission(permission))
            elif name == "notifications":
                if self.api >= 33:
                    self.item("发送通知", lambda: self.runtime_permission("android.permission.POST_NOTIFICATIONS"))
                else:
                    self.results.append(ItemResult("发送通知", "not_required", "当前 Android 版本无需单独授权"))
            elif name == "notificationAccess":
                self.item("读取当前通知", self.notifications)
            elif name == "usage":
                self.item("应用使用记录", self.usage_access)
            elif name == "diagnostics":
                self.item("系统诊断", lambda: self.development_permission("android.permission.DUMP"))
            elif name == "brightness":
                self.item("修改系统设置", lambda: self.appop("WRITE_SETTINGS"))
            elif name == "overlay":
                self.item("显示悬浮窗", lambda: self.appop("SYSTEM_ALERT_WINDOW"))
        return self.results

    def apply(self, accessibility_only=False, preserve_accessibility=False):
        if accessibility_only or preserve_accessibility:
            self.accessibility_recovery()
            if accessibility_only:
                self.item("无障碍操作", self.accessibility)
            return self.results
        for name, permission in (("读取日历", "android.permission.READ_CALENDAR"),
                                 ("读取联系人", "android.permission.READ_CONTACTS"),
                                 ("麦克风采集", "android.permission.RECORD_AUDIO")):
            self.item(name, lambda permission=permission: self.runtime_permission(permission))
        if self.api >= 33:
            self.item("发送通知", lambda: self.runtime_permission("android.permission.POST_NOTIFICATIONS"))
        else:
            self.results.append(ItemResult("发送通知", "not_required", "当前 Android 版本无需单独授权"))
        self.accessibility_recovery()
        self.item("无障碍操作", self.accessibility)
        self.item("修改系统设置", lambda: self.appop("WRITE_SETTINGS"))
        self.item("应用使用记录", self.usage_access)
        self.item("读取当前通知", self.notifications)
        self.item("App 内全部能力开关", self.product)
        return self.results


def run_setup(arguments):
    adb = Adb(find_adb(arguments.adb))
    try:
        serial = choose_device(adb, arguments.serial or os.environ.get("ANDROID_SERIAL"))
        api_raw = adb.shell("getprop", "ro.build.version.sdk")
        if not api_raw.isdigit() or int(api_raw) < 30:
            raise SetupError("需要 Android 11 / API 30 或更新版本")
        user = adb.shell("am", "get-current-user")
        if not user.isdigit():
            raise SetupError("无法确定当前 Android 用户，未修改权限")
        installed = adb.shell("pm", "path", "--user", user, arguments.package)
        if not re.search(r"(?m)^package:.+\.apk$", installed):
            raise SetupError("当前手机用户尚未安装 DroyAgent，请先安装 APK")
    except AdbError as error:
        raise SetupError(str(error)) from error
    results = PermissionSetup(adb, user, int(api_raw), arguments.package).apply(arguments.accessibility_only, arguments.preserve_accessibility)
    return serial, user, results


def main(argv=None):
    parser = argparse.ArgumentParser(description="从电脑配置并检查 DroyAgent 权限。"
                                     "需先允许 USB 调试；保留 App 数据与其他无障碍服务。")
    parser.add_argument("--serial", help="指定手机编号；也可使用 ANDROID_SERIAL，或自动选择唯一设备")
    parser.add_argument("--adb", help="指定 ADB 路径；也可使用 DROY_ADB、自动查找 SDK 或 PATH")
    parser.add_argument("--package", choices=SUPPORTED_PACKAGES, default=PACKAGE,
                        help="目标包名 / target package (default: io.droyagent.app.dev)")
    parser.add_argument("--json", action="store_true", help="输出 JSON 检查结果")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--accessibility-only", action="store_true",
                      help="配置自动恢复权限，开启并确认无障碍连接，保留其他权限和 App 能力开关")
    mode.add_argument("--preserve-accessibility", action="store_true",
                      help="仅配置自动恢复权限，保留系统无障碍开关及 App 选择，不打开 App")
    arguments = parser.parse_args(argv)
    try:
        serial, user, results = run_setup(arguments)
    except SetupError as error:
        if arguments.json:
            print(json.dumps({"ok": False, "error": str(error), "results": []}, ensure_ascii=True))
        else:
            print("授权未开始：" + str(error), file=sys.stderr)
        return 2
    failed = sum(item.status == "failed" for item in results)
    manual = ["Root（如需使用）：首次在手机 Root 管理器中允许 DroyAgent，并选择记住授权。",
              "文件（如需使用）：首次在系统文件选择器中选择允许读取的文件或导出文件夹，后续继续使用该选择。"]
    if arguments.accessibility_only or arguments.preserve_accessibility:
        manual = []
    if arguments.json:
        print(json.dumps({"ok": failed == 0, "serial": serial, "androidUser": user,
                          "results": [asdict(item) for item in results], "manualWhenUsed": manual}, ensure_ascii=True))
    else:
        print("DroyAgent 权限检查结果：" + serial + "（Android 用户 " + user + "）")
        for item in results:
            label = {"verified": "已确认", "not_required": "无需授权", "failed": "未完成"}[item.status]
            print("【" + label + "】" + item.name + "：" + item.detail)
        if arguments.preserve_accessibility:
            success = "自动恢复所需权限已确认；系统无障碍开关和 App 选择保持不变，未检查服务连接。"
        elif arguments.accessibility_only:
            success = "无障碍服务已开启并实际连接，自动恢复所需权限已确认。"
        else:
            success = "可由电脑开启的权限均已确认，之后可在 App 内按需关闭。"
        print("\n" + (success if not failed else str(failed) + " 项未完成。已开启的权限会保留，请检查手机限制后重试。"))
        for instruction in manual:
            print(instruction)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
