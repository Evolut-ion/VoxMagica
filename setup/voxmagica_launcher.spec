# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec for the VoxMagica Voice Setup launcher.
# Build: pyinstaller --clean --noconfirm setup\voxmagica_launcher.spec

import os

ROOT = os.path.abspath(os.path.dirname(SPECPATH))

datas = [
    # Bundle the mod jar (built artifact) so the app can install it into a
    # Hytale install without any extra downloads. This is the SHADED jar
    # (whisper-jni + its native libs included, built via `./gradlew
    # shadowJarRelease`) - the plain jarRelease output does not include
    # whisper-jni and would leave local transcription non-functional. Hexcode
    # and HytaleServer are deliberately NOT bundled/distributed - see
    # build.gradle's shadowJar configuration for why that's still true here.
    (os.path.join(ROOT, "build", "libs", "VoxMagica-0.1.0-release-shaded.jar"), "resources"),
]

a = Analysis(
    [os.path.join(ROOT, "setup", "launcher.py")],
    pathex=[ROOT],
    binaries=[],
    datas=datas,
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="VoxMagicaVoiceSetup",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    icon=None,
    version=None,
)