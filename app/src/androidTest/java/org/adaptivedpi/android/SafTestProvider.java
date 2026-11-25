package org.adaptivedpi.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Android создаёт этот класс в процессе тестового APK, без classloader приложения. */
public final class SafTestProvider extends ContentProvider {
    private static final String TAG = "SafTestFixture";
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_WAIT_MILLIS = 20_000;
    private Destination destination = new Destination("success", -1, MAX_WAIT_MILLIS);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "state":
                return destination.state();
            case "release":
                destination.release.countDown();
                return destination.state();
            case "reset":
                if (!("success".equals(arg) || "open-error".equals(arg) || "write-error".equals(arg))) {
                    throw new IllegalArgumentException("Неизвестный режим SAF: " + arg);
                }
                int expected = extras == null ? -1 : extras.getInt("expectedBytes", -1);
                int timeout = extras == null ? MAX_WAIT_MILLIS : extras.getInt("timeoutMillis", MAX_WAIT_MILLIS);
                if (expected < -1 || expected > MAX_BYTES || timeout < 100 || timeout > MAX_WAIT_MILLIS) {
                    throw new IllegalArgumentException("Некорректные параметры SAF");
                }
                Bundle previous = destination.retire();
                destination = new Destination(arg, expected, timeout);
                Bundle state = destination.state();
                state.putBundle("previous", previous);
                return state;
            default:
                throw new IllegalArgumentException("Неизвестная команда SAF: " + method);
        }
    }

    @Override
    public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Destination current = destination;
        if (!current.begin()) {
            current.fail(new IOException("Повторное открытие без reset"));
            throw new FileNotFoundException("Повторное открытие SAF");
        }
        if ("open-error".equals(current.mode)) {
            current.complete("open-error", 0, null);
            throw new FileNotFoundException("Контролируемый отказ openFile");
        }
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
            if ("write-error".equals(current.mode)) {
                pipe[0].closeWithError("Контролируемый отказ записи");
                current.complete("write-error", 0, null);
            } else {
                ParcelFileDescriptor read = pipe[0];
                Thread worker = new Thread(() -> receive(current, read), TAG);
                worker.setDaemon(true);
                worker.start();
            }
            return pipe[1];
        } catch (IOException | RuntimeException error) {
            if (pipe != null) {
                for (ParcelFileDescriptor end : pipe) {
                    try {
                        end.close();
                    } catch (IOException closeError) {
                        error.addSuppressed(closeError);
                    }
                }
            }
            current.fail(error);
            current.complete("error", 0, null);
            FileNotFoundException failure = new FileNotFoundException("Не удалось открыть канал SAF");
            failure.initCause(error);
            throw failure;
        }
    }

    private static void receive(Destination current, ParcelFileDescriptor read) {
        long deadline = SystemClock.elapsedRealtime() + current.timeout;
        int size = 0;
        String digest = null;
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(read)) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            StructPollfd event = new StructPollfd();
            event.fd = read.getFileDescriptor();
            event.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
            StructPollfd[] events = {event};
            byte[] buffer = new byte[8192];
            while (!current.cancelled) {
                long remaining = remaining(deadline, "чтения");
                if (Os.poll(events, (int) Math.min(remaining, 100)) == 0 || current.cancelled) continue;
                if ((event.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) {
                    throw new IOException("Ошибка дескриптора канала SAF: " + event.revents);
                }
                int length = input.read(buffer);
                if (length < 0) {
                    read.checkError();
                    if (size == 0 || (current.expected >= 0 && size != current.expected)) {
                        throw new IOException("Канал SAF закрыт до получения отчёта: " + size + " байт");
                    }
                    digest = hex(sha256.digest());
                    break;
                }
                if (size == 0) {
                    current.markWriting();
                    if (!current.release.await(remaining(deadline, "разблокировки"), TimeUnit.MILLISECONDS)) {
                        throw new IOException("Тайм-аут разблокировки SAF");
                    }
                }
                size += length;
                if (size > MAX_BYTES) throw new IOException("Получен слишком большой отчёт SAF");
                sha256.update(buffer, 0, length);
            }
        } catch (Exception error) {
            current.fail(error);
        } finally {
            current.complete(digest == null ? "cancelled-by-reset" : "success", size, digest);
        }
    }

    private static long remaining(long deadline, String step) throws IOException {
        long result = deadline - SystemClock.elapsedRealtime();
        if (result <= 0) throw new IOException("Тайм-аут " + step + " SAF");
        return result;
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = digits.charAt((bytes[i] & 0xff) >>> 4);
            result[2 * i + 1] = digits.charAt(bytes[i] & 0xf);
        }
        return new String(result);
    }

    private static final class Destination {
        final String mode;
        final int expected;
        final int timeout;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean cancelled;
        private int opened;
        private int bytes;
        private String outcome = "idle";
        private String digest;
        private String error;

        Destination(String mode, int expected, int timeout) {
            this.mode = mode;
            this.expected = expected;
            this.timeout = timeout;
        }

        synchronized boolean begin() {
            if (++opened != 1) return false;
            outcome = "reading";
            return true;
        }

        synchronized void markWriting() {
            if (error == null) outcome = "writing";
        }

        synchronized void fail(Exception failure) {
            if (error == null) {
                error = failure.toString();
                outcome = "error";
                Log.e(TAG, "Ошибка тестового канала SAF", failure);
            }
        }

        synchronized void complete(String result, int size, String sha256) {
            if (error == null) {
                outcome = cancelled ? "cancelled-by-reset" : result;
                if ("success".equals(outcome)) {
                    bytes = size;
                    digest = sha256;
                }
            }
            finished.countDown();
        }

        Bundle retire() {
            synchronized (this) {
                if (opened == 0 || finished.getCount() == 0) return state();
                // Остановить незавершённое чтение. Уже зарегистрированный сбой сохраняется.
                cancelled = true;
                release.countDown();
            }
            try {
                if (!finished.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Старый канал SAF не завершился при reset");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание старого канала SAF прервано", failure);
            }
            return state();
        }

        synchronized Bundle state() {
            Bundle result = new Bundle();
            result.putInt("pid", Process.myPid());
            result.putInt("uid", Process.myUid());
            result.putInt("opened", opened);
            result.putBoolean("finished", finished.getCount() == 0);
            result.putBoolean("writing", "writing".equals(outcome));
            result.putString("outcome", outcome);
            result.putInt("bytes", bytes);
            result.putString("digest", digest);
            result.putString("error", error);
            return result;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
