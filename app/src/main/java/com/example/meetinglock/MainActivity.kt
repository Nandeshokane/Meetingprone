package com.example.meetinglock

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private var currentHostSecret: String? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private val lockQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val json = JSONObject(contents)
            val hash = json.getString("unlock_hash")
            val durationSec = json.getLong("duration_sec")
            if (durationSec <= 0L || hash.length != 64) {
                Toast.makeText(this, "Invalid Lock QR payload", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            SessionManager.startLock(this, hash, durationSec)
            updateUI()
            Toast.makeText(this, "Phone Locked!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid Lock QR Code", Toast.LENGTH_SHORT).show()
        }
    }

    private val unlockQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        if (SessionManager.tryUnlockWithSecret(this, contents)) {
            updateUI()
            Toast.makeText(this, "Session Unlocked!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Wrong Unlock QR Code!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Block Back button / swipe gesture while locked
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!SessionManager.isLocked(this@MainActivity)) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnEnableOverlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnScanLockQr).setOnClickListener {
            if (!checkRequiredPermissions()) return@setOnClickListener
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Host Lock QR Code")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            lockQrLauncher.launch(options)
        }

        findViewById<Button>(R.id.btnQuickTest).setOnClickListener {
            if (!checkRequiredPermissions()) return@setOnClickListener
            val randomSecret = UUID.randomUUID().toString()
            SessionManager.startLock(this, SessionManager.sha256(randomSecret), 60L)
            updateUI()
            Toast.makeText(this, "60-second test lock started!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnHostGenerate).setOnClickListener {
            val minutes = findViewById<EditText>(R.id.etDurationMin)
                .text.toString().toLongOrNull()?.coerceIn(1L, 720L) ?: 5L
            currentHostSecret = UUID.randomUUID().toString()
            val hash = SessionManager.sha256(currentHostSecret!!)

            val payload = JSONObject().apply {
                put("duration_sec", minutes * 60L)
                put("unlock_hash", hash)
            }.toString()

            showQrCode(payload, "LOCK QR ($minutes min) - Attendees scan to lock")
            findViewById<Button>(R.id.btnHostShowUnlock).visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnHostShowUnlock).setOnClickListener {
            currentHostSecret?.let { secret ->
                showQrCode(secret, "UNLOCK QR - Attendees scan to unlock early")
            }
        }

        findViewById<Button>(R.id.btnOpenPhone).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Phone app", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnOpenSms).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MESSAGING)
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
                } catch (ex: Exception) {
                    Toast.makeText(this, "Could not open Messages app", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnScanUnlockQr).setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Host Unlock QR Code")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            unlockQrLauncher.launch(options)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        startTimerLoop()
    }

    override fun onPause() {
        super.onPause()
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
    }

    private fun checkRequiredPermissions(): Boolean {
        if (!SessionManager.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Please enable Step 1 (Accessibility Service) first", Toast.LENGTH_LONG).show()
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable Step 2 (Display Over Other Apps) first", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun showQrCode(content: String, label: String) {
        try {
            val bitmap = BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 640, 640)
            findViewById<ImageView>(R.id.ivQrCode).apply {
                setImageBitmap(bitmap)
                visibility = View.VISIBLE
            }
            findViewById<TextView>(R.id.tvQrLabel).apply {
                text = label
                visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error generating QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        val locked = SessionManager.isLocked(this)
        findViewById<View>(R.id.menuLayout).visibility = if (locked) View.GONE else View.VISIBLE
        findViewById<View>(R.id.lockedLayout).visibility = if (locked) View.VISIBLE else View.GONE

        if (!locked) {
            val accEnabled = SessionManager.isAccessibilityEnabled(this)
            findViewById<TextView>(R.id.tvAccessibilityStatus).apply {
                text = if (accEnabled) "Accessibility Lock: ENABLED" else "Accessibility Lock: NOT ENABLED"
                setTextColor(if (accEnabled) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            }

            val overlayEnabled = Settings.canDrawOverlays(this)
            findViewById<TextView>(R.id.tvOverlayStatus).apply {
                text = if (overlayEnabled) "Display Over Other Apps: ENABLED" else "Display Over Other Apps: NOT ENABLED"
                setTextColor(if (overlayEnabled) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            }
        }
    }

    private fun startTimerLoop() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = object : Runnable {
            override fun run() {
                if (SessionManager.isLocked(this@MainActivity)) {
                    val sec = SessionManager.getRemainingSeconds(this@MainActivity)
                    val hrs = sec / 3600
                    val mins = (sec % 3600) / 60
                    val remSec = sec % 60
                    val formatted = if (hrs > 0) {
                        String.format(Locale.US, "%02d:%02d:%02d remaining", hrs, mins, remSec)
                    } else {
                        String.format(Locale.US, "%02d:%02d remaining", mins, remSec)
                    }
                    findViewById<TextView>(R.id.tvTimer).text = formatted
                    timerHandler.postDelayed(this, 1000L)
                } else {
                    updateUI()
                }
            }
        }
        timerHandler.post(timerRunnable!!)
    }
}
