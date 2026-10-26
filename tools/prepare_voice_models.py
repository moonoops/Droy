#!/usr/bin/env python3
"""Prepare pinned local wake-word models for the Android APK.

Downloads are from the official sherpa-onnx release assets. Only the small int8
keyword spotter and its pronunciation dictionary are included. The app unpacks
them on first use. Voiceprint assets remain out of this release.
"""
import argparse
import hashlib
from pathlib import Path
import tarfile
import urllib.request
import zipfile

KWS_URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2'
SOURCES = {
    'kws.tar.bz2': (KWS_URL, 32885699, '68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6'),
}
FILES = {
    'encoder.onnx': ('encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx', 4600657, '2ca84d6bfe73e1ea3c9c49f600f7cad1c9ddd423c53c906b8bfe802444dd78d5'),
    'decoder.onnx': ('decoder-epoch-13-avg-2-chunk-8-left-64.onnx', 759829, '63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21'),
    'joiner.onnx': ('joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx', 86629, '190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1'),
    'tokens.txt': ('tokens.txt', 1928, '2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0'),
    'en.phone': ('en.phone', 3330061, 'f7000ec3a90544c0c7c16090d8951779c2b322e14dad5006290f498567d439ea'),
}
KWS_TEST_FILES = {
    'zh_3.wav': (257006, '710e79fb3450042be50bb2f6432a2a549b53577aea041a577e53a926fcabb614'),
    'en_0.wav': (212044, '6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f'),
}


def verified(data, size, digest):
    return len(data) == size and hashlib.sha256(data).hexdigest() == digest


def download(cache, name, source):
    url, size, digest = source
    target = cache / name
    if target.is_file() and verified(target.read_bytes(), size, digest):
        return target
    staging = target.with_suffix(target.suffix + '.part')
    try:
        with urllib.request.urlopen(url, timeout=60) as response, staging.open('wb') as output:
            while block := response.read(262144):
                output.write(block)
                if output.tell() > size:
                    raise ValueError(f'{name}: download exceeds pinned size')
        if not verified(staging.read_bytes(), size, digest):
            raise ValueError(f'{name}: checksum mismatch')
        staging.replace(target)
    finally:
        staging.unlink(missing_ok=True)
    return target


def package_ready(path):
    try:
        with zipfile.ZipFile(path) as archive:
            return set(archive.namelist()) == set(FILES) and all(
                verified(archive.read(name), size, digest)
                for name, (_, size, digest) in FILES.items())
    except (OSError, zipfile.BadZipFile, KeyError):
        return False


def prepare_test_fixtures(directory, cache):
    directory.mkdir(parents=True, exist_ok=True)
    source = download(cache, 'kws.tar.bz2', SOURCES['kws.tar.bz2'])
    with tarfile.open(source, 'r:bz2') as archive:
        for member in archive.getmembers():
            name = Path(member.name).name
            if name in KWS_TEST_FILES and member.isfile():
                data = archive.extractfile(member).read()
                if not verified(data, *KWS_TEST_FILES[name]):
                    raise ValueError(f'{name}: test fixture checksum mismatch')
                (directory / name).write_bytes(data)
    print(f'Public model fixtures prepared: {directory}')


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, epilog=f'Official source:\n{KWS_URL}',
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cache', type=Path, default=root.parent / 'artifacts/runtime-voice',
                        help='Verified source download cache, outside the public source repository')
    parser.add_argument('--output', type=Path, default=root / 'runtime/generated/assets/voice-models.zip',
                        help='Generated APK asset; do not commit model binaries')
    parser.add_argument('--test-fixtures', type=Path,
                        help='Also prepare pinned public WAV files for VoiceModelsDeviceTest in this directory')
    args = parser.parse_args()
    if package_ready(args.output):
        print('Local wake models verified')
        if args.test_fixtures:
            args.cache.mkdir(parents=True, exist_ok=True)
            prepare_test_fixtures(args.test_fixtures, args.cache)
        return
    args.cache.mkdir(parents=True, exist_ok=True)
    sources = {name: download(args.cache, name, spec) for name, spec in SOURCES.items()}
    files = {}
    by_name = {source: name for name, (source, _, _) in FILES.items()}
    with tarfile.open(sources['kws.tar.bz2'], 'r:bz2') as archive:
        for member in archive.getmembers():
            name = by_name.get(Path(member.name).name)
            if name and member.isfile():
                files[name] = archive.extractfile(member).read()
    for name, (_, size, digest) in FILES.items():
        if name not in files or not verified(files[name], size, digest):
            raise ValueError(f'{name}: official model content mismatch')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    staging = args.output.with_suffix('.zip.part')
    try:
        with zipfile.ZipFile(staging, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name in sorted(files):
                info = zipfile.ZipInfo(name, date_time=(2025, 12, 20, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, files[name])
        staging.replace(args.output)
    finally:
        staging.unlink(missing_ok=True)
    print(f'Local wake models prepared: {args.output.stat().st_size} bytes')
    if args.test_fixtures:
        prepare_test_fixtures(args.test_fixtures, args.cache)


if __name__ == '__main__':
    main()
