import hashlib
import json
from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "app" / "launcher-icons"
RES = ROOT / "app" / "src" / "main" / "res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def png_size(path):
    header = path.read_bytes()[:24]
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise AssertionError(f"Expected a PNG: {path}")
    return struct.unpack(">II", header[16:24])


class LauncherArtworkTest(unittest.TestCase):
    def test_sources_match_catalog_and_have_distinct_export_names(self):
        catalog = json.loads((SOURCES / "catalog.json").read_text())
        self.assertEqual(108, catalog["adaptiveViewportDp"])
        self.assertEqual(9, len(catalog["icons"]))
        self.assertEqual(9, len({entry["resource"] for entry in catalog["icons"]}))
        for entry in catalog["icons"]:
            with self.subTest(source=entry["source"]):
                source = SOURCES / entry["source"]
                self.assertEqual(entry["sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
                width, height = png_size(source)
                self.assertEqual(width, height)
                self.assertGreaterEqual(width, 512)

    def test_all_nine_icons_have_legacy_and_adaptive_resources(self):
        icons = json.loads((SOURCES / "catalog.json").read_text())["icons"]
        self.assertEqual((432, 432), png_size(RES / "drawable-nodpi" / "droy_launcher_paper.png"))
        for entry in icons:
            name = entry["resource"].split("/")[1]
            with self.subTest(icon=name):
                for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
                    self.assertEqual((size, size), png_size(RES / f"mipmap-{density}" / f"{name}.png"))
                adaptive = ET.parse(RES / "mipmap-anydpi-v26" / f"{name}.xml").getroot()
                self.assertEqual("adaptive-icon", adaptive.tag)
                self.assertEqual("@drawable/droy_launcher_paper", adaptive.find("background").get(ANDROID + "drawable"))
                self.assertEqual(f"@drawable/{name}_foreground", adaptive.find("foreground").get(ANDROID + "drawable"))
                self.assertEqual((432, 432), png_size(RES / "drawable-nodpi" / f"{name}_foreground.png"))
                self.assertEqual(60 if "sleep_" in name else 72, entry["contentDp"])


if __name__ == "__main__":
    unittest.main()
