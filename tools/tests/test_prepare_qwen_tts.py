import hashlib
import importlib.util
import io
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('prepare_qwen_tts', Path(__file__).resolve().parents[1] / 'prepare_qwen_tts.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SourceArchiveTest(unittest.TestCase):
    def fixture(self, root, name, data=b'fixture'):
        archive = root / 'runtime.tar.gz'
        with tarfile.open(archive, 'w:gz') as bundle:
            item = tarfile.TarInfo(name); item.size = len(data)
            bundle.addfile(item, io.BytesIO(data))
        return {'bytes': archive.stat().st_size, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}

    def test_verified_sources_extract_without_external_instructions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            spec = self.fixture(root, 'root/src/native.cpp')
            destination = MODULE.source('runtime', spec, root, None)
            self.assertEqual((destination / 'src/native.cpp').read_bytes(), b'fixture')
            self.assertEqual(MODULE.source('runtime', spec, root, None), destination)

    def test_checksum_failure_and_archive_escape_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            spec = self.fixture(root, 'root/../escape.cpp')
            with self.assertRaisesRegex(RuntimeError, 'Unsupported source archive'):
                MODULE.source('runtime', spec, root, None)
            self.assertFalse((root / 'escape.cpp').exists())
            spec['sha256'] = '0' * 64
            with self.assertRaisesRegex(RuntimeError, 'checksum verification'):
                MODULE.source('runtime', spec, root, None)


class SourcePathMappingTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'A host C compiler is required')
    def test_compiled_filenames_and_debug_paths_exclude_checkout_and_ndk(self):
        with tempfile.TemporaryDirectory(prefix='droy source paths ') as temporary:
            root = (Path(temporary) / 'project with spaces').resolve()
            ndk = (Path(temporary) / 'android ndk').resolve()
            root.mkdir(); ndk.mkdir()
            (ndk / 'fixture.h').write_text('const char *header_path = __FILE__;\n')
            source = root / 'fixture.c'
            source.write_text('#include "fixture.h"\nconst char *source_path = __FILE__;\n')
            output = root / 'fixture.o'
            # CMake's flags string must preserve each mapping as a single argument.
            flags = shlex.split(shlex.join(MODULE.source_path_flags(root, ndk)))
            subprocess.run([shutil.which('cc'), '-g', '-c', str(source), '-I', str(ndk),
                            *flags, '-o', str(output)], cwd=root, check=True,
                           capture_output=True)
            data = output.read_bytes()
            self.assertFalse(str(root).encode() in data, 'Compiled object leaked the checkout path')
            self.assertFalse(str(ndk).encode() in data, 'Compiled object leaked the NDK path')
            self.assertTrue(b'./fixture.c' in data, 'Missing relative source filename')
            self.assertTrue(b'android-ndk/fixture.h' in data, 'Missing mapped header filename')


if __name__ == '__main__':
    unittest.main()
