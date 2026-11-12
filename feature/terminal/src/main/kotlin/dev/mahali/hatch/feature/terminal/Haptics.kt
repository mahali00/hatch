package dev.mahali.hatch.feature.terminal

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

internal class Haptics(private val view: View, private val enabled: () -> Boolean) {
    fun key() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    
    fun bell() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun exited() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun armed() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK)

    // LONG_PRESS, not DRAG_START: DRAG_START is barely felt on Samsung phones.
    fun pickUp() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun dropTarget() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun dropped() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)

    fun disarmed() {
        if (Build.VERSION.SDK_INT >= 34) perform(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
    }

    private fun perform(constant: Int) {
        if (enabled()) view.performHapticFeedback(constant)
    }
}
