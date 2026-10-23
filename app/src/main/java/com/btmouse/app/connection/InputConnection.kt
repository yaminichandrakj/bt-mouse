package com.btmouse.app.connection

import kotlinx.coroutines.flow.StateFlow

interface InputConnection {
    val isConnected: StateFlow<Boolean>
    val statusMessage: StateFlow<String>
    val connectedDeviceName: StateFlow<String?>

    fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int = 0, hScroll: Int = 0)
    fun sendMouseButton(buttons: Int)
    fun sendKeyPress(keyCode: Byte, modifiers: Byte = 0)
    fun sendKeyRelease(keyCode: Byte, modifiers: Byte = 0)
    fun sendKeyStroke(keyCode: Byte, modifiers: Byte = 0)
    fun sendMediaKey(usageCode: Int)
    fun sendText(text: String)
    fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float = 1.0f, isEraser: Boolean = false) {}
}
