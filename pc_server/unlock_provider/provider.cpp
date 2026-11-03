// Lanyard Unlock: a Windows credential provider that lets the paired phone unlock this PC.
//
// LogonUI loads this DLL while the PC shows the lock screen. The provider listens on a
// local named pipe (\\.\pipe\LanyardUnlock-<session>). The Lanyard companion, still running
// in the locked user's session, writes the password the phone sent. The provider checks the
// caller really is that user, then asks Windows to sign in with it exactly as if it had been
// typed. Windows checks the password; this DLL never stores it and forgets it right away.
//
// Pipe request : UTF-16 "<user SID>\0<password>\0"
// Pipe reply   : UTF-16 result word: ok, wrong_password, locked_out, account_problem,
//                submitted, timeout, unknown_user, wrong_user, not_ready, bad_request

#include <windows.h>
#include <initguid.h>
#include <strsafe.h>
#include <propkey.h>
#include <credentialprovider.h>
#include <ntsecapi.h>
#define SECURITY_WIN32
#include <security.h>
#include <sddl.h>
#include <wincred.h>
#include <shlwapi.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

namespace {

// {397A4CD3-F354-469B-86E9-83E9C6786325}
const CLSID CLSID_LanyardUnlock = {0x397a4cd3, 0xf354, 0x469b, {0x86, 0xe9, 0x83, 0xe9, 0xc6, 0x78, 0x63, 0x25}};

HINSTANCE g_module = nullptr;
std::atomic<long> g_objects{0};

// NTSTATUS values ReportResult may hand back.
constexpr NTSTATUS kLogonFailure = static_cast<NTSTATUS>(0xC000006DL);
constexpr NTSTATUS kAccountLockedOut = static_cast<NTSTATUS>(0xC0000234L);

constexpr DWORD kRequestTimeoutMs = 5000;
constexpr auto kResultWait = std::chrono::seconds(20);

enum Field : DWORD { FieldTile, FieldLabel, FieldStatus, FieldCount };

// CPFG_CREDENTIAL_PROVIDER_LOGO / _LABEL from credentialprovider.h (Windows 8+), spelled out
// because the SDK on the build machine hides them behind its version macros.
const GUID kProviderLogoField = {0x2d837775, 0xf6cd, 0x464e, {0xa7, 0x45, 0x48, 0x2f, 0xd0, 0xb4, 0x74, 0x93}};
const GUID kProviderLabelField = {0x286bbff3, 0xbad4, 0x438f, {0xb0, 0x07, 0x79, 0xb7, 0x26, 0x7c, 0x3d, 0x48}};

const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFields[FieldCount] = {
    {FieldTile, CPFT_TILE_IMAGE, const_cast<LPWSTR>(L"Lanyard"), kProviderLogoField},
    {FieldLabel, CPFT_LARGE_TEXT, const_cast<LPWSTR>(L"Lanyard"), kProviderLabelField},
    {FieldStatus, CPFT_SMALL_TEXT, const_cast<LPWSTR>(L"Status"), {0}},
};

const wchar_t kStatusText[] = L"Tap Unlock in Lanyard on your phone.";

void Wipe(std::wstring& s) {
    if (!s.empty()) SecureZeroMemory(&s[0], s.size() * sizeof(wchar_t));
    s.clear();
}

// What the pipe thread and the credentials share: the one password waiting to be used.
struct Bridge {
    std::mutex m;
    std::condition_variable cv;
    std::wstring sid;       // who the waiting password belongs to
    std::wstring password;  // empty when nothing is waiting
    bool submitted = false; // handed to Windows by GetSerialization
    bool done = false;
    std::wstring result;

