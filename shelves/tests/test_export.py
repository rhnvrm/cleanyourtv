import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('exporter', Path(__file__).parents[1] / 'export-source.py')
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)

class ExportTests(unittest.TestCase):
    def tree(self, root):
        (root / 'app/src').mkdir(parents=True)
        (root / 'tests').mkdir()
        (root / 'README.md').write_text('public source')
        (root / 'app/src/A.java').write_text('class A {}')
        (root / 'private.png').write_bytes(b'private screenshot')
        (root / 'signing.properties').write_text('private signing password')
        (root / 'app/build').mkdir()
        (root / 'app/build/secret.apk').write_bytes(b'private binary')

    def test_only_allowlisted_source_is_exported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'src'
            root.mkdir()
            self.tree(root)
            target = Path(directory) / 'public'
            with patch.object(exporter, 'ROOT', root), patch.object(exporter, 'FILES', ['README.md']):
                self.assertEqual(exporter.export(target), 2)
            self.assertEqual(sorted(str(p.relative_to(target)) for p in target.rglob('*') if p.is_file()), ['README.md', 'app/src/A.java'])

    def test_sensitive_source_aborts_before_creating_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'src'
            root.mkdir()
            self.tree(root)
            (root / 'README.md').write_text('BEGIN ' + 'OPENSSH ' + 'PRIVATE KEY')
            target = Path(directory) / 'public'
            with patch.object(exporter, 'ROOT', root), patch.object(exporter, 'FILES', ['README.md']):
                with self.assertRaises(ValueError):
                    exporter.export(target)
            self.assertFalse(target.exists())

    def test_existing_destination_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                exporter.export(Path(directory))

    def test_source_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'src'
            root.mkdir()
            self.tree(root)
            (root / 'app/src/A.java').unlink()
            (root / 'app/src/A.java').symlink_to(root / 'signing.properties')
            with patch.object(exporter, 'ROOT', root), patch.object(exporter, 'FILES', ['README.md']):
                with self.assertRaises(ValueError):
                    exporter.export(Path(directory) / 'public')

if __name__ == '__main__':
    unittest.main()
