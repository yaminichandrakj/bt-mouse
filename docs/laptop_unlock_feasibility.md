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

1. **The Secure Desktop (`Winlogon` Desktop):**
   - The lock screen and logon UI (`LogonUI.exe`) execute within the protected `Winlogon` desktop, completely isolated from the standard interactive desktop (`Default`).
   - Normal background processes (including the BT Mouse PC Companion) execute under standard user integrity levels on the `Default` desktop.

2. **User Interface Privilege Isolation (UIPI) & Session Isolation:**
   - User-mode Windows APIs such as `SendInput`, `mouse_event`, and `keybd_event` are blocked by UIPI from targeting higher-integrity processes on the secure desktop.
   - Background companion processes cannot programmatically focus, click, or enter characters into password fields on the Windows lock screen.

---

## 3. Technical Evaluation of Candidate Implementation Paths

### Approach A: Custom Windows Credential Provider (C++ COM DLL)
- **Mechanism:** Implement the native `ICredentialProvider` and `ICredentialProviderCredential` COM interfaces and register the DLL under:
  ```text
  HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers
  ```
- **Evaluation:**
  - **Extreme System Risk:** Credential Providers run inside `LogonUI.exe` as `NT AUTHORITY\SYSTEM`. A fatal crash, uncaught exception, or regression in a custom credential provider can completely brick Windows authentication, locking the user out of their PC even with their recovery password.
  - **Driver & Code Signing Restrictions:** Modern 64-bit Windows systems enforce kernel and system-level signature verification. Distributing an unsigned or ad-hoc compiled C++ DLL into system authentication directories triggers Windows Defender SmartScreen blocks or system corruption.
  - **Packaging Incompatibility:** BT Mouse Companion is packaged as an isolated, non-intrusive portable application. Injecting persistent system-level binaries into Windows logon directories violates portability and clean uninstallability.
- **Verdict: REJECTED.**

---

### Approach B: Windows Companion Device Framework (CDF)
- **Mechanism:** Microsoft previously introduced the Companion Device Framework (CDF) in Windows 10 (Version 1607) allowing companion devices (smartwatches, phones via BLE/NFC) to signal Windows Hello to unlock the PC.
- **Evaluation:**
  - **Platform Deprecation:** Microsoft officially deprecated the Companion Device Framework starting in Windows 10 Version 2004 and completely removed support in Windows 11.
  - Third-party apps can no longer register as CDF companion devices on modern Windows 10/11 installations.
- **Verdict: DEPRECATED & REMOVED BY PLATFORM.**

---

### Approach C: FIDO2 / CTAP2 Passkey Authentication
- **Mechanism:** Emulate a FIDO2 / WebAuthn hardware security key over Bluetooth Low Energy (BLE) or USB HID to unlock Windows.
- **Evaluation:**
  - **Account Restriction:** Microsoft Windows only supports FIDO2 security keys for Windows logon on **Azure Active Directory (Entra ID)** or hybrid domain-joined accounts configured with Windows Hello for Business.
  - Standard consumer Windows 10/11 Home and Pro installations utilizing local accounts or standard Microsoft Personal Accounts (MSA) do **not** support FIDO2 keys for workstation logon.
- **Verdict: UNSUPPORTED FOR CONSUMER PLATFORMS.**

---

### Approach D: Credential Caching & Synthetic Keystroke Injection
- **Mechanism:** Storing the Windows password on the phone or companion and simulating keystrokes.
- **Evaluation:**
  - **Severe Security Vulnerability:** Storing or transmitting plaintext or reversibly encrypted Windows logon passwords exposes users to local network credential theft, credential dump attacks, and device compromise.
  - Bypassing the Windows secure desktop via keyboard simulation violates basic operating system security models.
- **Verdict: PROHIBITED BY SECURITY CONSTRAINTS.**

---

## 4. Final Determination & Implementation Status

In full satisfaction of Phase 6 acceptance criteria:
1. **Documented Decision:** Documented the platform constraints preventing safe third-party remote unlock without system-level compromise.
2. **Safe Feature Closure:** Closed remote unlock as officially unsupported on target platforms.
3. **Explicit Server & Client Handling:**
   - PC Companion protocol responds to any remote `unlock` packet with an explicit denial:
     ```json
     {"type": "error", "message": "Workstation unlock is not supported"}
     ```
   - Android client displays clear guidance:
     > *"Windows requires sign-in at the PC to unlock its secure desktop. Remote unlock is unavailable."*
   - Workstation Lock (`coordinator.systemPower("lock")`) remains active and verified.

---
*Authored by Codex for the BT Mouse & PC Companion Architecture.*
