package com.fieldtrack.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * App removal protection.
 *
 * Two levels:
 *  - DEVICE OWNER  : company phone provisioned once (QR / ADB) -> app CANNOT be
 *                    uninstalled at all (uninstall + factory reset are blocked).
 *  - DEVICE ADMIN  : user must first disable admin to uninstall; the moment they
 *                    try, the office is alerted and it is logged.
 */
object Admin {
    private const val TAG = "Admin"

    fun component(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) =
        c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(c: Context): Boolean =
        try { dpm(c).isDeviceOwnerApp(c.packageName) } catch (e: Exception) { false }

    fun isAdminActive(c: Context): Boolean =
        try { dpm(c).isAdminActive(component(c)) } catch (e: Exception) { false }

    /** "owner" = fully locked, "admin" = protected, "off" = removable */
    fun level(c: Context): String =
        if (isDeviceOwner(c)) "owner" else if (isAdminActive(c)) "admin" else "off"

    /** As Device Owner, block uninstall and factory reset. Safe no-op otherwise. */
    fun enforceLock(c: Context) {
        if (!isDeviceOwner(c)) return
        val p = dpm(c)
        val admin = component(c)
        try { p.setUninstallBlocked(admin, c.packageName, true) } catch (e: Exception) { Log.w(TAG, "block uninstall: ${e.message}") }
        try { p.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) {}
        try { p.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) } catch (e: Exception) {}
    }

    /** Intent that asks the user to turn on Device Admin (used on non-owner phones). */
    fun enableAdminIntent(c: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Turn this on so the duty tracking app stays installed. " +
                    "The office is notified if anyone tries to remove it."
            )

    /** Record in Firestore that someone tried to remove protection / the app. */
    fun reportRemovalAttempt(c: Context) {
        val mobile = Prefs.mobile(c) ?: return
        try {
            FirebaseFirestore.getInstance().collection("live").document(mobile).set(
                hashMapOf(
                    "mobile" to mobile,
                    "name" to (Prefs.name(c) ?: ""),
                    "removalAttemptAt" to FieldValue.serverTimestamp(),
                    "protection" to level(c)
                ),
                SetOptions.merge()
            )
        } catch (e: Exception) {
            Log.w(TAG, "report removal: ${e.message}")
        }
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Admin.enforceLock(context)
    }

    /** Shown to the user when they try to turn OFF device admin (first step of uninstall). */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Admin.reportRemovalAttempt(context)
        return "Duty tracking is required on this company device. " +
            "The office has been notified of this attempt."
    }
}
