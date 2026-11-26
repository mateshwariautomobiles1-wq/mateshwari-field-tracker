package com.fieldtrack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var loginView: View
    private lateinit var statusView: View
    private lateinit var etMobile: EditText
    private lateinit var etPin: EditText
    private lateinit var btnLogin: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLast: TextView
    private lateinit var btnLocation: Button
    private lateinit var btnBackground: Button
    private lateinit var btnNotif: Button
    private lateinit var btnBattery: Button
    private lateinit var btnGps: Button
    private lateinit var btnAdmin: Button

    private val fineLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (!Perms.hasFine(this)) openAppSettings("Location > Allow (choose Precise)")
            refresh()
        }

    private val bgLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!Perms.hasBackground(this)) openAppSettings("Location > choose \"Allow all the time\"")
            refresh()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!Perms.hasNotif(this)) openAppSettings("Turn Notifications on")
            refresh()
        }

    private val gpsLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refresh() }

    private val adminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginView = findViewById(R.id.loginView)
        statusView = findViewById(R.id.statusView)
        etMobile = findViewById(R.id.etMobile)
        etPin = findViewById(R.id.etPin)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)
        tvError = findViewById(R.id.tvError)
        tvName = findViewById(R.id.tvName)
        tvStatus = findViewById(R.id.tvStatus)
        tvLast = findViewById(R.id.tvLast)
        btnLocation = findViewById(R.id.btnLocation)
        btnBackground = findViewById(R.id.btnBackground)
        btnNotif = findViewById(R.id.btnNotif)
        btnBattery = findViewById(R.id.btnBattery)
        btnGps = findViewById(R.id.btnGps)
        btnAdmin = findViewById(R.id.btnAdmin)

        btnLogin.setOnClickListener { doLogin() }
        btnLocation.setOnClickListener {
            fineLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        btnBackground.setOnClickListener {
            if (!Perms.hasFine(this)) {
                toast("First allow the Location permission above")
            } else if (Build.VERSION.SDK_INT >= 29) {
                toast("On the next screen choose \"Allow all the time\"")
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        btnBattery.setOnClickListener { askBattery() }
        btnGps.setOnClickListener { askGps() }
        btnAdmin.setOnClickListener { enableProtection() }

        render()
    }

    override fun onResume() {
        super.onResume()
        Admin.enforceLock(this)
        render()
    }

    private fun render() {
        val loggedIn = Prefs.mobile(this) != null && FirebaseAuth.getInstance().currentUser != null
        loginView.visibility = if (loggedIn) View.GONE else View.VISIBLE
        statusView.visibility = if (loggedIn) View.VISIBLE else View.GONE
        if (loggedIn) refresh()
    }

    private fun refresh() {
        if (Prefs.mobile(this) == null) return
        tvName.text = "${Prefs.name(this) ?: ""}\n${Prefs.mobile(this)}"

        setRow(btnLocation, Perms.hasFine(this), "Location permission")
        setRow(btnBackground, Perms.hasBackground(this), "Location: Allow all the time")
        setRow(btnNotif, Perms.hasNotif(this), "Notification permission")
        setRow(btnBattery, Perms.ignoringBattery(this), "Battery: No restriction")
        setRow(btnGps, Perms.gpsEnabled(this), "Phone Location (GPS) ON")
        renderAdminRow()

        if (Perms.allOk(this)) {
            tvStatus.text = "✅ Tracking is ON"
            tvStatus.setTextColor(getColor(R.color.ok))
            Tracker.start(this)
        } else {
            tvStatus.text = "⚠️ Fix the red settings below"
            tvStatus.setTextColor(getColor(R.color.bad))
            if (Perms.hasFine(this)) Tracker.start(this)
        }

        val last = Prefs.lastFix(this)
        tvLast.text = if (last == 0L) "No location yet"
        else "Last location: " + DateUtils.getRelativeTimeSpanString(last)
    }

    private fun renderAdminRow() {
        when (Admin.level(this)) {
            "owner" -> {
                btnAdmin.text = "✓  Protected — app cannot be removed"
                btnAdmin.isEnabled = false
                btnAdmin.setBackgroundColor(getColor(R.color.okBg))
                btnAdmin.setTextColor(getColor(R.color.ok))
            }
            "admin" -> {
                btnAdmin.text = "✓  Uninstall protection ON"
                btnAdmin.isEnabled = false
                btnAdmin.setBackgroundColor(getColor(R.color.okBg))
                btnAdmin.setTextColor(getColor(R.color.ok))
            }
            else -> {
                btnAdmin.text = "✗  Uninstall protection — Tap to enable"
                btnAdmin.isEnabled = true
                btnAdmin.setBackgroundColor(getColor(R.color.bad))
                btnAdmin.setTextColor(getColor(android.R.color.white))
            }
        }
    }

    private fun enableProtection() {
        if (Admin.level(this) != "off") return
        try {
            adminLauncher.launch(Admin.enableAdminIntent(this))
        } catch (e: Exception) {
            toast("Could not open protection settings")
        }
    }

    private fun setRow(b: Button, ok: Boolean, label: String) {
        b.text = if (ok) "✓  $label" else "✗  $label — Tap"
        b.isEnabled = !ok
        b.setBackgroundColor(getColor(if (ok) R.color.okBg else R.color.bad))
        b.setTextColor(getColor(if (ok) R.color.ok else android.R.color.white))
    }

    private fun doLogin() {
        val mobile = etMobile.text.toString().filter { it.isDigit() }.takeLast(10)
        val pin = etPin.text.toString().trim()
        if (mobile.length != 10) return showError("Enter a 10-digit mobile number")
        if (pin.length < 4) return showError("Enter the PIN (ask your admin)")

        setLoading(true)
        val auth = FirebaseAuth.getInstance()
        auth.signInAnonymously()
            .addOnSuccessListener { res ->
                val uid = res.user!!.uid
                db.collection("sessions").document(uid).set(
                    hashMapOf(
                        "mobile" to mobile,
                        "pin" to pin,
                        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).addOnSuccessListener {
                    db.collection("employees").document(mobile).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists() && doc.getBoolean("active") == true) {
                                Prefs.login(this, mobile, doc.getString("name") ?: "")
                                setLoading(false)
                                render()
                                toast("Logged in. Now turn all settings green.")
                            } else {
                                failLogin("This account is disabled. Contact your admin.")
                            }
                        }
                        .addOnFailureListener { failLogin("Error: ${it.message}") }
                }.addOnFailureListener {
                    failLogin("Wrong mobile number or PIN")
                }
            }
            .addOnFailureListener { failLogin("Check your internet: ${it.message}") }
    }

    private fun failLogin(msg: String) {
        FirebaseAuth.getInstance().signOut()
        setLoading(false)
        showError(msg)
    }

    private fun setLoading(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !on
        if (on) tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            openAppSettings("Battery > choose Unrestricted / No restrictions")
        }
    }

    private fun askGps() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L).build()
        val settingsReq = LocationSettingsRequest.Builder().addLocationRequest(req).setAlwaysShow(true).build()
        LocationServices.getSettingsClient(this).checkLocationSettings(settingsReq)
            .addOnSuccessListener { refresh() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    gpsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                } else {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
    }

    private fun openAppSettings(hint: String) {
        toast(hint)
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
