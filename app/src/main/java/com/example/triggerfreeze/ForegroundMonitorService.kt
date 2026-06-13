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
    private var pollCount = 0
    private var lastPollTime = 0L
    private var totalPollTime = 0L
    private var consecutiveSameForeground = 0
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
        RuntimeLog.d("服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        pollCount = 0
        totalPollTime = 0L
        RuntimeLog.d("服务启动中...")
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        if (intent?.getBooleanExtra(EXTRA_SYNC, false) == true) {
            scope.launch {
                RuntimeLog.d("开始同步已冻结状态")
                FreezeManager.clearFrozenCache()
                val suspended = FreezeManager.getSuspendedPackages()
                suspended.forEach { FreezeManager.markFrozen(it) }
                RuntimeLog.d("同步完成：${suspended.size} 个应用已冻结")
            }
        }
        handler.post(monitorRunnable)
        RuntimeLog.d("监控轮询已启动，间隔 ${POLL_INTERVAL_MS}ms")
        if (prefs.isForceStopEnabled()) {
            RuntimeLog.d("频繁调用防护已开启，启动 logcat 监听")
            startLogcatMonitor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RuntimeLog.d("服务销毁中... 总轮询次数=$pollCount 平均耗时=${if (pollCount > 0) totalPollTime / pollCount else 0}ms")
        isServiceRunning = false
        handler.removeCallbacks(monitorRunnable)
        stopLogcatMonitor()
        scope.launch {
            RuntimeLog.d("开始解冻所有规则目标应用")
            val allFrozen = FreezeManager.getSuspendedPackages()
            val rules = prefs.getAllRules().filter { it.isEnabled }
            val ruleTargets = rules.flatMap { it.frozenPackages }.toSet()
            for (pkg in allFrozen) {
                if (pkg in ruleTargets) {
                    FreezeManager.unsuspendPackage(pkg)
                }
            }
            RuntimeLog.d("服务销毁：解冻完成")
        }
        super.onDestroy()
    }

    private fun pollForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        pollCount++
        val tick = System.currentTimeMillis()

        val foreground = runCatching { getForegroundPackage() }
            .onFailure { e ->
                RuntimeLog.e("获取前台应用失败: ${e.message}")
                TriggerLogger.add(
                    type = TriggerLogEntry.Type.ERROR,
                    triggerPackage = null,
                    targetPackage = "",
                    success = false,
                    detail = "获取前台应用失败：${e.message}"
                )
            }
            .getOrNull() ?: return

        val elapsed = System.currentTimeMillis() - tick
        totalPollTime += elapsed
        if (elapsed > 100) {
            RuntimeLog.w("第${pollCount}次轮询 getForegroundPackage() 耗时 ${elapsed}ms")
        }

        if (foreground == currentForeground) {
            consecutiveSameForeground++
            if (consecutiveSameForeground % 100 == 0) {
                RuntimeLog.d("第${pollCount}次轮询: 前台=${foreground}（连续${consecutiveSameForeground}次未变）平均轮询耗时=${totalPollTime / pollCount}ms")
            }
            return // 前台未变，跳过规则处理
        }

        RuntimeLog.d("前台应用变化: ${currentForeground ?: "null"} → $foreground (第${pollCount}次轮询)")
        consecutiveSameForeground = 0
        currentForeground = foreground

        val rules = prefs.getAllRules().filter { it.isEnabled }
        scope.launch {
            applyRules(foreground, rules)
        }
    }

    private suspend fun applyRules(foregroundPackage: String, rules: List<FreezeRule>) {
        val t0 = System.currentTimeMillis()
        val activeRule = rules.find { it.triggerPackage == foregroundPackage }

        val shouldBeFrozen = activeRule?.frozenPackages ?: emptySet()
        val allRuleTargets = rules.flatMap { it.frozenPackages }.toSet()

        if (activeRule != null) {
            RuntimeLog.d("规则命中: $foregroundPackage → 冻结 ${shouldBeFrozen.size} 个应用")
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
        if (shouldBeFrozen.isNotEmpty()) {
            RuntimeLog.d("开始冻结 ${shouldBeFrozen.size} 个应用...")
        }
        for (pkg in shouldBeFrozen) {
            val t = System.currentTimeMillis()
            FreezeManager.suspendPackage(pkg, triggerPackage = foregroundPackage)
            val dt = System.currentTimeMillis() - t
            if (dt > 500) RuntimeLog.w("suspendPackage($pkg) 耗时 ${dt}ms")
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
            var unfrozenCount = 0
            for (pkg in allRuleTargets) {
                val queuedSince = pendingUnfreeze[pkg] ?: continue
                if (now - queuedSince >= UNFREEZE_DELAY_MS) {
                    val t = System.currentTimeMillis()
                    FreezeManager.unsuspendPackage(pkg, triggerPackage = foregroundPackage)
                    val dt = System.currentTimeMillis() - t
                    if (dt > 500) RuntimeLog.w("unsuspendPackage($pkg) 耗时 ${dt}ms")
                    pendingUnfreeze.remove(pkg)
                    unfrozenCount++
                }
            }
            if (unfrozenCount > 0) {
                RuntimeLog.d("解冻 $unfrozenCount 个应用")
            }
        } else {
            // 正在触发规则 → 清除所有待解冻
            pendingUnfreeze.clear()
        }

        val totalElapsed = System.currentTimeMillis() - t0
        if (totalElapsed > 200) {
            RuntimeLog.w("applyRules() 总耗时 ${totalElapsed}ms (foreground=$foregroundPackage)")
        }
    }

    private fun startLogcatMonitor() {
        RuntimeLog.d("启动 logcat 监听...")
        logcatJob = scope.launch {
            while (isServiceRunning) {
                try {
                    RuntimeLog.d("创建 logcat 进程...")
                    val t = System.currentTimeMillis()
                    val proc = ShizukuExecutor.startLongRunning(
                        "logcat -s ActivityManager:* --regex=\"Forbidding suspended package\" 2>/dev/null"
                    )
                    if (proc == null) {
                        RuntimeLog.e("logcat 进程创建失败（Shizuku 可能未连接）")
                        delay(5000)
                        continue
                    }
                    RuntimeLog.d("logcat 进程已创建")
                    logcatProcess = proc
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    var lineCount = 0
                    while (isServiceRunning && proc.isAlive) {
                        line = try { reader.readLine() } catch (_: Exception) { null }
                        if (line == null) break
                        lineCount++
                        val pkg = parseBlockedPackage(line)
                        if (pkg != null) {
                            blockedAttemptTracker.record(pkg)
                        }
                    }
                    RuntimeLog.d("logcat 进程已退出，共读取 $lineCount 行，运行时间=${(System.currentTimeMillis() - t) / 1000}s")
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
                // UsageStatsManager returned null — usage stats might not be granted
            } catch (e: SecurityException) {
                RuntimeLog.w("UsageStatsManager 权限不足: ${e.message} → 切换至备用方案")
            } catch (e: Exception) {
                RuntimeLog.e("UsageStatsManager 异常: ${e.message} → 切换至备用方案")
            }
        }

        // Method 2: RunningAppProcessInfo (fallback, no special permission needed)
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.runningAppProcesses?.filterNotNull() ?: emptyList()
            val foreground = tasks.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
                ?.processName
            if (foreground == null) {
                RuntimeLog.w("RunningAppProcessInfo 未能获取前台应用")
            }
            foreground
        } catch (e: Exception) {
            RuntimeLog.e("RunningAppProcessInfo 异常: ${e.message}")
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