    void Finish(const wchar_t* word) {
        {
            std::lock_guard<std::mutex> lock(m);
            if (!done) {
                done = true;
                result = word;
            }
            Wipe(password);
        }
        cv.notify_all();
    }
};

HRESULT LookupNegotiate(ULONG* package) {
    HANDLE lsa = nullptr;
    NTSTATUS status = LsaConnectUntrusted(&lsa);
    if (status != 0) return HRESULT_FROM_NT(status);
    LSA_STRING name;
    name.Buffer = const_cast<PCHAR>(NEGOSSP_NAME_A);
    name.Length = static_cast<USHORT>(strlen(name.Buffer));
    name.MaximumLength = name.Length + 1;
    status = LsaLookupAuthenticationPackage(lsa, &name, package);
    LsaDeregisterLogonProcess(lsa);
    return status == 0 ? S_OK : HRESULT_FROM_NT(status);
}

// LogonUI expects the password CredProtect-ed, as its own password box does.
HRESULT ProtectPassword(const std::wstring& plain, std::wstring& out) {
    if (plain.empty()) {
        out.clear();
        return S_OK;
    }
    std::vector<wchar_t> input(plain.begin(), plain.end());
    input.push_back(L'\0');
    CRED_PROTECTION_TYPE kind;
    if (CredIsProtectedW(input.data(), &kind) && kind != CredUnprotected) {
        out = plain;
        SecureZeroMemory(input.data(), input.size() * sizeof(wchar_t));
        return S_OK;
    }
    DWORD cch = 0;
    CredProtectW(FALSE, input.data(), static_cast<DWORD>(input.size()), nullptr, &cch, nullptr);
    if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || cch == 0) {
        SecureZeroMemory(input.data(), input.size() * sizeof(wchar_t));
        return HRESULT_FROM_WIN32(GetLastError());
    }
    std::vector<wchar_t> sealed(cch);
    BOOL ok = CredProtectW(FALSE, input.data(), static_cast<DWORD>(input.size()), sealed.data(), &cch, nullptr);
    HRESULT hr = ok ? S_OK : HRESULT_FROM_WIN32(GetLastError());
    if (ok) out.assign(sealed.data());
    SecureZeroMemory(input.data(), input.size() * sizeof(wchar_t));
    SecureZeroMemory(sealed.data(), sealed.size() * sizeof(wchar_t));
    return hr;
}

// A local or domain account: KERB_INTERACTIVE_UNLOCK_LOGON with offsets instead of pointers.
HRESULT PackKerberos(bool unlock, const std::wstring& domain, const std::wstring& user,
                     const std::wstring& password, BYTE** out, DWORD* size) {
    const size_t bytes = sizeof(KERB_INTERACTIVE_UNLOCK_LOGON) +
                         (domain.size() + user.size() + password.size()) * sizeof(wchar_t);
    if (bytes > MAXDWORD) return E_INVALIDARG;
    BYTE* buffer = static_cast<BYTE*>(CoTaskMemAlloc(bytes));
    if (!buffer) return E_OUTOFMEMORY;
    ZeroMemory(buffer, bytes);
    auto* logon = reinterpret_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(buffer);
    KERB_INTERACTIVE_LOGON* kil = &logon->Logon;
    kil->MessageType = unlock ? KerbWorkstationUnlockLogon : KerbInteractiveLogon;
    BYTE* cursor = buffer + sizeof(KERB_INTERACTIVE_UNLOCK_LOGON);
    auto put = [&](UNICODE_STRING& field, const std::wstring& value) {
        field.Length = static_cast<USHORT>(value.size() * sizeof(wchar_t));
        field.MaximumLength = field.Length;
        field.Buffer = reinterpret_cast<PWSTR>(static_cast<ULONG_PTR>(cursor - buffer));
        if (field.Length) memcpy(cursor, value.data(), field.Length);
        cursor += field.Length;
    };
    put(kil->LogonDomainName, domain);
    put(kil->UserName, user);
    put(kil->Password, password);
    *out = buffer;
    *size = static_cast<DWORD>(bytes);
    return S_OK;
}

