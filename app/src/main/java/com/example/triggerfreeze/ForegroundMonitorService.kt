package com.example.triggerfreeze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.triggerfreeze.model.FreezeRule
import com.example.triggerfreeze.model.TriggerLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ForegroundMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { PreferencesManager(this) }

    private var currentForeground: String? = null
    private val pendingUnfreeze = mutableMapOf<String, Long>()
    private var logcatProcess: Process? = null
    private var logcatJob: Job? = null
    private val blockedAttemptTracker = BlockedAttemptTracker { pkg ->
        scope.launch {
            val action = prefs.getFrequentCallAction()
            val msg: String
            if (action == 1) {
                FreezeManager.restartPackage(pkg)
                msg = "频繁调用外部软件，已重启"
            } else {
                FreezeManager.forceStopPackage(pkg)
                msg = "频繁调用外部软件，已强行停止运行"
            }
            handler.post {
                Toast.makeText(
                    this@ForegroundMonitorService,
                    msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return
            pollForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        if (intent?.getBooleanExtra(EXTRA_SYNC, false) == true) {
            scope.launch {
                FreezeManager.clearFrozenCache()
                val suspended = FreezeManager.getSuspendedPackages()
                suspended.forEach { FreezeManager.markFrozen(it) }
            }
        }
        handler.post(monitorRunnable)
        if (prefs.isForceStopEnabled()) {
            startLogcatMonitor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        handler.removeCallbacks(monitorRunnable)
        stopLogcatMonitor()
        scope.launch {
            val allFrozen = FreezeManager.getSuspendedPackages()
            val rules = prefs.getAllRules().filter { it.isEnabled }
            val ruleTargets = rules.flatMap { it.frozenPackages }.toSet()
            for (pkg in allFrozen) {
                if (pkg in ruleTargets) {
                    FreezeManager.unsuspendPackage(pkg)
                }
            }
        }
        super.onDestroy()
    }

    private fun pollForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val foreground = runCatching { getForegroundPackage() }
            .onFailure { e ->
                TriggerLogger.add(
                    type = TriggerLogEntry.Type.ERROR,
                    triggerPackage = null,
                    targetPackage = "",
                    success = false,
                    detail = "获取前台应用失败：${e.message}"
                )
            }
            .getOrNull() ?: return

        if (foreground != currentForeground) {
            currentForeground = foreground
        }

        val rules = prefs.getAllRules().filter { it.isEnabled }
        scope.launch {
            applyRules(foreground, rules)
        }
    }

    private suspend fun applyRules(foregroundPackage: String, rules: List<FreezeRule>) {
        val activeRule = rules.find { it.triggerPackage == foregroundPackage }

        val shouldBeFrozen = activeRule?.frozenPackages ?: emptySet()
        val allRuleTargets = rules.flatMap { it.frozenPackages }.toSet()

        if (activeRule != null) {
            TriggerLogger.add(
                type = TriggerLogEntry.Type.TRIGGER,
                triggerPackage = foregroundPackage,
                targetPackage = shouldBeFrozen.joinToString(", "),
                success = true,
                detail = "触发规则：${activeRule.frozenPackages.size} 个应用将被冻结"
            )

            if (prefs.isTriggerToastEnabled()) {
                val frozenCount = shouldBeFrozen.size
                handler.post {
                    Toast.makeText(
                        this@ForegroundMonitorService,
                        "跳转屏蔽已开启（$frozenCount 个应用）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 冻结：每次轮询都执行，防止缓存与系统状态不一致导致漏冻结
        for (pkg in shouldBeFrozen) {
            FreezeManager.suspendPackage(pkg, triggerPackage = foregroundPackage)
        }

        // 解冻：延迟 5 秒执行，防止快速切回触发应用时出现空隙
        val now = System.currentTimeMillis()
        if (activeRule == null) {
            // 不在触发应用前台 → 记录待解冻时间
            for (pkg in allRuleTargets) {
                if (!pendingUnfreeze.containsKey(pkg)) {
                    pendingUnfreeze[pkg] = now
                }
            }
            // 解冻超过延迟时间的包
            for (pkg in allRuleTargets) {
                val queuedSince = pendingUnfreeze[pkg] ?: continue
                if (now - queuedSince >= UNFREEZE_DELAY_MS) {
                    FreezeManager.unsuspendPackage(pkg, triggerPackage = foregroundPackage)
                    pendingUnfreeze.remove(pkg)
                }
            }
        } else {
            // 正在触发规则 → 清除所有待解冻
            pendingUnfreeze.clear()
        }
    }

    private fun startLogcatMonitor() {
        logcatJob = scope.launch {
            while (isServiceRunning) {
                try {
                    val proc = ShizukuExecutor.startLongRunning(
                        "logcat -s ActivityManager:* --regex=\"Forbidding suspended package\" 2>/dev/null"
                    )
                    if (proc == null) {
                        delay(5000)
                        continue
                    }
                    logcatProcess = proc
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    while (isServiceRunning && proc.isAlive) {
                        line = try { reader.readLine() } catch (_: Exception) { null }
                        if (line == null) break
                        val pkg = parseBlockedPackage(line)
                        if (pkg != null) {
                            blockedAttemptTracker.record(pkg)
                        }
                    }
                    proc.destroy()
                    logcatProcess = null
                } catch (_: Exception) {
                    // ignore, will retry
                }
                delay(3000)
            }
        }
    }

    private fun stopLogcatMonitor() {
        logcatJob?.cancel()
        logcatJob = null
        logcatProcess?.destroy()
        logcatProcess = null
        blockedAttemptTracker.reset()
    }

    /**
     * Parse "Forbidding suspended package <pkgName> from starting intent"
     */
    private fun parseBlockedPackage(line: String): String? {
        val prefix = "Forbidding suspended package "
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val after = start + prefix.length
        val end = line.indexOf(' ', after)
        return if (end < 0) line.substring(after) else line.substring(after, end)
    }

    /**
     * Tracks blocked attempts per package using a sliding time window.
     * Calls [onThresholdExceeded] when the count in the window reaches the threshold.
     */
    private inner class BlockedAttemptTracker(
        private val onThresholdExceeded: (String) -> Unit
    ) {
        private val attempts = mutableMapOf<String, MutableList<Long>>()

        @Synchronized
        fun record(pkg: String) {
            val now = System.currentTimeMillis()
            val timeWindowMs = prefs.getForceStopTimeWindowMin() * 60_000L
            val threshold = prefs.getForceStopThreshold()
            val list = attempts.getOrPut(pkg) { mutableListOf() }
            list.add(now)
            // Remove entries outside the window
            val cutoff = now - timeWindowMs
            list.removeAll { it < cutoff }
            if (list.size >= threshold) {
                list.clear()
                onThresholdExceeded(pkg)
            }
        }

        @Synchronized
        fun reset() {
            attempts.clear()
        }
    }

    private fun getForegroundPackage(): String? {
        // Method 1: UsageStatsManager.queryEvents (requires PACKAGE_USAGE_STATS permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val endTime = System.currentTimeMillis() + 100
                val beginTime = endTime - POLL_INTERVAL_MS - 500
                val events = usm.queryEvents(beginTime, endTime)
                var foreground: String? = null
                while (events.hasNextEvent()) {
                    val event = UsageEvents.Event()
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    ) {
                        foreground = event.packageName
                    }
                }
                if (foreground != null) return foreground
            } catch (_: SecurityException) {
                // Usage Stats permission not granted — fall through to method 2
            }
        }

        // Method 2: RunningAppProcessInfo (fallback, no special permission needed)
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.runningAppProcesses?.filterNotNull() ?: emptyList()
            tasks.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
                ?.processName
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TriggerFreeze 监控中")
            .setContentText("正在监控前台应用变化")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "triggerfreeze_monitor"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 1500L
        private const val UNFREEZE_DELAY_MS = 5000L
        private const val EXTRA_SYNC = "sync_frozen_state"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ForegroundMonitorService::class.java).apply {
                putExtra(EXTRA_SYNC, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            isServiceRunning = false
            context.stopService(Intent(context, ForegroundMonitorService::class.java))
        }

        fun restartLogcat(context: Context) {
            val intent = Intent(context, ForegroundMonitorService::class.java)
            context.startService(intent)
        }
    }
}
