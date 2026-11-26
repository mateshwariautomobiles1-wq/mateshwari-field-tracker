package com.fieldtrack.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object Perms {
    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun hasFine(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(c: Context) =
        if (Build.VERSION.SDK_INT >= 29) granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) else hasFine(c)

    fun hasNotif(c: Context) =
        if (Build.VERSION.SDK_INT >= 33) granted(c, Manifest.permission.POST_NOTIFICATIONS) else true

    fun ignoringBattery(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    fun gpsEnabled(c: Context): Boolean {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun allOk(c: Context) =
        hasFine(c) && hasBackground(c) && hasNotif(c) && ignoringBattery(c) && gpsEnabled(c)
}