// A Microsoft or work account: let Windows pack the qualified name itself.
HRESULT PackIdentity(const std::wstring& qualified, const std::wstring& password, BYTE** out, DWORD* size) {
    std::vector<wchar_t> name(qualified.begin(), qualified.end());
    name.push_back(L'\0');
    std::vector<wchar_t> secret(password.begin(), password.end());
    secret.push_back(L'\0');
    DWORD bytes = 0;
    CredPackAuthenticationBufferW(0, name.data(), secret.data(), nullptr, &bytes);
    HRESULT hr = E_FAIL;
    if (GetLastError() == ERROR_INSUFFICIENT_BUFFER && bytes) {
        BYTE* buffer = static_cast<BYTE*>(CoTaskMemAlloc(bytes));
        if (!buffer) {
            hr = E_OUTOFMEMORY;
        } else if (CredPackAuthenticationBufferW(0, name.data(), secret.data(), buffer, &bytes)) {
            *out = buffer;
            *size = bytes;
            hr = S_OK;
        } else {
            hr = HRESULT_FROM_WIN32(GetLastError());
            SecureZeroMemory(buffer, bytes);
            CoTaskMemFree(buffer);
        }
    } else {
        hr = HRESULT_FROM_WIN32(GetLastError());
    }
    SecureZeroMemory(secret.data(), secret.size() * sizeof(wchar_t));
    return hr;
}

