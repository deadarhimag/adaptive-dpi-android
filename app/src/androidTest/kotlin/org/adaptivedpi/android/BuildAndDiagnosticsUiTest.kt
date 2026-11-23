package org.adaptivedpi.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.adaptivedpi.android.designsystem.AdaptiveDpiTheme
import org.adaptivedpi.android.designsystem.ThemeMode
import org.adaptivedpi.android.ui.VpnSwitchDialog
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticCode
import org.adaptivedpi.android.vpn.diagnostics.VpnDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class BuildAndDiagnosticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun uiTracksTheRunningServiceAndReturnsToIdleAfterStop() {
        try {
            compose.onNodeWithText(string(R.string.vpn_start)).performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { VpnController.state.value == VpnState.TestSessionRunning }
            compose.onNodeWithText(string(R.string.vpn_test_session_running)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.vpn_stop)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) { VpnController.state.value == VpnState.Idle }
            compose.onNodeWithText(string(R.string.vpn_idle)).assertIsDisplayed()
        } finally {
            compose.runOnIdle { VpnController.stop() }
        }
    }

    @Test
    fun diagnosticsShowsExportAndVpnHistoryWithoutScrollingPastTheStatus() {
        compose.onNodeWithText(string(R.string.diagnostics)).performScrollTo().performClick()
        val export = string(R.string.diagnostic_export_all)
        val action = hasText(export) and isEnabled()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(export).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.vpn_journal_title)).assertIsDisplayed()

        compose.onNodeWithText(export).performClick()
        compose.onNodeWithText(string(R.string.diagnostic_preview)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.diagnostic_save)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.diagnostic_share)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.cancel)).performClick()
        compose.onNodeWithText(string(R.string.diagnostic_preview)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.diagnostic_history)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun individualErrorUsesTheSameExportActionsAndReturnsToItsReportOnCancel() {
        val previous = VpnDiagnostics.events.value.map { it.id }.toSet()
        VpnDiagnostics.record(DiagnosticCode.SaveFailed)
        compose.waitUntil(timeoutMillis = 5_000) {
            VpnDiagnostics.events.value.any { it.id !in previous && it.code == DiagnosticCode.SaveFailed }
        }
        val event = VpnDiagnostics.events.value.first { it.id !in previous && it.code == DiagnosticCode.SaveFailed }
        try {
            compose.onNodeWithText(string(R.string.diagnostic_technical)).performClick()
            compose.onNodeWithText(string(R.string.diagnostic_export_error)).performScrollTo().performClick()
            compose.onNodeWithText(string(R.string.diagnostic_save)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.diagnostic_share)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.cancel)).performClick()
            compose.onNodeWithText(string(R.string.diagnostic_preview)).assertExists()
        } finally {
            VpnDiagnostics.acknowledge(event.id)
            compose.waitUntil(timeoutMillis = 5_000) {
                VpnDiagnostics.events.value.none { it.id == event.id && !it.acknowledged }
            }
        }
    }

    @Test
    fun transportExportOffersSaveShareAndCancelFromOneButton() {
        compose.onNodeWithText(string(R.string.diagnostics)).performScrollTo().performClick()
        val label = hasText(string(R.string.transport_export)) and hasClickAction()
        val export = compose.onNode(label)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(label and isEnabled()).fetchSemanticsNodes().size == 1
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launches = ConcurrentLinkedQueue<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT && intent.action != Intent.ACTION_CHOOSER) return null
                launches.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        val cache = File(compose.activity.cacheDir, "diagnostic-reports")
        val existingFiles = cache.list().orEmpty().toSet()
        try {
            export.performScrollTo().performClick()
            compose.onNode(hasText(string(R.string.transport_export)) and !hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.diagnostic_save)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.diagnostic_share)).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.cancel)).performClick()
            compose.onNodeWithText(string(R.string.diagnostic_save)).assertDoesNotExist()
            assertTrue(launches.isEmpty())
            assertTrue(cache.list().orEmpty().all { it in existingFiles })

            export.performClick()
            compose.onNodeWithText(string(R.string.diagnostic_save)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { launches.isNotEmpty() }
            val save = launches.remove()
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.action)
            assertEquals("text/plain", save.type)
            assertEquals(DiagnosticExports.TRANSPORT_FILE_NAME, save.getStringExtra(Intent.EXTRA_TITLE))
            compose.onNodeWithText(string(R.string.diagnostic_save)).assertDoesNotExist()

            export.performClick()
            compose.onNodeWithText(string(R.string.diagnostic_share)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { launches.isNotEmpty() }
            val share = launches.remove()
            assertEquals(Intent.ACTION_CHOOSER, share.action)
            assertEquals("content", checkNotNull(share.clipData).getItemAt(0).uri.scheme)
            compose.onNodeWithText(string(R.string.diagnostic_share)).assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun aboutShowsBuildNumberAndPackagedCommit() {
        compose.onNodeWithText(string(R.string.settings)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.about_title)).performScrollTo().performClick()

        val activity = compose.activity
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val revision = string(R.string.build_git_revision)
        val expectedCommit = if (revision == "unknown") string(R.string.about_unknown) else revision.take(12)
        val build = string(R.string.build_number)
        val expectedBuild = if (build == "local") {
            activity.getString(R.string.about_local_build, info.longVersionCode.toString())
        } else {
            activity.getString(R.string.about_build, build)
        }

        compose.onNodeWithText(activity.getString(R.string.about_version, info.versionName)).assertIsDisplayed()
        compose.onNodeWithText(expectedBuild).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.about_git_commit, expectedCommit)).assertIsDisplayed()
    }

    @Test
    fun anotherVpnDialogOffersThreeIndependentActions() {
        val actions = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                AdaptiveDpiTheme(ThemeMode.System) {
                    VpnSwitchDialog(
                        restricted = false,
                        onSwitch = { actions += "switch" },
                        onVpnSettings = { actions += "settings" },
                        onDismiss = { actions += "cancel" },
                    )
                }
            }
        }
        compose.onNodeWithText(string(R.string.vpn_switch_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.vpn_switch_cancel)).performClick()
        compose.onNodeWithText(string(R.string.vpn_switch_settings)).performClick()
        compose.onNodeWithText(string(R.string.vpn_switch_confirm)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf("cancel", "settings", "switch"), actions) }
    }

    @Test
    fun configurationRestrictionOffersSettingsWithoutSwitching() {
        compose.runOnUiThread {
            compose.activity.setContent {
                AdaptiveDpiTheme(ThemeMode.System) {
                    VpnSwitchDialog(
                        restricted = true,
                        onSwitch = { error("VPN ограничен системой") },
                        onVpnSettings = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText(string(R.string.vpn_switch_restricted_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.vpn_switch_settings)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.vpn_switch_confirm)).assertDoesNotExist()
    }

    private fun string(id: Int): String = compose.activity.getString(id)
}
