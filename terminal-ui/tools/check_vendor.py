#!/usr/bin/env python3
"""Check the pinned terminal import and the exclusion of local process capabilities."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
assets = root / "src/main/assets/third-party/termux-terminal"
manifest = json.loads((assets / "source-manifest.json").read_text())
assert manifest["repository"] == "https://github.com/termux/termux-app"
assert manifest["commit"] == "5b657c6adf4304e5198951ce815fe0205dcac29c"
assert manifest["tag"] == "v0.118.3"
for entry in manifest["files"]:
    source = (root / entry["localPath"]).resolve()
    assert source.is_relative_to(root) and ".." not in Path(entry["localPath"]).parts
    assert hashlib.sha256(source.read_bytes()).hexdigest() == entry["localSha256"], source
assert hashlib.sha256((assets / "UPSTREAM-LICENSE.md").read_bytes()).hexdigest() == manifest["licenseSourceSha256"]
assert "Apache License" in (assets / "Apache-2.0.txt").read_text()
assert "terminal-view" in (assets / "UPSTREAM-LICENSE.md").read_text()

forbidden_files = {"TerminalSession.java", "JNI.java", "ByteQueue.java", "CMakeLists.txt", "Android.mk"}
for source in (root / "src/main").rglob("*"):
    assert source.name not in forbidden_files, source
    assert source.suffix not in {".so", ".c", ".cpp"}, source
    if source.suffix == ".java":
        content = source.read_text()
        assert not re.search(r"ProcessBuilder|Runtime\.getRuntime\(\)\.exec|System\.loadLibrary|JNI\.|\bnative\s+\w+\s*\(", content), source
assert "uses-permission" not in (root / "src/main/AndroidManifest.xml").read_text()

resources = []
for folder in ("values", "values-zh"):
    resources.append({item.attrib["name"]: item.text for item in ET.parse(root / f"src/main/res/{folder}/strings.xml").getroot()})
assert resources[0].keys() == resources[1].keys()
assert all(resources[0].values()) and all(resources[1].values())
print(f"Verified {len(manifest['files'])} pinned source files, local-process exclusions and {len(resources[0])} paired strings.")