// The sign-in option icon: a rounded teal tile with a white "L".
HBITMAP MakeTileBitmap() {
    const int n = 128;
    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = n;
    info.bmiHeader.biHeight = -n;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void* bits = nullptr;
    HDC dc = CreateCompatibleDC(nullptr);
    if (!dc) return nullptr;
    HBITMAP bitmap = CreateDIBSection(dc, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (!bitmap) {
        DeleteDC(dc);
        return nullptr;
    }
    HGDIOBJ old = SelectObject(dc, bitmap);
    RECT all = {0, 0, n, n};
    HBRUSH bg = CreateSolidBrush(RGB(0x1F, 0x8A, 0x78));
    FillRect(dc, &all, bg);
    DeleteObject(bg);
    HFONT font = CreateFontW(84, 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                             CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
    HGDIOBJ oldFont = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, RGB(0xFF, 0xFF, 0xFF));
    DrawTextW(dc, L"L", 1, &all, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(dc, oldFont);
    DeleteObject(font);
    SelectObject(dc, old);
    DeleteDC(dc);
    // GDI leaves alpha at zero; make every pixel opaque.
    auto* px = static_cast<DWORD*>(bits);
    for (int i = 0; i < n * n; ++i) px[i] |= 0xFF000000;
    return bitmap;
}

bool SameText(const std::wstring& a, const std::wstring& b) {
    return _wcsicmp(a.c_str(), b.c_str()) == 0;
}

// ---------------------------------------------------------------------------
class Credential : public ICredentialProviderCredential2 {
public:
    Credential(std::shared_ptr<Bridge> bridge, CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario)
        : bridge_(std::move(bridge)), scenario_(scenario) {
        ++g_objects;
    }

    HRESULT Init(ICredentialProviderUser* user) {
        PWSTR sid = nullptr;
        HRESULT hr = user->GetSid(&sid);
        if (FAILED(hr)) return hr;
        sid_ = sid;
        CoTaskMemFree(sid);
        PWSTR qualified = nullptr;
        hr = user->GetStringValue(PKEY_Identity_QualifiedUserName, &qualified);
        if (FAILED(hr)) return hr;
        qualified_ = qualified;
        CoTaskMemFree(qualified);
        return S_OK;
    }

    const std::wstring& Sid() const { return sid_; }

    // IUnknown
    IFACEMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&refs_); }
    IFACEMETHODIMP_(ULONG) Release() override {
        LONG left = InterlockedDecrement(&refs_);
        if (!left) delete this;
        return left;
    }
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == __uuidof(IUnknown) || riid == __uuidof(ICredentialProviderCredential) ||
            riid == __uuidof(ICredentialProviderCredential2)) {
            *ppv = static_cast<ICredentialProviderCredential2*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    // ICredentialProviderCredential
    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents*) override { return S_OK; }
    IFACEMETHODIMP UnAdvise() override { return S_OK; }

    IFACEMETHODIMP SetSelected(BOOL* autoLogon) override {
        std::lock_guard<std::mutex> lock(bridge_->m);
        *autoLogon = (!bridge_->password.empty() && SameText(bridge_->sid, sid_)) ? TRUE : FALSE;
        return S_OK;
    }
    IFACEMETHODIMP SetDeselected() override { return S_OK; }

    IFACEMETHODIMP GetFieldState(DWORD field, CREDENTIAL_PROVIDER_FIELD_STATE* state,
                                 CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* interactive) override {
        if (field >= FieldCount || !state || !interactive) return E_INVALIDARG;
        *state = field == FieldTile ? CPFS_DISPLAY_IN_BOTH
               : field == FieldLabel ? CPFS_HIDDEN
               : CPFS_DISPLAY_IN_SELECTED_TILE;
        *interactive = CPFIS_NONE;
        return S_OK;
    }

    IFACEMETHODIMP GetStringValue(DWORD field, PWSTR* value) override {
        if (!value) return E_INVALIDARG;
        if (field == FieldLabel) return SHStrDupW(L"Lanyard", value);
        if (field == FieldStatus) return SHStrDupW(kStatusText, value);
        return E_INVALIDARG;
    }

    IFACEMETHODIMP GetBitmapValue(DWORD field, HBITMAP* bitmap) override {
        if (field != FieldTile || !bitmap) return E_INVALIDARG;
        *bitmap = MakeTileBitmap();
        return *bitmap ? S_OK : HRESULT_FROM_WIN32(GetLastError());
    }

    IFACEMETHODIMP GetCheckboxValue(DWORD, BOOL*, PWSTR*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetSubmitButtonValue(DWORD, DWORD*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetComboBoxValueCount(DWORD, DWORD*, DWORD*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetComboBoxValueAt(DWORD, DWORD, PWSTR*) override { return E_NOTIMPL; }
    IFACEMETHODIMP SetStringValue(DWORD, PCWSTR) override { return E_NOTIMPL; }
    IFACEMETHODIMP SetCheckboxValue(DWORD, BOOL) override { return E_NOTIMPL; }
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD, DWORD) override { return E_NOTIMPL; }
    IFACEMETHODIMP CommandLinkClicked(DWORD) override { return E_NOTIMPL; }

    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
                                    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization,
                                    PWSTR* statusText, CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon) override {
        *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
        *statusText = nullptr;
        *statusIcon = CPSI_NONE;
        ZeroMemory(serialization, sizeof(*serialization));

        std::wstring password;
        {
            std::lock_guard<std::mutex> lock(bridge_->m);
            if (bridge_->password.empty() || !SameText(bridge_->sid, sid_)) {
                SHStrDupW(kStatusText, statusText);
                return S_OK;
            }
            password.swap(bridge_->password);
            bridge_->submitted = true;
        }

        std::wstring sealed;
        HRESULT hr = ProtectPassword(password, sealed);
        Wipe(password);
        ULONG package = 0;
        if (SUCCEEDED(hr)) hr = LookupNegotiate(&package);
        if (SUCCEEDED(hr)) {
            std::wstring domain, user = qualified_;
            const size_t slash = qualified_.find(L'\\');
            if (slash != std::wstring::npos) {
                domain = qualified_.substr(0, slash);
                user = qualified_.substr(slash + 1);
            }
            if (domain.empty() || domain == L".") {
                wchar_t computer[MAX_COMPUTERNAME_LENGTH + 1] = {};
                DWORD cch = ARRAYSIZE(computer);
                GetComputerNameW(computer, &cch);
                domain = computer;
            }
            // Microsoft and work accounts sign in by their qualified name; local and domain
            // accounts take the Kerberos unlock structure.
            if (SameText(domain, L"MicrosoftAccount") || SameText(domain, L"AzureAD")) {
                hr = PackIdentity(qualified_, sealed, &serialization->rgbSerialization,
                                  &serialization->cbSerialization);
            } else {
                hr = PackKerberos(scenario_ == CPUS_UNLOCK_WORKSTATION, domain, user, sealed,
                                  &serialization->rgbSerialization, &serialization->cbSerialization);
            }
        }
        Wipe(sealed);
        if (FAILED(hr)) {
            bridge_->Finish(L"not_ready");
            return hr;
        }
        serialization->ulAuthenticationPackage = package;
        serialization->clsidCredentialProvider = CLSID_LanyardUnlock;
        *response = CPGSR_RETURN_CREDENTIAL_FINISHED;
        return S_OK;
    }

    IFACEMETHODIMP ReportResult(NTSTATUS status, NTSTATUS, PWSTR* statusText,
                                CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon) override {
        *statusText = nullptr;
        *statusIcon = CPSI_NONE;
        bool mine;
        {
            std::lock_guard<std::mutex> lock(bridge_->m);
            mine = bridge_->submitted && !bridge_->done && SameText(bridge_->sid, sid_);
        }
        if (!mine) return S_OK;
        if (status == 0) {
            bridge_->Finish(L"ok");
            return S_OK;
        }
        if (status == kLogonFailure) {
            bridge_->Finish(L"wrong_password");
            SHStrDupW(L"Lanyard: the password saved on your phone was not accepted.", statusText);
        } else if (status == kAccountLockedOut) {
            bridge_->Finish(L"locked_out");
        } else {
            bridge_->Finish(L"account_problem");
        }
        *statusIcon = CPSI_ERROR;
        return S_OK;
    }

    // ICredentialProviderCredential2
    IFACEMETHODIMP GetUserSid(PWSTR* sid) override { return SHStrDupW(sid_.c_str(), sid); }

private:
    ~Credential() { --g_objects; }

    LONG refs_ = 1;
    std::shared_ptr<Bridge> bridge_;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario_;
    std::wstring sid_;
    std::wstring qualified_;
};

