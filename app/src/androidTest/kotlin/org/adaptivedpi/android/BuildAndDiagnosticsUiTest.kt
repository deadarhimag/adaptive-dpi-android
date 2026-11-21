package org.adaptivedpi.android

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class BuildAndDiagnosticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(string(R.string.diagnostic_history)).performScrollTo().assertIsDisplayed()
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

    private fun string(id: Int): String = compose.activity.getString(id)
}
