package org.adaptivedpi.android.engine

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.AsynchronousCloseException

enum class TransportSubsystem { TUN, HEV, TCP, UDP, DNS, SOCKET, LIFECYCLE }
enum class TransportOperation { INIT, RUN, ACCEPT, AUTHENTICATE, CONNECT, PROTECT, BIND, READ, WRITE, SEND, RECEIVE, RESOLVE, CLOSE, STOP, SCHEDULE, SHUTDOWN_OUTPUT }
enum class TransportProtocol { NONE, TCP, UDP }
enum class TransportDirection { CONTROL, OUTBOUND, INBOUND }
enum class TransportDisposition { EXPECTED, UNEXPECTED, UNKNOWN }

enum class TransportCategory(val disposition: TransportDisposition, val title: String, val level: String) {
    TIMEOUT(TransportDisposition.EXPECTED, "Истекло время ожидания", "INFO"),
    REFUSED(TransportDisposition.EXPECTED, "Узел отказал в подключении", "INFO"),
    RESET(TransportDisposition.EXPECTED, "Соединение сброшено", "INFO"),
    CLOSED(TransportDisposition.EXPECTED, "Канал закрыт или соединение прервано", "INFO"),
    UNREACHABLE(TransportDisposition.EXPECTED, "Сеть или узел недоступны", "INFO"),
    DNS(TransportDisposition.EXPECTED, "Ошибка разрешения имени", "INFO"),
    CANCELLED(TransportDisposition.EXPECTED, "Операция отменена при освобождении ресурсов", "DEBUG"),
    BACKPRESSURE(TransportDisposition.EXPECTED, "Операция отложена из-за заполнения буфера", "DEBUG"),
    LIMIT(TransportDisposition.UNEXPECTED, "Достигнут лимит транспорта", "WARNING"),
    PROTECTION(TransportDisposition.UNEXPECTED, "Не удалось защитить исходящий сокет от VPN", "ERROR"),
    SOCKET_SETUP(TransportDisposition.UNEXPECTED, "Не удалось подготовить исходящий сокет", "ERROR"),
    TUN_IO(TransportDisposition.UNEXPECTED, "Ошибка ввода-вывода TUN", "ERROR"),
    HEV_INIT(TransportDisposition.UNEXPECTED, "Ошибка инициализации HEV/lwIP", "ERROR"),
    RELAY_PROTOCOL(TransportDisposition.UNEXPECTED, "Ошибка локального протокола HEV", "WARNING"),
    CLEANUP(TransportDisposition.UNEXPECTED, "Не удалось завершить очистку транспорта", "ERROR"),
    INTERNAL(TransportDisposition.UNEXPECTED, "Внутренний отказ транспорта", "ERROR"),
    UNKNOWN(TransportDisposition.UNKNOWN, "Причина отказа не определена", "WARNING"),
}

enum class TransportCode(val label: String) {
    UNKNOWN("unknown"), TIMEOUT("SocketTimeoutException"), PORT_UNREACHABLE("PortUnreachableException"),
    NO_ROUTE("NoRouteToHostException"), UNKNOWN_HOST("UnknownHostException"),
    EOF("EOFException"), RELAY_PROTOCOL("RELAY_PROTOCOL"), CANCELLED("CANCELLED"),
    NO_PHYSICAL_NETWORK("NO_PHYSICAL_NETWORK"), SOCKET_LIMIT("SOCKET_LIMIT"), DATAGRAM_LIMIT("DATAGRAM_LIMIT"),
    PROTECT_REJECTED("PROTECT_REJECTED"), SESSION_LIMIT("SESSION_LIMIT"), WORKER_REJECTED("WORKER_REJECTED"),
    TUN_READ("TUN_READ"), TUN_WRITE("TUN_WRITE"), TUN_BUFFER("TUN_BUFFER"),
    HEV_RESERVE("HEV_RESERVE"), HEV_CONFIG("HEV_CONFIG"), HEV_LOGGER("HEV_LOGGER"),
    HEV_TASKS("HEV_TASKS"), HEV_TUN_INIT("HEV_TUN_INIT"), HEV_LOOP("HEV_LOOP"),
    HEV_READY_TIMEOUT("HEV_READY_TIMEOUT"), RELAY_STOP("RELAY_STOP"),
    UNEXPECTED_EXIT("UNEXPECTED_EXIT"), NETWORK_CHANGED("NETWORK_CHANGED"), NETWORK_LOST("NETWORK_LOST"),
    VPN_PERMISSION_REVOKED("VPN_PERMISSION_REVOKED"), IPV4_DNS_UNAVAILABLE("IPV4_DNS_UNAVAILABLE"),
    FLOW_CANCELLED("FLOW_CANCELLED"), SESSION_CANCELLED("SESSION_CANCELLED"), NETWORK_CANCELLED("NETWORK_CANCELLED"),
    PEER_EOF("PEER_EOF"), SOCKET_EXCEPTION("SocketException"), IO_EXCEPTION("IOException"),
    RELAY_DRAINING("RELAY_DRAINING"),
}

enum class TransportCancellation(val code: TransportCode) {
    FLOW(TransportCode.FLOW_CANCELLED), SESSION(TransportCode.SESSION_CANCELLED), NETWORK(TransportCode.NETWORK_CANCELLED),
}

data class TransportSite(
    val subsystem: TransportSubsystem,
    val operation: TransportOperation,
    val protocol: TransportProtocol = TransportProtocol.NONE,
    val direction: TransportDirection = TransportDirection.CONTROL,
)