// ---------------------------------------------------------------------------
class Provider : public ICredentialProvider, public ICredentialProviderSetUserArray {
public:
    Provider() : bridge_(std::make_shared<Bridge>()) {
        ++g_objects;
        stopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    }

    // IUnknown
    IFACEMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&refs_); }
    IFACEMETHODIMP_(ULONG) Release() override {
        LONG left = InterlockedDecrement(&refs_);
        if (!left) delete this;
        return left;
    }
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == __uuidof(IUnknown) || riid == __uuidof(ICredentialProvider)) {
            *ppv = static_cast<ICredentialProvider*>(this);
        } else if (riid == __uuidof(ICredentialProviderSetUserArray)) {
            *ppv = static_cast<ICredentialProviderSetUserArray*>(this);
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }

    // ICredentialProvider
    IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario, DWORD) override {
        if (scenario != CPUS_UNLOCK_WORKSTATION && scenario != CPUS_LOGON) return E_NOTIMPL;
        scenario_ = scenario;
        return S_OK;
    }
    IFACEMETHODIMP SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) override { return E_NOTIMPL; }

    IFACEMETHODIMP Advise(ICredentialProviderEvents* events, UINT_PTR context) override {
        {
            std::lock_guard<std::mutex> lock(eventsLock_);
            if (events_) events_->Release();
            events_ = events;
            if (events_) events_->AddRef();
            context_ = context;
        }
        StartPipe();
        return S_OK;
    }

    IFACEMETHODIMP UnAdvise() override {
        StopPipe();
        std::lock_guard<std::mutex> lock(eventsLock_);
        if (events_) events_->Release();
        events_ = nullptr;
        return S_OK;
    }

    IFACEMETHODIMP GetFieldDescriptorCount(DWORD* count) override {
        *count = FieldCount;
        return S_OK;
    }

    IFACEMETHODIMP GetFieldDescriptorAt(DWORD index, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** out) override {
        if (index >= FieldCount || !out) return E_INVALIDARG;
        auto* copy = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(CoTaskMemAlloc(sizeof(**out)));
        if (!copy) return E_OUTOFMEMORY;
        *copy = kFields[index];
        HRESULT hr = SHStrDupW(kFields[index].pszLabel, &copy->pszLabel);
        if (FAILED(hr)) {
            CoTaskMemFree(copy);
            return hr;
        }
        *out = copy;
        return S_OK;
    }

    IFACEMETHODIMP GetCredentialCount(DWORD* count, DWORD* defaultIndex, BOOL* autoLogon) override {
        std::lock_guard<std::mutex> users(usersLock_);
        *count = static_cast<DWORD>(credentials_.size());
        *defaultIndex = CREDENTIAL_PROVIDER_NO_DEFAULT;
        *autoLogon = FALSE;
        std::lock_guard<std::mutex> lock(bridge_->m);
        if (bridge_->password.empty()) return S_OK;
        for (size_t i = 0; i < credentials_.size(); ++i) {
            if (SameText(credentials_[i]->Sid(), bridge_->sid)) {
                *defaultIndex = static_cast<DWORD>(i);
                *autoLogon = TRUE;
                break;
            }
        }
        return S_OK;
    }

    IFACEMETHODIMP GetCredentialAt(DWORD index, ICredentialProviderCredential** out) override {
        std::lock_guard<std::mutex> users(usersLock_);
        if (!out || index >= credentials_.size()) return E_INVALIDARG;
        return credentials_[index]->QueryInterface(IID_PPV_ARGS(out));
    }

    // ICredentialProviderSetUserArray
    IFACEMETHODIMP SetUserArray(ICredentialProviderUserArray* users) override {
        std::lock_guard<std::mutex> lock(usersLock_);
        ReleaseCredentials();
        DWORD count = 0;
        if (!users || FAILED(users->GetCount(&count))) return S_OK;
        for (DWORD i = 0; i < count; ++i) {
            ICredentialProviderUser* user = nullptr;
            if (FAILED(users->GetAt(i, &user))) continue;
            auto* credential = new (std::nothrow) Credential(bridge_, scenario_);
            if (credential && SUCCEEDED(credential->Init(user))) {
                credentials_.push_back(credential);
            } else if (credential) {
                credential->Release();
            }
            user->Release();
        }
        return S_OK;
    }

