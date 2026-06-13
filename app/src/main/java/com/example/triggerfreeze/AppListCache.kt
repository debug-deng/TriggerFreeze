package com.example.triggerfreeze

import android.content.Context
import com.example.triggerfreeze.model.AppInfo
import org.json.JSONArray
import org.json.JSONObject

object AppListCache {

    private const val PREFS_NAME = "app_list_cache"
    private const val KEY_CACHE = "cached_apps"
    private const val KEY_TIMESTAMP = "cache_timestamp"
    private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 24 小时

    fun save(context: Context, apps: List<AppInfo>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (app in apps) {
            array.put(JSONObject().apply {
                put("label", app.label)
                put("packageName", app.packageName)
                put("isSystem", app.isSystem)
            })
        }
        prefs.edit()
            .putString(KEY_CACHE, array.toString())
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): List<AppInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHE, null) ?: return emptyList()
        val array = JSONArray(json)
        val apps = mutableListOf<AppInfo>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            apps.add(
                AppInfo(
                    label = obj.getString("label"),
                    packageName = obj.getString("packageName"),
                    isSystem = obj.optBoolean("isSystem", false)
                )
            )
        }
        return apps
    }

    fun isExpired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        return timestamp == 0L || System.currentTimeMillis() - timestamp > MAX_CACHE_AGE_MS
    }

    fun hasCache(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CACHE)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