/** В журнал передаются только значения из закрытого набора и числовые коды. */
data class TransportFailure(
    val site: TransportSite,
    val category: TransportCategory,
    val code: TransportCode = TransportCode.UNKNOWN,
    val errno: Int? = null,
    val result: Int? = null,
) {
    val codeLabel: String get() = when {
        errno != null -> "errno=${ErrnoCategory.symbol(errno) ?: errno} ($errno)" +
            (if (code == TransportCode.UNKNOWN) "" else ", ${code.label}") + (result?.let { ", result=$it" } ?: "")
        result != null -> "${code.label} ($result)"
        else -> code.label
    }
}

// Номера Linux/Android. При неизвестном числе сохраняется только числовой errno.
private enum class ErrnoCategory(val number: Int, val category: TransportCategory? = null) {
    EBADF(9), EAGAIN(11, TransportCategory.BACKPRESSURE), ENOMEM(12, TransportCategory.LIMIT), EPIPE(32, TransportCategory.CLOSED),
    ENETDOWN(100, TransportCategory.UNREACHABLE), ENETUNREACH(101, TransportCategory.UNREACHABLE),
    ECONNABORTED(103, TransportCategory.CLOSED), ECONNRESET(104, TransportCategory.RESET),
    ENOBUFS(105, TransportCategory.BACKPRESSURE), ENOTCONN(107),
    ETIMEDOUT(110, TransportCategory.TIMEOUT), ECONNREFUSED(111, TransportCategory.REFUSED),
    EHOSTDOWN(112, TransportCategory.UNREACHABLE), EHOSTUNREACH(113, TransportCategory.UNREACHABLE),
    ECANCELED(125);

    companion object {
        fun symbol(number: Int) = entries.firstOrNull { it.number == number }?.name
        fun from(number: Int?) = entries.firstOrNull { it.number == number }
    }
}

/** Маркер внутренней причины. Подробности исключения могут содержать адреса. */
class TransportSocketException(
    val code: TransportCode,
    val category: TransportCategory,
    val operation: TransportOperation? = null,
    cause: Throwable? = null,
) : SocketException() {
    init { if (cause != null) initCause(cause) }
}

class TransportFailureReporter(
    private val errnoOf: (Throwable) -> Int? = { null },
    private val sink: (TransportFailure) -> Unit = {},
) {
    fun record(failure: TransportFailure) = sink(failure)

    fun recordError(
        site: TransportSite,
        error: Throwable,
        cancellation: TransportCancellation? = null,
        fallback: TransportCategory = TransportCategory.UNKNOWN,
        peerEof: Boolean = false,
    ) {
        val errno = errnoOf(error)
        val actualSite = when (error) {
            is UnknownHostException -> site.copy(subsystem = TransportSubsystem.DNS, operation = TransportOperation.RESOLVE)
            is TransportSocketException -> error.operation?.let { site.copy(operation = it) } ?: site
            else -> site
        }
        val nativeError = ErrnoCategory.from(errno)
        val codeFromType = when (error) {
            is SocketException -> TransportCode.SOCKET_EXCEPTION
            is EOFException -> TransportCode.EOF
            is IOException -> TransportCode.IO_EXCEPTION
            else -> TransportCode.UNKNOWN
        }
        val (category, code) = when {
            error is TransportSocketException && error.code != TransportCode.UNKNOWN -> error.category to error.code
            site.operation == TransportOperation.CLOSE && fallback == TransportCategory.CLEANUP -> fallback to codeFromType
            cancellation != null && isClosedOperation(error, errno, nativeError) -> TransportCategory.CANCELLED to cancellation.code
            nativeError == ErrnoCategory.ENOTCONN && site.operation == TransportOperation.SHUTDOWN_OUTPUT && peerEof ->
                TransportCategory.CLOSED to TransportCode.PEER_EOF
            fallback == TransportCategory.CLEANUP -> fallback to codeFromType
            nativeError?.category != null -> nativeError.category to TransportCode.UNKNOWN
            nativeError == ErrnoCategory.EBADF || nativeError == ErrnoCategory.ECANCELED -> TransportCategory.INTERNAL to codeFromType
            error is SocketTimeoutException -> TransportCategory.TIMEOUT to TransportCode.TIMEOUT
            error is PortUnreachableException -> TransportCategory.REFUSED to TransportCode.PORT_UNREACHABLE
            error is NoRouteToHostException -> TransportCategory.UNREACHABLE to TransportCode.NO_ROUTE
            error is UnknownHostException -> TransportCategory.DNS to TransportCode.UNKNOWN_HOST
            error is ProtocolException -> TransportCategory.RELAY_PROTOCOL to TransportCode.RELAY_PROTOCOL
            else -> fallback to codeFromType
        }
        record(TransportFailure(actualSite, category, code, errno))
    }

    private fun isClosedOperation(error: Throwable, errno: Int?, nativeError: ErrnoCategory?): Boolean {
        if (errno != null) return nativeError == ErrnoCategory.EBADF || nativeError == ErrnoCategory.ENOTCONN || nativeError == ErrnoCategory.ECANCELED
        return error is AsynchronousCloseException || error is InterruptedException ||
            error is InterruptedIOException && error !is SocketTimeoutException ||
            error is SocketException && error !is ConnectException && error !is NoRouteToHostException && error !is PortUnreachableException
    }
}