private:
    ~Provider() {
        StopPipe();
        if (events_) events_->Release();
        ReleaseCredentials();
        if (stopEvent_) CloseHandle(stopEvent_);
        --g_objects;
    }

    void ReleaseCredentials() {
        for (auto* c : credentials_) c->Release();
        credentials_.clear();
    }

    bool KnowsUser(const std::wstring& sid) {
        std::lock_guard<std::mutex> lock(usersLock_);
        for (auto* c : credentials_) {
            if (SameText(c->Sid(), sid)) return true;
        }
        return false;
    }

    void StartPipe() {
        if (thread_ || !stopEvent_) return;
        ResetEvent(stopEvent_);
        stopping_ = false;
        AddRef();  // the thread holds the provider
        thread_ = CreateThread(nullptr, 0, &Provider::PipeMain, this, 0, nullptr);
        if (!thread_) Release();
    }

    void StopPipe() {
        if (!thread_) return;
        stopping_ = true;
        SetEvent(stopEvent_);
        bridge_->cv.notify_all();
        if (GetCurrentThreadId() != GetThreadId(thread_)) WaitForSingleObject(thread_, 10000);
        CloseHandle(thread_);
        thread_ = nullptr;
    }

    static DWORD WINAPI PipeMain(LPVOID param) {
        auto* self = static_cast<Provider*>(param);
        self->PipeLoop();
        self->Release();
        return 0;
    }

    // Waits for an overlapped call to finish, the stop event, or the timeout.
    bool Await(HANDLE pipe, OVERLAPPED* ov, BOOL started, DWORD timeoutMs, DWORD* bytes) {
        if (!started && GetLastError() != ERROR_IO_PENDING) return false;
        HANDLE waits[2] = {ov->hEvent, stopEvent_};
        DWORD hit = WaitForMultipleObjects(2, waits, FALSE, timeoutMs);
        if (hit != WAIT_OBJECT_0) {
            CancelIoEx(pipe, ov);
            GetOverlappedResult(pipe, ov, bytes, TRUE);
            return false;
        }
        return GetOverlappedResult(pipe, ov, bytes, FALSE) != FALSE;
    }

    void PipeLoop() {
        DWORD session = 0;
        ProcessIdToSessionId(GetCurrentProcessId(), &session);
        wchar_t name[96];
        StringCchPrintfW(name, ARRAYSIZE(name), L"\\\\.\\pipe\\LanyardUnlock-%lu", session);

        // SYSTEM and interactively signed-in users may connect; nobody over the network.
        PSECURITY_DESCRIPTOR sd = nullptr;
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;;GA;;;SY)(A;;GRGW;;;IU)", SDDL_REVISION_1,
                                                                  &sd, nullptr)) {
            return;
        }
        SECURITY_ATTRIBUTES sa = {sizeof(sa), sd, FALSE};
        HANDLE io = CreateEventW(nullptr, TRUE, FALSE, nullptr);

        while (io && !stopping_) {
            HANDLE pipe = CreateNamedPipeW(name, PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED,
                                           PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT |
                                               PIPE_REJECT_REMOTE_CLIENTS,
                                           1, 4096, 4096, 0, &sa);
            if (pipe == INVALID_HANDLE_VALUE) {
                WaitForSingleObject(stopEvent_, 1000);
                continue;
            }
            OVERLAPPED ov = {};
            ov.hEvent = io;
            ResetEvent(io);
            DWORD bytes = 0;
            BOOL started = ConnectNamedPipe(pipe, &ov);
            bool connected = (!started && GetLastError() == ERROR_PIPE_CONNECTED) ||
                             Await(pipe, &ov, started, INFINITE, &bytes);
            if (connected && !stopping_) Serve(pipe, io);
            DisconnectNamedPipe(pipe);
            CloseHandle(pipe);
        }
        if (io) CloseHandle(io);
        LocalFree(sd);
    }

    void Serve(HANDLE pipe, HANDLE io) {
        wchar_t buffer[1024] = {};
        OVERLAPPED ov = {};
        ov.hEvent = io;
        ResetEvent(io);
        DWORD bytes = 0;
        BOOL started = ReadFile(pipe, buffer, sizeof(buffer) - sizeof(wchar_t), nullptr, &ov);
        const wchar_t* reply = L"bad_request";
        std::wstring result;
        if (Await(pipe, &ov, started, kRequestTimeoutMs, &bytes) && bytes >= 4) {
            const size_t chars = bytes / sizeof(wchar_t);
            const size_t sidLen = wcsnlen(buffer, chars);
            if (sidLen > 0 && sidLen + 1 < chars) {
                std::wstring sid(buffer, sidLen);
                std::wstring password(buffer + sidLen + 1, wcsnlen(buffer + sidLen + 1, chars - sidLen - 1));
                result = Handle(pipe, sid, password);
                reply = result.c_str();
                Wipe(password);
            }
        }
        SecureZeroMemory(buffer, sizeof(buffer));
        ResetEvent(io);
        ov = {};
        ov.hEvent = io;
        const DWORD size = static_cast<DWORD>((wcslen(reply) + 1) * sizeof(wchar_t));
        started = WriteFile(pipe, reply, size, nullptr, &ov);
        if (Await(pipe, &ov, started, 2000, &bytes)) FlushFileBuffers(pipe);
    }

    std::wstring Handle(HANDLE pipe, const std::wstring& sid, std::wstring& password) {
        if (!CallerIs(pipe, sid)) return L"wrong_user";
        if (!KnowsUser(sid)) return L"unknown_user";
        ICredentialProviderEvents* events = nullptr;
        UINT_PTR context = 0;
        {
            std::lock_guard<std::mutex> lock(eventsLock_);
            events = events_;
            if (events) events->AddRef();
            context = context_;
        }
        if (!events) return L"not_ready";
        {
            std::lock_guard<std::mutex> lock(bridge_->m);
            bridge_->sid = sid;
            bridge_->password.swap(password);
            bridge_->submitted = false;
            bridge_->done = false;
            bridge_->result.clear();
        }
        // LogonUI asks GetCredentialCount again, sees the auto sign-in and calls GetSerialization.
        events->CredentialsChanged(context);
        events->Release();

        std::unique_lock<std::mutex> lock(bridge_->m);
        const auto deadline = std::chrono::steady_clock::now() + kResultWait;
        while (!bridge_->done && !stopping_ && std::chrono::steady_clock::now() < deadline) {
            bridge_->cv.wait_for(lock, std::chrono::milliseconds(250));
        }
        std::wstring result = bridge_->done ? bridge_->result : bridge_->submitted ? L"submitted" : L"timeout";
        bridge_->done = true;
        Wipe(bridge_->password);
        return result;
    }

    // Only the locked user's own processes may hand over that user's password.
    static bool CallerIs(HANDLE pipe, const std::wstring& sidText) {
        if (!ImpersonateNamedPipeClient(pipe)) return false;
        HANDLE token = nullptr;
        BOOL opened = OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, TRUE, &token);
        if (!RevertToSelf()) ExitThread(1);
        if (!opened) return false;
        bool match = false;
        alignas(TOKEN_USER) BYTE info[SECURITY_MAX_SID_SIZE + sizeof(TOKEN_USER)] = {};
        DWORD length = 0;
        if (GetTokenInformation(token, TokenUser, info, sizeof(info), &length)) {
            PSID want = nullptr;
            if (ConvertStringSidToSidW(sidText.c_str(), &want)) {
                match = EqualSid(reinterpret_cast<TOKEN_USER*>(info)->User.Sid, want) != FALSE;
                LocalFree(want);
            }
        }
        CloseHandle(token);
        return match;
    }

    LONG refs_ = 1;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario_ = CPUS_INVALID;
    std::shared_ptr<Bridge> bridge_;
    std::mutex usersLock_;
    std::vector<Credential*> credentials_;
    std::mutex eventsLock_;
    ICredentialProviderEvents* events_ = nullptr;
    UINT_PTR context_ = 0;
    HANDLE thread_ = nullptr;
    HANDLE stopEvent_ = nullptr;
    std::atomic<bool> stopping_{false};
};

// ---------------------------------------------------------------------------
class ClassFactory : public IClassFactory {
public:
    IFACEMETHODIMP_(ULONG) AddRef() override { return 2; }
    IFACEMETHODIMP_(ULONG) Release() override { return 1; }
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IClassFactory)) {
            *ppv = static_cast<IClassFactory*>(this);
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto* provider = new (std::nothrow) Provider();
        if (!provider) return E_OUTOFMEMORY;
        HRESULT hr = provider->QueryInterface(riid, ppv);
        provider->Release();
        return hr;
    }
    IFACEMETHODIMP LockServer(BOOL lock) override {
        if (lock) ++g_objects; else --g_objects;
        return S_OK;
    }
};

ClassFactory g_factory;

}  // namespace

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}

STDAPI DllCanUnloadNow() {
    return g_objects.load() == 0 ? S_OK : S_FALSE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (clsid != CLSID_LanyardUnlock) return CLASS_E_CLASSNOTAVAILABLE;
    return g_factory.QueryInterface(riid, ppv);
}
