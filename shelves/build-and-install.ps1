# Python 3, JDK 17, Android SDK and adb must be available. All arguments go to install.py.
$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'install.py') @args
exit $LASTEXITCODE
