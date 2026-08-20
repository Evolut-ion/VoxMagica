#!/bin/sh
# Build the VoxMagica Voice Setup single-file Linux binary.
# Requires Python 3 with PyInstaller installed (pip install pyinstaller).
#
# NOTE: PyInstaller output is never cross-platform - run this ON Linux to get
# a Linux binary. Running build.bat on Windows produces a separate .exe; you
# need both build artifacts if you're shipping to both platforms.
set -e
cd "$(dirname "$0")/.."

python3 -m PyInstaller --clean --noconfirm setup/voxmagica_launcher.spec

echo
echo "Built: dist/VoxMagicaVoiceSetup"
echo "Bundle this binary ALONE - the mod jars are embedded inside it."
echo "(chmod +x it if the executable bit didn't survive however you transferred it here.)"
