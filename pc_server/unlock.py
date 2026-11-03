"""Unlock this PC from the phone.

Windows lets only a credential provider put a password into the lock screen, so Lanyard
ships one (unlock_provider/, LanyardUnlock.dll). LogonUI loads it while the PC is locked
and it listens on \\\\.\\pipe\\LanyardUnlock-<session>. This module hands it the password
the phone sent, installs or removes it (one admin prompt), and reports what happened.

The password is never written to disk here. Windows checks it as if it had been typed.
"""

import base64
import ctypes
import os
import subprocess
import sys
import time
from ctypes import wintypes
from pathlib import Path

IS_WINDOWS = os.name == "nt"

CLSID = "{397A4CD3-F354-469B-86E9-83E9C6786325}"
DLL_NAME = "LanyardUnlock.dll"
_CLSID_KEY = rf"SOFTWARE\Classes\CLSID\{CLSID}"
_PROVIDER_KEY = rf"SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{CLSID}"
# Owners that only Windows itself or an administrator can give a pipe: SYSTEM and Administrators.
_TRUSTED_PIPE_OWNERS = ("S-1-5-18", "S-1-5-32-544")
PASSWORD_MAX = 256

MESSAGES = {
    "ok": "PC unlocked",
    "submitted": "Sent to the lock screen",
    "wrong_password": "Windows didn't accept that password. Update it in Lanyard on your phone.",
    "locked_out": "Windows locked this account after too many tries. Wait, then try again.",
    "account_problem": "Windows wouldn't sign this account in. Unlock once at the PC.",
    "timeout": "The lock screen didn't answer. Wake the PC and try again.",
    "no_lock_screen": "Couldn't reach the lock screen. Wake the PC and try again.",
    "not_ready": "The lock screen isn't ready yet. Try again in a moment.",
    "unknown_user": "Your account isn't on this lock screen. Switch back to it at the PC.",
    "wrong_user": "Only the signed-in Windows account can be unlocked.",
    "not_locked": "Your PC is already unlocked",
    "not_installed": "Turn on phone unlock in Lanyard on your PC first (Settings).",
    "insecure": "Unlock needs an encrypted connection. Reconnect and try again.",
    "internet_off": "Unlocking over the internet is off. Turn it on in Lanyard on your PC.",
    "cooldown": "Too many wrong passwords. Wait a minute, then try again.",
    "busy": "Already unlocking",
    "bad_request": "Lanyard couldn't read the unlock request",
    "unsupported": "Phone unlock works only on Windows",
}


def message(code):
    return MESSAGES.get(code, "Couldn't unlock the PC")


# --- install state ----------------------------------------------------------
def bundled_dll():
    """The provider DLL shipped with this companion, or None (e.g. running from source unbuilt)."""
    here = Path(__file__).resolve().parent
    candidates = [here / DLL_NAME, here / "unlock_provider" / "build" / "Release" / DLL_NAME]
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidates.insert(0, Path(meipass) / DLL_NAME)
    return next((p for p in candidates if p.is_file()), None)


def installed_dll():
    """Path of the registered provider DLL, or None when it is not set up."""
    if not IS_WINDOWS:
        return None
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, _PROVIDER_KEY, 0,
                            winreg.KEY_READ | winreg.KEY_WOW64_64KEY):
            pass
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, _CLSID_KEY + r"\InprocServer32", 0,
                            winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
            value, _ = winreg.QueryValueEx(key, "")
    except OSError:
        return None
    path = Path(os.path.expandvars(str(value)))
    return path if path.is_file() else None


def status():
    """unsupported, unavailable (no DLL shipped), not_installed, outdated or ready."""
    if not IS_WINDOWS:
        return "unsupported"
    installed = installed_dll()
    shipped = bundled_dll()
    if installed is None:
        return "not_installed" if shipped else "unavailable"
    if shipped:
        try:
            if installed.read_bytes() != shipped.read_bytes():
                return "outdated"
        except OSError:
            pass
    return "ready"


