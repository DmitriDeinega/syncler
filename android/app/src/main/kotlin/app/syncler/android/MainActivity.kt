package app.syncler.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.syncler.android.ui.AuthScreen
import app.syncler.android.ui.AuthViewModel
import app.syncler.android.ui.DevicesViewModel
import app.syncler.android.ui.MainViewModel
import app.syncler.android.ui.SettingsDevicesScreen
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
                    val screen by mainViewModel.screen.collectAsStateWithLifecycle()

                    when {
                        !isUnlocked -> AuthScreen(viewModel = authViewModel)
                        screen == TopLevelScreen.Devices -> SettingsDevicesScreen(
                            viewModel = devicesViewModel,
                            onBack = mainViewModel::showInbox,
                        )
                        screen == TopLevelScreen.Pairing -> PairingScreen(
                            onDone = mainViewModel::showInbox,
                        )
                        else -> InboxScreen(
                            onLogout = mainViewModel::logout,
                            onManageDevices = mainViewModel::showDevices,
                            onPairSender = mainViewModel::showPairing,
                        )
                    }
                }
            }
        }
    }
}
