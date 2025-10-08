package com.example.socketclient

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Currently not used but can handle UI events here if needed
    }

    override fun onInterrupt() {
        // Required override, typically used to clean up ongoing tasks
    }

    /**
     * Simulates a touch gesture at the specified (x, y) coordinates.
     * Requires accessibility permission with gesture capability.
     */
    fun simulateTouch(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("Accessibility", "Touch simulated at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d("Accessibility", "Touch simulation cancelled at ($x, $y)")
            }
        }, null)
    }
}
