package com.alon.instablock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBackground = Color(0xFF1C1C1E)
private val ScreenBackground = Color(0xFF0A0A0B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = ScreenBackground, modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("InstaBlock", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Take back control of your scrolling", color = Color.Gray, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable Accessibility Permission")
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionCard("Intent Check") { IntentCheckRow() }

        Spacer(modifier = Modifier.height(16.dp))
        SectionCard("Blocked Features") {
            BlockTarget.values().forEachIndexed { index, target ->
                TargetRow(target)
                if (index != BlockTarget.values().lastIndex) {
                    HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 14.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionCard("Appearance") { GrayscaleRow() }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                BlockTarget.values().forEach { target ->
                    PrefsManager.setBlocked(context, target, true)
                    PrefsManager.setDailyLimitMinutes(context, target, 0)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CardBackground)
        ) {
            Text("DMs Only")
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun IntentCheckRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PrefsManager.isIntentCheckEnabled(context)) }
    var thresholdText by remember {
        mutableStateOf(PrefsManager.intentCheckThresholdMinutes(context).toString())
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ask \"Why are you here?\" on fresh launch", color = Color.White, fontSize = 15.sp)
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                PrefsManager.setIntentCheckEnabled(context, it)
            })
        }
        if (enabled) {
            Spacer(modifier = Modifier.height(10.dp))
            NumberField("Fresh-launch threshold (min)", thresholdText) {
                thresholdText = it
                it.toIntOrNull()?.let { v -> PrefsManager.setIntentCheckThresholdMinutes(context, v) }
            }
        }
    }
}

@Composable
fun TargetRow(target: BlockTarget) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(PrefsManager.isBlocked(context, target)) }
    var limitText by remember { mutableStateOf(PrefsManager.dailyLimitMinutes(context, target).toString()) }
    var swipeLimitText by remember { mutableStateOf(PrefsManager.maxSwipesPerDay(context, target).toString()) }
    var hourlyLimitText by remember { mutableStateOf(PrefsManager.hourlyLimitMinutes(context, target).toString()) }
    var friction by remember { mutableStateOf(PrefsManager.isFrictionDelayEnabled(context, target)) }
    var scheduleOn by remember { mutableStateOf(PrefsManager.isScheduleEnabled(context, target)) }
    var startHourText by remember {
        mutableStateOf((PrefsManager.scheduleStartMinute(context, target) / 60).toString())
    }
    var endHourText by remember {
        mutableStateOf((PrefsManager.scheduleEndMinute(context, target) / 60).toString())
    }

    fun saveSchedule() {
        val startHour = startHourText.toIntOrNull() ?: return
        val endHour = endHourText.toIntOrNull() ?: return
        PrefsManager.setScheduleWindow(context, target, startHour * 60, endHour * 60)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Block ${target.label}", color = Color.White, fontSize = 16.sp)
            Switch(checked = blocked, onCheckedChange = {
                blocked = it
                PrefsManager.setBlocked(context, target, it)
            })
        }

        if (blocked) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Daily minutes (0=full block)", limitText) {
                    limitText = it
                    it.toIntOrNull()?.let { v -> PrefsManager.setDailyLimitMinutes(context, target, v) }
                }
                NumberField("Daily swipes (0=unlimited)", swipeLimitText) {
                    swipeLimitText = it
                    it.toIntOrNull()?.let { v -> PrefsManager.setMaxSwipesPerDay(context, target, v) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Label fixed: 0 here means "no hourly cap", same as the
                // other 0-means-off fields - it does NOT mean "block".
                NumberField("Hourly minutes (0=unlimited)", hourlyLimitText) {
                    hourlyLimitText = it
                    it.toIntOrNull()?.let { v -> PrefsManager.setHourlyLimitMinutes(context, target, v) }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("10s breathing delay", color = Color.LightGray, fontSize = 14.sp)
                Switch(checked = friction, onCheckedChange = {
                    friction = it
                    PrefsManager.setFrictionDelayEnabled(context, target, it)
                })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Restrict to time window", color = Color.LightGray, fontSize = 14.sp)
                Switch(checked = scheduleOn, onCheckedChange = {
                    scheduleOn = it
                    PrefsManager.setScheduleEnabled(context, target, it)
                })
            }
            if (scheduleOn) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("From hour", startHourText) {
                        startHourText = it
                        saveSchedule()
                    }
                    NumberField("To hour", endHourText) {
                        endHourText = it
                        saveSchedule()
                    }
                }
            }
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.width(150.dp)
    )
}

@Composable
fun GrayscaleRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PrefsManager.isGrayscaleEnabled(context)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Grayscale while on Instagram", color = Color.White, fontSize = 15.sp)
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            PrefsManager.setGrayscaleEnabled(context, it)
        })
    }
}
