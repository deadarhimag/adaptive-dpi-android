package org.adaptivedpi.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.adaptivedpi.android.designsystem.AdaptiveDpiTheme
import org.adaptivedpi.android.designsystem.ThemeMode

/** Экран инструментального теста без ручного управления VPN. */
class TransportTestActivity : ComponentActivity() {
    private val result by lazy { ViewModelProvider(this)[TransportConsentResult::class.java] }
    val consentResult: StateFlow<Int?> get() = result.code

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        result.code.value = it.resultCode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AdaptiveDpiTheme(ThemeMode.System) {
                Surface {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.transport_test_instruction))
                    }
                }
            }
        }
    }

    fun launchConsent(intent: Intent) = consent.launch(intent)
}

internal class TransportConsentResult : ViewModel() {
    val code = MutableStateFlow<Int?>(null)
}
