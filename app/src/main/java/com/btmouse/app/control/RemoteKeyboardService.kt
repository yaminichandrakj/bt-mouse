package com.btmouse.app.control

import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "BT Mouse keyboard": a keyboard with no keys of its own. Keys typed on the laptop
 * (or another device) arrive here and go into whatever field has focus, exactly like a
 * hardware keyboard, including arrows, Backspace, Enter and Ctrl shortcuts.
 */
class RemoteKeyboardService : InputMethodService() {

    private var inputStarted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        _active.value = true
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _active.value = false
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputStarted = attribute != null && attribute.inputType != EditorInfo.TYPE_NULL
    }

    override fun onFinishInput() {
        inputStarted = false
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 24, 24))
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
        }
        val label = TextView(this).apply {
            text = "Typing from your other device"
            setTextColor(Color.rgb(220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val switch = TextView(this).apply {
            text = "Switch keyboard"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { switchAway() }
        }
        bar.addView(switch)
        return bar
    }

    private fun switchAway() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    /** True when a text field is focused and this keyboard is the one serving it. */
    fun isTypingTarget(): Boolean = inputStarted && currentInputConnection != null

    fun sendKey(code: Int, down: Boolean, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        ic.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta, -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    companion object {
        @Volatile var instance: RemoteKeyboardService? = null
            private set
        private val _active = MutableStateFlow(false)
        /** True while this keyboard is the selected input method (Android keeps it bound). */
        val active: StateFlow<Boolean> = _active.asStateFlow()
    }
}
