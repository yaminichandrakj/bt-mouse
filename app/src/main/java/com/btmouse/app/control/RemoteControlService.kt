package com.btmouse.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.btmouse.app.ecosystem.Ecosystem
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lets another device drive this phone: draws a pointer, turns clicks, drags and wheel
 * notches into touch gestures, and types into the focused field when the Lanyard
 * keyboard is not the active keyboard.
 *
 * Positions arrive in this display's pixels (the size sent in device_hello).
 */
class RemoteControlService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var borderView: View? = null

    private var x = 0f
    private var y = 0f
    private var leftDownAt = 0L
    private val dragPoints = ArrayList<Pair<Float, Float>>()

    // Live dragging: one continued stroke follows the pointer while the button is held.
    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveEnd: Pair<Float, Float>? = null
    private var liveBusy = false
    private var liveReleasing = false
    private var liveBroken = false
    private var pendingWheelY = 0f
    private var pendingWheelX = 0f
    private var wheelScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        _enabled.value = true
        Ecosystem.onControlCapabilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        _enabled.value = false
        main.post { hideOverlays() }
        Ecosystem.onControlCapabilityChanged()
    }

    // --- entry points (any thread) -------------------------------------
    fun setControlled(active: Boolean) {
        main.post { if (active) showOverlays() else hideOverlays() }
    }

    fun handle(json: JsonObject) {
        main.post { runCatching { handleOnMain(json) } }
    }

    private fun handleOnMain(json: JsonObject) {
        val ev = json.get("ev")?.asString ?: return
        json.get("x")?.takeIf { !it.isJsonNull }?.let { x = it.asFloat }
        json.get("y")?.takeIf { !it.isJsonNull }?.let { y = it.asFloat }
        clampToScreen()
        if (cursorView == null) showOverlays()
        when (ev) {
            "move" -> {
                moveCursor()
                if (leftDownAt > 0) {
                    addDragPoint()
                    val start = dragPoints.first()
                    if (!liveDragUnsupported && !liveBroken &&
                        (liveStroke != null || hypot(x - start.first, y - start.second) >= 12f)
                    ) pumpLive()
                }
            }
            "button" -> onButton(json.get("button")?.asString ?: "left", json.get("down")?.asBoolean ?: false)
            "wheel" -> onWheel(json.get("dy")?.asFloat ?: 0f, json.get("dx")?.asFloat ?: 0f)
            "key" -> onKey(
                json.get("code")?.asInt ?: return,
                json.get("down")?.asBoolean ?: false,
                json.get("meta")?.asInt ?: 0,
                json.get("char")?.takeIf { !it.isJsonNull }?.asString
            )
            "text" -> json.get("text")?.asString?.let { typeText(it) }
            "global" -> globalAction(json.get("action")?.asString ?: "")
            "tap" -> tap(x, y, 40L)
        }
    }

    // --- pointer -------------------------------------------------------
    private fun clampToScreen() {
        val (w, h) = com.btmouse.app.ecosystem.DeviceIdentity.screenSize(this)
        x = x.coerceIn(0f, (w - 1).toFloat())
        y = y.coerceIn(0f, (h - 1).toFloat())
    }

    private fun onButton(button: String, down: Boolean) {
        when (button) {
            "left" -> if (down) {
                leftDownAt = SystemClock.uptimeMillis()
                liveStroke = null
                liveEnd = null
                liveBusy = false
                liveReleasing = false
                liveBroken = false
                dragPoints.clear()
                dragPoints.add(x to y)
                cursorView?.buttonDown = true
            } else if (leftDownAt > 0) {
                val duration = SystemClock.uptimeMillis() - leftDownAt
                leftDownAt = 0
                cursorView?.buttonDown = false
                addDragPoint()
                if (liveStroke != null && !liveBroken) {
                    liveReleasing = true
                    if (!liveBusy) pumpLive()
                    dragPoints.clear()
                } else {
                    finishLeftGesture(duration)
                }
            }
            "right" -> if (!down) performGlobalAction(GLOBAL_ACTION_BACK)
            "middle" -> if (!down) performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun addDragPoint() {
        val last = dragPoints.lastOrNull()
        if (last == null || hypot(last.first - x, last.second - y) >= 4f) {
            dragPoints.add(x to y)
            if (dragPoints.size > 120) dragPoints.removeAt(1)
        }
    }

    private fun finishLeftGesture(durationMs: Long) {
        val start = dragPoints.first()
        val moved = dragPoints.maxOf { hypot(it.first - start.first, it.second - start.second) }
        when {
            moved < 12f && durationMs < 450 -> tap(start.first, start.second, 40L)
            moved < 12f -> tap(start.first, start.second, durationMs.coerceIn(500L, 2500L))
            else -> {
                val path = Path().apply {
                    moveTo(start.first, start.second)
                    dragPoints.drop(1).forEach { lineTo(it.first, it.second) }
                }
                dispatch(path, durationMs.coerceIn(80L, 3000L))
            }
        }
        dragPoints.clear()
    }

    /** Extend the held stroke to the pointer, one segment at a time; lift it on release. */
    private fun pumpLive() {
        if (liveBusy) return
        val target = x to y
        val prev = liveStroke
        val from = liveEnd ?: dragPoints.firstOrNull() ?: target
        if (prev != null && from == target && !liveReleasing) return
        val path = Path().apply {
            moveTo(from.first, from.second)
            // A segment must have length, even when the pointer has not moved.
            if (from == target) lineTo(target.first + 0.5f, target.second + 0.5f) else lineTo(target.first, target.second)
        }
        val keepGoing = !liveReleasing
        val stroke = runCatching {
            if (prev == null) GestureDescription.StrokeDescription(path, 0L, 40L, keepGoing)
            else prev.continueStroke(path, 0L, 40L, keepGoing)
        }.getOrNull()
        if (stroke == null) {
            breakLive()
            return
        }
        liveBusy = true
        liveStroke = stroke
        liveEnd = if (from == target) (target.first + 0.5f) to (target.second + 0.5f) else target
        val sent = runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    main.post {
                        liveBusy = false
                        if (!keepGoing) {
                            liveStroke = null
                            liveEnd = null
                        } else if (liveReleasing || liveEnd != (x to y)) {
                            pumpLive()
                        }
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    main.post { breakLive() }
                }
            }, null)
        }.getOrDefault(false)
        if (!sent) breakLive()
    }

    /** Continued strokes failed on this device: fall back to replaying the drag on release. */
    private fun breakLive() {
        liveBusy = false
        liveBroken = true
        liveStroke = null
        liveEnd = null
        liveFailures++
        if (liveFailures >= 3) liveDragUnsupported = true
    }

    private fun tap(px: Float, py: Float, duration: Long) {
        val path = Path().apply { moveTo(px, py); lineTo(px + 0.5f, py + 0.5f) }
        dispatch(path, duration)
    }

    private fun dispatch(path: Path, duration: Long) {
        runCatching {
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(1L))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    private fun onWheel(dy: Float, dx: Float) {
        pendingWheelY += dy
        pendingWheelX += dx
        if (!wheelScheduled) {
            wheelScheduled = true
            main.postDelayed({ flushWheel() }, 70L)
        }
    }

    private fun flushWheel() {
        wheelScheduled = false
        val (w, h) = com.btmouse.app.ecosystem.DeviceIdentity.screenSize(this)
        val dy = pendingWheelY
        val dx = pendingWheelX
        pendingWheelY = 0f
        pendingWheelX = 0f
        if (abs(dy) < 0.01f && abs(dx) < 0.01f) return
        // Wheel up shows content above, so the finger moves down the screen.
        val distY = (dy * h * 0.12f).coerceIn(-h * 0.45f, h * 0.45f)
        val distX = (-dx * w * 0.12f).coerceIn(-w * 0.45f, w * 0.45f)
        val sx = x.coerceIn(w * 0.1f, w * 0.9f)
        val sy = (y - distY / 2).coerceIn(h * 0.08f, h * 0.92f)
        val ex = (sx + distX).coerceIn(1f, w - 2f)
        val ey = (sy + distY).coerceIn(1f, h - 2f)
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        dispatch(path, 140L)
    }

    // --- keyboard ------------------------------------------------------
    private fun onKey(code: Int, down: Boolean, meta: Int, char: String?) {
        // Keys that mean the same everywhere on Android.
        when (code) {
            KeyEvent.KEYCODE_ESCAPE -> { if (down) performGlobalAction(GLOBAL_ACTION_BACK); return }
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> { if (!down) performGlobalAction(GLOBAL_ACTION_HOME); return }
        }
        if (code == KeyEvent.KEYCODE_TAB && meta and KeyEvent.META_ALT_ON != 0) {
            if (down) performGlobalAction(GLOBAL_ACTION_RECENTS)
            return
        }
        val keyboard = RemoteKeyboardService.instance
        if (keyboard != null && keyboard.isTypingTarget()) {
            // Printable keys arrive with the character the sender's own layout makes; type that
            // exactly (é, ß, @ on AltGr...). Everything else goes as a real key event.
            if (char != null && char.isNotEmpty()) {
                if (down) keyboard.commitText(char)
            } else {
                keyboard.sendKey(code, down, meta)
            }
            return
        }
        if (!down) return
        when {
            char != null && char.isNotEmpty() -> typeText(char)
            code == KeyEvent.KEYCODE_DEL -> deleteBack()
            code == KeyEvent.KEYCODE_ENTER -> pressEnter()
            meta and KeyEvent.META_CTRL_ON != 0 -> ctrlShortcut(code)
        }
    }

    private fun typeText(text: String) {
        val keyboard = RemoteKeyboardService.instance
        if (keyboard != null && keyboard.isTypingTarget()) {
            keyboard.commitText(text)
            return
        }
        val node = focusedEditable() ?: return
        val current = currentText(node)
        val start = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = node.textSelectionEnd.takeIf { it in start..current.length } ?: start
        val updated = current.substring(0, start) + text + current.substring(end)
        setText(node, updated, start + text.length)
    }

    private fun deleteBack() {
        val node = focusedEditable() ?: return
        val current = currentText(node)
        val start = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = node.textSelectionEnd.takeIf { it in start..current.length } ?: start
        if (start == end && start == 0) return
        val from = if (start == end) start - 1 else start
        setText(node, current.substring(0, from) + current.substring(end), from)
    }

    private fun pressEnter() {
        val node = focusedEditable() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return
        }
        if (node.isMultiLine) typeText("\n")
    }

    private fun ctrlShortcut(code: Int) {
        val node = focusedEditable() ?: return
        when (code) {
            KeyEvent.KEYCODE_A -> {
                val len = currentText(node).length
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                })
            }
            KeyEvent.KEYCODE_C -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            KeyEvent.KEYCODE_X -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            KeyEvent.KEYCODE_V -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    private fun globalAction(action: String) {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return
            else -> return
        }
        performGlobalAction(id)
    }

    // --- overlays ------------------------------------------------------
    private fun overlayParams(width: Int, height: Int, keepScreenOn: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showOverlays() {
        val wm = windowManager ?: return
        if (borderView == null) {
            val border = BorderView(this)
            runCatching { wm.addView(border, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true)) }
                .onSuccess { borderView = border }
        }
        if (cursorView == null) {
            val size = (resources.displayMetrics.density * 28).toInt()
            val view = CursorView(this)
            val params = overlayParams(size, size, false)
            runCatching { wm.addView(view, params) }.onSuccess {
                cursorView = view
                cursorParams = params
                moveCursor()
            }
        }
    }

    private fun hideOverlays() {
        val wm = windowManager ?: return
        cursorView?.let { runCatching { wm.removeView(it) } }
        borderView?.let { runCatching { wm.removeView(it) } }
        cursorView = null
        cursorParams = null
        borderView = null
        leftDownAt = 0
        dragPoints.clear()
    }

    private fun moveCursor() {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        params.x = x.toInt()
        params.y = y.toInt()
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    @SuppressLint("ViewConstructor")
    private class CursorView(context: Context) : View(context) {
        var buttonDown = false
            set(value) { field = value; invalidate() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.5f
        }
        private val arrow = Path()

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            arrow.reset()
            arrow.moveTo(s * 0.06f, s * 0.04f)
            arrow.lineTo(s * 0.06f, s * 0.80f)
            arrow.lineTo(s * 0.26f, s * 0.62f)
            arrow.lineTo(s * 0.40f, s * 0.94f)
            arrow.lineTo(s * 0.54f, s * 0.88f)
            arrow.lineTo(s * 0.40f, s * 0.57f)
            arrow.lineTo(s * 0.66f, s * 0.57f)
            arrow.close()
            fill.color = if (buttonDown) Color.rgb(140, 200, 255) else Color.WHITE
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
        }
    }

    /** Thin coloured frame so it is obvious another device is driving this one. */
    private class BorderView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.rgb(90, 170, 255)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 3f
        }

        override fun onDraw(canvas: Canvas) {
            val inset = paint.strokeWidth / 2
            canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        }
    }

    companion object {
        @Volatile var instance: RemoteControlService? = null
            private set
        private var liveFailures = 0
        private var liveDragUnsupported = false
        private val _enabled = MutableStateFlow(false)
        /** True while Android has this service turned on. */
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    }
}
