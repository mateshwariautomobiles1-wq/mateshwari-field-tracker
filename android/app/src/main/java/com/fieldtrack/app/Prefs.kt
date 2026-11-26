package com.fieldtrack.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("field_tracker", Context.MODE_PRIVATE)

    fun mobile(c: Context): String? = sp(c).getString("mobile", null)
    fun name(c: Context): String? = sp(c).getString("name", null)
    fun lastFix(c: Context): Long = sp(c).getLong("last_fix", 0L)

    fun login(c: Context, mobile: String, name: String) =
        sp(c).edit().putString("mobile", mobile).putString("name", name).apply()

    fun setName(c: Context, name: String) = sp(c).edit().putString("name", name).apply()
    fun setLastFix(c: Context, t: Long) = sp(c).edit().putLong("last_fix", t).apply()
    fun clear(c: Context) = sp(c).edit().clear().apply()
}
