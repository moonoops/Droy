#!/usr/bin/env python3
"""Prepare the pinned Android speech runtime and offline model assets for the APK."""
import argparse
import hashlib
import json
import pathlib
import shutil
import tempfile
import urllib.request

VERSION = '1.13.8'
URL = f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar'
SHA256 = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
SIZE = 50129134


def verified(path, size=SIZE, sha256=SHA256):
    if not path.is_file() or path.stat().st_size != size:
        return False
    digest = hashlib.sha256()
    with path.open('rb') as src:
        while chunk := src.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest() == sha256


def prepare(output, url, size, sha256, source=None):
    if verified(output, size, sha256):
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix='.part', delete=False) as tmp:
        staging = pathlib.Path(tmp.name)
    try:
        if source is not None:
            if not verified(source, size, sha256):
                raise ValueError(f'Local speech resource checksum mismatch: {source.name}')
            shutil.copyfile(source, staging)
        else:
            with urllib.request.urlopen(url, timeout=60) as response, staging.open('wb') as dst:
                while chunk := response.read(1024 * 1024):
                    dst.write(chunk)
                    if dst.tell() > size:
                        raise ValueError(f'Speech resource exceeds pinned size: {output.name}')
        if not verified(staging, size, sha256):
            raise ValueError(f'Speech resource checksum mismatch: {output.name}')
        staging.replace(output)
    finally:
        staging.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-source', type=pathlib.Path,
                        help='Reuse a local directory containing all pinned model files, after verification')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    prepare(root / 'runtime/generated/audio' / f'sherpa-onnx-{VERSION}.aar', URL, SIZE, SHA256)
    manifest = json.loads((root / 'app/src/main/assets/audio-models.json').read_text())
    output = root / 'runtime/generated/assets/audio-models/sensevoice-int8'
    for asset in manifest['files']:
        prepare(output / asset['name'], asset['url'], asset['bytes'], asset['sha256'],
                args.model_source / asset['name'] if args.model_source else None)
    print(f'Android speech runtime {VERSION} and {len(manifest["files"])} bundled model resources verified')


if __name__ == '__main__':
    main()
