"""Pinned offline audio assets: reuse, corruption, and failed publication."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('prepare_audio', Path(__file__).resolve().parents[1] / 'prepare_audio.py')
audio = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audio)


class AudioAssetsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.output = self.root / 'generated' / 'model.onnx'
        self.content = b'public synthetic model fixture'
        self.digest = hashlib.sha256(self.content).hexdigest()

    def prepare(self, source=None):
        audio.prepare(self.output, 'https://example.invalid/model', len(self.content), self.digest, source)

    def test_verified_generated_resource_needs_no_network(self):
        self.output.parent.mkdir()
        self.output.write_bytes(self.content)
        before = self.output.stat().st_mtime_ns
        with mock.patch.object(audio.urllib.request, 'urlopen', side_effect=AssertionError('No network')):
            self.prepare()
        self.assertEqual(before, self.output.stat().st_mtime_ns)

    def test_verified_local_source_is_published_without_network(self):
        source = self.root / 'source.onnx'
        source.write_bytes(self.content)
        with mock.patch.object(audio.urllib.request, 'urlopen', side_effect=AssertionError('No network')):
            self.prepare(source)
        self.assertEqual(self.content, self.output.read_bytes())
        self.assertEqual(self.content, source.read_bytes())

    def test_corrupt_download_does_not_replace_previous_resource(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b'previous version')
        with mock.patch.object(audio.urllib.request, 'urlopen', return_value=io.BytesIO(b'x' * len(self.content))):
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                self.prepare()
        self.assertEqual(b'previous version', self.output.read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_oversized_download_is_rejected(self):
        with mock.patch.object(audio.urllib.request, 'urlopen', return_value=io.BytesIO(self.content + b'x')):
            with self.assertRaisesRegex(ValueError, 'exceeds pinned size'):
                self.prepare()
        self.assertFalse(self.output.exists())

    def test_corrupt_local_source_is_rejected_without_downloading(self):
        source = self.root / 'source.onnx'
        source.write_bytes(b'x' * len(self.content))
        with mock.patch.object(audio.urllib.request, 'urlopen', side_effect=AssertionError('No fallback')):
            with self.assertRaisesRegex(ValueError, 'Local speech resource checksum mismatch'):
                self.prepare(source)
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
