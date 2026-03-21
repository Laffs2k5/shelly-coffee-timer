package com.shellycoffee.timer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shellycoffee.timer.api.CoffeeApi
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
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
            OutlinedTextField(
                value = shellyIp,
                onValueChange = { shellyIp = it; saved = false },
                label = { Text("Shelly Local IP") },
                placeholder = { Text("192.168.1.xxx") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = aioUser,
                onValueChange = { aioUser = it; saved = false },
                label = { Text("Adafruit IO Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = aioKey,
                onValueChange = { aioKey = it; saved = false },
                label = { Text("Adafruit IO Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
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
                Text("Settings saved.", color = Color(0xFF4CAF50))
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
        topBar = {
            TopAppBar(
                title = { Text("Coffee Timer") },
                actions = {
                    TextButton(onClick = onNavigateToSettings) {
                        Text("Settings")
                    }
                }
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
            // --- Status Section ---
            Text("Manual Control", style = MaterialTheme.typography.titleMedium)

            val statusText = when {
                status == null -> "Unknown -- device not responding"
                status!!.state == "on" -> "ON with ${status!!.remaining} min to go"
                else -> "OFF"
            }
            val statusColor = when {
                status == null -> Color.Red
                status!!.state == "on" -> Color(0xFF4CAF50)
                else -> Color.Gray
            }

            Text(
                text = statusText,
                fontSize = 20.sp,
                color = statusColor
            )

            if (status?.mode?.isNotBlank() == true) {
                Text("Mode: ${status!!.mode}", color = Color.Gray, fontSize = 14.sp)
            }

            if (status?.ntpSynced == false) {
                Text("NTP: not synced", color = Color.Red, fontSize = 12.sp)
            }

            // --- Timer Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { sendCmd("off") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f)
                ) { Text("OFF") }
                Button(
                    onClick = { sendCmd("sub") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f)
                ) { Text("-30") }
                Button(
                    onClick = { sendCmd("ext") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f)
                ) { Text("+30") }
                Button(
                    onClick = { sendCmd("t90") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f)
                ) { Text("90") }
            }

            HorizontalDivider()

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
                        fontSize = 18.sp
                    )
                }
            }

            HorizontalDivider()

            // --- Connection Section ---
            Text("Connection", style = MaterialTheme.typography.titleMedium)

            val (connText, connColor) = when (connectionMode) {
                CoffeeApi.ConnectionMode.LOCAL -> {
                    val ip = prefs.getString("shelly_ip", "") ?: ""
                    "Local ($ip)" to Color(0xFF4CAF50)
                }
                CoffeeApi.ConnectionMode.REMOTE -> "Remote (Adafruit IO)" to Color(0xFFFFC107)
                CoffeeApi.ConnectionMode.OFFLINE -> "Offline" to Color.Red
            }

            Text(connText, color = connColor)
            Text("Last updated: $lastUpdated", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
