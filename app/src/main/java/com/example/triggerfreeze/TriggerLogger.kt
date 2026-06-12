package com.example.triggerfreeze

import android.content.Context
import com.example.triggerfreeze.model.TriggerLogEntry
import org.json.JSONArray
import org.json.JSONObject

object TriggerLogger {

    private val logs = mutableListOf<TriggerLogEntry>()
    private const val MAX_LOGS = 500
    private const val PREFS_KEY = "trigger_logs"
    private const val PREFS_NAME = "triggerfreeze_logs"

    val allLogs: List<TriggerLogEntry> get() = logs.toList()

    fun add(entry: TriggerLogEntry) {
        synchronized(logs) {
            logs.add(0, entry)
            if (logs.size > MAX_LOGS) {
                logs.removeAt(logs.lastIndex)
            }
        }
    }

    fun add(
        type: TriggerLogEntry.Type,
        triggerPackage: String?,
        targetPackage: String,
        success: Boolean,
        detail: String = ""
    ) {
        add(TriggerLogEntry(
            timestamp = System.currentTimeMillis(),
            type = type,
            triggerPackage = triggerPackage,
            targetPackage = targetPackage,
            success = success,
            detail = detail
        ))
    }

    fun clear() {
        synchronized(logs) { logs.clear() }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        synchronized(logs) {
            for (entry in logs) {
                array.put(JSONObject().apply {
                    put("ts", entry.timestamp)
                    put("type", entry.type.name)
                    put("trigger", entry.triggerPackage ?: JSONObject.NULL)
                    put("target", entry.targetPackage)
                    put("success", entry.success)
                    put("detail", entry.detail)
                })
            }
        }
        prefs.edit().putString(PREFS_KEY, array.toString()).apply()
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        val loaded = mutableListOf<TriggerLogEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val entry = TriggerLogEntry(
                timestamp = obj.getLong("ts"),
                type = TriggerLogEntry.Type.valueOf(obj.getString("type")),
                triggerPackage = obj.optString("trigger", "").ifBlank { null },
                targetPackage = obj.getString("target"),
                success = obj.getBoolean("success"),
                detail = obj.optString("detail", "")
            )
            loaded.add(entry)
        }
        synchronized(logs) {
            logs.clear()
            logs.addAll(loaded)
        }
    }
}
