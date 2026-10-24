# -*- mode: python ; coding: utf-8 -*-

from PyInstaller.utils.hooks import collect_all

datas = [('icon.ico', '.'), ('icon.png', '.')]
binaries = []
hiddenimports = []
for package in (
    'PyQt6', 'imageio_ffmpeg',
    'sounddevice', 'cv2', 'numpy', 'pycaw', 'comtypes',
    'winrt.windows.media.control', 'winrt.windows.foundation',
    'winrt.windows.devices.bluetooth.rfcomm', 'winrt.windows.networking.sockets',
    'winrt.windows.storage.streams',
):
    try:
        package_datas, package_binaries, package_hiddenimports = collect_all(package)
        datas += package_datas
        binaries += package_binaries
        hiddenimports += package_hiddenimports
    except Exception:
        pass

a = Analysis(
    ['app_gui.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='BT-Mouse-Companion',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['icon.ico'],
)
