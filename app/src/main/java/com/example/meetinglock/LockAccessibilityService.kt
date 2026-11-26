package com.example.meetinglock

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

class LockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false

        private var instanceRef: WeakReference<LockAccessibilityService>? = null

        fun onLockStateChanged() {
            instanceRef?.get()?.let { service ->
                service.mainHandler.post { service.syncWatchdogAndEnforce() }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayTimerView: TextView? = null
    private var overlayBlockedAppView: TextView? = null
    private var lastRedirectTime = 0L
    private var watchdogActive = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!SessionManager.isLocked(this@LockAccessibilityService)) {
                hideLockOverlay()
                watchdogActive = false
                return
            }
            enforceLockState(eventPackage = null)
            updateOverlayTimer()
            mainHandler.postDelayed(this, 250L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instanceRef = WeakReference(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        syncWatchdogAndEnforce()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SessionManager.isLocked(this)) {
            hideLockOverlay()
            return
        }
        ensureWatchdogRunning()
        val eventPkg = event?.packageName?.toString()
        enforceLockState(eventPackage = eventPkg)
    }

    private fun syncWatchdogAndEnforce() {
        if (SessionManager.isLocked(this)) {
            ensureWatchdogRunning()
            enforceLockState(eventPackage = null)
        } else {
            watchdogActive = false
            mainHandler.removeCallbacks(watchdogRunnable)
            hideLockOverlay()
        }
    }

    private fun ensureWatchdogRunning() {
        if (!watchdogActive) {
            watchdogActive = true
            mainHandler.removeCallbacks(watchdogRunnable)
            mainHandler.post(watchdogRunnable)
        }
    }

    private fun detectForegroundAppPackage(eventPackage: String?): String? {
        // Inspect interactive TYPE_APPLICATION windows directly (sees Pixel Launcher / apps beneath overlays)
        try {
            val interactiveWindows = windows
            if (!interactiveWindows.isNullOrEmpty()) {
                var activeAppPkg: String? = null
                var topLayer = Int.MIN_VALUE
                for (win in interactiveWindows) {
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val pkg = try {
                            win.root?.packageName?.toString()
                        } catch (_: Exception) {
                            null
                        }
                        if (!pkg.isNullOrBlank()) {
                            if (win.isFocused || win.isActive) {
                                return pkg
                            }
                            if (win.layer > topLayer) {
                                topLayer = win.layer
                                activeAppPkg = pkg
                            }
                        }
                    }
                }
                if (!activeAppPkg.isNullOrBlank()) {
                    return activeAppPkg
                }
            }
        } catch (_: Exception) {}

        // Fallback: rootInActiveWindow
        try {
            val rootPkg = rootInActiveWindow?.packageName?.toString()
            if (!rootPkg.isNullOrBlank() && rootPkg != "com.android.systemui") {
                return rootPkg
            }
        } catch (_: Exception) {}

        // Last fallback: raw event package
        if (!eventPackage.isNullOrBlank() && eventPackage != "com.android.systemui") {
            return eventPackage
        }
        return null
    }

    private fun enforceLockState(eventPackage: String?) {
        if (!SessionManager.isLocked(this)) {
            hideLockOverlay()
            return
        }

        // Allow Android lock screen so incoming calls & unlocking work cleanly
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) {
            hideLockOverlay()
            return
        }

        val foregroundPkg = detectForegroundAppPackage(eventPackage) ?: return
        val allowedPackages = SessionManager.getAllowedAppPackages(this)

        if (allowedPackages.contains(foregroundPkg)) {
            hideLockOverlay()
        } else {
            // 1. Instantly cover Home screen / Recents / Blocked App with full-screen Accessibility Overlay
            showLockOverlay(foregroundPkg)
            // 2. Also pull MeetingLock MainActivity back to front
            bringMainActivityToFront(autoScanUnlock = false)
        }
    }

    private fun launchActivitySafely(intent: Intent, requestCode: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send(this, 0, null, null, null, null, options)
            } else {
                startActivity(intent)
            }
        } catch (_: Exception) {
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun bringMainActivityToFront(autoScanUnlock: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!autoScanUnlock && now - lastRedirectTime < 750L) return
        lastRedirectTime = now

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            if (autoScanUnlock) {
                putExtra(MainActivity.EXTRA_AUTO_SCAN_UNLOCK, true)
            }
        }
        launchActivitySafely(intent, if (autoScanUnlock) 202 else 201)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun roundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun buildOverlayButton(label: String, bgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(bgHex, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showLockOverlay(blockedPkg: String) {
        if (overlayView != null) {
            updateOverlayTimer()
            overlayBlockedAppView?.text = "Blocked exit attempt"
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FA090D16"))
            setPadding(dp(26), dp(40), dp(26), dp(40))
            isClickable = true
            isFocusable = true
        }

        val shieldIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield_lock)
            setColorFilter(Color.parseColor("#EF4444"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2A1215"))
                setStroke(dp(2), Color.parseColor("#EF4444"))
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                bottomMargin = dp(16)
            }
        }

        val title = TextView(this).apply {
            text = "SESSION LOCKED"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Home screen and all other apps are blocked. Only Phone Calls and SMS Messages are permitted."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val timerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#111827", 18, "#1F2937")
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(14)
            }
        }

        val timerText = TextView(this).apply {
            text = SessionManager.formatRemainingTime(
                SessionManager.getRemainingSeconds(this@LockAccessibilityService)
            )
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 42f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        overlayTimerView = timerText

        val timerCaption = TextView(this).apply {
            text = "TIME REMAINING"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        timerCard.addView(timerText)
        timerCard.addView(timerCaption)

        val blockedNotice = TextView(this).apply {
            text = "Blocked exit attempt"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        overlayBlockedAppView = blockedNotice

        val btnPhone = buildOverlayButton("Open Phone (Calls)", "#2563EB") {
            launchActivitySafely(Intent(Intent.ACTION_DIAL), 203)
        }

        val btnSms = buildOverlayButton("Open Messages (SMS)", "#16A34A") {
            val smsIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MESSAGING)
            }
            try {
                launchActivitySafely(smsIntent, 204)
            } catch (_: Exception) {
                launchActivitySafely(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), 205)
            }
        }

        val btnUnlock = buildOverlayButton("Scan Host Unlock QR", "#EA580C") {
            bringMainActivityToFront(autoScanUnlock = true)
        }

        root.addView(shieldIcon)
        root.addView(title)
        root.addView(subtitle)
        root.addView(timerCard)
        root.addView(blockedNotice)
        root.addView(btnPhone)
        root.addView(btnSms)
        root.addView(btnUnlock)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager?.addView(root, params)
            overlayView = root
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun updateOverlayTimer() {
        overlayTimerView?.text = SessionManager.formatRemainingTime(
            SessionManager.getRemainingSeconds(this)
        )
    }

    private fun hideLockOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {}
        overlayView = null
        overlayTimerView = null
        overlayBlockedAppView = null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        watchdogActive = false
        mainHandler.removeCallbacks(watchdogRunnable)
        hideLockOverlay()
        isRunning = false
        instanceRef = null
    }
}
