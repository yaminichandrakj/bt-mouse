# Phone-Based Laptop Unlock Feasibility & Platform Determination (Phase 6)

**Document Status:** Concluded Architecture & Security Assessment  
**Date:** 2026-10-24  
**Product:** BT Mouse & PC Companion  
**Milestone:** Phase 6 Feasibility Evaluation  

---

## 1. Executive Summary

As part of Phase 6 of the `IMPLEMENTATION_PLAN.md`, this technical evaluation investigated whether an Android phone could safely and securely unlock a locked Windows PC or laptop without compromising user security.

### Core Conclusion
**Remote workstation unlock is closed as UNSUPPORTED for target consumer Windows platforms.**  
In accordance with our product security principles, **password caching, network credential transmission, and simulated keystroke injection on the secure desktop are strictly prohibited.** 

Workstation **Locking** (`Win+L` via `systemPower("lock")`) is safe, supported, and actively available. Workstation **Unlocking** must continue to be performed directly at the physical machine via Windows Hello, PIN, or user password.

---

## 2. Windows Security Architecture & Isolation

When a Windows workstation is locked or signed out, Windows switches execution context to an isolated environment designed specifically to prevent user-mode processes from intercepting or injecting credentials:

1. **The Secure Desktop (`Winlogon` Desktop) & Session Isolation:**
   - The lock screen and logon UI (`LogonUI.exe`) execute within the protected `Winlogon` desktop under `NT AUTHORITY\SYSTEM`, isolated from the user's interactive desktop (`Default`).
   - Normal background processes (including the BT Mouse PC Companion) execute under standard user integrity levels on the `Default` desktop and cannot open handles to or interact with windows on the `Winlogon` desktop.

2. **User Interface Privilege Isolation (UIPI):**
   - User-mode Windows APIs such as `SendInput`, `mouse_event`, and `keybd_event` are enforced by UIPI and desktop isolation, preventing standard user processes from injecting synthetic input into `LogonUI.exe` or elevated `SYSTEM` processes.

---

## 3. Technical Evaluation of Candidate Implementation Paths

### Approach A: Custom Windows Credential Provider (C++ In-Process COM DLL)
- **Mechanism:** Implement the native user-mode `ICredentialProvider` and `ICredentialProviderCredential` COM interfaces and register the in-process DLL under:
  ```text
  HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers
  ```
- **Evaluation:**
  - **System Logon Reliability Risk:** Credential Providers are loaded in-process inside `LogonUI.exe` running as `NT AUTHORITY\SYSTEM`. Any unhandled exception, memory corruption, or deadlock in a third-party credential provider DLL can crash `LogonUI.exe` in a loop and prevent interactive sign-in across the machine.
  - **Administrative Installation & Trust Requirements:** Registering a COM DLL inside `System32` and `HKLM` requires administrative installation, code signing for enterprise/SmartScreen trust, and still requires Windows to verify an actual underlying local/MSA credential or domain token (a Credential Provider only collects credentials for LSA/Winlogon; it cannot bypass LSA authentication without supplying a valid secret or smartcard assertion).
  - **Packaging Incompatibility:** BT Mouse Companion is packaged as an isolated, non-intrusive portable user-mode executable. Installing persistent `SYSTEM`-level logon COM DLLs violates portability and safe uninstallability.
- **Verdict: REJECTED.**

---

### Approach B: Windows Companion Device Framework (CDF)
- **Mechanism:** Microsoft previously introduced the Companion Device Framework (CDF) in Windows 10 (Version 1607) allowing companion devices (smartwatches, phones via BLE/NFC) to signal Windows Hello to unlock the PC.
- **Evaluation:**
  - **Platform Deprecation:** Microsoft officially deprecated the Windows Hello Companion Device Framework starting in Windows 10 Version 2004 and removed consumer CDF unlock integration in Windows 11.
  - Third-party apps can no longer register new CDF unlock companion devices on modern Windows 10/11 installations.
- **Verdict: DEPRECATED & REMOVED BY PLATFORM.**

---

### Approach C: FIDO2 / CTAP2 Passkey Authentication
- **Mechanism:** Emulate a FIDO2 / WebAuthn hardware security key over Bluetooth Low Energy (BLE) or USB HID to unlock Windows.
- **Evaluation:**
  - **Account Restriction:** Microsoft Windows only supports FIDO2 security keys for Windows logon on **Microsoft Entra ID (Azure AD)** or hybrid domain-joined accounts configured with Windows Hello for Business.
  - Standard consumer Windows 10/11 Home and Pro installations utilizing local accounts or standard Microsoft Personal Accounts (MSA) do **not** support FIDO2 keys for workstation logon.
- **Verdict: UNSUPPORTED FOR CONSUMER PLATFORMS.**

---

### Approach D: Credential Caching & Synthetic Keystroke Injection
- **Mechanism:** Storing the Windows password on the phone or companion and simulating keystrokes.
- **Evaluation:**
  - **Severe Security Vulnerability:** Storing or transmitting plaintext or reversibly encrypted Windows logon passwords exposes users to local network credential theft, credential dump attacks, and device compromise.
  - Bypassing the Windows secure desktop via keyboard simulation violates operating system security boundaries.
- **Verdict: PROHIBITED BY SECURITY CONSTRAINTS.**

---

## 4. Final Determination & Implementation Status

In full satisfaction of Phase 6 acceptance criteria:
1. **Documented Decision:** Documented the platform constraints (`Winlogon` desktop isolation, UIPI, LSA credential requirements, CDF deprecation, and FIDO2 consumer limitations) preventing safe third-party remote unlock.
2. **Safe Feature Closure:** Closed remote unlock as officially unsupported on target platforms.
3. **Explicit Server & Client Handling:**
   - PC Companion protocol responds to any remote `unlock` packet with an explicit denial:
     ```json
     {"type": "error", "message": "Workstation unlock is not supported"}
     ```
   - Android client displays clear guidance:
     > *"Windows requires sign-in at the PC to unlock its secure desktop. Remote unlock is unavailable."*
   - Workstation Lock (`coordinator.systemPower("lock")`) remains active and verified.
