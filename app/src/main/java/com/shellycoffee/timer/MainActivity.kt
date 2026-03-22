package com.shellycoffee.timer

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shellycoffee.timer.api.CoffeeApi
import com.shellycoffee.timer.notification.CoffeeNotificationService
import com.shellycoffee.timer.notification.NotificationHelper
import com.shellycoffee.timer.notification.ScheduleAlarmManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create notification channel on first launch
        NotificationHelper.createChannel(this)

        // Force dark status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#1A1A1A")
        window.navigationBarColor = android.graphics.Color.parseColor("#1A1A1A")

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4CAF50),
                    onPrimary = Color(0xFF1A1A1A),
                    surface = Color(0xFF1A1A1A),
                    onSurface = Color(0xFFE0E0E0),
                    surfaceVariant = Color(0xFF2A2A2A),
                    onSurfaceVariant = Color(0xFFBBBBBB),
                    secondary = Color(0xFF4CAF50),
                    outline = Color(0xFF444444),
                    surfaceContainerHighest = Color(0xFF333333),
                )
            ) {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(onNavigateToSettings = { navController.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

// --- Settings Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("coffee_settings", Context.MODE_PRIVATE) }

    var shellyIp by remember { mutableStateOf(prefs.getString("shelly_ip", "") ?: "") }
    var aioUser by remember { mutableStateOf(prefs.getString("aio_user", "") ?: "") }
    var aioKey by remember { mutableStateOf(prefs.getString("aio_key", "") ?: "") }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back", color = Color(0xFFBBBBBB)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextField(
                value = shellyIp,
                onValueChange = { shellyIp = it; saved = false },
                label = { Text("Shelly Local IP") },
                placeholder = { Text("192.168.1.xxx") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )

            TextField(
                value = aioUser,
                onValueChange = { aioUser = it; saved = false },
                label = { Text("Adafruit IO Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )

            TextField(
                value = aioKey,
                onValueChange = { aioKey = it; saved = false },
                label = { Text("Adafruit IO Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )

            Button(
                onClick = {
                    prefs.edit()
                        .putString("shelly_ip", shellyIp)
                        .putString("aio_user", aioUser)
                        .putString("aio_key", aioKey)
                        .apply()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            if (saved) {
                Text("Settings saved.", color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

// --- Main Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("coffee_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<CoffeeApi.DeviceStatus?>(null) }
    var connectionMode by remember { mutableStateOf(CoffeeApi.ConnectionMode.OFFLINE) }
    var lastUpdated by remember { mutableStateOf("never") }
    var sending by remember { mutableStateOf(false) }
    var scheduleEnabled by remember { mutableStateOf(false) }
    var scheduleHour by remember { mutableIntStateOf(6) }
    var scheduleMinute by remember { mutableIntStateOf(0) }
    var showTimePicker by remember { mutableStateOf(false) }
    var notificationPermissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission granted — start service if coffee is on
            if (status?.state == "on" && !isServiceRunning(context)) {
                context.startForegroundService(
                    Intent(context, CoffeeNotificationService::class.java)
                        .putExtra("remaining", status?.remaining ?: 0)
                )
            }
        }
    }

    fun getPrefs(): Triple<String, String, String> {
        val ip = prefs.getString("shelly_ip", "") ?: ""
        val user = prefs.getString("aio_user", "") ?: ""
        val key = prefs.getString("aio_key", "") ?: ""
        return Triple(ip, user, key)
    }

    fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            val (ip, user, key) = getPrefs()
            val result = CoffeeApi.pollStatus(ip, user, key)
            withContext(Dispatchers.Main) {
                status = result.status
                connectionMode = result.mode
                if (result.status != null) {
                    lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    scheduleEnabled = result.status!!.scheduleEnabled == 1
                    scheduleHour = result.status!!.scheduleHour
                    scheduleMinute = result.status!!.scheduleMinute

                    // Start notification service if coffee is on
                    if (result.status!!.state == "on" && !isServiceRunning(context)) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            context.startForegroundService(
                                Intent(context, CoffeeNotificationService::class.java)
                                    .putExtra("remaining", result.status?.remaining ?: 0)
                            )
                        } else if (!notificationPermissionRequested) {
                            notificationPermissionRequested = true
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // Manage schedule alarm
                    if (result.status!!.scheduleEnabled == 1) {
                        ScheduleAlarmManager.scheduleWakeUp(
                            context,
                            result.status!!.scheduleHour,
                            result.status!!.scheduleMinute
                        )
                    } else {
                        ScheduleAlarmManager.cancelWakeUp(context)
                    }
                }
            }
        }
    }

    fun sendCmd(cmd: String) {
        sending = true
        scope.launch(Dispatchers.IO) {
            val (ip, user, key) = getPrefs()
            val result = CoffeeApi.sendCommand(ip, user, key, cmd, connectionMode)
            withContext(Dispatchers.Main) {
                if (result != null) {
                    status = result
                    lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    // Update running notification service with new remaining time
                    if (isServiceRunning(context)) {
                        context.startForegroundService(
                            Intent(context, CoffeeNotificationService::class.java)
                                .putExtra("remaining", result.remaining)
                        )
                    }
                }
                sending = false
            }
            // Also do a full refresh shortly after
            delay(1000)
            val refreshResult = CoffeeApi.pollStatus(ip, user, key)
            withContext(Dispatchers.Main) {
                status = refreshResult.status
                connectionMode = refreshResult.mode
                if (refreshResult.status != null) {
                    lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date())
                }
            }
        }
    }

    fun updateSchedule(enabled: Boolean, hour: Int, minute: Int) {
        scope.launch(Dispatchers.IO) {
            val (_, user, key) = getPrefs()
            if (user.isBlank() || key.isBlank()) return@launch

            val config = CoffeeApi.fetchRemoteConfig(user, key) ?: return@launch
            val newConfig = config.copy(
                version = config.version + 1,
                scheduleEnabled = if (enabled) 1 else 0,
                hour = hour,
                minute = minute
            )
            CoffeeApi.writeRemoteConfig(user, key, newConfig)

            // Refresh status after config change
            delay(1000)
            val (ip, _, _) = getPrefs()
            val result = CoffeeApi.pollStatus(ip, user, key)
            withContext(Dispatchers.Main) {
                status = result.status
                connectionMode = result.mode
                if (result.status != null) {
                    lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    scheduleEnabled = result.status!!.scheduleEnabled == 1
                    scheduleHour = result.status!!.scheduleHour
                    scheduleMinute = result.status!!.scheduleMinute
                }
            }
        }
    }

    // Poll every 10 seconds
    LaunchedEffect(Unit) {
        while (isActive) {
            refreshStatus()
            delay(10_000)
        }
    }

    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = scheduleHour,
            initialMinute = scheduleMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Set Schedule Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    scheduleHour = timePickerState.hour
                    scheduleMinute = timePickerState.minute
                    showTimePicker = false
                    updateSchedule(scheduleEnabled, scheduleHour, scheduleMinute)
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Coffee Timer") },
                actions = {
                    TextButton(onClick = onNavigateToSettings) {
                        Text("Settings", color = Color(0xFFBBBBBB))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Status Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        status == null -> {
                            Text(
                                text = "\u2615",
                                fontSize = 36.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Not connected",
                                fontSize = 18.sp,
                                color = Color(0xFF999999),
                            )
                            Text(
                                text = "Device not responding",
                                fontSize = 13.sp,
                                color = Color(0xFF666666),
                            )
                        }
                        status!!.state == "on" -> {
                            Text(
                                text = "\u2615",
                                fontSize = 36.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "ON",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${status!!.remaining} min remaining",
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        else -> {
                            Text(
                                text = "\u2615",
                                fontSize = 36.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "OFF",
                                fontSize = 22.sp,
                                color = Color(0xFF777777),
                            )
                        }
                    }

                    if (status?.ntpSynced == false) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("NTP: not synced", color = Color(0xFFEF5350), fontSize = 12.sp)
                    }
                }
            }

            // --- Timer Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { sendCmd("off") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF444444)),
                ) { Text("OFF", color = Color(0xFFE0E0E0)) }
                OutlinedButton(
                    onClick = { sendCmd("sub") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF444444)),
                ) { Text("-30", color = Color(0xFFE0E0E0)) }
                OutlinedButton(
                    onClick = { sendCmd("ext") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF444444)),
                ) { Text("+30", color = Color(0xFFE0E0E0)) }
                OutlinedButton(
                    onClick = { sendCmd("t90") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                ) { Text("90", color = Color(0xFF4CAF50)) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // --- Schedule Section ---
            Text("Schedule", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Schedule enabled")
                Switch(
                    checked = scheduleEnabled,
                    onCheckedChange = { checked ->
                        scheduleEnabled = checked
                        updateSchedule(checked, scheduleHour, scheduleMinute)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Schedule time")
                TextButton(onClick = { showTimePicker = true }) {
                    Text(
                        String.format(Locale.getDefault(), "%02d:%02d", scheduleHour, scheduleMinute),
                        fontSize = 18.sp,
                        color = Color(0xFFE0E0E0)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // --- Connection (subtle) ---
            val (connText, connColor) = when (connectionMode) {
                CoffeeApi.ConnectionMode.LOCAL -> "Wi-Fi" to Color(0xFF4CAF50)
                CoffeeApi.ConnectionMode.REMOTE -> "Internet" to Color(0xFFFFC107)
                CoffeeApi.ConnectionMode.OFFLINE -> "Offline" to Color(0xFFEF5350)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    connText,
                    color = connColor,
                    fontSize = 12.sp,
                )
                Text(
                    "Updated $lastUpdated",
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun isServiceRunning(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java)
    @Suppress("DEPRECATION")
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (service.service.className == CoffeeNotificationService::class.java.name) {
            return true
        }
    }
    return false
}
