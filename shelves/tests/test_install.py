import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('installer', Path(__file__).parents[1] / 'install.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)

class InstallerTests(unittest.TestCase):
    def test_home_resolution_rejects_chooser(self):
        with patch.object(installer, 'run', return_value='android/com.android.internal.app.ResolverActivity'):
            with self.assertRaises(RuntimeError):
                installer.home(['adb'])

    def test_failed_handover_rolls_back(self):
        calls = []
        def run(cmd):
            calls.append(cmd)
            if 'resolve-activity' in cmd:
                return 'stock/stock.Home'
            return ''
        with patch.object(installer, 'run', side_effect=run):
            with self.assertRaises(RuntimeError):
                installer.select_home(['adb'], 'new/new.Home', 'stock/stock.Home')
        self.assertEqual(calls[-1][-1], 'stock/stock.Home')
        self.assertFalse(any('disable-user' in cmd for cmd in calls))

    def test_install_defaults_leave_home_and_permissions_unchanged(self):
        calls = []
        def run(cmd, cwd=None):
            calls.append(cmd)
            if 'ro.build.version.sdk' in cmd:
                return '28'
            if 'resolve-activity' in cmd:
                return 'stock/stock.Home'
            if 'start' in cmd:
                return 'Status: ok'
            return ''
        with tempfile.TemporaryDirectory() as directory, patch.object(installer, 'run', side_effect=run):
            installer.main(['--host', 'tv.example', '--state-dir', directory])
            self.assertEqual(len(list(Path(directory).glob('*.json'))), 1)
        self.assertFalse(any('grant' in cmd or 'disable-user' in cmd for cmd in calls))
        self.assertEqual([cmd[-1] for cmd in calls if 'set-home-activity' in cmd], ['stock/stock.Home'])
        self.assertTrue(any(installer.DEFAULT_ID + '/' + installer.HOME_CLASS in cmd for cmd in calls))

    def test_handover_backs_up_home_before_selecting(self):
        calls = []
        def run(cmd, cwd=None):
            calls.append(cmd)
            if 'ro.build.version.sdk' in cmd:
                return '28'
            if 'resolve-activity' in cmd:
                return 'stock/stock.Home' if sum('resolve-activity' in c for c in calls) <= 2 else installer.DEFAULT_ID + '/' + installer.HOME_CLASS
            if 'start' in cmd:
                return 'Status: ok'
            return ''
        with tempfile.TemporaryDirectory() as directory, patch.object(installer, 'run', side_effect=run):
            installer.main(['--host', 'tv.example', '--select-home', '--state-dir', directory])
            state = list(Path(directory).glob('*.json'))
            self.assertEqual(len(state), 1)
            self.assertIn('stock/stock.Home', state[0].read_text())
        self.assertFalse(any('disable-user' in cmd or 'grant' in cmd for cmd in calls))

    def test_install_recovers_android_clearing_preferred_home(self):
        selection = ['stock/stock.Home']
        def run(cmd, cwd=None):
            if 'ro.build.version.sdk' in cmd:
                return '28'
            if 'resolve-activity' in cmd:
                return selection[0]
            if 'install' in cmd:
                selection[0] = 'android/com.android.internal.app.ResolverActivity'
            if 'set-home-activity' in cmd:
                selection[0] = cmd[-1]
            return 'Status: ok' if 'start' in cmd else ''
        with tempfile.TemporaryDirectory() as directory, patch.object(installer, 'run', side_effect=run):
            installer.main(['--host', 'tv.example', '--state-dir', directory])
        self.assertEqual(selection[0], 'stock/stock.Home')

    def test_restore_validates_device_and_application(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / ('tv.example_5555-' + installer.DEFAULT_ID + '.json')
            state.write_text(json.dumps({'endpoint': 'other:5555', 'target': 'other/Other', 'previous': 'stock/stock.Home'}))
            with patch.object(installer, 'run', return_value='') as run:
                with self.assertRaises(RuntimeError):
                    installer.main(['--host', 'tv.example', '--restore-home', '--state-dir', directory])
                self.assertEqual(run.call_count, 1)  # connect only, no mutating command

    def test_android_below_minimum_is_not_installed(self):
        def run(cmd, cwd=None):
            return '25' if 'ro.build.version.sdk' in cmd else ''
        with patch.object(installer, 'run', side_effect=run) as mock:
            with self.assertRaises(RuntimeError):
                installer.main(['--host', 'tv.example'])
            self.assertFalse(any('install' in call.args[0] for call in mock.call_args_list))

if __name__ == '__main__':
    unittest.main()
