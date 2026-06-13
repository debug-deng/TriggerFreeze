package com.example.triggerfreeze

import com.example.triggerfreeze.model.CommandResult
import com.example.triggerfreeze.model.TriggerLogEntry

object FreezeManager {

    private val frozenSet = mutableSetOf<String>()

    fun isFrozen(packageName: String): Boolean = packageName in frozenSet

    fun markFrozen(packageName: String) {
        frozenSet.add(packageName)
    }

    suspend fun suspendPackage(packageName: String, triggerPackage: String? = null): CommandResult {
        val t = System.currentTimeMillis()
        val command = "pm suspend ${shellQuote(packageName)}"
        val result = ShizukuExecutor.run(command)
        val elapsed = System.currentTimeMillis() - t
        if (result.isSuccess) {
            frozenSet.add(packageName)
        }
        RuntimeLog.timing("suspendPackage($packageName)", elapsed)
        TriggerLogger.add(
            type = if (result.isSuccess) TriggerLogEntry.Type.FREEZE else TriggerLogEntry.Type.ERROR,
            triggerPackage = triggerPackage,
            targetPackage = packageName,
            success = result.isSuccess,
            detail = if (result.isSuccess) "pm suspend 成功" else result.stderr.ifBlank { result.stdout }
        )
        return result
    }

    suspend fun unsuspendPackage(packageName: String, triggerPackage: String? = null): CommandResult {
        val t = System.currentTimeMillis()
        val command = "pm unsuspend ${shellQuote(packageName)}"
        val result = ShizukuExecutor.run(command)
        val elapsed = System.currentTimeMillis() - t
        if (result.isSuccess) {
            frozenSet.remove(packageName)
        }
        RuntimeLog.timing("unsuspendPackage($packageName)", elapsed)
        TriggerLogger.add(
            type = if (result.isSuccess) TriggerLogEntry.Type.UNFREEZE else TriggerLogEntry.Type.ERROR,
            triggerPackage = triggerPackage,
            targetPackage = packageName,
            success = result.isSuccess,
            detail = if (result.isSuccess) "pm unsuspend 成功" else result.stderr.ifBlank { result.stdout }
        )
        return result
    }

    suspend fun getSuspendedPackages(): Set<String> {
        val command = "pm list packages --suspended 2>/dev/null"
        val result = ShizukuExecutor.run(command)
        if (!result.isSuccess) return emptySet()
        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val prefix = "package:"
                if (line.startsWith(prefix)) line.removePrefix(prefix).trim() else null
            }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun clearFrozenCache() {
        frozenSet.clear()
    }

    suspend fun forceStopPackage(packageName: String): CommandResult {
        val command = "am force-stop ${shellQuote(packageName)}"
        val result = ShizukuExecutor.run(command)
        TriggerLogger.add(
            type = if (result.isSuccess) TriggerLogEntry.Type.UNFREEZE else TriggerLogEntry.Type.ERROR,
            triggerPackage = null,
            targetPackage = packageName,
            success = result.isSuccess,
            detail = if (result.isSuccess) "已强制停止（频繁调用）" else "强制停止失败: ${result.stderr}"
        )
        return result
    }

    suspend fun restartPackage(packageName: String): CommandResult {
        // 先强制停止
        forceStopPackage(packageName)
        // 再重新启动（使用 monkey 启动主 Activity）
        val command = "monkey -p ${shellQuote(packageName)} 1 2>/dev/null"
        val result = ShizukuExecutor.run(command)
        val success = result.isSuccess && !result.stderr.contains("monkey aborted")
        TriggerLogger.add(
            type = if (success) TriggerLogEntry.Type.TRIGGER else TriggerLogEntry.Type.ERROR,
            triggerPackage = null,
            targetPackage = packageName,
            success = success,
            detail = if (success) "已重启软件（频繁调用）" else "重启失败: ${result.stderr}"
        )
        return result
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }
}
