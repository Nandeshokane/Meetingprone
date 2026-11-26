package com.example.meetinglock

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_SCAN_UNLOCK = "extra_auto_scan_unlock"
    }

    private var currentHostSecret: String? = null
    private var showHostTab: Boolean = false
    private var forceShowSetupCard: Boolean = false
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
            Toast.makeText(this, "Session Locked!", Toast.LENGTH_SHORT).show()
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

        // Permission setup controls
        findViewById<MaterialButton>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnEnableOverlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<TextView>(R.id.btnManagePermissions).setOnClickListener {
            forceShowSetupCard = !forceShowSetupCard
            updateUI()
        }

        // Mode tabs (Attendee vs Host)
        findViewById<MaterialButton>(R.id.tabAttendee).setOnClickListener {
            showHostTab = false
            updateModeTabs()
        }

        findViewById<MaterialButton>(R.id.tabHost).setOnClickListener {
            showHostTab = true
            updateModeTabs()
        }

        // Attendee actions
        findViewById<MaterialButton>(R.id.btnScanLockQr).setOnClickListener {
            if (!checkRequiredPermissions()) return@setOnClickListener
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Host Lock QR Code")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            lockQrLauncher.launch(options)
        }

        findViewById<MaterialButton>(R.id.btnQuickTest).setOnClickListener {
            if (!checkRequiredPermissions()) return@setOnClickListener
            val randomSecret = UUID.randomUUID().toString()
            SessionManager.startLock(this, SessionManager.sha256(randomSecret), 60L)
            updateUI()
            Toast.makeText(this, "60-second lockdown active!", Toast.LENGTH_SHORT).show()
        }

        // Host duration preset chips
        val etDuration = findViewById<EditText>(R.id.etDurationMin)
        findViewById<MaterialButton>(R.id.btnPreset5).setOnClickListener { etDuration.setText("5") }
        findViewById<MaterialButton>(R.id.btnPreset15).setOnClickListener { etDuration.setText("15") }
        findViewById<MaterialButton>(R.id.btnPreset30).setOnClickListener { etDuration.setText("30") }
        findViewById<MaterialButton>(R.id.btnPreset60).setOnClickListener { etDuration.setText("60") }

        findViewById<MaterialButton>(R.id.btnHostGenerate).setOnClickListener {
            val minutes = etDuration.text.toString().toLongOrNull()?.coerceIn(1L, 720L) ?: 5L
            currentHostSecret = UUID.randomUUID().toString()
            val hash = SessionManager.sha256(currentHostSecret!!)

            val payload = JSONObject().apply {
                put("duration_sec", minutes * 60L)
                put("unlock_hash", hash)
            }.toString()

            showQrCode(payload, "LOCK QR ($minutes min) — Attendees scan to lock")
            findViewById<MaterialButton>(R.id.btnHostShowUnlock).visibility = View.VISIBLE
        }

        findViewById<MaterialButton>(R.id.btnHostShowUnlock).setOnClickListener {
            currentHostSecret?.let { secret ->
                showQrCode(secret, "UNLOCK QR — Attendees scan to exit lock early")
            }
        }

        // Locked screen allowed actions
        findViewById<MaterialButton>(R.id.btnOpenPhone).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Phone app", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnOpenSms).setOnClickListener {
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

        findViewById<MaterialButton>(R.id.btnScanUnlockQr).setOnClickListener {
            launchUnlockScanner()
        }

        updateModeTabs()
        handleIntentExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_SCAN_UNLOCK, false) == true &&
            SessionManager.isLocked(this)
        ) {
            intent.removeExtra(EXTRA_AUTO_SCAN_UNLOCK)
            launchUnlockScanner()
        }
    }

    private fun launchUnlockScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan Host Unlock QR Code")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        unlockQrLauncher.launch(options)
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

    private fun updateModeTabs() {
        val tabAttendee = findViewById<MaterialButton>(R.id.tabAttendee)
        val tabHost = findViewById<MaterialButton>(R.id.tabHost)
        val panelAttendee = findViewById<View>(R.id.panelAttendee)
        val panelHost = findViewById<View>(R.id.panelHost)

        if (showHostTab) {
            tabHost.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            tabHost.setTextColor(Color.WHITE)
            tabAttendee.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            tabAttendee.setTextColor(Color.parseColor("#94A3B8"))
            panelAttendee.visibility = View.GONE
            panelHost.visibility = View.VISIBLE
        } else {
            tabAttendee.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            tabAttendee.setTextColor(Color.WHITE)
            tabHost.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            tabHost.setTextColor(Color.parseColor("#94A3B8"))
            panelAttendee.visibility = View.VISIBLE
            panelHost.visibility = View.GONE
        }
    }

    private fun checkRequiredPermissions(): Boolean {
        if (!SessionManager.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Enable Step 1 (Accessibility Service) first", Toast.LENGTH_LONG).show()
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable Step 2 (Display Over Other Apps) first", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun showQrCode(content: String, label: String) {
        try {
            val bitmap = BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 640, 640)
            findViewById<ImageView>(R.id.ivQrCode).setImageBitmap(bitmap)
            findViewById<TextView>(R.id.tvQrLabel).text = label
            findViewById<View>(R.id.qrCardContainer).visibility = View.VISIBLE
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
            val overlayEnabled = Settings.canDrawOverlays(this)
            val allReady = accEnabled && overlayEnabled

            val headerBadge = findViewById<TextView>(R.id.tvHeaderStatusBadge)
            if (allReady) {
                headerBadge.text = "READY"
                headerBadge.setTextColor(Color.parseColor("#34D399"))
            } else {
                headerBadge.text = "SETUP NEEDED"
                headerBadge.setTextColor(Color.parseColor("#F87171"))
            }

            findViewById<View>(R.id.cardPermissionsReady).visibility =
                if (allReady) View.VISIBLE else View.GONE
            findViewById<View>(R.id.cardSetupPermissions).visibility =
                if (!allReady || forceShowSetupCard) View.VISIBLE else View.GONE

            findViewById<TextView>(R.id.tvAccessibilityStatus).apply {
                text = if (accEnabled) "Accessibility Shield: ENABLED" else "Accessibility Shield: NOT ENABLED"
                setTextColor(Color.parseColor(if (accEnabled) "#34D399" else "#F87171"))
            }

            findViewById<TextView>(R.id.tvOverlayStatus).apply {
                text = if (overlayEnabled) "Display Over Other Apps: ENABLED" else "Display Over Other Apps: NOT ENABLED"
                setTextColor(Color.parseColor(if (overlayEnabled) "#34D399" else "#F87171"))
            }
        }
    }

    private fun startTimerLoop() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = object : Runnable {
            override fun run() {
                if (SessionManager.isLocked(this@MainActivity)) {
                    val sec = SessionManager.getRemainingSeconds(this@MainActivity)
                    findViewById<TextView>(R.id.tvTimer).text =
                        SessionManager.formatRemainingTime(sec)
                    timerHandler.postDelayed(this, 1000L)
                } else {
                    updateUI()
                }
            }
        }
        timerHandler.post(timerRunnable!!)
    }
}
