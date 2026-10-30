#!/usr/bin/env python3
"""Regenerate Android round-line icons from the vendored, pinned SVG sources."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'tools/icons/catalog.json'
SVG_NAMESPACE = '{http://www.w3.org/2000/svg}'


def vector_from_svg(source: str, icon_name: str) -> str:
    svg = ET.fromstring(source)
    expected = {'viewBox': '0 0 24 24', 'fill': 'none', 'stroke': 'currentColor',
                'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round'}
    if svg.tag != SVG_NAMESPACE + 'svg' or any(svg.get(k) != v for k, v in expected.items()):
        raise ValueError(f'{icon_name}: source does not match the round-line icon contract')
    paths = []
    for child in svg:
        # Fail on a new SVG feature instead of silently dropping visible geometry.
        if child.tag != SVG_NAMESPACE + 'path' or set(child.attrib) != {'d'} or not child.get('d', '').strip():
            raise ValueError(f'{icon_name}: unsupported SVG path or attributes')
        paths.append('    <path android:fillColor="#00000000" android:strokeColor="#FF000000"'
                     ' android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round"'
                     f' android:pathData={json.dumps(child.get("d"))} />')
    if not paths:
        raise ValueError(f'{icon_name}: empty SVG')
    return ('<!-- Generated from Tabler Icons outline: ' + icon_name + '. MIT; see tools/icons/README.md. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp"'
            ' android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
            + '\n'.join(paths) + '\n</vector>\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Check generated resources without writing files.')
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text())
    changed = []
    for resource, name in catalog['resources'].items():
        source = CATALOG.parent / 'tabler/outline' / f'{name}.svg'
        vector = vector_from_svg(source.read_text(), name)
        target = ROOT / 'app/src/main/res/drawable' / f'{resource}.xml'
        if not target.exists() or target.read_text() != vector:
            changed.append(resource)
            if not args.check:
                target.write_text(vector)
    if args.check and changed:
        parser.exit(1, 'Stale icon resources: ' + ', '.join(changed) + '\n')
    print(f'{len(catalog["resources"])} round-line vectors checked; {len(changed)} '
          + ('stale.' if args.check else 'updated.'))


if __name__ == '__main__':
    main()
