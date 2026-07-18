package com.antijail.overlay.engine

import android.os.SystemClock
import com.antijail.overlay.accessibility.AutoClickAccessibilityService
import com.antijail.overlay.model.FishSettings
import com.antijail.overlay.model.Point
import com.antijail.overlay.model.SetupLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fish mode automation — pirate HP watch and kill sequence while fishing.
 */
class FishEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var pirateWatchJob: Job? = null

    @Volatile private var stopRequested = false
    @Volatile private var pirateCombatActive = false
    @Volatile private var detectionCooldownUntilMs = 0L

    private var activeSettings: FishSettings = FishSettings()
    private var pirateDetector: (() -> Boolean)? = null
    private var onPirateCombatToast: (() -> Unit)? = null

    private companion object {
        private const val ATTACK_HOLD_MS = 200L
        private const val PIRATE_POLL_INTERVAL_MS = 1_300L
        private const val PIRATE_POST_COMBAT_DELAY_MS = 100L
        private const val PIRATE_PRE_STOP_MS = 50L
        private const val PIRATE_STEP_MS = 100L
        private const val PIRATE_ATTACK_HOLD_MS = 4_000L
        private const val GESTURE_CANCEL_SETTLE_MS = 50L
    }

    fun isRunning(): Boolean = loopJob?.isActive == true

    fun start(
        settings: FishSettings,
        layoutProvider: () -> SetupLayout,
        pirateDetector: () -> Boolean = { false },
        onPirateCombatToast: () -> Unit = {}
    ) {
        stop()
        stopRequested = false
        detectionCooldownUntilMs = 0L
        activeSettings = settings
        this.pirateDetector = pirateDetector
        this.onPirateCombatToast = onPirateCombatToast

        loopJob = scope.launch {
            if (settings.killPirates) {
                runPirateWatchLoop(layoutProvider)
            } else {
                while (!stopRequested) {
                    delay(500L)
                }
            }
        }
    }

    fun stop() {
        stopRequested = true
        pirateCombatActive = false
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        loopJob?.cancel()
        loopJob = null
        pirateWatchJob?.cancel()
        pirateWatchJob = null
    }

    private suspend fun delayUnlessStopped(ms: Long): Boolean {
        if (stopRequested) return false
        delay(ms)
        return !stopRequested
    }

    private suspend fun cancelGesturesAndSettle() {
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        delayUnlessStopped(GESTURE_CANCEL_SETTLE_MS)
    }

    private suspend fun awaitGesture(onDispatch: (onDone: () -> Unit) -> Unit) {
        if (stopRequested) return
        suspendCancellableCoroutine { cont ->
            var resumed = false
            fun finish() {
                if (!resumed) {
                    resumed = true
                    cont.resume(Unit)
                }
            }
            cont.invokeOnCancellation { finish() }
            onDispatch { finish() }
        }
    }

    private suspend fun awaitTap(
        service: AutoClickAccessibilityService,
        point: Point,
        holdMs: Long = ATTACK_HOLD_MS
    ) {
        awaitGesture { onDone -> service.tap(point, holdMs, onDone) }
    }

    private fun ensureRunning(): Boolean = !stopRequested

    private suspend fun runPirateWatchLoop(layoutProvider: () -> SetupLayout) {
        while (!stopRequested) {
            if (!activeSettings.killPirates) {
                if (!delayUnlessStopped(500L)) return
                continue
            }
            if (pirateCombatActive) {
                if (!delayUnlessStopped(100L)) return
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (now < detectionCooldownUntilMs) {
                val wait = (detectionCooldownUntilMs - now).coerceAtMost(200L)
                if (!delayUnlessStopped(wait)) return
                continue
            }
            if (pirateDetector?.invoke() == true) {
                runPirateKillSequence(layoutProvider)
                continue
            }
            if (!delayUnlessStopped(PIRATE_POLL_INTERVAL_MS)) return
        }
    }

    private suspend fun runPirateKillSequence(layoutProvider: () -> SetupLayout) {
        if (pirateCombatActive || stopRequested) return
        val service = AutoClickAccessibilityService.instance ?: return
        val layout = layoutProvider()

        pirateCombatActive = true
        cancelGesturesAndSettle()
        onPirateCombatToast?.invoke()

        try {
            if (!delayUnlessStopped(PIRATE_PRE_STOP_MS)) return
            awaitTap(service, layout.resolvedFishStopButton(), ATTACK_HOLD_MS)
            if (!delayUnlessStopped(PIRATE_STEP_MS)) return

            awaitTap(service, layout.tool2Button, ATTACK_HOLD_MS)
            if (!delayUnlessStopped(PIRATE_STEP_MS)) return
            awaitTap(service, layout.tool2Button, ATTACK_HOLD_MS)
            if (!delayUnlessStopped(PIRATE_STEP_MS)) return

            awaitTap(service, layout.attackDown, PIRATE_ATTACK_HOLD_MS)
            if (!delayUnlessStopped(PIRATE_STEP_MS)) return

            awaitTap(service, layout.tool1Button, ATTACK_HOLD_MS)
            if (!delayUnlessStopped(PIRATE_STEP_MS)) return

            awaitTap(service, layout.resolvedFishStopButton(), ATTACK_HOLD_MS)
        } finally {
            pirateCombatActive = false
            if (!stopRequested) {
                detectionCooldownUntilMs =
                    SystemClock.elapsedRealtime() + PIRATE_POST_COMBAT_DELAY_MS
            }
        }
    }
}
