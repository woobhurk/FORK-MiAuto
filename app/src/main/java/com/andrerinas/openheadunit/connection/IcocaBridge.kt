package com.andrerinas.openheadunit.connection

import android.content.Context
import android.view.Surface
import com.andrerinas.openheadunit.osn.OsnCarLink
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges the MiAuto ICCOA CarLink SDK (a plain Java facade, [IccoaManager]) into DiAuto's
 * reactive architecture.
 *
 * DiAuto drives every consumer from a single `StateFlow` (see `CommManager.connectionState`);
 * the ICCOA SDK instead pushes callbacks on worker threads. This class is the adapter between
 * the two: it implements [IccoaManager.Listener], folds the raw callbacks into one observable
 * [state], and exposes the navigation stream the cluster / HUD outputs consume.
 *
 * MiAuto is ICCOA-only — there is no Android Auto path at runtime — so this bridge (not
 * `CommManager`) is the source of truth for both the home screen and the projection activity.
 */
class IcocaBridge(context: Context) : IccoaManager.Listener {

    private val appContext = context.applicationContext

    /** Coarse connection phase, mapped from the SDK's numeric state. */
    enum class Phase {
        /** Not started, or stopped by the user. */
        Idle,

        /** BLE advertising; waiting for a phone to discover us. */
        Advertising,

        /** Phone found us and is pairing / negotiating (PIN exchanged, Wi-Fi or USB negotiated). */
        Pairing,

        /** Session established; the phone may now push video. */
        Connected,
    }

    data class State(
        val phase: Phase = Phase.Idle,
        /** Pairing code to show on screen, if the SDK handed one out. */
        val pinCode: String? = null,
        val carDeviceName: String? = null,
        /** Last raw SDK log line, kept for the diagnostics screen. */
        val lastLog: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<UCarCommon.NavigationInfo?>(null)

    /** Latest navigation snapshot from the phone (ICCOA `NotifyNavigationInfo`). */
    val navigation: StateFlow<UCarCommon.NavigationInfo?> = _navigation.asStateFlow()

    /**
     * The underlying SDK facade, or null when this APK was built without `icar-sdk.dex`.
     *
     * Constructing [IccoaManager] resolves `com.ucar.vehiclesdk.*`. Those classes live only in the
     * injected dex, which is not part of a source checkout. Doing that from [AppComponent] — which
     * [com.andrerinas.openheadunit.App.onCreate] builds unconditionally — used to kill the process
     * with [NoClassDefFoundError] before the launcher was drawn. A missing SDK disables the link;
     * it must not disable the app.
     */
    val manager: IccoaManager? = try {
        IccoaManager(context.applicationContext, this)
    } catch (error: LinkageError) {
        AppLog.e(
            "ICCOA SDK is not in this APK (${error.javaClass.simpleName}: ${error.message}). " +
                "Opening without CarLink."
        )
        null
    }

    @Volatile
    private var started = false

    /** Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising. */
    fun start() {
        val sdk = manager ?: run {
            AppLog.w("ICCOA: start() ignored — SDK is not packaged")
            return
        }
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            sdk.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        AppLog.i("ICCOA: start()")
        updatePhase(Phase.Advertising)
        sdk.start()
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager?.stop()
        safeOsn { OsnCarLink.onDisconnected(appContext) }
        _state.update { it.copy(phase = Phase.Idle, pinCode = null) }
    }

    val isStarted: Boolean get() = started

    /**
     * Drops the current session but keeps the link alive and advertising, so the phone can come
     * straight back without the user restarting the app. Backs the projection screen's disconnect
     * affordance, which must not behave like a power-off.
     */
    fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession()")
        manager?.disconnectSession()
        _state.update { it.copy(phase = Phase.Advertising, pinCode = null) }
    }

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager?.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    fun setScreenSize(width: Int, height: Int) {
        manager?.setScreenSize(width, height)
    }

    /**
     * Hands the projection [surface] to the SDK. Idempotent: the SDK ignores a repeated call for
     * an already-casting surface, and [IccoaManager] only starts the cast once.
     */
    fun setSurface(surface: Surface) {
        manager?.setSurface(surface)
    }

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    fun lastPinCode(): String? = manager?.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        AppLog.i("ICCOA: $msg")
        _state.update { it.copy(lastLog = msg) }
    }

    override fun onConnectionState(stateCode: Int) {
        val phase = when (stateCode) {
            0 -> Phase.Advertising
            1, 2 -> Phase.Pairing
            3 -> Phase.Connected
            else -> Phase.Advertising
        }
        AppLog.i("ICCOA: connection state $stateCode -> $phase")
        updatePhase(phase)
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        AppLog.i("ICCOA: pinCode=$pinCode carDeviceName=$carDeviceName")
        _state.update {
            it.copy(phase = Phase.Pairing, pinCode = pinCode, carDeviceName = carDeviceName)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        updatePhase(Phase.Connected)
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info
        if (info != null) safeOsn { OsnCarLink.onNavigation(appContext, info) }
    }

    override fun onMusic(info: UCarCommon.MusicInfo?) {
        if (info != null) safeOsn { OsnCarLink.onMusic(appContext, info) }
    }

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: Phase) {
        val previous = _state.value.phase
        _state.update { current ->
            current.copy(
                phase = phase,
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
            )
        }
        if (previous == Phase.Connected && phase != Phase.Connected) {
            safeOsn { OsnCarLink.onDisconnected(appContext) }
        }
    }

    /** Cluster / HUD output must not take down the process that draws the standby screen. */
    private inline fun safeOsn(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            AppLog.w("OSN output failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
