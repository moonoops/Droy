"""Exercise PC permission setup with stateful ADB fixtures, never a real phone."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch


TOOLS = Path(__file__).resolve().parents[1]
SCRIPT = TOOLS / "enable_permissions.py"
SPEC = importlib.util.spec_from_file_location("droy_enable_permissions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

MOCK_ADB = r'''
import json, os, shlex, sys
args = sys.argv[1:]
with open(os.environ["MOCK_ADB_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
config = json.loads(os.environ["MOCK_ADB_CONFIG"])
package = config.get("package", "io.droyagent.app.dev")
accessibility = package + "/io.droyagent.androiduse.DroyAccessibilityService"
state_path = os.environ["MOCK_ADB_STATE"]
with open(state_path) as stream:
    state = json.load(stream)
def save():
    with open(state_path, "w") as stream:
        json.dump(state, stream)
def respond(value="", code=0):
    print(value)
    save()
    sys.exit(code)
if args == ["devices"]:
    respond(config.get("devices", "List of devices attached\nphone-1\tdevice\n"))
if args[:1] != ["-s"]:
    respond("unexpected target", 90)
command = args[2:]
if command == ["get-state"]:
    respond(config.get("device_state", "device"), config.get("state_exit", 0))
if command[:2] == ["install", "-r"]:
    respond("Success", config.get("install_exit", 0))
if command[:1] != ["shell"]:
    respond("unexpected host command", 91)
remote = shlex.split(command[1]) if len(command) == 2 else command[1:]
for prefix in config.get("fail_commands", []):
    if remote[:len(prefix)] == prefix:
        respond("refused; private fake token should never be printed: test-secret-value", 1)
if remote == ["getprop", "ro.build.version.sdk"]:
    respond(config.get("api", "36"))
if remote == ["getprop", "ro.product.cpu.abilist"]:
    respond("arm64-v8a,armeabi-v7a")
if remote == ["am", "get-current-user"]:
    respond(config.get("user", "0"))
if remote[:2] == ["am", "start"]:
    if config.get("browser_task_on_top") and "--activity-reorder-to-front" not in remote:
        respond("Status: ok\nActivity: io.droyagent.app.dev/io.droyagent.app.BrowserActivity\nComplete")
    respond(config.get("launch_output", "Status: ok\nActivity: " + package + "/io.droyagent.app.MainActivity\nComplete"),
            config.get("launch_exit", 0))
if remote[:2] == ["pm", "path"]:
    respond("package:/data/app/mock/base.apk" if config.get("installed", True) else "")
if remote[:2] == ["pm", "grant"]:
    if remote[-1] not in config.get("unconfirmed_permissions", []):
        state["permissions"].append(remote[-1])
    respond()
if remote[:2] == ["dumpsys", "package"]:
    current = config.get("user", "0")
    permissions = ["android.permission.READ_CALENDAR", "android.permission.READ_CONTACTS", "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS",
                   "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
                   "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
                   "android.permission.NEARBY_WIFI_DEVICES", "android.permission.READ_PHONE_STATE",
                   "android.permission.ACTIVITY_RECOGNITION", "android.permission.BODY_SENSORS", "android.permission.health.READ_HEART_RATE"]
    development = ["android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP", "android.permission.PACKAGE_USAGE_STATS"]
    lines = ["Packages:", "  Package [" + package + "] (mock):", "    requested permissions:"]
    lines.extend("      " + p for p in development)
    if not config.get("omit_install_grants"):
        lines.append("    install permissions:")
        lines.extend("      " + p + ": granted=" + ("true" if p in state["permissions"] else "false") for p in development)
    lines.extend(["    User " + current + ": installed=true", "      runtime permissions:"])
    lines.extend("        " + p + ": granted=" + ("true" if p in state["permissions"] else "false") + ", flags=[USER_SET]" for p in permissions)
    lines.extend(["    User 999: installed=true", "      runtime permissions:"])
    lines.extend("        " + p + ": granted=true, flags=[USER_SET]" for p in permissions)
    respond("\n".join(lines))
if remote[:1] == ["settings"]:
    operation, table, key = remote[3:6]
    if table != "secure":
        respond("wrong settings table", 92)
    if operation == "get":
        if key == "enabled_notification_listeners" and state.get("pending_listener"):
            if state["pending_listener_reads"] > 0:
                state["pending_listener_reads"] -= 1
            else:
                values = state["settings"].get(key, "null")
                listener = state.pop("pending_listener")
                state["settings"][key] = listener if values == "null" else values + ":" + listener
        respond(state["settings"].get(key, "null"))
    if operation == "put":
        if key not in config.get("unconfirmed_settings", []):
            if key == "enabled_accessibility_services" and accessibility in state["settings"].get(key, "") and accessibility not in remote[6]:
                state["accessibility_rebound"] = True
            state["settings"][key] = remote[6]
        respond()
if remote[:3] == ["cmd", "appops", "set"]:
    if remote[-2] not in config.get("unconfirmed_appops", []):
        state["appops"][remote[-2]] = remote[-1]
    respond()
if remote[:3] == ["cmd", "appops", "get"]:
    operation = remote[-1]
    respond(operation + ": " + state["appops"].get(operation, "ignore"))
if remote[:3] == ["cmd", "notification", "allow_listener"]:
    if not config.get("unconfirmed_listener"):
        if config.get("listener_delay_reads"):
            state["pending_listener"] = remote[3]
            state["pending_listener_reads"] = config["listener_delay_reads"]
        else:
            values = state["settings"].get("enabled_notification_listeners", "null")
            state["settings"]["enabled_notification_listeners"] = remote[3] if values == "null" else values + ":" + remote[3]
    respond()
if remote[:2] == ["content", "call"]:
    if remote[-1] == "enable-all" and not config.get("unconfirmed_product"):
        state["product"] = True
    connected = accessibility in state["settings"].get("enabled_accessibility_services", "") and state["settings"].get("accessibility_enabled") == "1"
    connected = connected and not config.get("accessibility_never_connects") and (not config.get("accessibility_crashed") or state.get("accessibility_rebound"))
    if state.get("accessibility_reads", 0) < config.get("accessibility_delay_reads", 0):
        connected = False
    state["accessibility_reads"] = state.get("accessibility_reads", 0) + 1
    status = "schemaVersion=1, stopped=false, enabled=true" if state["product"] else "schemaVersion=1, stopped=true, enabled=false"
    if not config.get("invalid_accessibility_status"):
        status += ", accessibilityConnected=" + ("true" if connected else "false")
    respond("Result: Bundle[{" + status + "}]")
respond("unexpected remote command", 93)
'''


class PermissionSetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="droy permissions ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.adb = self.root / "mock adb"
        self.adb.write_text("#!" + sys.executable + "\n" + MOCK_ADB)
        self.adb.chmod(0o755)
        self.log = self.root / "calls.jsonl"
        self.state_path = self.root / "state.json"
        self.initial_state = {"permissions": [], "settings": {"enabled_accessibility_services": "other.app/other.app.Reader"},
                              "appops": {}, "product": False}
        self.state_path.write_text(json.dumps(self.initial_state))

    def environment(self, config=None, serial=None):
        environment = dict(os.environ)
        environment.pop("ANDROID_SERIAL", None)
        environment.update(DROY_ADB=str(self.adb), DROY_PYTHON=sys.executable,
                           MOCK_ADB_LOG=str(self.log), MOCK_ADB_STATE=str(self.state_path),
                           MOCK_ADB_CONFIG=json.dumps(config or {}))
        if serial is not None:
            environment["ANDROID_SERIAL"] = serial
        return environment

    def run_setup(self, *arguments, config=None, serial=None):
        return subprocess.run([sys.executable, str(SCRIPT), "--json", *arguments],
                              env=self.environment(config, serial), capture_output=True, text=True, timeout=20)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def remote_calls(self):
        return [__import__("shlex").split(call[3]) for call in self.calls() if len(call) == 4 and call[2] == "shell"]

    def summary(self, result):
        self.assertNotIn("test-secret-value", result.stdout + result.stderr)
        return json.loads(result.stdout)

    def assert_no_mutation(self):
        self.assertEqual(json.loads(self.state_path.read_text()), self.initial_state)

    def run_selected(self, permissions, api=36, config=None):
        with patch.dict(os.environ, self.environment(config)):
            adb = MODULE.Adb(str(self.adb), "phone-1")
            return MODULE.PermissionSetup(adb, "0", api).apply_selected(permissions)

    def test_selected_hardware_permissions_use_android16_granular_grants_without_radio_changes(self):
        results = self.run_selected(["location", "bluetooth", "nearbyWifi", "phoneState", "activityRecognition", "bodySensors"])
        self.assertTrue(all(item.status == "verified" for item in results), results)
        expected = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.NEARBY_WIFI_DEVICES", "android.permission.READ_PHONE_STATE",
                    "android.permission.ACTIVITY_RECOGNITION", "android.permission.health.READ_HEART_RATE"]
        saved = json.loads(self.state_path.read_text())
        self.assertEqual(saved["permissions"], expected)
        self.assertEqual(saved["settings"], self.initial_state["settings"])
        self.assertEqual(saved["appops"], {})
        self.assertFalse(saved["product"])
        self.assertFalse(any("enable-all" in command or command[:1] == ["svc"] for command in self.remote_calls()))

    def test_android11_bluetooth_and_wifi_reuse_foreground_location_without_duplicate_grants(self):
        results = self.run_selected(["bluetooth", "location", "nearbyWifi", "bodySensors"], api=30)
        self.assertTrue(all(item.status == "verified" for item in results), results)
        self.assertEqual(json.loads(self.state_path.read_text())["permissions"], [
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION", "android.permission.BODY_SENSORS"])

    def test_android12_wifi_uses_location_while_bluetooth_uses_nearby_permissions(self):
        results = self.run_selected(["bluetooth", "nearbyWifi"], api=31)
        self.assertTrue(all(item.status == "verified" for item in results), results)
        self.assertEqual(json.loads(self.state_path.read_text())["permissions"], [
            "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])

    def test_unconfirmed_health_grant_is_reported_as_failed(self):
        results = self.run_selected(["bodySensors"], config={"unconfirmed_permissions": ["android.permission.health.READ_HEART_RATE"]})
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].status, "failed")
        self.assertFalse(json.loads(self.state_path.read_text())["product"])

    def test_legacy_all_setup_does_not_add_new_hardware_runtime_permissions(self):
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(set(json.loads(self.state_path.read_text())["permissions"]), {
            "android.permission.READ_CALENDAR", "android.permission.READ_CONTACTS", "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS", "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.DUMP", "android.permission.PACKAGE_USAGE_STATS"})

    def test_selected_contacts_does_not_enable_other_permissions_or_products(self):
        results = self.run_selected(["contacts"])
        self.assertEqual([item.name for item in results], ["读取联系人"])
        self.assertTrue(all(item.status == "verified" for item in results))
        state = json.loads(self.state_path.read_text())
        self.assertEqual(state["permissions"], ["android.permission.READ_CONTACTS"])
        self.assertFalse(state["product"])
        self.assertEqual(state["settings"], self.initial_state["settings"])
        self.assertFalse(any("enable-all" in command for command in self.remote_calls()))

    def test_empty_selected_permissions_do_not_touch_android(self):
        self.assertEqual(self.run_selected([]), [])
        self.assertEqual(self.calls(), [])
        self.assert_no_mutation()

    def test_unknown_selected_permission_rejected_before_mutation(self):
        with self.assertRaises(ValueError):
            self.run_selected(["contacts", "arbitrary.shell.command"])
        self.assertEqual(self.calls(), [])
        self.assert_no_mutation()

    def test_selected_accessibility_preserves_other_services_without_enable_all(self):
        results = self.run_selected(["accessibility"])
        self.assertEqual([item.name for item in results], ["自动恢复写入权限", "自动恢复状态读取权限", "无障碍操作"])
        self.assertTrue(all(item.status == "verified" for item in results))
        state = json.loads(self.state_path.read_text())
        self.assertFalse(state["product"])
        self.assertEqual(state["settings"]["enabled_accessibility_services"], "other.app/other.app.Reader:" + MODULE.ACCESSIBILITY)
        self.assertNotIn("android.permission.RECORD_AUDIO", state["permissions"])


    def test_default_package_targets_only_development_app(self):
        self.assertEqual(MODULE.PACKAGE, "io.droyagent.app.dev")
        official_accessibility = "io.droyagent.app/io.droyagent.androiduse.DroyAccessibilityService"
        official_notification = "io.droyagent.app/io.droyagent.app.DroyNotificationService"
        self.initial_state["settings"]["enabled_accessibility_services"] = official_accessibility
        self.initial_state["settings"]["enabled_notification_listeners"] = official_notification
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.remote_calls()
        self.assertIn(["pm", "path", "--user", "0", MODULE.PACKAGE], commands)
        self.assertIn(["cmd", "notification", "allow_listener",
                       "io.droyagent.app.dev/io.droyagent.app.DroyNotificationService", "0"], commands)
        self.assertTrue(any("content://io.droyagent.app.dev.permissions" in command for command in commands))
        self.assertFalse(any("io.droyagent.app" in command for command in commands))
        settings = json.loads(self.state_path.read_text())["settings"]
        self.assertEqual(settings["enabled_accessibility_services"], official_accessibility + ":" + MODULE.ACCESSIBILITY)
        self.assertEqual(settings["enabled_notification_listeners"], official_notification + ":" + MODULE.NOTIFICATION)

    def test_explicit_official_package_uses_official_authorities_and_class_names(self):
        result = self.run_setup("--package", "io.droyagent.app", config={"package": "io.droyagent.app"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.remote_calls()
        self.assertIn(["pm", "path", "--user", "0", "io.droyagent.app"], commands)
        self.assertIn(["cmd", "notification", "allow_listener",
                       "io.droyagent.app/io.droyagent.app.DroyNotificationService", "0"], commands)
        self.assertTrue(any("content://io.droyagent.app.permissions" in command for command in commands))
        self.assertFalse(any("io.droyagent.app.dev" in command for command in commands))
        state = json.loads(self.state_path.read_text())
        self.assertIn("io.droyagent.app/io.droyagent.androiduse.DroyAccessibilityService",
                      state["settings"]["enabled_accessibility_services"])

    def test_unrelated_package_is_rejected_before_adb(self):
        result = self.run_setup("--package", "unrelated.app")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls(), [])
        self.assert_no_mutation()

    def test_all_permissions_verified_and_other_services_preserved(self):
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        summary = self.summary(result)
        self.assertTrue(summary["ok"])
        self.assertEqual(len(summary["results"]), 11)
        self.assertTrue(all(item["status"] == "verified" for item in summary["results"]))
        state = json.loads(self.state_path.read_text())
        self.assertEqual(state["settings"]["enabled_accessibility_services"],
                         "other.app/other.app.Reader:" + MODULE.ACCESSIBILITY)
        self.assertTrue(state["product"])
        self.assertIn("android.permission.RECORD_AUDIO", state["permissions"])
        self.assertEqual(len(summary["manualWhenUsed"]), 2)
        self.assertFalse(any(word in (" ".join(call)) for call in self.calls() for word in
                             ("uninstall", "clear-data", "su ", "MANAGE_EXTERNAL_STORAGE")))

    def test_repeated_setup_is_idempotent_and_does_not_duplicate_accessibility(self):
        for _ in range(2):
            result = self.run_setup()
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        actual = json.loads(self.state_path.read_text())["settings"]["enabled_accessibility_services"].split(":")
        self.assertEqual(actual.count(MODULE.ACCESSIBILITY), 1)
        writes = [command for command in self.remote_calls() if command[:6] ==
                  ["settings", "--user", "0", "put", "secure", "enabled_accessibility_services"]]
        self.assertEqual(len(writes), 1)

    def test_accessibility_only_preserves_other_permissions_and_product_choices(self):
        self.initial_state.update(permissions=["already.granted"], appops={"WRITE_SETTINGS": "ignore", "GET_USAGE_STATS": "ignore"})
        self.initial_state["settings"]["enabled_notification_listeners"] = "other.app/.Notifications"
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup("--accessibility-only")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        summary = self.summary(result)
        self.assertEqual([item["name"] for item in summary["results"]],
                         ["自动恢复写入权限", "自动恢复状态读取权限", "无障碍操作"])
        self.assertEqual(summary["manualWhenUsed"], [])
        state = json.loads(self.state_path.read_text())
        for key in ("appops", "product"):
            self.assertEqual(state[key], self.initial_state[key])
        self.assertEqual(state["permissions"], ["already.granted", "android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP"])
        self.assertEqual(state["settings"]["enabled_notification_listeners"], "other.app/.Notifications")
        self.assertFalse(any("enable-all" in command for command in self.remote_calls()))

    def test_preserve_mode_keeps_manual_off_and_product_choices_without_starting_app(self):
        self.initial_state.update(permissions=["already.granted"], appops={"GET_USAGE_STATS": "ignore"}, product=False, recovery_revoked=True)
        self.initial_state["settings"].update(accessibility_enabled="0")
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup("--preserve-accessibility")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        summary = self.summary(result)
        self.assertEqual(len(summary["results"]), 2)
        self.assertEqual(summary["manualWhenUsed"], [])
        state = json.loads(self.state_path.read_text())
        for key in ("settings", "appops", "product", "recovery_revoked"):
            self.assertEqual(state[key], self.initial_state[key])
        self.assertEqual(state["permissions"], ["already.granted", "android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP"])
        self.assertFalse(any(command[:1] == ["settings"] or command[:2] in (["am", "start"], ["content", "call"])
                             for command in self.remote_calls()))

    def test_full_setup_confirms_usage_install_grant_before_enabling_its_appop(self):
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.summary(result)["ok"])
        permission = "android.permission.PACKAGE_USAGE_STATS"
        commands = self.remote_calls()
        grant = commands.index(["pm", "grant", "--user", "0", MODULE.PACKAGE, permission])
        appop = commands.index(["cmd", "appops", "set", "--user", "0", MODULE.PACKAGE, "GET_USAGE_STATS", "allow"])
        self.assertLess(grant, appop)
        self.assertIn(["dumpsys", "package", MODULE.PACKAGE], commands[grant + 1:appop])
        state = json.loads(self.state_path.read_text())
        self.assertIn(permission, state["permissions"])
        self.assertEqual(state["appops"]["GET_USAGE_STATS"], "allow")

    def test_usage_appop_does_not_hide_an_unconfirmed_install_grant(self):
        self.initial_state["appops"]["GET_USAGE_STATS"] = "allow"
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup(config={"unconfirmed_permissions": ["android.permission.PACKAGE_USAGE_STATS"]})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        summary = self.summary(result)
        self.assertEqual([item["name"] for item in summary["results"] if item["status"] == "failed"], ["应用使用记录"])
        self.assertFalse(any(command[:2] == ["cmd", "appops"] and "GET_USAGE_STATS" in command
                             for command in self.remote_calls()))
        state = json.loads(self.state_path.read_text())
        self.assertNotIn("android.permission.PACKAGE_USAGE_STATS", state["permissions"])
        self.assertTrue(state["product"])

    def test_refused_usage_install_grant_does_not_enable_usage_or_expose_adb_output(self):
        grant = ["pm", "grant", "--user", "0", MODULE.PACKAGE, "android.permission.PACKAGE_USAGE_STATS"]
        result = self.run_setup(config={"fail_commands": [grant]})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        summary = self.summary(result)
        self.assertEqual([item["name"] for item in summary["results"] if item["status"] == "failed"], ["应用使用记录"])
        self.assertFalse(any(command[:2] == ["cmd", "appops"] and "GET_USAGE_STATS" in command
                             for command in self.remote_calls()))
        self.assertNotIn("GET_USAGE_STATS", json.loads(self.state_path.read_text())["appops"])

    def test_preserve_mode_keeps_enabled_service_without_claiming_a_live_connection(self):
        self.initial_state["settings"].update(enabled_accessibility_services="other.app/.Reader:" + MODULE.ACCESSIBILITY,
                                              accessibility_enabled="1")
        self.state_path.write_text(json.dumps(self.initial_state))
        result = subprocess.run([sys.executable, str(SCRIPT), "--preserve-accessibility"],
                                env=self.environment({"accessibility_never_connects": True}),
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("未检查服务连接", result.stdout)
        self.assertNotIn("实际连接", result.stdout)
        self.assertEqual(json.loads(self.state_path.read_text())["settings"], self.initial_state["settings"])

    def test_recovery_grants_must_be_confirmed_in_install_permission_block(self):
        for config in ({"unconfirmed_permissions": ["android.permission.WRITE_SECURE_SETTINGS"]},
                       {"omit_install_grants": True}):
            with self.subTest(config=config):
                self.state_path.write_text(json.dumps(self.initial_state))
                result = self.run_setup("--preserve-accessibility", config=config)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertFalse(self.summary(result)["ok"])
                self.assertEqual(json.loads(self.state_path.read_text())["settings"], self.initial_state["settings"])

    def test_preserve_and_explicit_accessibility_enable_cannot_be_combined(self):
        result = self.run_setup("--preserve-accessibility", "--accessibility-only")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.calls(), [])
        self.assert_no_mutation()

    def test_delayed_accessibility_connection_is_polled_without_rebinding(self):
        result = self.run_setup("--accessibility-only", config={"accessibility_delay_reads": 2})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        state = json.loads(self.state_path.read_text())
        self.assertEqual(state["accessibility_reads"], 3)
        self.assertNotIn("accessibility_rebound", state)

    def test_crashed_accessibility_is_rebound_once_without_removing_other_services(self):
        self.initial_state["settings"].update(enabled_accessibility_services="other.app/other.app.Reader:" + MODULE.ACCESSIBILITY,
                                               accessibility_enabled="1")
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup("--accessibility-only", config={"accessibility_crashed": True})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        writes = [command[-1] for command in self.remote_calls() if command[:6] ==
                  ["settings", "--user", "0", "put", "secure", "enabled_accessibility_services"]]
        self.assertEqual(writes, ["other.app/other.app.Reader", "other.app/other.app.Reader:" + MODULE.ACCESSIBILITY])
        self.assertTrue(json.loads(self.state_path.read_text())["accessibility_rebound"])

    def test_unconnected_accessibility_never_reports_success_and_keeps_enablement(self):
        result = self.run_setup("--accessibility-only", config={"accessibility_never_connects": True})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        summary = self.summary(result)
        self.assertFalse(summary["ok"])
        self.assertIn("服务仍未连接", summary["results"][-1]["detail"])
        state = json.loads(self.state_path.read_text())
        self.assertEqual(state["settings"]["enabled_accessibility_services"], "other.app/other.app.Reader:" + MODULE.ACCESSIBILITY)
        self.assertEqual(state["settings"]["accessibility_enabled"], "1")

    def test_missing_connection_field_is_an_error_without_rebind_or_secret_output(self):
        result = self.run_setup("--accessibility-only", config={"invalid_accessibility_status": True})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("当前版本 APK", self.summary(result)["results"][-1]["detail"])
        self.assertNotIn("accessibility_rebound", json.loads(self.state_path.read_text()))

    def test_shell_quoting_preserves_dollar_in_existing_service_class(self):
        existing = "other.app/other.app.Nested$Reader"
        self.initial_state["settings"]["enabled_accessibility_services"] = existing
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout)
        writes = [call for call in self.calls() if len(call) == 4 and
                  "put secure enabled_accessibility_services" in call[3]]
        self.assertEqual(len(writes), 1)
        self.assertIn("'" + existing + ":", writes[0][3])
        self.assertEqual(json.loads(self.state_path.read_text())["settings"]["enabled_accessibility_services"],
                         existing + ":" + MODULE.ACCESSIBILITY)

    def test_explicit_serial_overrides_environment_and_skips_auto_selection(self):
        result = self.run_setup("--serial", "chosen-device", serial="other-device")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(all(call[:2] == ["-s", "chosen-device"] for call in self.calls()))

    def test_environment_serial_is_used(self):
        result = self.run_setup(serial="environment-device")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(all(call[:2] == ["-s", "environment-device"] for call in self.calls()))

    def test_all_device_states_count_when_detecting_ambiguity(self):
        for state in ("device", "offline", "unauthorized"):
            with self.subTest(state=state):
                result = self.run_setup(config={"devices": "List of devices attached\na\tdevice\nb\t" + state})
                self.assertEqual(result.returncode, 2)
                self.assertIn("多个设备", self.summary(result)["error"])
                self.assert_no_mutation()

    def test_missing_or_unready_device_never_changes_permissions(self):
        for config in ({"devices": "List of devices attached\n"}, {"device_state": "offline"}, {"state_exit": 1}):
            with self.subTest(config=config):
                result = self.run_setup(config=config)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(self.summary(result)["ok"])
                self.assert_no_mutation()

    def test_only_installed_droy_package_can_be_changed(self):
        result = self.run_setup(config={"installed": False})
        self.assertEqual(result.returncode, 2)
        self.assertIn("尚未安装", self.summary(result)["error"])
        self.assert_no_mutation()
        self.assertFalse(any("grant" in command for command in self.remote_calls()))

    def test_bad_api_or_user_is_rejected_before_changes(self):
        for config in ({"api": "29"}, {"api": "bad"}, {"user": "unknown"}):
            with self.subTest(config=config):
                result = self.run_setup(config=config)
                self.assertEqual(result.returncode, 2)
                self.summary(result)
                self.assert_no_mutation()

    def test_current_secondary_user_is_explicit_on_every_scoped_operation(self):
        result = self.run_setup(config={"user": "10"})
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.summary(result)["androidUser"], "10")
        for command in self.remote_calls():
            if command[:2] in (["pm", "grant"], ["pm", "path"], ["content", "call"]):
                self.assertEqual(command[command.index("--user") + 1], "10")
            if command[:1] == ["settings"] or command[:2] == ["cmd", "appops"]:
                self.assertEqual(command[command.index("--user") + 1], "10")
            if command[:3] == ["cmd", "notification", "allow_listener"]:
                self.assertEqual(command[-1], "10")

    def test_notification_runtime_permission_not_required_before_api_33(self):
        result = self.run_setup(config={"api": "30"})
        self.assertEqual(result.returncode, 0, result.stdout)
        notifications = next(item for item in self.summary(result)["results"] if item["name"] == "发送通知")
        self.assertEqual(notifications["status"], "not_required")
        self.assertFalse(any("android.permission.POST_NOTIFICATIONS" in command for command in self.remote_calls()))

    def test_each_failed_readback_is_reported_while_independent_items_continue(self):
        configs = [{"unconfirmed_permissions": ["android.permission.READ_CONTACTS"]},
                   {"unconfirmed_permissions": ["android.permission.RECORD_AUDIO"]},
                   {"unconfirmed_settings": ["accessibility_enabled"]},
                   {"unconfirmed_appops": ["WRITE_SETTINGS"]}, {"unconfirmed_listener": True},
                   {"unconfirmed_product": True}]
        for config in configs:
            with self.subTest(config=config):
                self.state_path.write_text(json.dumps(self.initial_state))
                result = self.run_setup(config=config)
                self.assertEqual(result.returncode, 1, result.stdout)
                summary = self.summary(result)
                self.assertEqual(len(summary["results"]), 11)
                self.assertEqual(sum(item["status"] == "failed" for item in summary["results"]), 1)
                self.assertFalse(summary["ok"])

    def test_adb_failure_hides_raw_output_and_keeps_verifying_independent_items(self):
        result = self.run_setup(config={"fail_commands": [["cmd", "appops", "set"]]})
        self.assertEqual(result.returncode, 1)
        summary = self.summary(result)
        failed = [item["name"] for item in summary["results"] if item["status"] == "failed"]
        self.assertEqual(failed, ["修改系统设置", "应用使用记录"])
        self.assertTrue(json.loads(self.state_path.read_text())["product"])

    def test_notification_listener_delayed_readback_is_polled_without_regranting(self):
        result = self.run_setup(config={"listener_delay_reads": 2})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.summary(result)["ok"])
        commands = self.remote_calls()
        grants = [command for command in commands if command[:3] == ["cmd", "notification", "allow_listener"]]
        reads = [command for command in commands if command ==
                 ["settings", "--user", "0", "get", "secure", "enabled_notification_listeners"]]
        self.assertEqual(len(grants), 1)
        self.assertEqual(len(reads), 3)

    def test_notification_listener_never_confirmed_times_out_and_still_checks_product(self):
        import time
        started = time.monotonic()
        result = self.run_setup(config={"unconfirmed_listener": True})
        elapsed = time.monotonic() - started
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertLess(elapsed, 10)
        failed = [item["name"] for item in self.summary(result)["results"] if item["status"] == "failed"]
        self.assertEqual(failed, ["读取当前通知"])
        commands = self.remote_calls()
        self.assertEqual(sum(command[:3] == ["cmd", "notification", "allow_listener"] for command in commands), 1)
        self.assertGreater(sum(command == ["settings", "--user", "0", "get", "secure", "enabled_notification_listeners"]
                               for command in commands), 1)
        self.assertTrue(json.loads(self.state_path.read_text())["product"])

    def test_malformed_accessibility_readback_does_not_replace_existing_services(self):
        self.initial_state["settings"]["enabled_accessibility_services"] = "unexpected\nservice/data"
        self.state_path.write_text(json.dumps(self.initial_state))
        result = self.run_setup()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(self.state_path.read_text())["settings"]["enabled_accessibility_services"],
                         "unexpected\nservice/data")
        self.assertFalse(any(command[:6] == ["settings", "--user", "0", "put", "secure", "enabled_accessibility_services"]
                             for command in self.remote_calls()))

    def test_default_console_output_explains_success_and_once_only_choices_in_chinese(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], env=self.environment(),
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        for text in ("权限检查结果", "读取日历", "读取联系人", "无障碍操作", "读取当前通知",
                     "App 内全部能力开关", "之后可在 App 内按需关闭", "Root 管理器", "记住授权", "系统文件选择器"):
            self.assertIn(text, result.stdout)
        self.assertNotIn("VERIFIED", result.stdout)

    def test_default_console_output_reports_incomplete_permissions_in_chinese(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], env=self.environment({"unconfirmed_product": True}),
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 1)
        self.assertIn("【未完成】App 内全部能力开关", result.stdout)
        self.assertIn("已开启的权限会保留", result.stdout)

    def test_product_launch_is_waited_and_verified_before_provider_calls(self):
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        commands = self.remote_calls()
        launch = commands.index(["am", "start", "--user", "0", "-W", "--activity-reorder-to-front", "-n", "io.droyagent.app.dev/io.droyagent.app.MainActivity"])
        for permission in ("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP"):
            self.assertLess(commands.index(["pm", "grant", "--user", "0", MODULE.PACKAGE, permission]), launch)
        provider_calls = [i for i, command in enumerate(commands) if command[:2] == ["content", "call"]]
        self.assertEqual(len(provider_calls), 3)
        self.assertTrue(all(launch < call for call in provider_calls))

    def test_existing_browser_task_is_preserved_while_main_activity_is_brought_forward(self):
        result = self.run_setup("--accessibility-only", config={"browser_task_on_top": True})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.summary(result)["ok"])
        launch = next(command for command in self.remote_calls() if command[:2] == ["am", "start"])
        self.assertIn("--activity-reorder-to-front", launch)
        self.assertNotIn("--activity-clear-top", launch)
        self.assertNotIn("-S", launch)

    def test_launch_failure_never_calls_provider_or_claims_product_enabled(self):
        configurations = [
            {"launch_exit": 1},
            {"launch_output": "Error: Activity not started"},
            {"launch_output": "Status: timeout\nActivity: io.droyagent.app.dev/io.droyagent.app.MainActivity"},
            {"launch_output": "Status: ok\nActivity: other.app/.MainActivity"},
            {"launch_output": "Status: ok\nComplete"},
        ]
        for config in configurations:
            with self.subTest(config=config):
                self.log.unlink(missing_ok=True)
                self.state_path.write_text(json.dumps(self.initial_state))
                result = self.run_setup(config=config)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                failed = [item["name"] for item in self.summary(result)["results"] if item["status"] == "failed"]
                self.assertEqual(failed, ["无障碍操作", "App 内全部能力开关"])
                self.assertFalse(any(command[:2] == ["content", "call"] for command in self.remote_calls()))
                self.assertFalse(json.loads(self.state_path.read_text())["product"])

    def test_existing_activity_full_component_is_accepted(self):
        result = self.run_setup(config={"launch_output": "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\nActivity: io.droyagent.app.dev/io.droyagent.app.MainActivity\nComplete"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.summary(result)["ok"])

    def test_installer_grants_only_after_successful_install_and_before_launch(self):
        apk = self.root / "test app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.droyagent.app.dev" android:versionCode="5"/>')
        result = subprocess.run(["/bin/sh", str(TOOLS / "install.sh"), "--apk", str(apk),
                                 "--serial", "phone-1", "--grant-permissions", "--launch"],
                                env=self.environment(), capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        calls = self.calls()
        installation = next(i for i, call in enumerate(calls) if call[2:4] == ["install", "-r"])
        grant = next(i for i, call in enumerate(calls) if call[2:3] == ["shell"] and len(call) == 4 and "pm grant" in call[3])
        launch = next(i for i, call in enumerate(calls) if call[2:5] == ["shell", "am", "start"])
        self.assertLess(installation, grant)
        self.assertLess(grant, launch)

    def test_default_installer_grants_recovery_without_changing_accessibility_after_install(self):
        apk = self.root / "test app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.droyagent.app.dev" android:versionCode="5"/>')
        result = subprocess.run(["/bin/sh", str(TOOLS / "install.sh"), "--apk", str(apk), "--serial", "phone-1"],
                                env=self.environment(), capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("未检查服务连接", result.stdout)
        commands = self.remote_calls()
        self.assertFalse(any(command[:1] == ["settings"] or command[:2] in (["am", "start"], ["content", "call"])
                             for command in commands))
        installation = next(i for i, call in enumerate(self.calls()) if call[2:4] == ["install", "-r"])
        grants = [i for i, call in enumerate(self.calls()) if call[2:3] == ["shell"] and len(call) == 4 and "pm grant" in call[3]]
        self.assertEqual(len(grants), 2)
        self.assertTrue(all(installation < grant for grant in grants))
        state = json.loads(self.state_path.read_text())
        self.assertEqual(state["settings"], self.initial_state["settings"])
        self.assertFalse(state["product"])

    def test_installer_reports_partial_permission_failure_without_launch_or_data_loss(self):
        apk = self.root / "test app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.droyagent.app.dev" android:versionCode="5"/>')
        result = subprocess.run(["/bin/sh", str(TOOLS / "install.sh"), "--apk", str(apk), "--grant-permissions", "--launch"],
                                env=self.environment({"unconfirmed_product": True}),
                                capture_output=True, text=True, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("APK installed", result.stderr)
        self.assertIn("Verified changes are kept", result.stderr)
        self.assertFalse(any(call[2:5] == ["shell", "am", "start"] for call in self.calls()))
        self.assertFalse(any("uninstall" in call or "clear" in call for call in self.calls()))


class AdbDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="droy sdk discovery ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.workspace = self.root / "workspace"
        self.home = self.root / "home"
        self.env = {"HOME": str(self.home), "PATH": ""}
        self.patcher = patch.object(MODULE, "WORKSPACE_DIR", self.workspace)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def executable(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("mock")
        path.chmod(0o755)
        return str(path)

    def sdk_adb(self, sdk, filename="adb"):
        return self.executable(sdk / "platform-tools" / filename)

    def test_explicit_path_wins_over_environment_sdk_workspace_and_path(self):
        explicit = self.executable(self.root / "explicit adb")
        self.env["DROY_ADB"] = self.executable(self.root / "environment adb")
        self.env["ANDROID_HOME"] = str(self.root / "sdk")
        self.sdk_adb(Path(self.env["ANDROID_HOME"]))
        self.sdk_adb(self.workspace / "artifacts" / "toolchains" / "android-sdk")
        self.assertEqual(MODULE.find_adb(explicit, self.env, "darwin"), explicit)
        self.assertEqual(MODULE.find_adb(None, self.env, "darwin"), self.env["DROY_ADB"])

    def test_invalid_explicit_adb_never_falls_back(self):
        self.sdk_adb(self.workspace / "artifacts" / "toolchains" / "android-sdk")
        for explicit, env in ((str(self.root / "missing adb"), self.env),
                              (None, {**self.env, "DROY_ADB": str(self.root / "missing adb")})):
            with self.subTest(explicit=explicit):
                with self.assertRaisesRegex(MODULE.SetupError, "不会自动换用"):
                    MODULE.find_adb(explicit, env, "darwin")

    def test_sdk_environment_precedes_workspace_and_home(self):
        self.env["ANDROID_HOME"] = str(self.root / "preferred sdk")
        self.env["ANDROID_SDK_ROOT"] = str(self.root / "secondary sdk")
        preferred = self.sdk_adb(Path(self.env["ANDROID_HOME"]))
        self.sdk_adb(Path(self.env["ANDROID_SDK_ROOT"]))
        self.sdk_adb(self.workspace / "artifacts" / "toolchains" / "android-sdk")
        self.sdk_adb(self.home / "Library" / "Android" / "sdk")
        self.assertEqual(MODULE.find_adb(None, self.env, "darwin"), preferred)

    def test_invalid_sdk_environment_can_fall_back_to_other_sdk(self):
        self.env["ANDROID_HOME"] = str(self.root / "missing sdk")
        self.env["ANDROID_SDK_ROOT"] = str(self.root / "secondary sdk")
        expected = self.sdk_adb(Path(self.env["ANDROID_SDK_ROOT"]))
        self.assertEqual(MODULE.find_adb(None, self.env, "darwin"), expected)

    def test_workspace_sdk_precedes_standard_sdk_and_path(self):
        expected = self.sdk_adb(self.workspace / "artifacts" / "toolchains" / "android-sdk")
        self.sdk_adb(self.home / "Library" / "Android" / "sdk")
        self.env["PATH"] = str(self.root / "bin")
        self.executable(self.root / "bin" / "adb")
        self.assertEqual(MODULE.find_adb(None, self.env, "darwin"), expected)

    def test_mac_standard_sdk_without_terminal_environment(self):
        expected = self.sdk_adb(self.home / "Library" / "Android" / "sdk")
        self.assertEqual(MODULE.find_adb(None, self.env, "darwin"), expected)

    def test_linux_standard_sdk_without_terminal_environment(self):
        expected = self.sdk_adb(self.home / "Android" / "Sdk")
        self.assertEqual(MODULE.find_adb(None, self.env, "linux"), expected)

    def test_windows_localappdata_standard_sdk(self):
        self.env["LOCALAPPDATA"] = str(self.root / "local data")
        expected = self.sdk_adb(Path(self.env["LOCALAPPDATA"]) / "Android" / "Sdk", "adb.exe")
        self.assertEqual(MODULE.find_adb(None, self.env, "win32"), expected)

    def test_windows_userprofile_fallback_without_localappdata(self):
        self.env["USERPROFILE"] = str(self.root / "windows home")
        expected = self.sdk_adb(Path(self.env["USERPROFILE"]) / "AppData" / "Local" / "Android" / "Sdk", "adb.exe")
        self.assertEqual(MODULE.find_adb(None, self.env, "win32"), expected)

    def test_path_fallback_when_no_sdk_found(self):
        self.env["PATH"] = str(self.root / "bin")
        expected = self.executable(self.root / "bin" / "adb")
        self.assertEqual(MODULE.find_adb(None, self.env, "linux"), expected)

    def test_missing_adb_gives_official_download_link(self):
        with self.assertRaisesRegex(MODULE.SetupError, "未找到 ADB") as caught:
            MODULE.find_adb(None, self.env, "darwin")
        self.assertIn("https://developer.android.com/tools/releases/platform-tools", str(caught.exception))

    def test_mac_wrapper_is_executable_and_checks_python_before_launching(self):
        wrapper = TOOLS / "enable_permissions.command"
        self.assertTrue(os.access(wrapper, os.X_OK))
        self.assertIn("sys.version_info >= (3, 8)", wrapper.read_text())
        self.assertIn("https://www.python.org/downloads/", wrapper.read_text())

    def test_windows_wrapper_checks_all_python_entry_paths(self):
        wrapper = (TOOLS / "enable_permissions.cmd").read_text()
        self.assertEqual(wrapper.count("sys.version_info >= (3, 8)"), 3)
        self.assertIn("需要 Python 3.8", wrapper)
        self.assertIn("https://www.python.org/downloads/", wrapper)
        self.assertIn("exit /b %droy_result%", wrapper)


class ReadbackParserTests(unittest.TestCase):
    def test_connection_deadline_timeout_allows_recovery_but_early_timeout_is_not_hidden(self):
        from unittest.mock import Mock
        adb = Mock()
        adb.shell.side_effect = MODULE.AdbTimeout("bounded read timeout")
        setup = MODULE.PermissionSetup(adb, "0", 36)
        with patch.object(MODULE.time, "monotonic", side_effect=[0.0, 0.0, MODULE.ACCESSIBILITY_READY_SECONDS]):
            self.assertFalse(setup.wait_for_accessibility())
        with patch.object(MODULE.time, "monotonic", side_effect=[0.0, 0.0, 0.1]):
            with self.assertRaises(MODULE.AdbTimeout):
                setup.wait_for_accessibility()

    def test_connection_readback_requires_explicit_live_boolean_and_supported_schema(self):
        self.assertTrue(MODULE.accessibility_is_connected("Result: Bundle[{schemaVersion=1, accessibilityConnected=true, enabled=false}]"))
        self.assertFalse(MODULE.accessibility_is_connected("Result: Bundle[{schemaVersion=1, accessibilityConnected=false, enabled=true}]"))
        for output in ("enabled=true schemaVersion=1", "accessibilityConnected=true schemaVersion=2",
                       "accessibilityConnected=1 schemaVersion=1", "Error: provider unavailable"):
            with self.subTest(output=output):
                with self.assertRaises(ValueError):
                    MODULE.accessibility_is_connected(output)

    def test_rebind_readback_failure_still_restores_droy_without_changing_other_services(self):
        class ReadbackFailureAdb:
            def __init__(self):
                self.settings = {"enabled_accessibility_services": "other.app/.Reader:" + MODULE.ACCESSIBILITY,
                                 "accessibility_enabled": "1"}
                self.fail_next_read = False

            def shell(self, *command):
                operation, key = command[3], command[5]
                if operation == "get":
                    if self.fail_next_read:
                        self.fail_next_read = False
                        return "malformed\nservice/list"
                    return self.settings[key]
                self.settings[key] = command[6]
                if key == "enabled_accessibility_services" and MODULE.ACCESSIBILITY not in command[6]:
                    self.fail_next_read = True
                return ""

        adb = ReadbackFailureAdb()
        setup = MODULE.PermissionSetup(adb, "0", 36)
        with patch.object(setup, "open_app"), patch.object(setup, "wait_for_accessibility", return_value=False):
            with self.assertRaisesRegex(ValueError, "系统服务列表"):
                setup.accessibility()
        self.assertEqual(adb.settings["enabled_accessibility_services"], "other.app/.Reader:" + MODULE.ACCESSIBILITY)

    def test_permission_from_another_profile_is_never_accepted(self):
        dump = """  User 0: installed=true
    runtime permissions:
      android.permission.READ_CONTACTS: granted=false, flags=[]
  User 10: installed=true
    runtime permissions:
      android.permission.READ_CONTACTS: granted=true, flags=[]
