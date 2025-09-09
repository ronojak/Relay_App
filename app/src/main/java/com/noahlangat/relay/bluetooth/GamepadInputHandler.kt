package com.noahlangat.relay.bluetooth

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noahlangat.relay.protocol.GamepadState
import com.noahlangat.relay.ui.components.LogMessage
import com.noahlangat.relay.ui.components.LogLevel
import com.noahlangat.relay.ui.components.LogSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import kotlin.math.abs

/**
 * Processes gamepad input events and converts them to GamepadState objects
 */
class GamepadInputHandler {
    
    private val _gamepadStateFlow = MutableSharedFlow<GamepadState>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val gamepadStateFlow: SharedFlow<GamepadState> = _gamepadStateFlow
    
    private val _logMessageFlow = MutableSharedFlow<LogMessage>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val logMessageFlow: SharedFlow<LogMessage> = _logMessageFlow
    
    private var currentState = GamepadState()
    private var lastStateTime = 0L
    private var lastTransmittedState: GamepadState? = null
    
    // Rate limiting - max 120Hz
    private val minIntervalMs = 1000 / 120 // ~8.33ms
    
    // Deadzone configuration (as percentage)
    private val stickDeadzone = 0.08f // 8%
    private val triggerDeadzone = 0.05f // 5%
    
    // Debouncing - minimum interval between identical states
    private val debounceIntervalMs = 5L
    
    /**
     * Process key events (buttons)
     */
    fun handleKeyEvent(event: KeyEvent, deviceId: Int): Boolean {
        if (!isGamepadEvent(event)) return false
        
        val buttonBit = mapKeyCodeToButtonBit(event.keyCode) ?: return false
        val isPressed = event.action == KeyEvent.ACTION_DOWN
        val buttonName = getButtonNameFromKeyCode(event.keyCode)
        
        Timber.d("GamepadInputHandler: Key event - keyCode=${event.keyCode} ($buttonName), pressed=$isPressed")
        updateButtonState(buttonBit, isPressed, deviceId, buttonName)
        return true
    }
    
