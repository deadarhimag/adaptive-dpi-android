package org.adaptivedpi.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import java.io.File

class DiagnosticFileProvider : FileProvider()

internal object DiagnosticExports {
    private const val CACHE_DIR = "diagnostic-reports"
    private const val MAX_CACHED_FILES = 16
    private const val MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1000L
    const val COMBINED_FILE_NAME = "adaptive-dpi-diagnostics.txt"

    fun fileName(event: DiagnosticEvent) = "adaptive-dpi-${event.id}.txt"

    fun save(context: Context, uri: Uri, report: String) {
        require(uri.scheme == "content")
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
            it.write(report)
        }
    }

    fun shareIntent(context: Context, event: DiagnosticEvent): Intent = shareIntent(context, fileName(event), event.report())

    @Synchronized
    fun shareIntent(context: Context, fileName: String, report: String): Intent {
        val directory = File(context.cacheDir, CACHE_DIR)
        check(directory.isDirectory || directory.mkdirs())

        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        val existing = directory.listFiles().orEmpty().filter { it.isFile }.sortedByDescending(File::lastModified)
        existing.forEachIndexed { index, file ->
            if (index >= MAX_CACHED_FILES - 1 || file.lastModified() < cutoff) {
                check(file.delete() || !file.exists())
            }
        }

        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        file.writeText(report, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, fileName, uri) }
    }
}
