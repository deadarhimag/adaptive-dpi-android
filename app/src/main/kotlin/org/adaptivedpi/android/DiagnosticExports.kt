package org.adaptivedpi.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.adaptivedpi.android.vpn.diagnostics.DiagnosticEvent
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import java.io.File
import java.io.Writer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class DiagnosticFileProvider : FileProvider()

internal object DiagnosticExports {
    private const val CACHE_DIR = "diagnostic-reports"
    private const val PENDING_DIR = "pending-export"
    private val pendingName = Regex("[0-9a-f-]{36}\\.txt")
    private const val MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_PENDING_FILES = 8
    private const val MAX_PENDING_BYTES = 1_048_576
    private val retained = mutableSetOf<String>()

    const val COMBINED_FILE_NAME = "adaptive-dpi-diagnostics.txt"
    const val TRANSPORT_FILE_NAME = "adaptive-dpi-transport.txt"

    fun fileName(event: DiagnosticEvent) = "adaptive-dpi-${event.id}.txt"

    fun save(context: Context, uri: Uri, report: String) = save(context, uri) { it.write(report) }

    suspend fun saveTransportJournal(context: Context, uri: Uri) = save(context, uri) {
        TransportDiagnostics.export(it)
    }

    private inline fun save(context: Context, uri: Uri, write: (Writer) -> Unit) {
        require(uri.scheme == "content")
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use(write)
    }

    fun shareIntent(context: Context, event: DiagnosticEvent): Intent = shareIntent(context, fileName(event), event.report())

    fun shareIntent(context: Context, fileName: String, report: String): Intent =
        shareIntent(context, fileName) { it.write(report) }

    suspend fun shareTransportJournal(context: Context): Intent = shareIntent(context, TRANSPORT_FILE_NAME) {
        TransportDiagnostics.export(it)
    }

    fun shareChooser(send: Intent, title: CharSequence): Intent = Intent.createChooser(send, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = send.clipData }

