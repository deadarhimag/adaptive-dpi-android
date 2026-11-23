package org.adaptivedpi.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.net.VpnService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class VpnConsentInstrumentedTest {
    @Test
    fun grantsVpnThroughAndroidConfirmationDialog() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyFreshVpnConsent") == "true")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val settings = automation.serviceInfo
        val previousFlags = settings.flags
        settings.flags = previousFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = settings
        try {
            ActivityScenario.launch(TransportTestActivity::class.java).use { scenario ->
                lateinit var consentResult: StateFlow<Int?>
                scenario.onActivity {
                    consentResult = it.consentResult
                    val request = checkNotNull(VpnService.prepare(it)) { "Для теста нужен AVD без ранее выданного согласия" }
                    assertEquals("com.android.vpndialogs", request.component?.packageName)
                    it.launchConsent(request)
                }
                withTimeout(15_000) {
                    var confirmed = false
                    while (!confirmed) {
                        val root = automation.rootInActiveWindow
                        if (root?.packageName?.toString() == "com.android.vpndialogs") {
                            confirmed = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                                .any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        }
                        if (!confirmed) delay(100)
                    }
                }
                assertEquals(Activity.RESULT_OK, withTimeout(10_000) { consentResult.filterNotNull().first() })
                scenario.onActivity { assertNull(VpnService.prepare(it)) }
            }
        } finally {
            settings.flags = previousFlags
            automation.serviceInfo = settings
        }
    }
}
