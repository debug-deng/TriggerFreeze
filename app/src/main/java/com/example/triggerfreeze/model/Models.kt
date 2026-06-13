package com.example.triggerfreeze.model

data class AppInfo(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

data class TriggerLogEntry(
    val timestamp: Long,
    val type: Type,
    val triggerPackage: String?,
    val targetPackage: String,
    val success: Boolean,
    val detail: String
) {
    enum class Type { TRIGGER, FREEZE, UNFREEZE, ERROR, SYSTEM }
}

data class FreezeRule(
    val triggerPackage: String,
    val frozenPackages: Set<String>,
    val isEnabled: Boolean = true
)

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean
        get() {
            val output = "$stdout\n$stderr"
            return exitCode == 0 &&
                !output.contains("Permission Denial", ignoreCase = true) &&
                !output.contains("not found", ignoreCase = true) &&
                !output.contains("Unknown command", ignoreCase = true)
        }
}
