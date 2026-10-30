import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import io

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location('prepare_embedding', TOOLS / 'prepare_embedding.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareEmbeddingTest(unittest.TestCase):
    def test_corrupt_download_does_not_replace_verified_resource(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'model.onnx'
            target.write_bytes(b'previous')
            expected = hashlib.sha256(b'new valid model').hexdigest()
            with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(b'bad')):
                with self.assertRaisesRegex(RuntimeError, 'checksum'):
                    module.fetch('https://example.invalid/model', target, len(b'new valid model'), expected)
            self.assertEqual(b'previous', target.read_bytes())
            self.assertFalse(target.with_suffix('.onnx.part').exists())

    def test_existing_resource_is_reverified_before_skipping_network(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'model.onnx'
            data = b'test model'
            target.write_bytes(data)
            with patch.object(module.urllib.request, 'urlopen') as download:
                module.fetch('https://example.invalid/model', target, len(data), hashlib.sha256(data).hexdigest())
                download.assert_not_called()

    def test_model_or_tokenizer_change_invalidates_vector_fingerprint(self):
        import copy
        import json
        spec = json.loads((TOOLS / 'embedding.lock.json').read_text())
        changed = copy.deepcopy(spec)
        changed['model']['files'][1]['sha256'] = '0' * 64
        self.assertNotEqual(module.model_fingerprint(spec), module.model_fingerprint(changed))


if __name__ == '__main__':
    unittest.main()
