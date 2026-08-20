@echo off
REM Build the VoxMagica Voice Setup single-file exe.
REM Requires Python with PyInstaller installed.
setlocal
cd /d "%~dp0\.."

python -m PyInstaller --clean --noconfirm setup\voxmagica_launcher.spec
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo.
echo Built: dist\VoxMagicaVoiceSetup.exe
echo Bundle this exe ALONE - the mod jars are embedded inside it.
endlocal