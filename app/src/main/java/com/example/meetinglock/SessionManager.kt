package com.example.meetinglock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.security.MessageDigest

object SessionManager {
    private const val PREFS = "meeting_lock_prefs"
    private const val KEY_LOCKED = "is_locked"
    private const val KEY_HASH = "unlock_hash"
    private const val KEY_START_REALTIME = "start_realtime"
    private const val KEY_END_REALTIME = "end_realtime"
    private const val KEY_END_WALLTIME = "end_walltime"

    fun startLock(context: Context, unlockHash: String, durationSeconds: Long) {
        val nowRealtime = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val durationMs = durationSeconds * 1000L

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCKED, true)
            .putString(KEY_HASH, unlockHash)
            .putLong(KEY_START_REALTIME, nowRealtime)
            .putLong(KEY_END_REALTIME, nowRealtime + durationMs)
            .putLong(KEY_END_WALLTIME, nowWall + durationMs)
            .apply()
    }

    fun isLocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOCKED, false)) return false

        if (getRemainingSeconds(context) <= 0L) {
            unlock(context)
            return false
        }
        return true
    }

    fun getRemainingSeconds(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOCKED, false)) return 0L

        val startRealtime = prefs.getLong(KEY_START_REALTIME, 0L)
        val nowRealtime = SystemClock.elapsedRealtime()

        // If device rebooted, elapsedRealtime resets below startRealtime -> fallback to wall clock
        val remainingMs = if (nowRealtime < startRealtime) {
            prefs.getLong(KEY_END_WALLTIME, 0L) - System.currentTimeMillis()
        } else {
            prefs.getLong(KEY_END_REALTIME, 0L) - nowRealtime
        }
        return (remainingMs / 1000L).coerceAtLeast(0L)
    }

    fun tryUnlockWithSecret(context: Context, secret: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedHash = prefs.getString(KEY_HASH, null) ?: return false
        if (sha256(secret.trim()) == savedHash) {
            unlock(context)
            return true
        }
        return false
    }

    fun unlock(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCKED, false)
            .remove(KEY_HASH)
            .remove(KEY_START_REALTIME)
            .remove(KEY_END_REALTIME)
            .remove(KEY_END_WALLTIME)
            .apply()
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (LockAccessibilityService.isRunning) return true
        val expectedFull = "${context.packageName}/${LockAccessibilityService::class.java.name}"
        val expectedShort = "${context.packageName}/.LockAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any {
            it.equals(expectedFull, ignoreCase = true) ||
                it.equals(expectedShort, ignoreCase = true)
        }
    }

    fun getAllowedPackages(context: Context): Set<String> {
        val allowed = mutableSetOf(
            context.packageName,
            // Pixel 7a core Phone, Messages, Keyboard, System UI & Permission Dialogs
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.inputmethod.latin",
            "com.android.systemui",
            "com.android.server.telecom",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )

        // Dynamically add current default Dialer
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { allowed.add(it) }
        } catch (_: Exception) {}

        // Dynamically add current default SMS app
        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let { allowed.add(it) }
        } catch (_: Exception) {}

        // Dynamically add all enabled keyboards
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { allowed.add(it.packageName) }
        } catch (_: Exception) {}

        return allowed
    }
}
