package com.antijail.overlay.engine

import android.os.Handler
import android.os.Looper
import com.antijail.overlay.accessibility.AutoClickAccessibilityService
import com.antijail.overlay.capture.ScreenCaptureService
import com.antijail.overlay.model.ActionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutomationEngine {
    private var loopJob: Job? = null
    private var actionJob: Job? = null
    @Volatile
    private var stopRequested: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = loopJob?.isActive == true

    fun start(
        profileProvider: () -> ActionProfile,
        captureServiceProvider: () -> ScreenCaptureService?,
        canRunProvider: () -> Boolean,
        onAutoPaused: (() -> Unit)? = null,
        onDetectionStatus: ((hasGreen: Boolean, willPause: Boolean) -> Unit)? = null
    ) {
        if (isRunning()) return
        stopRequested = false
        loopJob = CoroutineScope(Dispatchers.Default).launch {
            // Give MediaProjection / ImageReader a moment to produce stable frames.
            delay(2500L)
            while (isActive) {
                if (!canRunProvider()) {
                    delay(300L)
                    continue
                }
                val profile = profileProvider()
                val capture = captureServiceProvider()
                val hasGreen = capture?.regionShowsDrillGreen(profile.drillRegion) ?: false

                if (stopRequested) break

                if (hasGreen) {
                    mainHandler.post { onDetectionStatus?.invoke(true, false) }
                } else {
                    mainHandler.post { onDetectionStatus?.invoke(false, true) }
                    val service = AutoClickAccessibilityService.instance
                    if (service != null && canRunProvider()) {
                        val up = findMovementPoint(profile, "UP")
                        val down = findMovementPoint(profile, "DOWN")
                        val right = findMovementPoint(profile, "RIGHT")
                        val left = findMovementPoint(profile, "LEFT")

                        delay(GestureDelays.PRE_ACTION_MS)
                        service.tap(profile.pausePoint, holdMs = 200L)
                        delay(700L)
                        if (stopRequested || !canRunProvider()) break
                        if (up != null && down != null) {
                            delay(GestureDelays.PRE_ACTION_MS)
                            service.drag(up, down, durationMs = 220L)
                        }
                        delay(700L)
                        if (stopRequested || !canRunProvider()) break
                        if (right != null && left != null) {
                            delay(GestureDelays.PRE_ACTION_MS)
                            service.drag(right, left, durationMs = 220L)
                        }
                        delay(700L)
                    }
                    stop()
                    onAutoPaused?.invoke()
                    break
                }
                delay(3000L)
            }
        }
    }

    fun stop() {
        stopRequested = true
        loopJob?.cancel()
        loopJob = null
        actionJob?.cancel()
        actionJob = null
    }

    fun cancelCurrentAction() {
        stopRequested = true
        actionJob?.cancel()
        actionJob = null
    }

    fun runHome(profile: ActionProfile) {
        actionJob?.cancel()
        actionJob = CoroutineScope(Dispatchers.Default).launch {
            runClickSeries(profile.homeClicks) { !stopRequested }
            runDragSeries(profile.homeDrags) { !stopRequested }
        }
    }

    fun runFood(profile: ActionProfile) {
        actionJob?.cancel()
        actionJob = CoroutineScope(Dispatchers.Default).launch {
            runClickSeries(profile.foodClicks) { !stopRequested }
            runDragSeries(profile.foodDrags) { !stopRequested }
        }
    }

    private suspend fun runClickSeries(
        clicks: List<com.antijail.overlay.model.ClickPoint>,
        canContinue: () -> Boolean
    ) {
        val service = AutoClickAccessibilityService.instance ?: return
        for (click in clicks) {
            if (!canContinue()) return
            delay(GestureDelays.PRE_ACTION_MS)
            service.tap(click.point, holdMs = 200L)
            delay(250L)
        }
    }

    private suspend fun runDragSeries(
        drags: List<com.antijail.overlay.model.DragPath>,
        canContinue: () -> Boolean
    ) {
        val service = AutoClickAccessibilityService.instance ?: return
        for (drag in drags) {
            if (!canContinue()) return
            delay(GestureDelays.PRE_ACTION_MS)
            service.drag(drag.start, drag.end, drag.durationMs)
            delay(300L)
        }
    }

    private fun findMovementPoint(profile: ActionProfile, label: String) =
        profile.movementPoints.firstOrNull { it.label.equals(label, ignoreCase = true) }?.point
}
