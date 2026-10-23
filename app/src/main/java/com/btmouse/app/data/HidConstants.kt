package com.btmouse.app.data

object HidConstants {

    const val REPORT_ID_MOUSE: Byte = 1
    const val REPORT_ID_KEYBOARD: Byte = 2
    const val REPORT_ID_CONSUMER: Byte = 3

    // Mouse button bitmasks
    const val MOUSE_BUTTON_NONE = 0
    const val MOUSE_BUTTON_LEFT = 1 shl 0     // 1
    const val MOUSE_BUTTON_RIGHT = 1 shl 1    // 2
    const val MOUSE_BUTTON_MIDDLE = 1 shl 2   // 4
    const val MOUSE_BUTTON_BACK = 1 shl 3     // 8
    const val MOUSE_BUTTON_FORWARD = 1 shl 4  // 16

    // Keyboard modifier bitmasks
    const val MODIFIER_NONE: Byte = 0
    const val MODIFIER_LEFT_CTRL: Byte = 1 shl 0
    const val MODIFIER_LEFT_SHIFT: Byte = 1 shl 1
    const val MODIFIER_LEFT_ALT: Byte = 1 shl 2
    const val MODIFIER_LEFT_GUI: Byte = 1 shl 3   // Windows / Command key
    const val MODIFIER_RIGHT_CTRL: Byte = 1 shl 4
    const val MODIFIER_RIGHT_SHIFT: Byte = 1 shl 5
    const val MODIFIER_RIGHT_ALT: Byte = 1 shl 6
    const val MODIFIER_RIGHT_GUI: Byte = (1 shl 7).toByte()

    // Keyboard USB HID Keycodes
    const val KEY_NONE: Byte = 0x00
    const val KEY_A: Byte = 0x04
    const val KEY_B: Byte = 0x05
    const val KEY_C: Byte = 0x06
    const val KEY_D: Byte = 0x07
    const val KEY_E: Byte = 0x08
    const val KEY_F: Byte = 0x09
    const val KEY_G: Byte = 0x0A
    const val KEY_H: Byte = 0x0B
    const val KEY_I: Byte = 0x0C
    const val KEY_J: Byte = 0x0D
    const val KEY_K: Byte = 0x0E
    const val KEY_L: Byte = 0x0F
    const val KEY_M: Byte = 0x10
    const val KEY_N: Byte = 0x11
    const val KEY_O: Byte = 0x12
    const val KEY_P: Byte = 0x13
    const val KEY_Q: Byte = 0x14
    const val KEY_R: Byte = 0x15
    const val KEY_S: Byte = 0x16
    const val KEY_T: Byte = 0x17
    const val KEY_U: Byte = 0x18
    const val KEY_V: Byte = 0x19
    const val KEY_W: Byte = 0x1A
    const val KEY_X: Byte = 0x1B
    const val KEY_Y: Byte = 0x1C
    const val KEY_Z: Byte = 0x1D

    const val KEY_1: Byte = 0x1E
    const val KEY_2: Byte = 0x1F
    const val KEY_3: Byte = 0x20
    const val KEY_4: Byte = 0x21
    const val KEY_5: Byte = 0x22
    const val KEY_6: Byte = 0x23
    const val KEY_7: Byte = 0x24
    const val KEY_8: Byte = 0x25
    const val KEY_9: Byte = 0x26
    const val KEY_0: Byte = 0x27

    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESC: Byte = 0x29
    const val KEY_BACKSPACE: Byte = 0x2A
    const val KEY_TAB: Byte = 0x2B
    const val KEY_SPACE: Byte = 0x2C
    const val KEY_MINUS: Byte = 0x2D
    const val KEY_EQUAL: Byte = 0x2E
    const val KEY_LEFT_BRACKET: Byte = 0x2F
    const val KEY_RIGHT_BRACKET: Byte = 0x30
    const val KEY_BACKSLASH: Byte = 0x31
    const val KEY_SEMICOLON: Byte = 0x33
    const val KEY_QUOTE: Byte = 0x34
    const val KEY_GRAVE: Byte = 0x35
    const val KEY_COMMA: Byte = 0x36
    const val KEY_DOT: Byte = 0x37
    const val KEY_SLASH: Byte = 0x38
    const val KEY_CAPS_LOCK: Byte = 0x39

