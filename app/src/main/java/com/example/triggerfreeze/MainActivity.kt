package com.example.triggerfreeze

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.triggerfreeze.model.AppInfo
import com.example.triggerfreeze.model.FreezeRule
import com.example.triggerfreeze.model.TriggerLogEntry
import com.example.triggerfreeze.ui.theme.TriggerFreezeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 2604

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. Install the global crash handler first so we capture even the
        //    very first line that throws during setContent / Compose init.
        CrashReporter.install(this)
        try {
            enableEdgeToEdge()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            setContent {
                TriggerFreezeTheme {
                    TriggerFreezeContent()
                }
            }
        } catch (t: Throwable) {
            // 2. If the primary startup path throws, log it AND render a plain
            //    View-based error screen so the user can read the stack trace
            //    on-device, not just in `adb logcat`.
            CrashReporter.logCaught("MainActivity.onCreate", t)
            showFallbackErrorScreen(t)
        }
    }

    private fun showFallbackErrorScreen(throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val logPath = CrashReporter.logFile(this).absolutePath
        val text = buildString {
            append("⚠️ 应用启动时出错\n\n")
            append(throwable.javaClass.name)
            append('\n')
            append(throwable.message ?: "(no message)")
            append("\n\n--- 堆栈 ---\n")
            append(sw.toString())
            append("\n--- 日志文件 ---\n")
            append(logPath)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        setContentView(scroll)
    }
}

private enum class Screen {
    Main, SelectTrigger, SelectFrozen, Settings
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerFreezeContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = context.applicationContext as Application
    val prefs = remember { PreferencesManager(appContext) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf(Screen.Main) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var includeSystemApps by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var isLoadingCached by remember { mutableStateOf(true) }
    var shizukuStatus by remember { mutableStateOf(readShizukuStatus()) }
    var rules by remember {
        mutableStateOf(
            runCatching { prefs.getAllRules() }
                .onFailure { CrashReporter.logCaught("prefs.getAllRules", it) }
                .getOrDefault(emptyList())
        )
    }

    var selectedTriggerPkg by remember { mutableStateOf<String?>(null) }
    var selectedFrozenPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingTriggerPkg by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var showTriggerLogs by remember { mutableStateOf(false) }
    var showRuntimeLogs by remember { mutableStateOf(false) }

    fun loadApps(forceRefresh: Boolean = false) {
        scope.launch {
            busy = true
            // 1. 先显示缓存（如果有且首次加载）
            if (!forceRefresh && AppListCache.hasCache(appContext) && apps.isEmpty()) {
                val cached = AppListCache.load(appContext)
                if (cached.isNotEmpty()) {
                    apps = cached
                    isLoadingCached = false
                }
            }
            // 2. 后台异步刷新
            val loaded = runCatching {
                withContext(Dispatchers.IO) { loadInstalledApps(appContext) }
            }.onFailure { CrashReporter.logCaught("loadInstalledApps", it) }
                .getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                apps = loaded
                AppListCache.save(appContext, loaded)
            } else if (apps.isEmpty()) {
                snackbarHostState.showSnackbar("加载应用列表失败，已记录到日志")
            }
            isLoadingCached = false
            shizukuStatus = readShizukuStatus()
            busy = false
        }
    }

    fun refreshRules() {
        rules = prefs.getAllRules()
    }

