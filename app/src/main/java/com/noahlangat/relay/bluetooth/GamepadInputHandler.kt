package com.noahlangat.relay.bluetooth

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.noahlangat.relay.protocol.GamepadState
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

        val inputDevice: InputDevice? = InputDevice.getDevice(deviceId)
        val actualDeviceName = inputDevice?.name ?: "Gamepad"

        Timber.d("GamepadInputHandler: Key event - keyCode=${event.keyCode}, pressed=$isPressed")
        updateButtonState(buttonBit, isPressed, deviceId, actualDeviceName)
        return true
    }

    /**
     * Process motion events (sticks, triggers, dpad)
     */
    fun handleMotionEvent(event: MotionEvent, deviceId: Int): Boolean {
        if (!isGamepadEvent(event)) return false

        val inputDevice: InputDevice? = InputDevice.getDevice(deviceId)
        val actualDeviceName = inputDevice?.name ?: "Gamepad"

        Timber.d("GamepadInputHandler: Motion event - deviceId=$deviceId")
        val newState = currentState.copy(
            deviceId = deviceId.toByte(),
            deviceName = actualDeviceName, // Set the device name
            leftStickX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X), stickDeadzone).toInt().toShort(),
            leftStickY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y), stickDeadzone).toInt().toShort(),
            rightStickX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z), stickDeadzone).toInt().toShort(),
            rightStickY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ), stickDeadzone).toInt().toShort(),
            leftTrigger = applyTriggerDeadzone(event.getAxisValue(MotionEvent.AXIS_LTRIGGER)).toInt().toShort(),
            rightTrigger = applyTriggerDeadzone(event.getAxisValue(MotionEvent.AXIS_RTRIGGER)).toInt().toShort(),
            dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X).toInt().toShort(),
            dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y).toInt().toShort(),
            timestamp = System.currentTimeMillis() * 1000 // Convert to microseconds
        )

        updateGamepadState(newState)
        return true
    }

    /**
     * Handle device disconnection
     */
    fun handleDeviceDisconnected(deviceId: Int) {
        val inputDevice: InputDevice? = InputDevice.getDevice(deviceId)
        val actualDeviceName = inputDevice?.name ?: "Gamepad"
        // Reset state when device disconnects
        val resetState = GamepadState(
            deviceId = deviceId.toByte(),
            deviceName = actualDeviceName,
            timestamp = System.currentTimeMillis() * 1000
        )
        updateGamepadState(resetState)
        Timber.i("Gamepad disconnected, state reset: $deviceId")
    }

    private fun updateButtonState(buttonBit: Int, isPressed: Boolean, deviceId: Int, deviceName: String) {
        val currentButtons = currentState.buttons.toInt()
        val newButtons = if (isPressed) {
            currentButtons or (1 shl buttonBit)
        } else {
            currentButtons and (1 shl buttonBit).inv()
        }

        val newState = currentState.copy(
            deviceId = deviceId.toByte(),
            deviceName = deviceName,
            buttons = newButtons.toShort(),
            timestamp = System.currentTimeMillis() * 1000
        )

        updateGamepadState(newState)

        Timber.v("Button ${getButtonName(buttonBit)} ${if (isPressed) "pressed" else "released"}")
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
            else -> null
        }
    }

    private fun getButtonName(buttonBit: Int): String {
        return when (buttonBit) {
            GamepadState.BUTTON_CROSS -> "Cross"
            GamepadState.BUTTON_CIRCLE -> "Circle"
            GamepadState.BUTTON_SQUARE -> "Square"
            GamepadState.BUTTON_TRIANGLE -> "Triangle"
            GamepadState.BUTTON_L1 -> "L1"
            GamepadState.BUTTON_R1 -> "R1"
            GamepadState.BUTTON_L2 -> "L2"
            GamepadState.BUTTON_R2 -> "R2"
            GamepadState.BUTTON_SHARE -> "Share"
            GamepadState.BUTTON_OPTIONS -> "Options"
            GamepadState.BUTTON_L3 -> "L3"
            GamepadState.BUTTON_R3 -> "R3"
            GamepadState.BUTTON_PS -> "PS"
            GamepadState.BUTTON_TOUCHPAD -> "Touchpad"
            GamepadState.BUTTON_MUTE -> "Mute"
            else -> "Unknown($buttonBit)"
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

    companion object {
        private const val TAG = "GamepadInputHandler"
    }
}