    fun pruneExpiredShares(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        File(context.cacheDir, CACHE_DIR).listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    @Synchronized
    fun retainPending(name: String) {
        if (pendingName.matches(name)) retained.add(name)
    }

    @Synchronized
    fun releasePending(name: String) {
        retained.remove(name)
    }

    @Synchronized
    fun prunePending(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        File(context.noBackupFilesDir, PENDING_DIR).listFiles().orEmpty()
            .filter(::isOrphan)
            .filter { it.name.endsWith(".tmp") || it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private fun isOrphan(file: File): Boolean {
        val name = file.name.removeSuffix(".tmp").removeSuffix(".writing")
        return file.isFile && pendingName.matches(name) && name !in retained
    }

    /**
     * Показанный пользователю снимок общего отчёта на время системного диалога сохранения.
     * Файл приватный и переживает гибель процесса; в сохранённом состоянии Activity хранится только имя.
     */
    @Synchronized
    fun storePending(context: Context, report: String, name: String = "${java.util.UUID.randomUUID()}.txt"): String {
        require(pendingName.matches(name))
        require(report.length <= MAX_PENDING_BYTES)
        val bytes = report.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PENDING_BYTES)
        prunePending(context)
        val directory = File(context.noBackupFilesDir, PENDING_DIR)
        directory.mkdirs()
        check(directory.isDirectory)
        val files = checkNotNull(directory.listFiles()).filter(File::isFile)
        var occupied = files.size
        for (orphan in files.filter(::isOrphan).sortedBy(File::lastModified)) {
            if (occupied < MAX_PENDING_FILES) break
            if (orphan.delete()) occupied--
        }
        check(occupied < MAX_PENDING_FILES)
        // Запись через временный файл: после сбоя не остаётся оборванного снимка под рабочим именем.
        val temporary = File(directory, "$name.tmp")
        try {
            temporary.writeBytes(bytes)
            check(temporary.renameTo(File(directory, name)))
        } finally {
            temporary.delete()
        }
        retained.add(name)
        return name
    }

    fun readPending(context: Context, name: String): String? = pendingFile(context, name)?.readText(Charsets.UTF_8)

    fun hasPending(context: Context, name: String): Boolean = pendingFile(context, name) != null

    fun hasInterruptedWrite(context: Context, name: String): Boolean =
        pendingName.matches(name) && File(File(context.noBackupFilesDir, PENDING_DIR), "$name.writing").isFile

    @Synchronized
    fun cancelPending(context: Context, name: String): Boolean {
        if (hasInterruptedWrite(context, name)) return false
        deletePending(context, name)
        return true
    }

    private fun pendingFile(context: Context, name: String): File? {
        if (!pendingName.matches(name)) return null
        val directory = File(context.noBackupFilesDir, PENDING_DIR)
        val file = File(directory, name).takeIf(File::isFile) ?: File(directory, "$name.writing")
        return file.takeIf { it.isFile && it.length() <= MAX_PENDING_BYTES }
    }

    internal enum class WriteClaim { Claimed, Interrupted, Missing }

    // Переименование фиксирует попытку до первого обращения к внешнему поставщику.
    @Synchronized
    fun claimWrite(context: Context, name: String): WriteClaim {
        require(pendingName.matches(name))
        val directory = File(context.noBackupFilesDir, PENDING_DIR)
        val writing = File(directory, "$name.writing")
        if (writing.isFile) return WriteClaim.Interrupted
        val pending = File(directory, name)
        if (!pending.isFile) return WriteClaim.Missing
        check(pending.renameTo(writing))
        return WriteClaim.Claimed
    }

    internal enum class PreparedSnapshot { Ready, Interrupted, Missing, Invalid }

    @Synchronized
    fun inspectPrepared(context: Context, name: String): PreparedSnapshot = inspectPrepared(context, name, retry = false)

    @Synchronized
    fun armRetry(context: Context, name: String): PreparedSnapshot = inspectPrepared(context, name, retry = true)

    private fun inspectPrepared(context: Context, name: String, retry: Boolean): PreparedSnapshot {
        if (!pendingName.matches(name)) return PreparedSnapshot.Invalid
        val ready = File(context.noBackupFilesDir, PENDING_DIR).toPath().resolve(name)
        val writing = ready.resolveSibling("$name.writing")
        // Маркер прерванной записи имеет приоритет, даже если рядом обнаружен другой файл.
        val state = inspectSnapshotFile(writing) ?: return inspectSnapshotFile(ready) ?: PreparedSnapshot.Missing
        if (state == PreparedSnapshot.Invalid) return state
        if (!retry) return PreparedSnapshot.Interrupted
        Files.move(writing, ready)
        return PreparedSnapshot.Ready
    }

    private fun inspectSnapshotFile(path: Path): PreparedSnapshot? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() > MAX_PENDING_BYTES) {
            PreparedSnapshot.Invalid
        } else {
            Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (reader.read(buffer) != -1) Unit
            }
            PreparedSnapshot.Ready
        }
    } catch (_: NoSuchFileException) {
        null
    } catch (_: CharacterCodingException) {
        PreparedSnapshot.Invalid
    }

    @Synchronized
    fun deletePending(context: Context, name: String) {
        retained.remove(name)
        if (!pendingName.matches(name)) return
        val directory = File(context.noBackupFilesDir, PENDING_DIR)
        File(directory, name).delete()
        File(directory, "$name.tmp").delete()
        File(directory, "$name.writing").delete()
    }

    private inline fun shareIntent(context: Context, fileName: String, write: (Writer) -> Unit): Intent {
        val directory = File(context.cacheDir, CACHE_DIR)
        directory.mkdirs()
        check(directory.isDirectory)
        pruneExpiredShares(context)

        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        var prepared = false
        try {
            file.bufferedWriter(Charsets.UTF_8).use(write)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file, fileName)
            return Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply {
                    clipData = ClipData.newUri(context.contentResolver, fileName, uri)
                    prepared = true
                }
        } finally {
            if (!prepared) file.delete()
        }
    }
}
