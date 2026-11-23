package org.adaptivedpi.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

private const val VPN_DIALOG_PACKAGE = "com.android.vpndialogs"
private const val VPN_CONFIRM_BUTTON = "android:id/button1"

class VpnConsentInstrumentedTest {
    @Test
    fun grantsVpnThroughAndroidConfirmationDialog() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyFreshVpnConsent") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val startedAt = SystemClock.elapsedRealtime()
        fun mark(phase: String) = Log.i(VPN_CONSENT_TAG, "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} $phase")

        val settings = automation.serviceInfo
        val previousFlags = settings.flags
        settings.flags = previousFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = settings
        try {
            ActivityScenario.launch(TransportTestActivity::class.java).use { scenario ->
                try {
                    lateinit var consentResult: StateFlow<Int?>
                    scenario.onActivity { activity ->
                        consentResult = activity.consentResult
                        val request = checkNotNull(VpnService.prepare(activity)) {
                            "Для теста нужен AVD без ранее выданного согласия"
                        }
                        assertEquals(VPN_DIALOG_PACKAGE, request.component?.packageName)
                        val locked = activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
                        mark("phase=launch component=${request.component?.flattenToShortString()} keyguardLocked=$locked")
                        activity.launchConsent(request)
                        mark("phase=launched")
                    }

                    clickConfirmation(automation, consentResult) { mark(it) }
                    val result = withTimeoutOrNull(10_000) { consentResult.filterNotNull().first() }
                    mark("phase=activity_result code=$result")
                    assertEquals("Результат Activity после нажатия подтверждения", Activity.RESULT_OK, result)
                    scenario.onActivity {
                        val pending = VpnService.prepare(it)
                        mark("phase=verify_permission prepareNull=${pending == null}")
                        assertNull("После RESULT_OK Android должен подтвердить разрешение VPN", pending)
                    }
                } catch (failure: Throwable) {
                    mark("phase=failed exception=${failure.javaClass.simpleName}")
                    if (arguments.getString("captureVpnConsentFailure") == "true") {
                        captureVpnConsentFailure(automation, instrumentation.targetContext.cacheDir)
                    }
                    throw failure
                }
            }
        } finally {
            settings.flags = previousFlags
            automation.serviceInfo = settings
        }
    }

    private suspend fun clickConfirmation(
        automation: UiAutomation,
        consentResult: StateFlow<Int?>,
        mark: (String) -> Unit,
    ) {
        var windowSeen = false
        var buttonSeen = false
        var clickAttempted = false
        val clicked = withTimeoutOrNull(15_000) {
            var accepted = false
            while (!accepted) {
                check(consentResult.value == null) {
                    "Android уже вернул результат ${consentResult.value} до нажатия подтверждения"
                }
                val roots = sequence {
                    for (window in automation.windows) {
                        if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            window.root?.let { yield(it) }
                        }
                    }
                    automation.rootInActiveWindow?.let { yield(it) }
                }.distinctBy { it.windowId }
                accepted = roots.filter { it.packageName?.toString() == VPN_DIALOG_PACKAGE }.any { root ->
                    if (!windowSeen) {
                        windowSeen = true
                        mark("phase=window_found id=${root.windowId}")
                    }
                    root.findAccessibilityNodeInfosByViewId(VPN_CONFIRM_BUTTON).any buttonCheck@{ button ->
                        if (button.packageName?.toString() != VPN_DIALOG_PACKAGE) return@buttonCheck false
                        val canClick = button.isVisibleToUser && button.isEnabled && button.isClickable
                        if (!buttonSeen) {
                            buttonSeen = true
                            mark(
                                "phase=button_found id=$VPN_CONFIRM_BUTTON visible=${button.isVisibleToUser} " +
                                    "enabled=${button.isEnabled} clickable=${button.isClickable}",
                            )
                        }
                        if (!canClick) return@buttonCheck false
                        if (!clickAttempted) {
                            clickAttempted = true
                            mark("phase=click_attempt")
                        }
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                if (!accepted) delay(100)
            }
            true
        }
        check(clicked == true) {
            when {
                !windowSeen -> "Окно системного согласия отсутствует в доступных окнах Accessibility"
                !buttonSeen -> "Окно системного согласия найдено, кнопка $VPN_CONFIRM_BUTTON недоступна"
                !clickAttempted -> "Кнопка подтверждения найдена, но недоступна для нажатия"
                else -> "Accessibility не принял действие ACTION_CLICK для кнопки подтверждения"
            }
        }
        mark("phase=click_accepted")
    }
}