def _ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def _install_script(source):
    clsid = _ps_quote(rf"Registry::HKEY_LOCAL_MACHINE\{_CLSID_KEY}")
    provider = _ps_quote(rf"Registry::HKEY_LOCAL_MACHINE\{_PROVIDER_KEY}")
    # Program Files: only administrators may replace a DLL that LogonUI runs as SYSTEM.
    return f"""
$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:ProgramFiles 'Lanyard'
$dll = Join-Path $dir '{DLL_NAME}'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Get-ChildItem -LiteralPath $dir -Filter 'LanyardUnlock.old-*.dll' | Remove-Item -Force -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $dll) {{
  try {{ Remove-Item -LiteralPath $dll -Force }}
  catch {{ Rename-Item -LiteralPath $dll -NewName ('LanyardUnlock.old-' + [DateTime]::Now.Ticks + '.dll') -Force }}
}}
Copy-Item -LiteralPath {_ps_quote(source)} -Destination $dll -Force
New-Item -Path {clsid} -Force | Out-Null
Set-Item -Path {clsid} -Value 'Lanyard Unlock'
New-Item -Path ({clsid} + '\\InprocServer32') -Force | Out-Null
Set-Item -Path ({clsid} + '\\InprocServer32') -Value $dll
New-ItemProperty -Path ({clsid} + '\\InprocServer32') -Name ThreadingModel -Value 'Apartment' -PropertyType String -Force | Out-Null
New-Item -Path {provider} -Force | Out-Null
Set-Item -Path {provider} -Value 'Lanyard Unlock'
"""


def _uninstall_script():
    clsid = _ps_quote(rf"Registry::HKEY_LOCAL_MACHINE\{_CLSID_KEY}")
    provider = _ps_quote(rf"Registry::HKEY_LOCAL_MACHINE\{_PROVIDER_KEY}")
    return f"""
Remove-Item -Path {provider} -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -Path {clsid} -Recurse -Force -ErrorAction SilentlyContinue
$dll = Join-Path (Join-Path $env:ProgramFiles 'Lanyard') '{DLL_NAME}'
try {{ Remove-Item -LiteralPath $dll -Force -ErrorAction Stop }} catch {{ }}
"""


def _run_elevated(script):
    """Runs a PowerShell script as administrator (one UAC prompt). Returns (ok, detail)."""
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    outer = ("$p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
             f"-ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand {encoded}'; exit $p.ExitCode")
    try:
        done = subprocess.run(["powershell.exe", "-NoProfile", "-Command", outer], capture_output=True,
                              text=True, timeout=180, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except Exception as exc:
        return False, str(exc)
    if done.returncode == 0:
        return True, ""
    detail = (done.stderr or done.stdout or "").strip()
    if "cancel" in detail.lower():
        return False, "Windows asked for administrator permission and it was declined."
    return False, detail.splitlines()[0][:200] if detail else f"Setup exited with code {done.returncode}"


def install():
    if not IS_WINDOWS:
        return False, message("unsupported")
    source = bundled_dll()
    if source is None:
        return False, "This copy of Lanyard doesn't include the unlock component."
    ok, detail = _run_elevated(_install_script(source))
    if ok and installed_dll() is None:
        return False, "Setup finished but Windows didn't register the unlock component."
    return ok, detail


def uninstall():
    if not IS_WINDOWS:
        return False, message("unsupported")
    ok, detail = _run_elevated(_uninstall_script())
    if ok and installed_dll() is not None:
        return False, "Windows still has the unlock component registered."
    return ok, detail


# --- talking to the lock screen ----------------------------------------------
if IS_WINDOWS:
    _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _adv = ctypes.WinDLL("advapi32", use_last_error=True)

    _k32.CreateFileW.restype = wintypes.HANDLE
    _k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
                                 wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    _k32.WaitNamedPipeW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD]
    _k32.SetNamedPipeHandleState.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p,
                                             ctypes.c_void_p]
    _k32.WriteFile.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD),
                               ctypes.c_void_p]
    _k32.ReadFile.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD),
                              ctypes.c_void_p]
    _k32.CloseHandle.argtypes = [wintypes.HANDLE]
    _k32.GetCurrentProcess.restype = wintypes.HANDLE
    _k32.ProcessIdToSessionId.argtypes = [wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    _k32.WTSGetActiveConsoleSessionId.restype = wintypes.DWORD
    _k32.SetThreadExecutionState.restype = wintypes.DWORD
    _k32.SetThreadExecutionState.argtypes = [wintypes.DWORD]
    _k32.LocalFree.argtypes = [ctypes.c_void_p]
    _adv.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    _adv.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD,
                                         ctypes.POINTER(wintypes.DWORD)]
    _adv.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.LPWSTR)]
    _adv.GetSecurityInfo.restype = wintypes.DWORD
    _adv.GetSecurityInfo.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p),
                                     ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                                     ctypes.POINTER(ctypes.c_void_p)]

