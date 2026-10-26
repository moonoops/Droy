"""Local failure-path tests; no downloads, ELF tools, or device access."""

import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("prepare_android_node", Path(__file__).resolve().parents[1] / "prepare_android_node.py")
node_payload = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(node_payload)


class RuntimeToolSelectionTests(unittest.TestCase):
    def archive(self, entries):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w") as archive:
            for name, content in entries.items():
                member = tarfile.TarInfo("./" + node_payload.PREFIX + name)
                if isinstance(content, tuple):
                    member.type, member.linkname = tarfile.SYMTYPE, content[0]
                    archive.addfile(member)
                else:
                    member.size = len(content)
                    archive.addfile(member, io.BytesIO(content))
        data.seek(0)
        return tarfile.open(fileobj=data, mode="r:")

    def test_extracts_locked_executables_without_shipping_package_install_tools(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = self.archive({
                "bin/bash": b"bash executable", "bin/rg": b"search executable",
                "bin/pkg": b"termux package manager", "etc/bash.bashrc": b"termux shell initialization",
            })
            with mock.patch.object(node_payload, "tar_payload", return_value=archive):
                records = node_payload.unpack({"name": "bash"}, b"unused", Path(temporary))
            self.assertEqual(set(records), {"bin/bash", "bin/rg"})
            self.assertEqual(records["bin/bash"]["path"].read_bytes(), b"bash executable")

    def test_extracts_and_resolves_terminfo_aliases(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = self.archive({
                "share/terminfo/x/xterm-256color": b"terminal description",
                "share/terminfo/x/xterm": ("xterm-256color",),
                "share/terminfo/d/dumb": b"plain terminal",
            })
            with mock.patch.object(node_payload, "tar_payload", return_value=archive):
                records = node_payload.unpack({"name": "ncurses"}, b"unused", Path(temporary))
            resolved = node_payload.resolve_record(records, "share/terminfo/x/xterm")
            self.assertEqual(resolved["path"].read_bytes(), b"terminal description")
            self.assertEqual(len(records), 3)

    def test_rejects_escaping_terminal_alias(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = self.archive({"share/terminfo/x/xterm": ("../../../../outside",)})
            with mock.patch.object(node_payload, "tar_payload", return_value=archive):
                with self.assertRaisesRegex(ValueError, "Unsupported library symlink"):
                    node_payload.unpack({"name": "ncurses"}, b"unused", Path(temporary))

    def test_lock_requires_bash_and_ripgrep(self):
        lock = json.loads((node_payload.REPO_ROOT / "runtime/node-runtime.lock.json").read_text())
        for name in ("bash", "ripgrep"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "lock.json"
                node_payload.write_json(path, {**lock, "packages": [p for p in lock["packages"] if p["name"] != name]})
                with self.assertRaisesRegex(ValueError, "Missing root package: " + name):
                    node_payload.read_lock(path)


class PayloadPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="droy payload ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "output"
        self.staged = self.root / "staged"
        self.manifest = {"schema_version": 1, "architecture": "arm64-v8a", "executable": "libdroy_node.so"}
        for name in node_payload.PAYLOAD_PATHS[:-1]:
            (self.staged / name).mkdir(parents=True)
            (self.staged / name / "content").write_text("new " + name)
        self.output.mkdir()
        (self.output / ".cache").mkdir()
        (self.output / ".cache" / "verified.deb").write_bytes(b"cached package")
        (self.output / "unrelated.txt").write_text("keep")

    def previous_payload(self):
        for name in node_payload.PAYLOAD_PATHS[:-1]:
            (self.output / name).mkdir()
            (self.output / name / "content").write_text("old " + name)
        node_payload.write_json(self.output / "runtime-manifest.json", {**self.manifest, "revision": "old"})

    def snapshot(self):
        return {str(path.relative_to(self.output)): path.read_bytes()
                for path in self.output.rglob("*") if path.is_file()}

    def test_success_replaces_payload_and_preserves_cache_and_unrelated_files(self):
        self.previous_payload()
        node_payload.publish_payload(self.staged, self.output, {**self.manifest, "revision": "new"})
        for name in node_payload.PAYLOAD_PATHS[:-1]:
            self.assertEqual((self.output / name / "content").read_text(), "new " + name)
        self.assertEqual(json.loads((self.output / "runtime-manifest.json").read_text())["revision"], "new")
        self.assertEqual((self.output / ".cache" / "verified.deb").read_bytes(), b"cached package")
        self.assertEqual((self.output / "unrelated.txt").read_text(), "keep")
        self.assertFalse(list(self.output.glob(".droy-node-publish-*")))

    def test_copy_failure_preserves_every_previous_file(self):
        self.previous_payload()
        before = self.snapshot()
        original = node_payload.shutil.copytree

        def fail_copy(source, destination, *args, **kwargs):
            if Path(source) == self.staged / "assets":
                raise OSError("simulated disk full")
            return original(source, destination, *args, **kwargs)

        with mock.patch.object(node_payload.shutil, "copytree", side_effect=fail_copy):
            with self.assertRaisesRegex(OSError, "disk full"):
                node_payload.publish_payload(self.staged, self.output, self.manifest)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(list(self.output.glob(".droy-node-publish-*")))

    def test_replace_failure_restores_every_previous_file(self):
        self.previous_payload()
        before = self.snapshot()
        original = Path.replace

        def fail_replace(path, destination):
            if path.parent.name == "new" and path.name == "assets":
                raise OSError("simulated rename failure")
            return original(path, destination)

        with mock.patch.object(Path, "replace", fail_replace):
            with self.assertRaisesRegex(OSError, "rename failure"):
                node_payload.publish_payload(self.staged, self.output, self.manifest)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(list(self.output.glob(".droy-node-publish-*")))

    def test_failed_first_publication_leaves_no_partial_payload(self):
        before = self.snapshot()
        original = Path.replace

        def fail_replace(path, destination):
            if path.parent.name == "new" and path.name == "headers":
                raise OSError("simulated rename failure")
            return original(path, destination)

        with mock.patch.object(Path, "replace", fail_replace):
            with self.assertRaises(OSError):
                node_payload.publish_payload(self.staged, self.output, self.manifest)
        self.assertEqual(self.snapshot(), before)
        self.assertTrue(all(not (self.output / name).exists() for name in node_payload.PAYLOAD_PATHS))

    def test_failed_recovery_preserves_backup_for_manual_recovery(self):
        self.previous_payload()
        original = Path.replace

        def fail_replace(path, destination):
            if path.name == "assets" and path.parent.name in {"new", "previous"}:
                raise OSError("simulated persistent rename failure")
            return original(path, destination)

        with mock.patch.object(Path, "replace", fail_replace):
            with self.assertRaisesRegex(OSError, "Payload recovery failed; preserved files"):
                node_payload.publish_payload(self.staged, self.output, self.manifest)
        pending, = self.output.glob(".droy-node-publish-*")
        self.assertEqual((pending / "previous/assets/content").read_text(), "old assets")
        self.assertEqual((pending / "previous/jniLibs/content").read_text(), "old jniLibs")

    def test_unowned_existing_directory_is_not_replaced(self):
        (self.output / "assets").mkdir()
        (self.output / "assets" / "personal.txt").write_text("keep")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "dedicated output directory"):
            node_payload.publish_payload(self.staged, self.output, self.manifest)
        self.assertEqual(self.snapshot(), before)

    def test_unrecognized_manifest_does_not_authorize_replacement(self):
        self.previous_payload()
        (self.output / "runtime-manifest.json").write_text('{"project": "other"}')
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "recognized DroyAgent"):
            node_payload.publish_payload(self.staged, self.output, self.manifest)
        self.assertEqual(self.snapshot(), before)

    def test_symbolic_payload_paths_are_not_replaced(self):
        (self.output / "assets").symlink_to(self.staged / "assets", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            node_payload.publish_payload(self.staged, self.output, self.manifest)
        self.assertEqual((self.staged / "assets" / "content").read_text(), "new assets")


if __name__ == "__main__":
    unittest.main()
