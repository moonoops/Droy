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
        self.assertIn(".MainActivity", activities)
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
