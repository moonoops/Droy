#!/usr/bin/env python3
"""Build the pinned Android Qwen3-TTS CPU runtime; never download model weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(262144), b''):
            result.update(chunk)
    return result.hexdigest()


def source_path_flags(root, ndk):
    return [f'-ffile-prefix-map={root}=.', f'-ffile-prefix-map={ndk}=android-ndk']


def source(name, spec, cache, archive_directory):
    archive = cache / (name + '.tar.gz')
    if archive_directory:
        candidate = archive_directory / (name + '.tar.gz')
        if candidate.exists() and candidate.resolve() != archive.resolve():
            shutil.copyfile(candidate, archive)
    if not archive.exists():
        temporary = archive.with_suffix('.part')
        try:
            url = f'https://codeload.github.com/{spec["repository"]}/tar.gz/{spec["revision"]}'
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output)
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if archive.stat().st_size != spec['bytes'] or digest(archive) != spec['sha256']:
        raise RuntimeError(f'{name} source archive failed checksum verification: {archive}')
    destination = cache / name
    marker = destination / '.droy-source-sha256'
    if marker.exists() and marker.read_text() == spec['sha256']:
        return destination
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir()
    with tarfile.open(archive) as bundle:
        for item in bundle:
            relative = Path(*Path(item.name).parts[1:])
            if relative.is_absolute() or '..' in relative.parts:
                raise RuntimeError(f'Unsupported source archive member: {item.name}')
            if not relative.parts or item.isdir() or relative.parts[0].startswith('.') or item.name.endswith('/AGENTS.md'):
                continue
            if item.issym() or item.islnk() or not item.isfile():
                raise RuntimeError(f'Unsupported source archive member: {item.name}')
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.extractfile(item) as input_stream, target.open('wb') as output:
                shutil.copyfileobj(input_stream, output)
    marker.write_text(spec['sha256'])
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--cmake', default=shutil.which('cmake'))
    parser.add_argument('--source-archives', type=Path)
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    spec = json.loads((ROOT / 'tools/qwen_tts.lock.json').read_text())
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    ndk = args.ndk or (Path(sdk) / 'ndk' / spec['ndk'] if sdk else None)
    if not ndk or not (ndk / 'build/cmake/android.toolchain.cmake').is_file():
        parser.error('Provide --ndk or ANDROID_HOME with the pinned Android NDK installed')
    if not args.cmake:
        parser.error('CMake 3.22+ is required; provide --cmake or add it to PATH')
    cache = ROOT / 'runtime/generated/qwen-tts'
    cache.mkdir(parents=True, exist_ok=True)
    runtime = source('runtime', spec['runtime'], cache, args.source_archives)
    ggml = source('ggml', spec['ggml'], cache, args.source_archives)
    build = cache / 'android-build'
    prefix_maps = shlex.join(source_path_flags(ROOT, ndk.resolve()))
    subprocess.run([args.cmake, '-S', str(ROOT / 'app/src/main/cpp/qwen'), '-B', str(build),
                    '-DCMAKE_BUILD_TYPE=Release', f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
                    f'-DCMAKE_C_FLAGS={prefix_maps}', f'-DCMAKE_CXX_FLAGS={prefix_maps}',
                    '-DANDROID_ABI=arm64-v8a', f'-DANDROID_PLATFORM=android-{spec["androidApi"]}',
                    '-DANDROID_STL=c++_static', f'-DDROY_QWEN_SOURCE={runtime}', f'-DDROY_GGML_SOURCE={ggml}'], check=True)
    subprocess.run([args.cmake, '--build', str(build), '--target', 'droy_qwen_tts', '-j', str(args.jobs)], check=True)
    output = ROOT / 'runtime/generated/jniLibs/arm64-v8a/libdroy_qwen_tts.so'
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.so.part')
    shutil.copyfile(build / 'libdroy_qwen_tts.so', temporary)
    strip_tools = list((ndk / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-strip'))
    if len(strip_tools) != 1:
        raise RuntimeError('Cannot locate the pinned NDK llvm-strip')
    subprocess.run([str(strip_tools[0]), '--strip-unneeded', str(temporary)], check=True)
    temporary.replace(output)
    licenses = ROOT / 'runtime/generated/assets/licenses/qwen-tts'
    licenses.mkdir(parents=True, exist_ok=True)
    for name, directory in [('qwen3-tts.cpp', runtime), ('ggml', ggml)]:
        shutil.copyfile(directory / 'LICENSE', licenses / (name + '-LICENSE.txt'))
    (licenses / 'sources.json').write_text(json.dumps(spec, indent=2) + '\n')
    print(f'Qwen3-TTS Android runtime: {output} ({output.stat().st_size} bytes, SHA-256 {digest(output)})')


if __name__ == '__main__':
    main()
