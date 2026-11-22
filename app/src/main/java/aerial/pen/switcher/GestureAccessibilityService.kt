package aerial.pen.switcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        fun performBack(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun performRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        fun ready(): Boolean = instance != null
    }
}
