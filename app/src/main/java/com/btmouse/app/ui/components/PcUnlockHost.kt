package com.btmouse.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.PcUnlock
import com.btmouse.app.data.PcPasswordVault
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.Hairline
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkFaint
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.StatusRedText
import kotlinx.coroutines.delay
import javax.crypto.Cipher

/** Starts a phone unlock of the connected PC; see [rememberPcUnlock]. */
class PcUnlockController internal constructor(private val begin: () -> Unit) {
    fun start() = begin()
}

/**
 * Unlocks the connected PC with its Windows password, kept in [PcPasswordVault]. The first
 * time it asks for the password; after that a fingerprint (or screen lock) opens it and it
 * goes to the PC over the encrypted link. Shows its own password dialog when needed.
 */
@Composable
fun rememberPcUnlock(coordinator: ConnectionCoordinator, notify: (String) -> Unit): PcUnlockController {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val vault = remember(context) { PcPasswordVault(context) }
    val pcName by coordinator.connectedDeviceName.collectAsState()
    var asking by remember { mutableStateOf<String?>(null) } // the dialog's note; null hides it
    var busy by remember { mutableStateOf(false) }

    fun send(password: String) {
        if (coordinator.unlockPc(password)) {
            busy = true
            notify("Unlocking your PC")
        } else {
            notify("Unlock needs an encrypted connection. Reconnect and try again.")
        }
    }

    LaunchedEffect(Unit) {
        PcUnlock.results.collect { result ->
            busy = false
            notify(result.message)
            if (result.code == "wrong_password") {
                pcName?.let { vault.forget(it) }
                asking = "Windows didn't accept the saved password. Enter it again."
            }
        }
    }
    LaunchedEffect(busy) {
        if (busy) {
            delay(35_000)
            busy = false
        }
    }

    val pc = pcName?.takeIf { it.isNotBlank() }
    fun startUnlock() {
        val host = activity
        when {
            pc == null -> notify("Connect to your PC first")
            busy -> notify("Already unlocking")
            !coordinator.canSendSecrets -> notify("Unlock needs an encrypted connection. Reconnect and try again.")
            host == null -> notify("Phone unlock isn't available here")
            BiometricManager.from(host).canAuthenticate(PcPasswordVault.authenticators) != BiometricManager.BIOMETRIC_SUCCESS ->
                notify("Set up a fingerprint or screen lock on this phone first")
            !vault.has(pc) -> { asking = "" }
            else -> {
                val opener = vault.opener(pc)
                if (opener == null) {
                    asking = "This phone's fingerprints changed, so enter your Windows password again."
                } else {
                    authenticate(host, "Unlock $pc", opener, notify) { cipher ->
                        val password = vault.open(pc, cipher)
                        if (password == null) {
                            vault.forget(pc)
                            asking = "Lanyard couldn't read the saved password. Enter it again."
                            return@authenticate
                        }
                        send(password)
                    }
                }
            }
        }
    }
    val begin = rememberUpdatedState<() -> Unit>(::startUnlock)

    asking?.let { note ->
        PasswordDialog(pc ?: "your PC", note, onDismiss = { asking = null }) { password ->
            asking = null
            val host = activity
            val sealer = vault.sealer()
            if (host == null || sealer == null || pc == null) {
                notify("This phone couldn't keep the password safely")
            } else {
                authenticate(host, "Save password for $pc", sealer, notify) { cipher ->
                    runCatching { vault.save(pc, cipher, password) }
                        .onFailure { notify("Couldn't save the password; unlocking this once") }
                    send(password)
                }
            }
        }
    }

    return remember { PcUnlockController { begin.value() } }
}

@Composable
private fun PasswordDialog(pc: String, note: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Control,
        title = { Text("Unlock $pc from this phone", color = OnInk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (note.isNotEmpty()) Text(note, color = StatusRedText, fontSize = 14.sp)
                Text(
                    "Enter the password of your Windows account on this PC. If you sign in with a Microsoft " +
                        "account, use that account's password, not your PIN.",
                    color = OnInkMuted, fontSize = 14.sp, lineHeight = 20.sp
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(256) },
                    placeholder = { Text("Windows password", color = OnInkFaint) },
                    singleLine = true,
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton({ shown = !shown }) {
                            Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (shown) "Hide password" else "Show password", tint = OnInkMuted)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnInk,
                        unfocusedBorderColor = Hairline,
                        focusedTextColor = OnInk,
                        unfocusedTextColor = OnInk
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "It stays on this phone, locked behind your fingerprint, and is sent only when you tap Unlock.",
                    color = OnInkFaint, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            PillButton("Save and unlock", { if (password.isNotEmpty()) onSave(password) }, style = PillStyle.Selected)
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = OnInk) } }
    )
}

private fun authenticate(
    activity: FragmentActivity,
    title: String,
    cipher: Cipher,
    onError: (String) -> Unit,
    onOk: (Cipher) -> Unit
) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val ready = result.cryptoObject?.cipher
                if (ready != null) onOk(ready) else onError("Couldn't confirm it's you")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                if (!cancelled) onError(errString.toString())
            }
        })
    val allowed = PcPasswordVault.authenticators
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("Confirm it's you")
        .setAllowedAuthenticators(allowed)
        .apply { if (allowed and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) setNegativeButtonText("Cancel") }
        .build()
    runCatching { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
        .onFailure { onError("Couldn't ask for your fingerprint") }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
