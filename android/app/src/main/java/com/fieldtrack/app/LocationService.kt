package com.fieldtrack.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlin.math.max

/**
 * Background (foreground-service) location tracking.
 * - Har ~1 minute GPS fix leta hai
 * - History me point tab save hota hai jab 30m+ move ho ya 10 min ho jaye
 * - "live/{mobile}" doc har 2 min (ya move hone par) update hota hai
 * - Internet na ho to Firestore offline save karke baad me bhej deta hai
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_TRACK = "tracking"
        private const val CHANNEL_ALERT = "alerts"
        private const val NOTIF_ID = 101
        private const val ALERT_ID = 102
        private const val FIX_INTERVAL_MS = 60_000L
        private const val SAVE_DIST_M = 30f
        private const val SAVE_EVERY_MS = 10 * 60_000L
        private const val LIVE_EVERY_MS = 2 * 60_000L
        private const val HEARTBEAT_MS = 5 * 60_000L
    }

    private lateinit var fused: FusedLocationProviderClient
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())

    private var mobile: String? = null
    private var updatesStarted = false
    private var lastSaved: Location? = null
    private var lastLive: Location? = null
    private var lastLiveAt = 0L
    private var lastGpsState: Boolean? = null
    private var empListener: ListenerRegistration? = null
    private var loggingOut = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { onFix(it) }
        }

        override fun onLocationAvailability(a: LocationAvailability) {
            if (!a.isLocationAvailable) onGpsChanged()
        }
    }

    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onGpsChanged()
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastLiveAt >= HEARTBEAT_MS) {
                writeLive(mapOf("gpsOn" to Perms.gpsEnabled(this@LocationService)))
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannels()
        ContextCompat.registerReceiver(
            this, gpsReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android rule: startForegroundService ke baad turant startForeground zaroori hai
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(Perms.gpsEnabled(this)),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground fail: ${e.message}")
            loggingOut = true // khud band ho rahe hain, restart loop nahi
            stopSelf()
            return START_NOT_STICKY
        }

        mobile = Prefs.mobile(this)
        if (mobile == null || !Perms.hasFine(this)) {
            loggingOut = true
            stopSelf()
            return START_NOT_STICKY
        }

        Admin.enforceLock(this)
        startUpdates()
        watchEmployee()
        onGpsChanged(force = true)
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updatesStarted) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(30_000L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            updatesStarted = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission nahi hai")
        }
    }

    /** Admin ne employee delete / inactive kiya to tracking band */
    private fun watchEmployee() {
        val m = mobile ?: return
        if (empListener != null) return
        empListener = db.collection("employees").document(m).addSnapshotListener { snap, err ->
            if (err != null || snap == null || snap.metadata.isFromCache) return@addSnapshotListener
            if (!snap.exists() || snap.getBoolean("active") != true) {
                forceLogout()
            } else {
                snap.getString("name")?.let { Prefs.setName(this, it) }
            }
        }
    }

    private fun onFix(loc: Location) {
        val m = mobile ?: return
        val now = System.currentTimeMillis()
        Prefs.setLastFix(this, now)
        val ts = Timestamp(Date(loc.time))
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

        val prev = lastSaved
        val moved = prev == null || loc.distanceTo(prev) >= max(SAVE_DIST_M, loc.accuracy)
        val due = prev == null || loc.time - prev.time >= SAVE_EVERY_MS
        if (loc.accuracy <= 150f && (moved || due)) {
            db.collection("employees").document(m).collection("locations").add(
                hashMapOf(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "acc" to loc.accuracy.toDouble(),
                    "speed" to speed,
                    "battery" to battery(),
                    "t" to ts
                )
            )
            lastSaved = loc
        }

        val pl = lastLive
        if (pl == null || loc.distanceTo(pl) >= SAVE_DIST_M || now - lastLiveAt >= LIVE_EVERY_MS) {
            writeLive(
                mapOf(
                    "lat" to loc.latitude, "lng" to loc.longitude,
                    "acc" to loc.accuracy.toDouble(), "speed" to speed,
                    "t" to ts, "gpsOn" to true
                )
            )
            lastLive = loc
        }
        if (lastGpsState != true) onGpsChanged(force = true)
    }

    private fun writeLive(extra: Map<String, Any>) {
        val m = mobile ?: return
        lastLiveAt = System.currentTimeMillis()
        val data = hashMapOf<String, Any>(
            "mobile" to m,
            "name" to (Prefs.name(this) ?: ""),
            "battery" to battery(),
            "charging" to charging(),
            "seenAt" to Timestamp.now(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "appVersion" to BuildConfig.VERSION_NAME,
            "protection" to Admin.level(this)
        )
        data.putAll(extra)
        db.collection("live").document(m).set(data, SetOptions.merge())
    }

    private fun onGpsChanged(force: Boolean = false) {
        val on = Perms.gpsEnabled(this)
        if (!force && on == lastGpsState) return
        lastGpsState = on
        writeLive(mapOf("gpsOn" to on))
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(on))
        if (on) nm.cancel(ALERT_ID) else nm.notify(ALERT_ID, buildGpsAlert())
    }

    private fun forceLogout() {
        if (loggingOut) return
        loggingOut = true
        Tracker.logout(this)
        stopSelf()
    }

    private fun battery(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun charging(): Boolean =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACK, "Duty Tracking", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Location Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(gpsOn: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_TRACK)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle(if (gpsOn) "Duty tracking is ON" else "Location is OFF!")
            .setContentText(
                if (gpsOn) "Your location is being shared with the office"
                else "Please turn ON phone Location (GPS)"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildGpsAlert(): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Location is OFF")
            .setContentText("Tap to turn Location ON. The office has been alerted.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Prefs.mobile(this) != null) Tracker.kick(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        updatesStarted = false
        try { unregisterReceiver(gpsReceiver) } catch (_: Exception) {}
        empListener?.remove()
        empListener = null
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        if (!loggingOut && Prefs.mobile(this) != null) Tracker.kick(this)
        super.onDestroy()
    }
}