    // Function keys
    const val KEY_F1: Byte = 0x3A
    const val KEY_F2: Byte = 0x3B
    const val KEY_F3: Byte = 0x3C
    const val KEY_F4: Byte = 0x3D
    const val KEY_F5: Byte = 0x3E
    const val KEY_F6: Byte = 0x3F
    const val KEY_F7: Byte = 0x40
    const val KEY_F8: Byte = 0x41
    const val KEY_F9: Byte = 0x42
    const val KEY_F10: Byte = 0x43
    const val KEY_F11: Byte = 0x44
    const val KEY_F12: Byte = 0x45

    // Control and Navigation keys
    const val KEY_PRINT_SCREEN: Byte = 0x46
    const val KEY_SCROLL_LOCK: Byte = 0x47
    const val KEY_PAUSE: Byte = 0x48
    const val KEY_INSERT: Byte = 0x49
    const val KEY_HOME: Byte = 0x4A
    const val KEY_PAGE_UP: Byte = 0x4B
    const val KEY_DELETE: Byte = 0x4C
    const val KEY_END: Byte = 0x4D
    const val KEY_PAGE_DOWN: Byte = 0x4E
    const val KEY_RIGHT_ARROW: Byte = 0x4F
    const val KEY_LEFT_ARROW: Byte = 0x50
    const val KEY_DOWN_ARROW: Byte = 0x51
    const val KEY_UP_ARROW: Byte = 0x52

    // Numpad keys
    const val KEY_NUM_LOCK: Byte = 0x53
    const val KEYPAD_DIVIDE: Byte = 0x54
    const val KEYPAD_MULTIPLY: Byte = 0x55
    const val KEYPAD_SUBTRACT: Byte = 0x56
    const val KEYPAD_ADD: Byte = 0x57
    const val KEYPAD_ENTER: Byte = 0x58
    const val KEYPAD_1: Byte = 0x59
    const val KEYPAD_2: Byte = 0x5A
    const val KEYPAD_3: Byte = 0x5B
    const val KEYPAD_4: Byte = 0x5C
    const val KEYPAD_5: Byte = 0x5D
    const val KEYPAD_6: Byte = 0x5E
    const val KEYPAD_7: Byte = 0x5F
    const val KEYPAD_8: Byte = 0x60
    const val KEYPAD_9: Byte = 0x61
    const val KEYPAD_0: Byte = 0x62
    const val KEYPAD_DOT: Byte = 0x63

    // Consumer keys (Media controls)
    const val CONSUMER_PLAY_PAUSE = 0xCD
    const val CONSUMER_SCAN_NEXT = 0xB5
    const val CONSUMER_SCAN_PREV = 0xB6
    const val CONSUMER_STOP = 0xB7
    const val CONSUMER_VOLUME_UP = 0xE9
    const val CONSUMER_VOLUME_DOWN = 0xEA
    const val CONSUMER_MUTE = 0xE2

