package app.syncler.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.syncler.android.ui.AuthScreen
import app.syncler.android.ui.AuthViewModel
import app.syncler.android.ui.DevicesViewModel
import app.syncler.android.ui.MainViewModel
import app.syncler.android.ui.SettingsScreen
import app.syncler.android.ui.TopLevelScreen
import app.syncler.feature.inbox.InboxScreen
import app.syncler.feature.pairing.PairingScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel by viewModels<MainViewModel>()
    private val authViewModel by viewModels<AuthViewModel>()
    private val devicesViewModel by viewModels<DevicesViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val isUnlocked by mainViewModel.isUnlocked.collectAsStateWithLifecycle(initialValue = false)
                    if (!isUnlocked) {
                        AuthScreen(viewModel = authViewModel)
                    } else {
                        UnlockedApp(
                            mainViewModel = mainViewModel,
                            devicesViewModel = devicesViewModel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockedApp(
    mainViewModel: MainViewModel,
    devicesViewModel: DevicesViewModel,
) {
    val tab by mainViewModel.screen.collectAsStateWithLifecycle()
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TopLevelScreen.Inbox,
                    onClick = { mainViewModel.select(TopLevelScreen.Inbox) },
                    icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    label = { Text("Inbox") },
                )
                NavigationBarItem(
                    selected = tab == TopLevelScreen.Senders,
                    onClick = { mainViewModel.select(TopLevelScreen.Senders) },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text("Senders") },
                )
                NavigationBarItem(
                    selected = tab == TopLevelScreen.Settings,
                    onClick = { mainViewModel.select(TopLevelScreen.Settings) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            TopLevelScreen.Inbox -> InboxScreen(modifier = Modifier.padding(padding))
            TopLevelScreen.Senders -> PairingScreen(
                onDone = {},
                modifier = Modifier.padding(padding),
            )
            TopLevelScreen.Settings -> SettingsScreen(
                devicesViewModel = devicesViewModel,
                onLogout = mainViewModel::logout,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
