#!/usr/bin/env python3
"""Prepare pinned local E5 resources and build JNI against Droy's existing ORT."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import urllib.request
import zipfile

from prepare_qwen_tts import digest, source, source_path_flags

ROOT = Path(__file__).resolve().parents[1]


def fetch(url, target, size, sha256):
    if target.is_file() and target.stat().st_size == size and digest(target) == sha256:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.part')
    try:
        with urllib.request.urlopen(url, timeout=120) as incoming, temporary.open('wb') as output:
            shutil.copyfileobj(incoming, output)
        if temporary.stat().st_size != size or digest(temporary) != sha256:
            raise RuntimeError(f'Embedding resource failed checksum verification: {target.name}')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def model_fingerprint(spec):
    data = {'model': spec['model'], 'tokenizer': 'xlm-roberta-sentencepiece-offset1',
            'pooling': 'attention-mask-mean-l2', 'queryPrefix': 'query: ', 'documentPrefix': 'passage: '}
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--cmake', default=shutil.which('cmake'))
    parser.add_argument('--source-archives', type=Path)
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--resources-only', action='store_true')
    args = parser.parse_args()
    spec = json.loads((ROOT / 'tools/embedding.lock.json').read_text())
    cache = ROOT / 'runtime/generated/embedding'
    cache.mkdir(parents=True, exist_ok=True)
    resources = ROOT / 'runtime/generated/assets/embedding/e5-small'
    for item in spec['model']['files']:
        fetch(f'https://huggingface.co/{spec["model"]["id"]}/resolve/{spec["model"]["revision"]}/{item["source"]}',
              resources / item['name'], item['bytes'], item['sha256'])
    manifest = {**spec['model'], 'fingerprint': model_fingerprint(spec)}
    (resources / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.resources_only:
        print(f'Embedding model resources verified ({sum(f["bytes"] for f in spec["model"]["files"])} bytes)')
        return
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    ndk = args.ndk or (Path(sdk) / 'ndk' / spec['ndk'] if sdk else None)
    if not ndk or not (ndk / 'build/cmake/android.toolchain.cmake').is_file() or not args.cmake:
        parser.error('Provide the pinned Android NDK and CMake 3.22+ via --ndk/--cmake or environment')
    sentencepiece = source('sentencepiece', spec['sentencepiece'], cache, args.source_archives)
    ort = spec['onnxruntime']
    fetch(ort['headerUrl'], cache / 'onnxruntime_c_api.h', ort['headerBytes'], ort['headerSha256'])
    for header in ort.get('supportHeaders', []):
        fetch(header['url'], cache / header['name'], header['bytes'], header['sha256'])
    # Link the same library already packaged by sherpa, never bundle a second ORT.
    with zipfile.ZipFile(ROOT / 'runtime/generated/audio/sherpa-onnx-1.13.8.aar') as archive:
        library = archive.read('jni/arm64-v8a/libonnxruntime.so')
    if ('VERS_' + ort['version']).encode() not in library:
        raise RuntimeError('Bundled speech ONNX Runtime version does not match the embedding API')
    (cache / 'libonnxruntime.so').write_bytes(library)
    build = cache / 'android-build'
    flags = shlex.join(source_path_flags(ROOT, ndk.resolve()))
    subprocess.run([args.cmake, '-S', str(ROOT / 'app/src/main/cpp/embedding'), '-B', str(build),
                    '-DCMAKE_BUILD_TYPE=Release', f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
                    f'-DCMAKE_C_FLAGS={flags}', f'-DCMAKE_CXX_FLAGS={flags}', '-DANDROID_ABI=arm64-v8a',
                    f'-DANDROID_PLATFORM=android-{spec["androidApi"]}', '-DANDROID_STL=c++_static',
                    f'-DDROY_SENTENCEPIECE_SOURCE={sentencepiece}', f'-DDROY_ORT_LIBRARY={cache}/libonnxruntime.so',
                    f'-DDROY_ORT_HEADERS={cache}'], check=True)
    subprocess.run([args.cmake, '--build', str(build), '--target', 'droy_embedding', '-j', str(args.jobs)], check=True)
    output = ROOT / 'runtime/generated/jniLibs/arm64-v8a/libdroy_embedding.so'
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.so.part')
    shutil.copyfile(build / 'libdroy_embedding.so', temporary)
    strip_tools = list((ndk / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-strip'))
    if len(strip_tools) != 1:
        raise RuntimeError('Cannot locate the pinned NDK llvm-strip')
    subprocess.run([str(strip_tools[0]), '--strip-unneeded', str(temporary)], check=True)
    temporary.replace(output)
    licenses = ROOT / 'runtime/generated/assets/licenses/embedding'
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sentencepiece / 'LICENSE', licenses / 'sentencepiece-LICENSE.txt')
    (licenses / 'sources.json').write_text(json.dumps(spec, indent=2) + '\n')
    print(f'Embedding Android runtime ready: {output.stat().st_size} bytes; model fingerprint {manifest["fingerprint"]}')


if __name__ == '__main__':
    main()
