package com.example.triggerfreeze

import android.content.pm.PackageManager
import com.example.triggerfreeze.model.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

private suspend fun executeProcess(start: () -> Process): CommandResult {
    var process: Process? = null
    return try {
        withTimeout(15_000) {
            process = withContext(Dispatchers.IO) { start() }
            collectProcess(process!!)
        }
    } catch (error: Throwable) {
        process?.destroy()
        CommandResult(-1, "", error.message ?: error::class.java.simpleName)
    }
}

private suspend fun collectProcess(process: Process): CommandResult = coroutineScope {
    val stdout = async(Dispatchers.IO) {
        process.inputStream.bufferedReader().use { it.readText() }
    }
    val stderr = async(Dispatchers.IO) {
        process.errorStream.bufferedReader().use { it.readText() }
    }
    val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
    CommandResult(exitCode, stdout.await(), stderr.await())
}

object ShizukuExecutor {
    fun isReady(): Boolean {
        return Shizuku.pingBinder() &&
            (Shizuku.isPreV11() ||
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }

    suspend fun run(command: String): CommandResult {
        if (!Shizuku.pingBinder()) {
            return CommandResult(-1, "", "Shizuku 未连接")
        }
        if (!Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        ) {
            return CommandResult(-1, "", "Shizuku 未授权")
        }
        return executeProcess {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        }
    }

    fun startLongRunning(command: String): Process? {
        if (!Shizuku.pingBinder()) return null
        if (!Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (_: Throwable) {
            null
        }
    }
}