    /**
     * Complete Universal HID Report Descriptor:
     * - Collection 1: Mouse (Report ID 1) with 5 buttons, X, Y, Wheel, Pan
     * - Collection 2: Keyboard (Report ID 2) with Modifiers, LEDs, 6-key rollover
     * - Collection 3: Consumer Controls (Report ID 3) for Media keys
     */
    val HID_REPORT_DESCRIPTOR = byteArrayOf(
        // ==================== MOUSE (Report ID 1) ====================
        0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),        // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
        0x85.toByte(), REPORT_ID_MOUSE,     //   Report ID (1)
        0x09.toByte(), 0x01.toByte(),        //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(),        //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(),        //     Usage Minimum (Button 1)
        0x29.toByte(), 0x05.toByte(),        //     Usage Maximum (Button 5)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
        0x95.toByte(), 0x05.toByte(),        //     Report Count (5)
        0x75.toByte(), 0x01.toByte(),        //     Report Size (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x75.toByte(), 0x03.toByte(),        //     Report Size (3)
        0x81.toByte(), 0x01.toByte(),        //     Input (Constant) - 3 bits padding
        0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),        //     Usage (X)
        0x09.toByte(), 0x31.toByte(),        //     Usage (Y)
        0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
        0x95.toByte(), 0x02.toByte(),        //     Report Count (2)
        0x81.toByte(), 0x06.toByte(),        //     Input (Data, Variable, Relative)
        0x09.toByte(), 0x38.toByte(),        //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x06.toByte(),        //     Input (Data, Variable, Relative)
        0x05.toByte(), 0x0C.toByte(),        //     Usage Page (Consumer Devices)
        0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan - Horizontal Scroll)
        0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x06.toByte(),        //     Input (Data, Variable, Relative)
        0xC0.toByte(),                      //   End Collection
        0xC0.toByte(),                      // End Collection

        // ==================== KEYBOARD (Report ID 2) ====================
        0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),        // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD,  //   Report ID (2)
        0x05.toByte(), 0x07.toByte(),        //   Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(),        //   Usage Minimum (Keyboard LeftControl)
        0x29.toByte(), 0xE7.toByte(),        //   Usage Maximum (Keyboard Right GUI)
        0x15.toByte(), 0x00.toByte(),        //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),        //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),        //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),        //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),        //   Input (Data, Variable, Absolute) - Modifiers
        0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),        //   Report Size (8)
        0x81.toByte(), 0x01.toByte(),        //   Input (Constant) - Reserved byte
        0x95.toByte(), 0x05.toByte(),        //   Report Count (5) - LED indicators
        0x75.toByte(), 0x01.toByte(),        //   Report Size (1)
        0x05.toByte(), 0x08.toByte(),        //   Usage Page (LEDs)
        0x19.toByte(), 0x01.toByte(),        //   Usage Minimum (Num Lock)
        0x29.toByte(), 0x05.toByte(),        //   Usage Maximum (Kana)
        0x91.toByte(), 0x02.toByte(),        //   Output (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
        0x75.toByte(), 0x03.toByte(),        //   Report Size (3)
        0x91.toByte(), 0x01.toByte(),        //   Output (Constant) - LED padding
        0x95.toByte(), 0x06.toByte(),        //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),        //   Report Size (8)
        0x15.toByte(), 0x00.toByte(),        //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),        //   Logical Maximum (101 keys)
        0x05.toByte(), 0x07.toByte(),        //   Usage Page (Key Codes)
        0x19.toByte(), 0x00.toByte(),        //   Usage Minimum (Reserved)
        0x29.toByte(), 0x65.toByte(),        //   Usage Maximum (Keyboard Application)
        0x81.toByte(), 0x00.toByte(),        //   Input (Data, Array) - Key array
        0xC0.toByte(),                      // End Collection

        // ==================== CONSUMER (Report ID 3) ====================
        0x05.toByte(), 0x0C.toByte(),        // Usage Page (Consumer)
        0x09.toByte(), 0x01.toByte(),        // Usage (Consumer Control)
        0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
        0x85.toByte(), REPORT_ID_CONSUMER,  //   Report ID (3)
        0x15.toByte(), 0x00.toByte(),        //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x03.toByte(), // Logical Maximum (1023)
        0x19.toByte(), 0x00.toByte(),        //   Usage Minimum (0)
        0x2A.toByte(), 0xFF.toByte(), 0x03.toByte(), // Usage Maximum (1023)
        0x75.toByte(), 0x10.toByte(),        //   Report Size (16 bits)
        0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
        0x81.toByte(), 0x00.toByte(),        //   Input (Data, Array)
        0xC0.toByte()                       // End Collection
    )

    /**
     * Map a character to (modifier, hidKeyCode)
     */
    fun charToHid(char: Char): Pair<Byte, Byte>? {
        return when (char) {
            in 'a'..'z' -> Pair(MODIFIER_NONE, (KEY_A + (char - 'a')).toByte())
            in 'A'..'Z' -> Pair(MODIFIER_LEFT_SHIFT, (KEY_A + (char - 'A')).toByte())
            in '1'..'9' -> Pair(MODIFIER_NONE, (KEY_1 + (char - '1')).toByte())
            '0' -> Pair(MODIFIER_NONE, KEY_0)
            '!' -> Pair(MODIFIER_LEFT_SHIFT, KEY_1)
            '@' -> Pair(MODIFIER_LEFT_SHIFT, KEY_2)
            '#' -> Pair(MODIFIER_LEFT_SHIFT, KEY_3)
            '$' -> Pair(MODIFIER_LEFT_SHIFT, KEY_4)
            '%' -> Pair(MODIFIER_LEFT_SHIFT, KEY_5)
            '^' -> Pair(MODIFIER_LEFT_SHIFT, KEY_6)
            '&' -> Pair(MODIFIER_LEFT_SHIFT, KEY_7)
            '*' -> Pair(MODIFIER_LEFT_SHIFT, KEY_8)
            '(' -> Pair(MODIFIER_LEFT_SHIFT, KEY_9)
            ')' -> Pair(MODIFIER_LEFT_SHIFT, KEY_0)
            ' ' -> Pair(MODIFIER_NONE, KEY_SPACE)
            '\n' -> Pair(MODIFIER_NONE, KEY_ENTER)
            '\t' -> Pair(MODIFIER_NONE, KEY_TAB)
            '-' -> Pair(MODIFIER_NONE, KEY_MINUS)
            '_' -> Pair(MODIFIER_LEFT_SHIFT, KEY_MINUS)
            '=' -> Pair(MODIFIER_NONE, KEY_EQUAL)
            '+' -> Pair(MODIFIER_LEFT_SHIFT, KEY_EQUAL)
            '[' -> Pair(MODIFIER_NONE, KEY_LEFT_BRACKET)
            '{' -> Pair(MODIFIER_LEFT_SHIFT, KEY_LEFT_BRACKET)
            ']' -> Pair(MODIFIER_NONE, KEY_RIGHT_BRACKET)
            '}' -> Pair(MODIFIER_LEFT_SHIFT, KEY_RIGHT_BRACKET)
            '\\' -> Pair(MODIFIER_NONE, KEY_BACKSLASH)
            '|' -> Pair(MODIFIER_LEFT_SHIFT, KEY_BACKSLASH)
            ';' -> Pair(MODIFIER_NONE, KEY_SEMICOLON)
            ':' -> Pair(MODIFIER_LEFT_SHIFT, KEY_SEMICOLON)
            '\'' -> Pair(MODIFIER_NONE, KEY_QUOTE)
            '"' -> Pair(MODIFIER_LEFT_SHIFT, KEY_QUOTE)
            '`' -> Pair(MODIFIER_NONE, KEY_GRAVE)
            '~' -> Pair(MODIFIER_LEFT_SHIFT, KEY_GRAVE)
            ',' -> Pair(MODIFIER_NONE, KEY_COMMA)
            '<' -> Pair(MODIFIER_LEFT_SHIFT, KEY_COMMA)
            '.' -> Pair(MODIFIER_NONE, KEY_DOT)
            '>' -> Pair(MODIFIER_LEFT_SHIFT, KEY_DOT)
            '/' -> Pair(MODIFIER_NONE, KEY_SLASH)
            '?' -> Pair(MODIFIER_LEFT_SHIFT, KEY_SLASH)
            else -> null
        }
    }
}
