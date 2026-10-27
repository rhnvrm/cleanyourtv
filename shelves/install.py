#!/usr/bin/env python3
"""Build/install TV Shelves without disabling a stock launcher or granting optional access."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
DEFAULT_ID = 'io.github.tvhomeshelves.launcher'
HOME_CLASS = 'com.gathin.tvhome.ShelfHomeActivity'
COMPONENT = re.compile(r'^[A-Za-z][\w.]*/[A-Za-z.][\w.$]*$')


def run(command, cwd=ROOT):
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True)
    if result.returncode or re.search(r'(?im)^\s*(error:|failure\b|exception occurred)', result.stdout):
        raise RuntimeError((result.stdout + result.stderr).strip() or 'Command failed')
    return result.stdout.strip()


def home(adb):
    output = run(adb + ['shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a',
                        'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'])
    candidates = [line.strip() for line in output.splitlines() if COMPONENT.fullmatch(line.strip())]
    if not candidates:
        raise RuntimeError('Cannot identify the current Home. Nothing was changed.')
    component = candidates[-1]
    if component.startswith('android/'):
        raise RuntimeError('Choose a default Home in TV settings first; Android returned its chooser.')
    return component


def select_home(adb, target, previous):
    try:
        run(adb + ['shell', 'cmd', 'package', 'set-home-activity', '--user', '0', target])
        if home(adb) != target:
            raise RuntimeError('TV did not select the new Home')
        run(adb + ['shell', 'am', 'start', '-W', '-n', target])
    except RuntimeError:
        # Previous Home was never disabled. Keep the recovery file even if the TV disconnects.
        run(adb + ['shell', 'cmd', 'package', 'set-home-activity', '--user', '0', previous])
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default=os.environ.get('TV_HOST'), help='ADB host or host:port')
    parser.add_argument('--application-id', default=DEFAULT_ID)
    parser.add_argument('--select-home', action='store_true', help='explicitly select the new Home after installing')
    parser.add_argument('--restore-home', action='store_true', help='restore saved Home; no install or settings grants')
    parser.add_argument('--release', action='store_true', help='use a signed release; requires local signing.properties')
    parser.add_argument('--state-dir', type=Path, default=ROOT / '.install-state')
    args = parser.parse_args(argv)
    if not args.host or not re.fullmatch(r'[A-Za-z0-9._-]+(?::[0-9]+)?', args.host):
        parser.error('--host is required (hostname or IPv4, optionally :port)')
    if not re.fullmatch(r'[A-Za-z][\w]*(?:\.[A-Za-z][\w]*)+', args.application_id):
        parser.error('invalid application ID')
    if args.restore_home and args.select_home:
        parser.error('--restore-home and --select-home are mutually exclusive')
    endpoint = args.host if ':' in args.host else args.host + ':5555'
    adb_binary = os.environ.get('TV_ADB_BINARY', 'adb')
    run([adb_binary, 'connect', endpoint])
    adb = [adb_binary, '-s', endpoint]
    target = args.application_id + '/' + HOME_CLASS
    # Device-specific recovery files stay ignored and do not share state between TVs or app IDs.
    state_file = args.state_dir / (endpoint.replace(':', '_') + '-' + args.application_id + '.json')
    if args.restore_home:
        saved = json.loads(state_file.read_text())
        if saved.get('endpoint') != endpoint or saved.get('target') != target or not COMPONENT.fullmatch(saved.get('previous', '')):
            raise RuntimeError('Recovery file does not match this TV/application')
        run(adb + ['shell', 'cmd', 'package', 'set-home-activity', '--user', '0', saved['previous']])
        if home(adb) != saved['previous']:
            raise RuntimeError('Previous Home was not restored')
        run(adb + ['shell', 'input', 'keyevent', 'HOME'])
        print('Previous Home restored. Apps, optional permissions and data were left intact.')
        return
    if int(run(adb + ['shell', 'getprop', 'ro.build.version.sdk'])) < 26:
        raise RuntimeError('Android 8 or later is required; nothing installed')
    previous = home(adb)
    if previous != target:
        args.state_dir.mkdir(parents=True, exist_ok=True)
        # Save the immediately preceding Home for this explicit handover.
        state_file.write_text(json.dumps({'endpoint': endpoint, 'target': target, 'previous': previous}, indent=2))
        try:
            state_file.chmod(0o600)
        except OSError:
            pass
    if args.release and not (ROOT / 'signing.properties').is_file():
        raise RuntimeError('Create local signing.properties first; see README. No unsigned release is installed.')
    variant = 'release' if args.release else 'debug'
    wrapper = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    command = [wrapper, 'assemble' + variant.capitalize(), '-PlauncherApplicationId=' + args.application_id, '--console=plain']
    if os.name == 'nt':
        command = ['cmd', '/c'] + command
    print('Building ' + variant + ' APK…', flush=True)
    run(command)
    apk = ROOT / 'app/build/outputs/apk' / variant / ('app-' + variant + '.apk')
    try:
        run(adb + ['install', '-r', str(apk)])
        # Android can clear its preferred Home when another HOME handler is installed.
        # Reassert the recorded selection, even in install-only mode.
        run(adb + ['shell', 'cmd', 'package', 'set-home-activity', '--user', '0', previous])
        if home(adb) != previous:
            raise RuntimeError('Could not preserve the previous Home; use --restore-home')
        output = run(adb + ['shell', 'am', 'start', '-W', '-n', target])
        if 'Status: ok' not in output:
            raise RuntimeError('New activity did not report a successful launch')
        if args.select_home:
            select_home(adb, target, previous)
    except RuntimeError:
        run(adb + ['shell', 'cmd', 'package', 'set-home-activity', '--user', '0', previous])
        raise
    print('Installed. Stock launcher remains enabled. Optional permissions were not granted.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        sys.exit(1)