    val permissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            shizukuStatus = readShizukuStatus()
        }
    }
    DisposableEffect(Unit) {
        // Shizuku throws IllegalStateException if the SDK isn't initialized
        // (e.g. Shizuku Manager is not installed). Don't let that kill the app.
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
            .onFailure { CrashReporter.logCaught("Shizuku.addRequestPermissionResultListener", it) }
        onDispose {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuStatus = readShizukuStatus()
                refreshRules()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    val appMap = remember(apps) {
        apps.associateBy { it.packageName }
    }

    var triggerToastEnabled by remember { mutableStateOf(prefs.isTriggerToastEnabled()) }
    var forceStopEnabled by remember { mutableStateOf(prefs.isForceStopEnabled()) }
    var forceStopTimeWindow by remember { mutableStateOf(prefs.getForceStopTimeWindowMin()) }
    var forceStopThreshold by remember { mutableStateOf(prefs.getForceStopThreshold()) }
    var forceStopAction by remember { mutableStateOf(prefs.getFrequentCallAction()) }

    fun triggerLabel(pkg: String): String =
        appMap[pkg]?.label ?: pkg

    var backPressCount = remember { mutableStateOf(0L) }

    BackHandler(
        enabled = screen == Screen.Main,
        onBack = {
            val now = System.currentTimeMillis()
            if (now - backPressCount.value < 2000) {
                backPressCount.value = 0L
                (context as? ComponentActivity)?.finish()
            } else {
                backPressCount.value = now
                scope.launch {
                    snackbarHostState.showSnackbar("再按一次返回键退出")
                }
            }
        }
    )

    BackHandler(
        enabled = screen != Screen.Main,
        onBack = {
            screen = Screen.Main
            selectedTriggerPkg = null
            selectedFrozenPkgs = emptySet()
            editingTriggerPkg = null
        }
    )

    Scaffold(
        topBar = {
            when (screen) {
                Screen.Main -> {
                    TopAppBar(
                        title = {
                            Column {
                                Text("TriggerFreeze", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${rules.size} 条规则",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { screen = Screen.Settings }) {
                                Icon(Icons.Filled.Settings, contentDescription = "设置")
                            }
                            IconButton(onClick = { showLogs = true }) {
                                Icon(Icons.Filled.BugReport, contentDescription = "查看错误日志")
                            }
                        }
                    )
                }

                Screen.SelectTrigger, Screen.SelectFrozen, Screen.Settings -> {
                    TopAppBar(
                        title = {
                            Text(
                                when (screen) {
                                    Screen.SelectTrigger -> "选择触发应用"
                                    Screen.SelectFrozen -> "选择被冻结的应用"
                                    Screen.Settings -> "设置"
                                    else -> ""
                                }
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                screen = Screen.Main
                                selectedTriggerPkg = null
                                selectedFrozenPkgs = emptySet()
                                editingTriggerPkg = null
                            }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (screen == Screen.Main) {
                FloatingActionButton(
                    onClick = {
                        selectedTriggerPkg = null
                        selectedFrozenPkgs = emptySet()
                        editingTriggerPkg = null
                        query = ""
                        screen = Screen.SelectTrigger
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加规则")
                }
            }
        }
    ) { innerPadding ->
        if (busy && screen == Screen.Main) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (screen) {
                Screen.Main -> {
                    MainContent(
                        rules = rules,
                        appMap = appMap,
                        shizukuStatus = shizukuStatus,
                        busy = busy,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        appContext = appContext,
                        onToggleRule = { pkg ->
                            prefs.toggleRule(pkg)
                            refreshRules()
                        },
                        onDeleteRule = { pkg ->
                            prefs.removeRule(pkg)
                            refreshRules()
                        },
                        onEditRule = { pkg ->
                            val rule = prefs.getRule(pkg)
                            if (rule != null) {
                                selectedTriggerPkg = rule.triggerPackage
                                selectedFrozenPkgs = rule.frozenPackages
                                editingTriggerPkg = rule.triggerPackage
                                screen = Screen.SelectFrozen
                            }
                        },
                        onRequestShizukuPermission = {
                            if (Shizuku.pingBinder()) {
                                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Shizuku 未连接")
                                }
                            }
                        },
                        onOpenUsageSettings = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            context.startActivity(intent)
                        },
                        onStartService = {
                            ForegroundMonitorService.start(appContext)
                            scope.launch {
                                snackbarHostState.showSnackbar("监控服务已启动")
                            }
                        },
                        onStopService = {
                            ForegroundMonitorService.stop(appContext)
                            scope.launch {
                                snackbarHostState.showSnackbar("监控服务已停止")
                            }
                        }
                    )
                }

                Screen.Settings -> {
                    SettingsScreen(
                        appContext = appContext,
                        triggerToastEnabled = triggerToastEnabled,
                        onToggleTriggerToast = { enabled ->
                            prefs.setTriggerToastEnabled(enabled)
                            triggerToastEnabled = enabled
                        },
                        forceStopEnabled = forceStopEnabled,
                        onToggleForceStop = { enabled ->
                            prefs.setForceStopEnabled(enabled)
                            forceStopEnabled = enabled
                            if (enabled) {
                                ForegroundMonitorService.restartLogcat(appContext)
                            }
                        },
                        forceStopTimeWindow = forceStopTimeWindow,
                        onForceStopTimeWindowChange = { v ->
                            prefs.setForceStopTimeWindowMin(v)
                            forceStopTimeWindow = v
                        },
                        forceStopThreshold = forceStopThreshold,
                        onForceStopThresholdChange = { v ->
                            prefs.setForceStopThreshold(v)
                            forceStopThreshold = v
                        },
                        forceStopAction = forceStopAction,
                        onForceStopActionChange = { v ->
                            prefs.setFrequentCallAction(v)
                            forceStopAction = v
                        },
                        onShowTriggerLogs = { showTriggerLogs = true },
                        onShowRuntimeLogs = { showRuntimeLogs = true },
                        onBack = { screen = Screen.Main }
                    )
                }

                Screen.SelectTrigger -> {
                    SelectAppScreen(
                        apps = apps,
                        query = query,
                        onQueryChange = { query = it },
                        includeSystemApps = includeSystemApps,
                        onIncludeSystemAppsChange = { includeSystemApps = it },
                        allowMultiSelect = false,
                        selectedPackages = selectedTriggerPkg?.let { setOf(it) } ?: emptySet(),
                        onTogglePackage = { pkg ->
                            selectedTriggerPkg = if (selectedTriggerPkg == pkg) null else pkg
                        },
                        onConfirm = {
                            if (selectedTriggerPkg != null) {
                                query = ""
                                screen = Screen.SelectFrozen
                            }
                        },
                        confirmLabel = "下一步：选择被冻结应用",
                        busy = false
                    )
                }

                Screen.SelectFrozen -> {
                    SelectAppScreen(
                        apps = apps.filter { it.packageName != selectedTriggerPkg },
                        query = query,
                        onQueryChange = { query = it },
                        includeSystemApps = includeSystemApps,
                        onIncludeSystemAppsChange = { includeSystemApps = it },
                        allowMultiSelect = true,
                        selectedPackages = selectedFrozenPkgs,
                        onTogglePackage = { pkg ->
                            selectedFrozenPkgs = if (pkg in selectedFrozenPkgs) {
                                selectedFrozenPkgs - pkg
                            } else {
                                selectedFrozenPkgs + pkg
                            }
                        },
                        onConfirm = {
                            val trigger = selectedTriggerPkg
                            if (trigger != null && selectedFrozenPkgs.isNotEmpty()) {
                                val rule = FreezeRule(
                                    triggerPackage = trigger,
                                    frozenPackages = selectedFrozenPkgs,
                                    isEnabled = true
                                )
                                prefs.addRule(rule)
                                refreshRules()
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "规则已保存：${triggerLabel(trigger)} → ${selectedFrozenPkgs.size} 个应用"
                                    )
                                }
                            }
                            selectedTriggerPkg = null
                            selectedFrozenPkgs = emptySet()
                            editingTriggerPkg = null
                            screen = Screen.Main
                        },
                        confirmLabel = if (editingTriggerPkg != null) "保存更改" else "保存规则",
                        busy = false
                    )
                }
            }
        }
    }

    if (showLogs) {
        CrashLogDialog(
            logText = CrashReporter.read(appContext),
            logPath = CrashReporter.logFile(appContext).absolutePath,
            onDismiss = { showLogs = false },
            onClear = {
                CrashReporter.clear(appContext)
                showLogs = false
            }
        )
    }

    if (showTriggerLogs) {
        TriggerLogDialog(
            logs = TriggerLogger.allLogs,
            appMap = appMap,
            onDismiss = { showTriggerLogs = false },
            onClear = {
                TriggerLogger.clear()
                showTriggerLogs = false
            }
        )
    }

    if (showRuntimeLogs) {
        RuntimeLogDialog(
            logs = RuntimeLog.all,
            onDismiss = { showRuntimeLogs = false },
            onClear = {
                RuntimeLog.clear()
                showRuntimeLogs = false
            }
        )
    }
}

@Composable
private fun RuntimeLogDialog(
    logs: List<String>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "共 ${logs.size} 条记录 · 用于诊断发热/性能问题",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Text("暂无日志，启动监控服务后会自动记录")
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = logs.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text("清空") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun TriggerLogDialog(
    logs: List<TriggerLogEntry>,
    appMap: Map<String, AppInfo>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("触发日志") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "共 ${logs.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Text("暂无触发日志")
                } else {
                    LazyColumn(modifier = Modifier.height(400.dp)) {
                        items(logs) { entry ->
                            val color = when (entry.type) {
                                TriggerLogEntry.Type.TRIGGER -> MaterialTheme.colorScheme.primary
                                TriggerLogEntry.Type.FREEZE -> MaterialTheme.colorScheme.error
                                TriggerLogEntry.Type.UNFREEZE -> MaterialTheme.colorScheme.tertiary
                                TriggerLogEntry.Type.ERROR -> MaterialTheme.colorScheme.error
                                TriggerLogEntry.Type.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val label = when (entry.type) {
                                TriggerLogEntry.Type.TRIGGER -> "▶ 触发"
                                TriggerLogEntry.Type.FREEZE -> "❄ 冻结"
                                TriggerLogEntry.Type.UNFREEZE -> "▶ 解冻"
                                TriggerLogEntry.Type.ERROR -> "⚠ 错误"
                                TriggerLogEntry.Type.SYSTEM -> "ℹ 系统"
                            }
                            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date(entry.timestamp))
                            val targetLabel = appMap[entry.targetPackage]?.label ?: entry.targetPackage
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "$label $targetLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        time,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (entry.triggerPackage != null && entry.type == TriggerLogEntry.Type.FREEZE) {
                                    val triggerLabel = appMap[entry.triggerPackage]?.label ?: entry.triggerPackage
                                    Text(
                                        "触发源：$triggerLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (entry.detail.isNotBlank()) {
                                    Text(
                                        entry.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text("清空") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun CrashLogDialog(
    logText: String,
    logPath: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("错误日志") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "路径：$logPath",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text("清空") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun MainContent(
    rules: List<FreezeRule>,
    appMap: Map<String, AppInfo>,
    shizukuStatus: String,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    appContext: Context,
    onToggleRule: (String) -> Unit,
    onDeleteRule: (String) -> Unit,
    onEditRule: (String) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var isServiceRunning by remember { mutableStateOf(ForegroundMonitorService.isServiceRunning) }
    var hasUsageStats by remember { mutableStateOf(checkUsageStatsPermission(appContext)) }

    LaunchedEffect(isServiceRunning, shizukuStatus) {
        hasUsageStats = checkUsageStatsPermission(appContext)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = onRequestShizukuPermission,
                            leadingIcon = {
                                Icon(Icons.Filled.SettingsInputComponent, contentDescription = null)
                            },
                            label = { Text(shizukuStatus) },
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = onOpenUsageSettings,
                            leadingIcon = {
                                Icon(
                                    if (hasUsageStats) Icons.Filled.CheckCircle else Icons.Filled.Block,
                                    contentDescription = null
                                )
                            },
                            label = {
                                Text(if (hasUsageStats) "使用记录已授权" else "需授权使用记录")
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (isServiceRunning) "监控服务运行中" else "监控服务未启动",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = isServiceRunning,
                            enabled = !busy && shizukuStatus.contains("授权"),
                            onCheckedChange = { running ->
                                if (running) {
                                    onStartService()
                                } else {
                                    onStopService()
                                }
                                isServiceRunning = running
                            }
                        )
                    }

                }
            }
        }

        if (rules.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "暂无规则，点击 + 添加",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = rules,
                key = { it.triggerPackage }
            ) { rule ->
                val triggerLabel = appMap[rule.triggerPackage]?.label ?: rule.triggerPackage
                val frozenLabels = rule.frozenPackages.mapNotNull { appMap[it]?.label ?: it }

                ElevatedCard(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (rule.isEnabled)
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                triggerLabel,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = rule.isEnabled,
                                onCheckedChange = { onToggleRule(rule.triggerPackage) }
                            )
                        }

                        Text(
                            "冻结：${frozenLabels.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (frozenLabels.size != rule.frozenPackages.size) {
                            val unknown = rule.frozenPackages.size - frozenLabels.size
                            Text(
                                "另有 $unknown 个已卸载应用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = { onEditRule(rule.triggerPackage) }, enabled = rule.isEnabled) {
                                Icon(Icons.Filled.Settings, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDeleteRule(rule.triggerPackage) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectAppScreen(
    apps: List<AppInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    includeSystemApps: Boolean,
    onIncludeSystemAppsChange: (Boolean) -> Unit,
    allowMultiSelect: Boolean,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    busy: Boolean
) {
    val keyboard = LocalSoftwareKeyboardController.current

    val filteredApps = remember(apps, query, includeSystemApps) {
        apps.asSequence()
            .filter { includeSystemApps || !it.isSystem }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索应用") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() })
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${filteredApps.size} 个应用",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilterChip(
                selected = includeSystemApps,
                onClick = { onIncludeSystemAppsChange(!includeSystemApps) },
                label = { Text("系统应用") }
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = filteredApps,
                key = { it.packageName }
            ) { app ->
                val isSelected = app.packageName in selectedPackages
                val context = LocalContext.current
                val iconBitmap = remember(app.packageName) {
                    runCatching {
                        val drawable = context.packageManager.getApplicationIcon(app.packageName)
                        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        drawable.setBounds(0, 0, 64, 64)
                        drawable.draw(canvas)
                        bitmap.asImageBitmap()
                    }.getOrNull()
                }
                Surface(
                    modifier = Modifier.clickable { onTogglePackage(app.packageName) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface,
                    tonalElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (allowMultiSelect) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onTogglePackage(app.packageName) }
                            )
                        }
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = app.label,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!allowMultiSelect) {
                            Text(
                                if (isSelected) "已选" else "选择",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedPackages.isNotEmpty()
        ) {
            Text(confirmLabel)
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsScreen(
    appContext: Context,
    triggerToastEnabled: Boolean,
    onToggleTriggerToast: (Boolean) -> Unit,
    forceStopEnabled: Boolean,
    onToggleForceStop: (Boolean) -> Unit,
    forceStopTimeWindow: Int,
    onForceStopTimeWindowChange: (Int) -> Unit,
    forceStopThreshold: Int,
    onForceStopThresholdChange: (Int) -> Unit,
    forceStopAction: Int,
    onForceStopActionChange: (Int) -> Unit,
    onShowTriggerLogs: () -> Unit,
    onShowRuntimeLogs: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = onShowTriggerLogs,
                                leadingIcon = {
                                    Icon(Icons.Filled.BugReport, contentDescription = null)
                                },
                                label = { Text("触发日志") },
                                modifier = Modifier.weight(1f)
                            )
                            AssistChip(
                                onClick = onShowRuntimeLogs,
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                },
                                label = { Text("运行日志") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "触发时弹出提示",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = triggerToastEnabled,
                                onCheckedChange = onToggleTriggerToast
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "频繁调用强制关闭",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = forceStopEnabled,
                                onCheckedChange = onToggleForceStop
                            )
                        }

                        if (forceStopEnabled) {
                            HorizontalDivider()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("时间窗口", style = MaterialTheme.typography.bodySmall)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (forceStopTimeWindow > 1) onForceStopTimeWindowChange(forceStopTimeWindow - 1)
                                        }
                                    ) { Text("-", style = MaterialTheme.typography.titleMedium) }
                                    Text(
                                        "$forceStopTimeWindow 分钟",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    IconButton(
                                        onClick = { onForceStopTimeWindowChange(forceStopTimeWindow + 1) }
                                    ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("触发次数", style = MaterialTheme.typography.bodySmall)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (forceStopThreshold > 3) onForceStopThresholdChange(forceStopThreshold - 1)
                                        }
                                    ) { Text("-", style = MaterialTheme.typography.titleMedium) }
                                    Text(
                                        "$forceStopThreshold 次",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    IconButton(
                                        onClick = { onForceStopThresholdChange(forceStopThreshold + 1) }
                                    ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                                }
                            }

                            Text(
                                "触发后操作",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = forceStopAction == 0,
                                    onClick = { onForceStopActionChange(0) },
                                    label = { Text("强制停止") }
                                )
                                FilterChip(
                                    selected = forceStopAction == 1,
                                    onClick = { onForceStopActionChange(1) },
                                    label = { Text("重启软件") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadInstalledApps(context: Context): List<AppInfo> {
    val packageManager = context.packageManager
    val flags = PackageManager.ApplicationInfoFlags.of(
        PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
    )
    return packageManager.getInstalledApplications(flags)
        .asSequence()
        .filter { it.packageName != context.packageName }
        .map { info ->
            AppInfo(
                label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                packageName = info.packageName,
                isSystem = info.isSystemApp()
            )
        }
        .sortedWith(
            compareBy<AppInfo> { it.isSystem }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
        .toList()
}

private fun ApplicationInfo.isSystemApp(): Boolean {
    return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

private fun readShizukuStatus(): String {
    return runCatching {
        when {
            !Shizuku.pingBinder() -> "Shizuku 未连接"
            Shizuku.isPreV11() -> "Shizuku 已连接"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku 已授权"
            else -> "Shizuku 待授权"
        }
    }.getOrDefault("Shizuku 未连接")
}

private fun checkUsageStatsPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
    return try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000
        val events = usm.queryEvents(beginTime, endTime)
        events != null
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}
