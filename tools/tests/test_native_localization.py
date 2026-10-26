"""Keep the two native languages' substitution contracts aligned."""
import json
from pathlib import Path
import re
import unittest


class NativeLocalizationContractTest(unittest.TestCase):
    def test_native_copy_has_english_and_matching_placeholders(self):
        root = Path(__file__).resolve().parents[2]
        pairs = re.compile(r'NativeLocale\.text\(("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*")\)')
        count = 0
        for path in (root / "app/src/main/java").rglob("*.java"):
            for match in pairs.finditer(path.read_text(encoding="utf-8")):
                chinese, english = map(json.loads, match.groups())
                with self.subTest(path=str(path.relative_to(root)), text=chinese):
                    self.assertTrue(english.strip())
                    placeholders = r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]|\{\w+\}"
                    self.assertEqual(sorted(re.findall(placeholders, chinese)),
                                     sorted(re.findall(placeholders, english)))
                count += 1
        self.assertGreater(count, 0, "The native bilingual-copy scan found no text pairs")
