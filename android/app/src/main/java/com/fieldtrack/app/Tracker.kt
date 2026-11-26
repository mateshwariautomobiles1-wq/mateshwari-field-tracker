package com.fieldtrack.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

object Tracker {
    private const val TAG = "Tracker"

    /** Service chalu karo + har 15 min watchdog lagao */
    fun start(c: Context) {
        startService(c)
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun startService(c: Context) {
        if (Prefs.mobile(c) == null || !Perms.hasFine(c)) return
        try {
            ContextCompat.startForegroundService(c, Intent(c, LocationService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Service start nahi hui: ${e.message}")
        }
    }

    /** App band/swipe hone par turant dobara chalane ki koshish */
    fun kick(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "kick", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchdogWorker>().build()
        )
    }

    fun logout(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork("watchdog")
        Prefs.clear(c)
        FirebaseAuth.getInstance().signOut()
        c.stopService(Intent(c, LocationService::class.java))
    }
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (Prefs.mobile(applicationContext) != null) Tracker.startService(applicationContext)
        return Result.success()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.mobile(c) != null) Tracker.start(c)
    }
}
