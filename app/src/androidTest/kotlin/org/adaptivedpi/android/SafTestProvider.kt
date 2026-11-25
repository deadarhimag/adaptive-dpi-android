package org.adaptivedpi.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Изолированный поставщик тестового APK. Основное приложение получает реальный content URI. */
class SafTestProvider : ContentProvider() {
    private class Destination(val mode: String) {
        val opened = AtomicInteger()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var bytes: ByteArray? = null
        @Volatile var writing = false
    }

    @Volatile private var destination = Destination("success")

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        when (method) {
            "reset" -> {
                destination.release.countDown()
                destination = Destination(checkNotNull(arg))
            }
            "release" -> destination.release.countDown()
            "state" -> Unit
            else -> error("Неизвестная команда фикстуры")
        }
        return Bundle().apply {
            putInt("opened", destination.opened.get())
            putBoolean("finished", destination.finished.count == 0L)
            putBoolean("writing", destination.writing)
            destination.bytes?.let {
                putInt("bytes", it.size)
                putString("digest", digest(it))
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val current = destination
        when (current.mode) {
            "open-error" -> {
                current.opened.incrementAndGet()
                throw FileNotFoundException("Контролируемый отказ openFile")
            }
            "write-error" -> {
                val (read, write) = ParcelFileDescriptor.createPipe()
                read.close()
                current.opened.incrementAndGet()
                return write
            }
            else -> {
                val (read, write) = ParcelFileDescriptor.createPipe()
                thread(isDaemon = true, name = "SAF fixture") {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(read).use {
                            val first = it.read()
                            check(first >= 0)
                            current.writing = true
                            check(current.release.await(20, TimeUnit.SECONDS))
                            current.bytes = byteArrayOf(first.toByte()) + it.readBytes()
                        }
                    } finally {
                        current.finished.countDown()
                    }
                }
                current.opened.incrementAndGet()
                return write
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
