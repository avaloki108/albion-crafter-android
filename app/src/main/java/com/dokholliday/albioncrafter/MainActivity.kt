package com.dokholliday.albioncrafter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dokholliday.albioncrafter.ui.AlbionTheme
import com.dokholliday.albioncrafter.ui.CalculatorScreen
import com.dokholliday.albioncrafter.ui.MarketScreen
import com.dokholliday.albioncrafter.ui.PlannerScreen
import com.dokholliday.albioncrafter.ui.ScannerScreen
import com.dokholliday.albioncrafter.ui.SettingsScreen
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlbionTheme {
                AlbionApp()
            }
        }
    }
}

private enum class Screen(val label: String) {
    PLAN("Plan"),
    CALC("Calculator"),
    SCAN("Scanner"),
    MARKET("Market"),
    SETTINGS("Settings"),
}

class AppStatus {
    var statusJson by mutableStateOf<JSONObject?>(null)
    var startupError by mutableStateOf<String?>(null)

    val catalogEmpty: Boolean
        get() = statusJson?.optJSONObject("catalog")?.optInt("item_count", 0) == 0

    fun refresh(onDone: () -> Unit = {}) {
        PythonBridge.callAsync("get_status") { result ->
            result.onSuccess { statusJson = it }
                .onFailure { startupError = it.message ?: it.toString() }
            onDone()
        }
    }
}

@Composable
fun AlbionApp() {
    val context = LocalContext.current
    val appStatus = remember { AppStatus() }
    var screen by remember { mutableStateOf(Screen.PLAN) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { PythonBridge.start(context) }
            .onFailure { appStatus.startupError = it.message ?: it.toString() }
        appStatus.refresh { ready = true }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = {
                            when (s) {
                                Screen.PLAN -> Icon(Icons.Filled.Search, contentDescription = null)
                                Screen.CALC -> Icon(Icons.Filled.Edit, contentDescription = null)
                                Screen.SCAN -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                                Screen.MARKET -> Icon(Icons.Filled.Storage, contentDescription = null)
                                Screen.SETTINGS -> Icon(Icons.Filled.Settings, contentDescription = null)
                            }
                        },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (screen) {
            Screen.PLAN -> PlannerScreen(mod, appStatus)
            Screen.CALC -> CalculatorScreen(mod)
            Screen.SCAN -> ScannerScreen(mod)
            Screen.MARKET -> MarketScreen(mod, appStatus)
            Screen.SETTINGS -> SettingsScreen(mod, appStatus)
        }
    }
}
