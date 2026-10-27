#!/usr/bin/env python3
"""Create a source-only snapshot in a NEW directory. Never copy a maintenance workspace."""
import argparse
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parent
FILES = ['.gitignore', 'README.md', 'LICENSE', 'FONT-LICENSE-OFL.txt', 'build.gradle',
         'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'install.py',
         'build-and-install.sh', 'build-and-install.ps1', 'export-source.py',
         'app/build.gradle', 'app/lint.xml', 'app/proguard-rules.pro', 'gradle/wrapper/gradle-wrapper.jar',
         'gradle/wrapper/gradle-wrapper.properties']
# Resource images/fonts and the Gradle wrapper are intentional source assets.
TREES = ['app/src', 'tests']
ALLOWED = {'.java', '.xml', '.png', '.webp', '.jpg', '.ttf', '.otf', '.py'}
SENSITIVE = re.compile(rb'BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|192[.]168[.](?!1[.]50\b)[0-9]+[.][0-9]+|[\w.+-]+@(?:gmail|outlook)[.]com')


def export(destination):
    destination = destination.resolve()
    if destination.exists():
        raise ValueError('Destination must be new; refusing to mix in existing files')
    chosen = [ROOT / name for name in FILES]
    for tree in TREES:
        for path in (ROOT / tree).rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts:
                if path.suffix not in ALLOWED:
                    raise ValueError('Unexpected source file type: ' + str(path.relative_to(ROOT)))
                chosen.append(path)
    # Validate everything before creating the export.
    for path in chosen:
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing source file or symlink: ' + str(path.relative_to(ROOT)))
        if SENSITIVE.search(path.read_bytes()):
            raise ValueError('Possible private data in ' + str(path.relative_to(ROOT)))
    destination.mkdir(parents=True)
    for path in chosen:
        target = destination / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    return len(chosen)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    try:
        print('Exported', export(args.destination), 'source files. Review before pushing.')
    except ValueError as error:
        parser.error(str(error))