"""
        self.assertFalse(MODULE.permission_is_granted(dump, "0", "android.permission.READ_CONTACTS"))
        self.assertTrue(MODULE.permission_is_granted(dump, "10", "android.permission.READ_CONTACTS"))

    def test_development_permissions_accept_only_granted_install_entries_of_droy(self):
        permission = "android.permission.WRITE_SECURE_SETTINGS"
        dump = """Packages:
  Package [io.droyagent.app.dev] (mock):
    requested permissions:
      android.permission.WRITE_SECURE_SETTINGS
    install permissions:
      android.permission.WRITE_SECURE_SETTINGS: granted=true, flags=[]
      android.permission.DUMP: granted=false
    User 0: installed=true
      runtime permissions:
        android.permission.DUMP: granted=true, flags=[]
"""
        self.assertTrue(MODULE.development_permission_is_granted(dump, permission))
        self.assertFalse(MODULE.development_permission_is_granted(dump, "android.permission.DUMP"))
        self.assertFalse(MODULE.development_permission_is_granted(dump.replace("[io.droyagent.app.dev]", "[other.app]"), permission))
        self.assertFalse(MODULE.development_permission_is_granted(dump.replace("    install permissions:\n", ""), permission))
        self.assertFalse(MODULE.development_permission_is_granted(dump.replace("granted=true, flags=[]", "granted=trueish"), permission))

    def test_development_grant_is_not_inferred_from_a_later_package(self):
        dump = """Packages:
  Package [io.droyagent.app.dev] (mock):
    requested permissions:
      android.permission.DUMP
    User 0: installed=true
      runtime permissions:
        android.permission.DUMP: granted=true
  Package [other.app] (mock):
    install permissions:
      android.permission.DUMP: granted=true
