package org.adaptivedpi.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import org.adaptivedpi.android.R
import org.adaptivedpi.android.designsystem.AdaptivePanel

@Composable
internal fun AboutScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val info = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val unknown = stringResource(R.string.about_unknown)
    val revision = stringResource(R.string.build_git_revision).takeUnless { it == "unknown" }?.take(12)
    val modified = stringResource(R.string.build_git_modified) == "true"
    val buildNumber = stringResource(R.string.build_number)

    AdaptivePanel {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.about_version, info?.versionName ?: unknown))
        val versionCode = info?.longVersionCode?.toString() ?: unknown
        Text(stringResource(R.string.about_version_code, versionCode))
        Text(
            if (buildNumber == "local") stringResource(R.string.about_local_build, versionCode)
            else stringResource(R.string.about_build, buildNumber),
        )
        SelectionContainer {
            Text(stringResource(R.string.about_git_commit, revision ?: unknown))
        }
        if (modified) Text(stringResource(R.string.about_local_changes))
        OutlinedButton(
            onClick = {
                runCatching { uriHandler.openUri(PROJECT_URL) }
                    .onFailure { Toast.makeText(context, R.string.about_link_unavailable, Toast.LENGTH_SHORT).show() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.about_github)) }
    }
    AdaptivePanel {
        Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.titleMedium)
        LicenseLink(R.string.about_mit, "${PROJECT_URL}/blob/main/LICENSE")
        LicenseLink(R.string.about_androidx, "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt")
        LicenseLink(R.string.about_kotlin, "https://github.com/JetBrains/kotlin/blob/v2.3.21/license/LICENSE.txt")
        LicenseLink(R.string.about_coroutines, "https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt")
        LicenseLink(R.string.about_symbols, "https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/LICENSE")
    }
}

@Composable
private fun LicenseLink(label: Int, url: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    OutlinedButton(
        onClick = {
            runCatching { uriHandler.openUri(url) }
                .onFailure { Toast.makeText(context, R.string.about_link_unavailable, Toast.LENGTH_SHORT).show() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(label)) }
}

private const val PROJECT_URL = "https://github.com/deadarhimag/adaptive-dpi-android"