    /**
     * Process motion events (sticks, triggers, dpad)
     */
    fun handleMotionEvent(event: MotionEvent, deviceId: Int): Boolean {
        if (!isGamepadEvent(event)) return false
        
        val leftStickX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X), stickDeadzone).toInt().toShort()
        val leftStickY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y), stickDeadzone).toInt().toShort()
        val rightStickX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z), stickDeadzone).toInt().toShort()
        val rightStickY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ), stickDeadzone).toInt().toShort()
        val leftTrigger = applyTriggerDeadzone(event.getAxisValue(MotionEvent.AXIS_LTRIGGER)).toInt().toShort()
        val rightTrigger = applyTriggerDeadzone(event.getAxisValue(MotionEvent.AXIS_RTRIGGER)).toInt().toShort()
        val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X).toInt().toShort()
        val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y).toInt().toShort()
        
        // Log significant motion changes
        logMotionChanges(leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger, dpadX, dpadY, deviceId)
        
        Timber.d("GamepadInputHandler: Motion event - deviceId=$deviceId")
        val newState = currentState.copy(
            deviceId = deviceId.toByte(),
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            dpadX = dpadX,
            dpadY = dpadY,
            timestamp = System.currentTimeMillis() * 1000 // Convert to microseconds
        )
        
        updateGamepadState(newState)
        return true
    }
    
    /**
     * Handle device disconnection
     */
    fun handleDeviceDisconnected(deviceId: Int) {
        // Reset state when device disconnects
        val resetState = GamepadState(
            deviceId = deviceId.toByte(),
            timestamp = System.currentTimeMillis() * 1000
        )
        updateGamepadState(resetState)
        Timber.i("Gamepad disconnected, state reset: $deviceId")
    }
    
    private fun updateButtonState(buttonBit: Int, isPressed: Boolean, deviceId: Int, buttonName: String = "") {
        val currentButtons = currentState.buttons.toInt()
        val newButtons = if (isPressed) {
            currentButtons or (1 shl buttonBit)
        } else {
            currentButtons and (1 shl buttonBit).inv()
        }
        
        val newState = currentState.copy(
            deviceId = deviceId.toByte(),
            buttons = newButtons.toShort(),
            timestamp = System.currentTimeMillis() * 1000
        )
        
        updateGamepadState(newState)
        
        val displayName = buttonName.ifEmpty { getButtonName(buttonBit) }
        val action = if (isPressed) "PRESSED" else "RELEASED"
        val buttonBitmask = String.format("0x%04X", newButtons and 0xFFFF)
        
        Timber.i("🎮 GAMEPAD: $displayName $action (bit:$buttonBit, buttons:$buttonBitmask, device:$deviceId)")
        
        // Emit log message for UI
        val logMessage = LogMessage(
            message = "🎮 $displayName $action",
            level = LogLevel.INFO,
            deviceName = "Gamepad",
            deviceId = deviceId,
            source = LogSource.GAMEPAD
        )
        _logMessageFlow.tryEmit(logMessage)
        
        // Log all currently pressed buttons for debugging
        if (isPressed) {
            val pressedButtons = getActiveButtonNames(newButtons.toShort())
            if (pressedButtons.isNotEmpty()) {
                Timber.d("🎮 Active buttons: ${pressedButtons.joinToString(", ")}")
            }
        }
    }
    
    private fun updateGamepadState(newState: GamepadState) {
        val currentTime = System.currentTimeMillis()
        
        // Rate limiting check
        if (currentTime - lastStateTime < minIntervalMs) {
            return
        }
        
        // Change detection - only transmit if state changed significantly
        if (!hasSignificantChange(newState)) {
            return
        }
        
        // Debouncing check
        if (currentTime - lastStateTime < debounceIntervalMs && newState == lastTransmittedState) {
            return
        }
        
        currentState = newState
        lastStateTime = currentTime
        lastTransmittedState = newState
        
        // Emit state change
        _gamepadStateFlow.tryEmit(newState)
        
        Timber.v("Gamepad state updated and transmitted")
        
        // Log comprehensive state summary for debugging
        logGamepadStateSummary(newState)
    }
    
    private fun hasSignificantChange(newState: GamepadState): Boolean {
        val lastState = lastTransmittedState ?: return true
        
        // Check for button changes
        if (newState.buttons != lastState.buttons) return true
        
        // Check for significant stick movement (threshold to avoid micro-movements)
        val stickThreshold = 500 // About 1.5% of full range
        if (abs(newState.leftStickX - lastState.leftStickX) > stickThreshold) return true
        if (abs(newState.leftStickY - lastState.leftStickY) > stickThreshold) return true
        if (abs(newState.rightStickX - lastState.rightStickX) > stickThreshold) return true
        if (abs(newState.rightStickY - lastState.rightStickY) > stickThreshold) return true
        
        // Check for trigger changes
        val triggerThreshold = 200 // About 0.3% of full range
        if (abs(newState.leftTrigger - lastState.leftTrigger) > triggerThreshold) return true
        if (abs(newState.rightTrigger - lastState.rightTrigger) > triggerThreshold) return true
        
        // Check for D-pad changes
        if (newState.dpadX != lastState.dpadX || newState.dpadY != lastState.dpadY) return true
        
        return false
    }
    
    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        val absValue = abs(value)
        return if (absValue < deadzone) {
            0f
        } else {
            // Scale to maintain full range outside deadzone
            val sign = if (value >= 0) 1f else -1f
            val scaledValue = (absValue - deadzone) / (1f - deadzone)
            (sign * scaledValue * Short.MAX_VALUE).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
        }
    }
    
    private fun applyTriggerDeadzone(value: Float): Float {
        return if (value < triggerDeadzone) {
            0f
        } else {
            // Scale trigger value to 0-65535 range
            val scaledValue = (value - triggerDeadzone) / (1f - triggerDeadzone)
            (scaledValue * 65535f).coerceIn(0f, 65535f)
        }
    }
    
    private fun isGamepadEvent(event: KeyEvent): Boolean {
        val source = event.source
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
    
    private fun isGamepadEvent(event: MotionEvent): Boolean {
        val source = event.source
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
    
    private fun mapKeyCodeToButtonBit(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> GamepadState.BUTTON_CROSS
            KeyEvent.KEYCODE_BUTTON_B -> GamepadState.BUTTON_CIRCLE
            KeyEvent.KEYCODE_BUTTON_X -> GamepadState.BUTTON_SQUARE
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadState.BUTTON_TRIANGLE
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadState.BUTTON_L1
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadState.BUTTON_R1
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadState.BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R2 -> GamepadState.BUTTON_R2
            KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadState.BUTTON_SHARE
            KeyEvent.KEYCODE_BUTTON_START -> GamepadState.BUTTON_OPTIONS
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadState.BUTTON_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadState.BUTTON_R3
            KeyEvent.KEYCODE_BUTTON_MODE -> GamepadState.BUTTON_PS
            // Handle D-pad as additional buttons if needed
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, 
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER -> null // Handle via motion events
            else -> {
                Timber.w("⚠️ Unknown gamepad key code: $keyCode (${getButtonNameFromKeyCode(keyCode)})")
                null
            }
        }
    }
    
    private fun getButtonName(buttonBit: Int): String {
        return when (buttonBit) {
            GamepadState.BUTTON_CROSS -> "Cross (X)"
            GamepadState.BUTTON_CIRCLE -> "Circle (O)"
            GamepadState.BUTTON_SQUARE -> "Square (□)"
            GamepadState.BUTTON_TRIANGLE -> "Triangle (△)"
            GamepadState.BUTTON_L1 -> "L1 (LB)"
            GamepadState.BUTTON_R1 -> "R1 (RB)"
            GamepadState.BUTTON_L2 -> "L2 (LT)"
            GamepadState.BUTTON_R2 -> "R2 (RT)"
            GamepadState.BUTTON_SHARE -> "Share (Back)"
            GamepadState.BUTTON_OPTIONS -> "Options (Start)"
            GamepadState.BUTTON_L3 -> "L3 (LS)"
            GamepadState.BUTTON_R3 -> "R3 (RS)"
            GamepadState.BUTTON_PS -> "PS (Home)"
            GamepadState.BUTTON_TOUCHPAD -> "Touchpad"
            GamepadState.BUTTON_MUTE -> "Mute"
            else -> "Unknown($buttonBit)"
        }
    }
    
    private fun getButtonNameFromKeyCode(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A/Cross"
            KeyEvent.KEYCODE_BUTTON_B -> "B/Circle"
            KeyEvent.KEYCODE_BUTTON_X -> "X/Square"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y/Triangle"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1/LB"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1/RB"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2/LT"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2/RT"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "Select/Share"
            KeyEvent.KEYCODE_BUTTON_START -> "Start/Options"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3/LS"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3/RS"
            KeyEvent.KEYCODE_BUTTON_MODE -> "Home/PS"
            KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
            KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad Center"
            else -> "KeyCode_$keyCode"
        }
    }
    
    private fun getActiveButtonNames(buttonMask: Short): List<String> {
        val activeButtons = mutableListOf<String>()
        val buttons = buttonMask.toInt()
        
        for (bit in 0..15) {
            if ((buttons and (1 shl bit)) != 0) {
                activeButtons.add(getButtonName(bit))
            }
        }
        return activeButtons
    }
    
    private fun logMotionChanges(lx: Short, ly: Short, rx: Short, ry: Short, lt: Short, rt: Short, dpadX: Short, dpadY: Short, deviceId: Int) {
        val threshold = 1000 // Only log significant movements
        
        // Left stick movement
        if (abs(lx.toInt()) > threshold || abs(ly.toInt()) > threshold) {
            val lxPercent = String.format("%.1f", (lx / 327.67f))
            val lyPercent = String.format("%.1f", (ly / 327.67f))
            val message = "🕸️ LEFT STICK: X=$lxPercent%, Y=$lyPercent%"
            Timber.i(message)
            
            // Emit UI log message
            val logMessage = LogMessage(
                message = message,
                level = LogLevel.INFO,
                deviceName = "Gamepad",
                deviceId = deviceId,
                source = LogSource.GAMEPAD
            )
            _logMessageFlow.tryEmit(logMessage)
        }
        
        // Right stick movement
        if (abs(rx.toInt()) > threshold || abs(ry.toInt()) > threshold) {
            val rxPercent = String.format("%.1f", (rx / 327.67f))
            val ryPercent = String.format("%.1f", (ry / 327.67f))
            val message = "🕸️ RIGHT STICK: X=$rxPercent%, Y=$ryPercent%"
            Timber.i(message)
            
            // Emit UI log message
            val logMessage = LogMessage(
                message = message,
                level = LogLevel.INFO,
                deviceName = "Gamepad",
                deviceId = deviceId,
                source = LogSource.GAMEPAD
            )
            _logMessageFlow.tryEmit(logMessage)
        }
        
        // Trigger presses
        if (lt.toInt() > 500) {
            val ltPercent = String.format("%.1f", (lt / 655.35f))
            val message = "⏮️ LEFT TRIGGER: $ltPercent%"
            Timber.i(message)
            
            // Emit UI log message
            val logMessage = LogMessage(
                message = message,
                level = LogLevel.INFO,
                deviceName = "Gamepad",
                deviceId = deviceId,
                source = LogSource.GAMEPAD
            )
            _logMessageFlow.tryEmit(logMessage)
        }
        if (rt.toInt() > 500) {
            val rtPercent = String.format("%.1f", (rt / 655.35f))
            val message = "⏭️ RIGHT TRIGGER: $rtPercent%"
            Timber.i(message)
            
            // Emit UI log message
            val logMessage = LogMessage(
                message = message,
                level = LogLevel.INFO,
                deviceName = "Gamepad",
                deviceId = deviceId,
                source = LogSource.GAMEPAD
            )
            _logMessageFlow.tryEmit(logMessage)
        }
        
        // D-pad movement
        if (dpadX.toInt() != 0 || dpadY.toInt() != 0) {
            val direction = when {
                dpadY.toInt() < 0 && dpadX.toInt() == 0 -> "UP"
                dpadY.toInt() > 0 && dpadX.toInt() == 0 -> "DOWN"
                dpadX.toInt() < 0 && dpadY.toInt() == 0 -> "LEFT"
                dpadX.toInt() > 0 && dpadY.toInt() == 0 -> "RIGHT"
                dpadY.toInt() < 0 && dpadX.toInt() < 0 -> "UP-LEFT"
                dpadY.toInt() < 0 && dpadX.toInt() > 0 -> "UP-RIGHT"
                dpadY.toInt() > 0 && dpadX.toInt() < 0 -> "DOWN-LEFT"
                dpadY.toInt() > 0 && dpadX.toInt() > 0 -> "DOWN-RIGHT"
                else -> "CENTER"
            }
            val message = "🧭 D-PAD: $direction"
            Timber.i(message)
            
            // Emit UI log message
            val logMessage = LogMessage(
                message = message,
                level = LogLevel.INFO,
                deviceName = "Gamepad",
                deviceId = deviceId,
                source = LogSource.GAMEPAD
            )
            _logMessageFlow.tryEmit(logMessage)
        }
    }
    
    /**
     * Get current gamepad state
     */
    fun getCurrentState(): GamepadState = currentState
    
    /**
     * Configure deadzone settings
     */
    fun configureDeadzones(stickDeadzone: Float, triggerDeadzone: Float) {
        // Configuration would be applied here if needed
        Timber.d("Deadzone configuration: stick=$stickDeadzone, trigger=$triggerDeadzone")
    }
    
    private fun logGamepadStateSummary(state: GamepadState) {
        // Log comprehensive gamepad state for debugging
        
        val activeButtons = getActiveButtonNames(state.buttons)
        val leftStick = state.getNormalizedLeftStick()
        val rightStick = state.getNormalizedRightStick()
        val triggers = state.getNormalizedTriggers()
        
        val summary = buildString {
            append("📊 GAMEPAD STATE: ")
            
            if (activeButtons.isNotEmpty()) {
                append("Buttons=[${activeButtons.joinToString(",")}] ")
            }
            
            if (abs(leftStick.first) > 0.1f || abs(leftStick.second) > 0.1f) {
                append("LS=(${String.format("%.2f", leftStick.first)},${String.format("%.2f", leftStick.second)}) ")
            }
            
            if (abs(rightStick.first) > 0.1f || abs(rightStick.second) > 0.1f) {
                append("RS=(${String.format("%.2f", rightStick.first)},${String.format("%.2f", rightStick.second)}) ")
            }
            
            if (triggers.first > 0.1f) {
                append("LT=${String.format("%.2f", triggers.first)} ")
            }
            
            if (triggers.second > 0.1f) {
                append("RT=${String.format("%.2f", triggers.second)} ")
            }
            
            if (state.dpadX.toInt() != 0 || state.dpadY.toInt() != 0) {
                append("DPAD=(${state.dpadX},${state.dpadY}) ")
            }
            
            append("Device=${state.deviceId}")
        }
        
        Timber.d(summary)
    }
    
    companion object {
        private const val TAG = "GamepadInputHandler"
    }
}