"""
        self.assertFalse(MODULE.development_permission_is_granted(dump, "android.permission.DUMP"))

    def test_conflicting_uid_and_package_appops_fail_closed(self):
        self.assertTrue(MODULE.appop_is_allowed("Uid mode: WRITE_SETTINGS: allow\nWRITE_SETTINGS: allow", "WRITE_SETTINGS"))
        self.assertFalse(MODULE.appop_is_allowed("Uid mode: WRITE_SETTINGS: ignore\nWRITE_SETTINGS: allow", "WRITE_SETTINGS"))
        self.assertFalse(MODULE.appop_is_allowed("No operations.", "WRITE_SETTINGS"))

    def test_product_readback_requires_current_schema_all_enabled_and_not_stopped(self):
        self.assertTrue(MODULE.product_is_enabled("Result: Bundle[{stopped=false, enabled=true, schemaVersion=1}]"))
        for state in ("Bundle[{enabled=true}]", "enabled=true stopped=false schemaVersion=2",
                      "enabled=true stopped=true schemaVersion=1", "Error: unknown provider"):
            self.assertFalse(MODULE.product_is_enabled(state))

    def test_component_aliases_compare_equal(self):
        self.assertEqual(MODULE.component_key("io.droyagent.app/.DroyNotificationService"),
                         MODULE.component_key("io.droyagent.app/io.droyagent.app.DroyNotificationService"))


if __name__ == "__main__":
    unittest.main()
