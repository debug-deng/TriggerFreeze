package com.example.triggerfreeze

import android.content.Context
import android.content.SharedPreferences
import com.example.triggerfreeze.model.FreezeRule
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("triggerfreeze_rules", Context.MODE_PRIVATE)

    fun getAllRules(): List<FreezeRule> {
        val json = prefs.getString(KEY_RULES, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            FreezeRule(
                triggerPackage = obj.getString("trigger"),
                frozenPackages = (0 until obj.getJSONArray("frozen").length()).map { i ->
                    obj.getJSONArray("frozen").getString(i)
                }.toSet(),
                isEnabled = obj.optBoolean("enabled", true)
            )
        }
    }

    fun saveAllRules(rules: List<FreezeRule>) {
        val array = JSONArray()
        for (rule in rules) {
            val obj = JSONObject()
            obj.put("trigger", rule.triggerPackage)
            val frozen = JSONArray()
            for (pkg in rule.frozenPackages) {
                frozen.put(pkg)
            }
            obj.put("frozen", frozen)
            obj.put("enabled", rule.isEnabled)
            array.put(obj)
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    fun addRule(rule: FreezeRule) {
        val rules = getAllRules().toMutableList()
        rules.removeAll { it.triggerPackage == rule.triggerPackage }
        rules.add(rule)
        saveAllRules(rules)
    }

    fun removeRule(triggerPackage: String) {
        val rules = getAllRules().toMutableList()
        rules.removeAll { it.triggerPackage == triggerPackage }
        saveAllRules(rules)
    }

    fun toggleRule(triggerPackage: String) {
        val rules = getAllRules().toMutableList()
        val index = rules.indexOfFirst { it.triggerPackage == triggerPackage }
        if (index >= 0) {
            val old = rules[index]
            rules[index] = old.copy(isEnabled = !old.isEnabled)
            saveAllRules(rules)
        }
    }

    fun getRule(triggerPackage: String): FreezeRule? {
        return getAllRules().find { it.triggerPackage == triggerPackage }
    }

    fun isTriggerToastEnabled(): Boolean {
        return prefs.getBoolean(KEY_TRIGGER_TOAST, true)
    }

    fun setTriggerToastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIGGER_TOAST, enabled).apply()
    }

    fun isForceStopEnabled(): Boolean {
        return prefs.getBoolean(KEY_FORCE_STOP_ENABLED, false)
    }

    fun setForceStopEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_STOP_ENABLED, enabled).apply()
    }

    fun getForceStopTimeWindowMin(): Int {
        return prefs.getInt(KEY_FORCE_STOP_WINDOW, 1).coerceIn(1, 10)
    }

    fun setForceStopTimeWindowMin(minutes: Int) {
        prefs.edit().putInt(KEY_FORCE_STOP_WINDOW, minutes.coerceIn(1, 10)).apply()
    }

    fun getForceStopThreshold(): Int {
        return prefs.getInt(KEY_FORCE_STOP_THRESHOLD, 10).coerceIn(3, 100)
    }

    fun setForceStopThreshold(count: Int) {
        prefs.edit().putInt(KEY_FORCE_STOP_THRESHOLD, count.coerceIn(3, 100)).apply()
    }

    fun getFrequentCallAction(): Int {
        // 0 = 强制停止, 1 = 重启软件（强制停止再打开）
        return prefs.getInt(KEY_FREQUENT_CALL_ACTION, 0).coerceIn(0, 1)
    }

    fun setFrequentCallAction(action: Int) {
        prefs.edit().putInt(KEY_FREQUENT_CALL_ACTION, action.coerceIn(0, 1)).apply()
    }

    companion object {
        private const val KEY_RULES = "freeze_rules"
        private const val KEY_TRIGGER_TOAST = "trigger_toast_enabled"
        private const val KEY_FORCE_STOP_ENABLED = "force_stop_enabled"
        private const val KEY_FORCE_STOP_WINDOW = "force_stop_window_min"
        private const val KEY_FORCE_STOP_THRESHOLD = "force_stop_threshold"
        private const val KEY_FREQUENT_CALL_ACTION = "frequent_call_action"
    }
}