_INVALID_HANDLE = ctypes.c_void_p(-1).value
_GENERIC_READ_WRITE = 0x80000000 | 0x40000000
_OPEN_EXISTING = 3
_SQOS_IDENTIFICATION = 0x00100000 | 0x00010000  # SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION
_PIPE_READMODE_MESSAGE = 0x2
_ERROR_FILE_NOT_FOUND = 2
_ERROR_PIPE_BUSY = 231
_ES_DISPLAY_REQUIRED = 0x2


def _sid_text(sid_pointer):
    text = wintypes.LPWSTR()
    if not _adv.ConvertSidToStringSidW(sid_pointer, ctypes.byref(text)):
        return None
    try:
        return text.value
    finally:
        _k32.LocalFree(text)


def current_sid():
    """The SID of the Windows account this companion runs as."""
    token = wintypes.HANDLE()
    if not _adv.OpenProcessToken(_k32.GetCurrentProcess(), 0x0008, ctypes.byref(token)):  # TOKEN_QUERY
        return None
    try:
        buffer = ctypes.create_string_buffer(256)
        needed = wintypes.DWORD()
        if not _adv.GetTokenInformation(token, 1, buffer, len(buffer), ctypes.byref(needed)):  # TokenUser
            return None
        return _sid_text(ctypes.cast(buffer, ctypes.POINTER(ctypes.c_void_p))[0])
    finally:
        _k32.CloseHandle(token)


def _pipe_names():
    sessions = []
    own = wintypes.DWORD()
    if _k32.ProcessIdToSessionId(os.getpid(), ctypes.byref(own)):
        sessions.append(own.value)
    console = _k32.WTSGetActiveConsoleSessionId()
    if console != 0xFFFFFFFF and console not in sessions:
        sessions.append(console)
    return [rf"\\.\pipe\LanyardUnlock-{s}" for s in sessions]


def _pipe_owned_by_windows(handle):
    """Refuses a pipe some other program created first to catch the password."""
    owner = ctypes.c_void_p()
    descriptor = ctypes.c_void_p()
    if _adv.GetSecurityInfo(handle, 1, 0x1, ctypes.byref(owner), None, None, None, ctypes.byref(descriptor)) != 0:
        return False  # SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION
    try:
        return _sid_text(owner) in _TRUSTED_PIPE_OWNERS
    finally:
        _k32.LocalFree(descriptor)


def _open_pipe(timeout):
    deadline = time.monotonic() + timeout
    while True:
        for name in _pipe_names():
            handle = _k32.CreateFileW(name, _GENERIC_READ_WRITE, 0, None, _OPEN_EXISTING, _SQOS_IDENTIFICATION, None)
            if handle and handle != _INVALID_HANDLE:
                return handle
            if ctypes.get_last_error() == _ERROR_PIPE_BUSY:
                _k32.WaitNamedPipeW(name, 1000)
        if time.monotonic() >= deadline:
            return None
        # The lock screen loads sign-in options once it is awake; wake the display and wait.
        _k32.SetThreadExecutionState(_ES_DISPLAY_REQUIRED)
        time.sleep(0.4)


def unlock(password, connect_timeout=6.0):
    """Hands the password to the lock screen. Returns a result code from MESSAGES."""
    if not IS_WINDOWS:
        return "unsupported"
    if installed_dll() is None:
        return "not_installed"
    sid = current_sid()
    if not sid:
        return "wrong_user"
    handle = _open_pipe(connect_timeout)
    if handle is None:
        return "no_lock_screen"
    payload = ctypes.create_string_buffer((sid + "\0" + password + "\0").encode("utf-16-le"))
    try:
        if not _pipe_owned_by_windows(handle):
            return "no_lock_screen"
        mode = wintypes.DWORD(_PIPE_READMODE_MESSAGE)
        _k32.SetNamedPipeHandleState(handle, ctypes.byref(mode), None, None)
        written = wintypes.DWORD()
        size = len(payload) - 1  # create_string_buffer adds one NUL of its own
        if not _k32.WriteFile(handle, payload, size, ctypes.byref(written), None) or written.value != size:
            return "no_lock_screen"
        reply = ctypes.create_string_buffer(256)
        got = wintypes.DWORD()
        if not _k32.ReadFile(handle, reply, len(reply), ctypes.byref(got), None):
            return "submitted" if got.value == 0 else "timeout"
        word = reply.raw[:got.value].decode("utf-16-le", errors="ignore").rstrip("\0").strip()
        return word if word in MESSAGES else "timeout"
    finally:
        ctypes.memset(payload, 0, len(payload))
        _k32.CloseHandle(handle)
