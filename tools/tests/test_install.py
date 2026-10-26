"""Exercise the installer without connecting to ADB or changing a device."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


INSTALLER = Path(__file__).resolve().parents[1] / "install.sh"
PERMISSION_RUNNER = INSTALLER.with_name("enable_permissions.py")
MOCK_ADB = r'''
import json, os, sys
args = sys.argv[1:]
with open(os.environ["MOCK_ADB_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
config = json.loads(os.environ["MOCK_ADB_CONFIG"])
if args == ["devices"]:
    print(config.get("devices", "List of devices attached\nphone-1\tdevice\n"))
    sys.exit(config.get("devices_exit", 0))
if args[:1] != ["-s"]:
    sys.exit(90)
command = args[2:]
if command == ["get-state"]:
    print(config.get("state", "device"))
    sys.exit(config.get("state_exit", 0))
if command == ["shell", "getprop", "ro.build.version.sdk"]:
    print(config.get("api", "30"))
    sys.exit(0)
if command == ["shell", "getprop", "ro.product.cpu.abilist"]:
    print(config.get("abis", "arm64-v8a,armeabi-v7a"))
    sys.exit(0)
if command[:2] == ["install", "-r"]:
    print(config.get("install_output", "Success"))
    sys.exit(config.get("install_exit", 0))
if command[:3] == ["shell", "am", "start"]:
    print(config.get("launch_output", "Starting: Intent\nStatus: ok\nComplete"))
    sys.exit(config.get("launch_exit", 0))
sys.exit(91)
'''
MOCK_PYTHON = r'''
import json, os, sys
args = sys.argv[1:]
with open(os.environ["MOCK_PYTHON_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
config = json.loads(os.environ["MOCK_ADB_CONFIG"])
if args[:1] == ["-c"]:
    if "from setup import apk_info" in args[1]:
        sys.exit(config.get("apk_validation_exit", 0))
    sys.exit(config.get("python_version_exit", 0))
if args and args[0] == os.environ["MOCK_PERMISSION_RUNNER"]:
    print(config.get("permission_output", "Accessibility connection verified"))
    sys.exit(config.get("permission_exit", 0))
sys.exit(92)
'''


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="droy installer ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "test app.apk"
        self.apk.write_bytes(b"mock APK")
        self.adb = self.root / "mock adb"
        self.adb.write_text("#!" + sys.executable + "\n" + MOCK_ADB)
        self.adb.chmod(0o755)
        self.log = self.root / "calls.jsonl"
        self.python = self.root / "mock python"
        self.python.write_text("#!" + sys.executable + "\n" + MOCK_PYTHON)
        self.python.chmod(0o755)
        self.python_log = self.root / "python calls.jsonl"

    def run_installer(self, *arguments, config=None, serial=None, python=None):
        environment = dict(os.environ)
        environment.pop("ANDROID_SERIAL", None)
        environment.update(DROY_ADB=str(self.adb), MOCK_ADB_LOG=str(self.log),
                           MOCK_ADB_CONFIG=json.dumps(config or {}),
                           DROY_PYTHON=str(self.python if python is None else python),
                           MOCK_PYTHON_LOG=str(self.python_log),
                           MOCK_PERMISSION_RUNNER=str(PERMISSION_RUNNER))
        if serial is not None:
            environment["ANDROID_SERIAL"] = serial
        return subprocess.run(["/bin/sh", str(INSTALLER), "--apk", str(self.apk), *arguments],
                              env=environment, capture_output=True, text=True, timeout=10)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def python_calls(self):
        return [json.loads(line) for line in self.python_log.read_text().splitlines()] if self.python_log.exists() else []

    def permission_calls(self):
        return [call for call in self.python_calls() if call[:1] == [str(PERMISSION_RUNNER)]]

    def assert_not_installed(self):
        self.assertFalse(any("install" in call for call in self.calls()))

    def test_single_device_install_preserves_data_and_handles_spaces(self):
        result = self.run_installer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["-s", "phone-1", "install", "-r", str(self.apk)], self.calls())
        self.assertFalse(any("uninstall" in call or "clear" in call for call in self.calls()))
        self.assertEqual(self.python_calls()[0], ["-c", "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)"])
        self.assertEqual(self.permission_calls(), [[str(PERMISSION_RUNNER), "--adb", str(self.adb),
                                                  "--serial", "phone-1", "--package", "io.droyagent.app.dev", "--preserve-accessibility"]])

    def test_grant_permissions_uses_full_permission_mode(self):
        result = self.run_installer("--grant-permissions", "--serial", "chosen-device")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.permission_calls(), [[str(PERMISSION_RUNNER), "--adb", str(self.adb),
                                                  "--serial", "chosen-device", "--package", "io.droyagent.app.dev"]])

    def test_permission_failure_keeps_install_and_reports_matching_retry_mode(self):
        for arguments in ([], ["--grant-permissions"]):
            with self.subTest(arguments=arguments):
                result = self.run_installer(*arguments, config={"permission_exit": 1})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("APK installed", result.stderr)
                self.assertIn("Verified changes are kept", result.stderr)
                retry = result.stderr.split("rerun ", 1)[1]
                self.assertIn("tools/enable_permissions.py", retry)
                self.assertEqual("--preserve-accessibility" in retry, not arguments)
                self.assertIn("--serial DEVICE", retry)
                self.assertNotIn("Installed. Configure", result.stdout)
                self.assertFalse(any("uninstall" in call or "clear" in call for call in self.calls()))

    def test_missing_python_fails_before_installation_in_both_modes(self):
        for arguments in ([], ["--grant-permissions"]):
            with self.subTest(arguments=arguments):
                result = self.run_installer(*arguments, python=self.root / "missing python")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Python 3.8 or newer is required", result.stderr)
                self.assertIn("DROY_PYTHON", result.stderr)
                self.assert_not_installed()
                self.assertEqual(self.permission_calls(), [])

    def test_unsupported_python_fails_before_installation_in_both_modes(self):
        for arguments in ([], ["--grant-permissions"]):
            with self.subTest(arguments=arguments):
                result = self.run_installer(*arguments, config={"python_version_exit": 1})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Python 3.8 or newer is required", result.stderr)
                self.assert_not_installed()
                self.assertEqual(self.permission_calls(), [])

    def test_shell_metacharacters_in_apk_and_serial_remain_literal(self):
        self.apk = self.root / "test ' $(unexpected).apk"
        self.apk.write_bytes(b"mock APK")
        serial = "phone ' $(unexpected)"
        result = self.run_installer("--serial", serial)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["-s", serial, "install", "-r", str(self.apk)], self.calls())
        self.assertEqual(self.permission_calls(), [[str(PERMISSION_RUNNER), "--adb", str(self.adb),
                                                  "--serial", serial, "--package", "io.droyagent.app.dev", "--preserve-accessibility"]])

    def test_explicit_serial_overrides_environment_and_uses_waited_launch(self):
        result = self.run_installer("--serial", "chosen-device", "--launch", serial="other-device")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(call[:2] == ["-s", "chosen-device"] for call in self.calls()))
        self.assertIn(["-s", "chosen-device", "shell", "am", "start", "-W", "-n",
                       "io.droyagent.app.dev/io.droyagent.app.MainActivity"], self.calls())

    def test_environment_serial_is_used(self):
        result = self.run_installer(serial="environment-device")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(call[:2] == ["-s", "environment-device"] for call in self.calls()))

    def test_no_device_does_not_install(self):
        result = self.run_installer(config={"devices": "List of devices attached\n"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No ADB device", result.stderr)
        self.assert_not_installed()

    def test_multiple_devices_include_unavailable_devices(self):
        for state in ("device", "offline", "unauthorized"):
            with self.subTest(state=state):
                result = self.run_installer(config={"devices": f"List of devices attached\nfirst\tdevice\nsecond\t{state}\n"})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Multiple ADB devices", result.stderr)
                self.assert_not_installed()

    def test_unready_device_does_not_install(self):
        result = self.run_installer(config={"state": "offline"})
        self.assertNotEqual(result.returncode, 0)
        self.assert_not_installed()

    def test_unsupported_android_and_abi_do_not_install(self):
        for config in ({"api": "29"}, {"api": "not-a-number"}, {"abis": "x86_64,x86"}):
            with self.subTest(config=config):
                result = self.run_installer(config=config)
                self.assertNotEqual(result.returncode, 0)
                self.assert_not_installed()

    def test_install_failure_never_launches_or_removes_data(self):
        result = self.run_installer("--launch", config={"install_exit": 1, "install_output": "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No data was cleared", result.stderr)
        self.assertFalse(any("am" in call or "uninstall" in call or "clear" in call for call in self.calls()))
        self.assertEqual(self.permission_calls(), [])

    def test_launch_requires_success_status_even_when_adb_exits_zero(self):
        for output in ("Error: Activity not started, unable to resolve Intent", "Status: timeout\nComplete", "Starting: Intent"):
            with self.subTest(output=output):
                result = self.run_installer("--launch", config={"launch_output": output})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("APK installed, but Android did not confirm launch", result.stderr)
                self.assertNotIn("Installed. Configure", result.stdout)

    def test_launch_command_failure_is_reported(self):
        result = self.run_installer("--launch", config={"launch_exit": 1, "launch_output": "device offline"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("APK installed, but the launch command failed", result.stderr)

    def test_already_running_warning_and_crlf_status_are_successful(self):
        result = self.run_installer("--launch", config={"launch_output": "Warning: Activity not started, its current task has been brought to the front\r\nStatus: ok\r\nComplete\r"})
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_official_package_requires_explicit_selection_and_forwards_target(self):
        result = self.run_installer("--package", "io.droyagent.app", "--launch")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["-s", "phone-1", "shell", "am", "start", "-W", "-n",
                       "io.droyagent.app/io.droyagent.app.MainActivity"], self.calls())
        self.assertEqual(self.permission_calls(), [[str(PERMISSION_RUNNER), "--adb", str(self.adb),
            "--serial", "phone-1", "--package", "io.droyagent.app", "--preserve-accessibility"]])

    def test_invalid_package_is_rejected_before_adb_or_install(self):
        result = self.run_installer("--package", "unrelated.app")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsupported Droy package", result.stderr)
        self.assertEqual(self.calls(), [])

    def test_apk_validation_failure_stops_before_installation(self):
        result = self.run_installer(config={"apk_validation_exit": 1})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Nothing was installed", result.stderr)
        self.assert_not_installed()
        self.assertEqual(self.permission_calls(), [])

    def test_real_manifest_validation_rejects_official_apk_for_default_dev_target(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.droyagent.app" android:versionCode="5"/>')
        result = self.run_installer(python=Path(sys.executable))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("APK 包名与所选组件不一致", result.stderr)
        self.assertIn("Nothing was installed", result.stderr)
        self.assert_not_installed()

    def test_option_cannot_be_consumed_as_another_options_value(self):
        for option in ("--serial", "--apk", "--package"):
            for suffix in ([], ["--launch"]):
                with self.subTest(option=option, suffix=suffix):
                    result = self.run_installer(option, *suffix)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"{option} requires a value", result.stderr)
                    self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
