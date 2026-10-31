"""Protect geometry and transparency when exporting the vendored UI icon family."""
import importlib.util
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('generate_icons', ROOT / 'tools/generate_icons.py')
icons = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(icons)
ANDROID = '{http://schemas.android.com/apk/res/android}'


class IconExportTest(unittest.TestCase):
    def test_all_pinned_paths_survive_export_without_an_opaque_fill(self):
        catalog = json.loads((ROOT / 'tools/icons/catalog.json').read_text())
        self.assertTrue(catalog['resources'])
        for resource, name in catalog['resources'].items():
            with self.subTest(resource=resource, source=name):
                source = (ROOT / f'tools/icons/tabler/outline/{name}.svg').read_text()
                expected = ET.fromstring(source)
                result = ET.fromstring(icons.vector_from_svg(source, name))
                self.assertEqual([p.get('d') for p in expected],
                                 [p.get(ANDROID + 'pathData') for p in result])
                self.assertTrue(all(p.get(ANDROID + 'fillColor') == '#00000000' for p in result))
                self.assertEqual((ROOT / f'app/src/main/res/drawable/{resource}.xml').read_text(),
                                 icons.vector_from_svg(source, name))

    def test_new_svg_features_cannot_silently_lose_geometry(self):
        sample = (ROOT / 'tools/icons/tabler/outline/message-dots.svg').read_text()
        for unsupported in ('<circle cx="12" cy="12" r="2"/>', '<path d="M0 0" opacity=".5"/>'):
            with self.subTest(unsupported=unsupported), self.assertRaises(ValueError):
                icons.vector_from_svg(sample.replace('</svg>', unsupported + '</svg>'), 'fixture')
        with self.assertRaises(ValueError):
            icons.vector_from_svg(sample.replace('stroke-width="2"', 'stroke-width="1"'), 'fixture')
