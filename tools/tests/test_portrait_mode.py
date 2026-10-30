"""Keep every Droy entry point under the same portrait-only policy."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class PortraitModeContractTest(unittest.TestCase):
    def test_all_product_activities_and_aliases_are_portrait(self):
        application = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        activities = {activity.get(ANDROID + "name"): activity
                      for activity in application.findall("activity")}
        self.assertIn(".NativeAppActivity", activities)
        self.assertIn(".media.ShareReceiveActivity", activities)
        for name, activity in activities.items():
            with self.subTest(activity=name):
                self.assertEqual("portrait", activity.get(ANDROID + "screenOrientation"))
        for alias in application.findall("activity-alias"):
            with self.subTest(alias=alias.get(ANDROID + "name")):
                self.assertIn(alias.get(ANDROID + "targetActivity"), activities)

    def test_android_16_compatibility_preserves_the_orientation_policy(self):
        application = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        properties = {item.get(ANDROID + "name"): item.get(ANDROID + "value")
                      for item in application.findall("property")}
        self.assertEqual("true", properties.get("android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"))
        self.assertEqual("false", properties.get("android.window.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE"))

    def test_native_launcher_and_legacy_component_share_the_native_target(self):
        application = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        launchers = []
        for entry in application:
            for filter_ in entry.findall("intent-filter"):
                categories = {item.get(ANDROID + "name") for item in filter_.findall("category")}
                if "android.intent.category.LAUNCHER" in categories:
                    launchers.append(entry.get(ANDROID + "name"))
        self.assertEqual(9, len(launchers))
        self.assertEqual(9, len(set(launchers)))
        self.assertTrue(all(name.startswith(".launcher.") for name in launchers))
        native = next(item for item in application.findall("activity") if item.get(ANDROID + "name") == ".NativeAppActivity")
        self.assertEqual("true", native.get(ANDROID + "exported"))
        self.assertEqual("singleTask", native.get(ANDROID + "launchMode"))
        self.assertFalse(native.findall("intent-filter"))
        gateway = next(item for item in application.findall("activity") if item.get(ANDROID + "name") == ".LauncherEntryActivity")
        self.assertEqual("", gateway.get(ANDROID + "taskAffinity"))
        self.assertEqual("true", gateway.get(ANDROID + "noHistory"))
        self.assertEqual("true", gateway.get(ANDROID + "excludeFromRecents"))
        enabled = []
        for entry in application.findall("activity-alias"):
            if entry.get(ANDROID + "name") in launchers:
                self.assertEqual(".LauncherEntryActivity", entry.get(ANDROID + "targetActivity"))
                self.assertEqual("true", entry.get(ANDROID + "exported"))
                if entry.get(ANDROID + "enabled") == "true":
                    enabled.append(entry.get(ANDROID + "name"))
        self.assertEqual([".launcher.Closeup"], enabled)
        alias = next(item for item in application.findall("activity-alias") if item.get(ANDROID + "name") == ".MainActivity")
        self.assertEqual(".NativeAppActivity", alias.get(ANDROID + "targetActivity"))
        self.assertFalse(alias.findall("intent-filter"))
