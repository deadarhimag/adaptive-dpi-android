package org.adaptivedpi.android.vpn

enum class VpnState {
    Idle,
    AwaitingConsent,
    Starting,
    TestSessionRunning,
    TransportStarting,
    EngineRunning,
    Stopping,
    ConsentDenied,
    ConsentRevoked,
    StartFailed,
    Failed;

    val canStart: Boolean
        get() = when (this) {
            Idle, ConsentDenied, ConsentRevoked, StartFailed, Failed -> true
            else -> false
        }

    val canStop: Boolean
        get() = this == Starting || this == TestSessionRunning || this == TransportStarting || this == EngineRunning || this == Stopping
}

internal enum class VpnEvent {
    ConsentRequired,
    ConsentDenied,
    ConsentAbandoned,
    StartRequested,
    ServiceStarted,
    TransportTunCreated,
    EngineConfirmed,
    StopRequested,
    ConsentRevoked,
    ServiceFailed,
    CriticalFailure,
    ServiceDestroyed,
    SystemConsentRevoked,
}

internal fun VpnState.reduce(event: VpnEvent): VpnState = when (event) {
    VpnEvent.ConsentRequired -> if (canStart) VpnState.AwaitingConsent else this
    VpnEvent.ConsentDenied -> if (this == VpnState.AwaitingConsent) VpnState.ConsentDenied else this
    VpnEvent.ConsentAbandoned -> if (this == VpnState.AwaitingConsent) VpnState.Idle else this
    VpnEvent.StartRequested -> if (canStart || this == VpnState.AwaitingConsent) VpnState.Starting else this
    VpnEvent.ServiceStarted -> if (this == VpnState.Starting) VpnState.TestSessionRunning else this
    VpnEvent.TransportTunCreated -> if (this == VpnState.Starting) VpnState.TransportStarting else this
    VpnEvent.EngineConfirmed -> if (this == VpnState.TestSessionRunning || this == VpnState.TransportStarting) VpnState.EngineRunning else this
    VpnEvent.StopRequested -> if (canStop) VpnState.Stopping else this
    VpnEvent.ConsentRevoked, VpnEvent.SystemConsentRevoked -> when (this) {
        VpnState.AwaitingConsent, VpnState.Starting, VpnState.TestSessionRunning, VpnState.TransportStarting,
        VpnState.EngineRunning, VpnState.Stopping ->
            VpnState.ConsentRevoked
        else -> this
    }
    VpnEvent.ServiceFailed -> when (this) {
        VpnState.AwaitingConsent, VpnState.Starting, VpnState.TransportStarting -> VpnState.StartFailed
        VpnState.Stopping -> VpnState.Idle
        else -> this
    }
    VpnEvent.CriticalFailure -> when (this) {
        VpnState.Starting, VpnState.TestSessionRunning, VpnState.TransportStarting, VpnState.EngineRunning,
        VpnState.Stopping -> VpnState.Failed
        else -> this
    }
    VpnEvent.ServiceDestroyed -> when (this) {
        VpnState.Starting, VpnState.TransportStarting -> VpnState.StartFailed
        VpnState.TestSessionRunning, VpnState.EngineRunning, VpnState.Stopping -> VpnState.Idle
        else -> this
    }
}
