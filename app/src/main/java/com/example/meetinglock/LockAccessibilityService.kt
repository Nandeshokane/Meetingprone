package com.example.meetinglock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class LockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }

    private var lastRedirectTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!SessionManager.isLocked(this)) return

        val currentPackage = event.packageName?.toString() ?: return
        if (currentPackage.isBlank()) return

        val allowedPackages = SessionManager.getAllowedPackages(this)
        if (!allowedPackages.contains(currentPackage)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRedirectTime < 300L) return
            lastRedirectTime = now

            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
