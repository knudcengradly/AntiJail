package com.antijail.overlay.engine

import android.os.SystemClock
import com.antijail.overlay.accessibility.AutoClickAccessibilityService
import com.antijail.overlay.capture.TextDetection
import com.antijail.overlay.model.BatteryShopImage
import com.antijail.overlay.model.MiningDirection
import com.antijail.overlay.model.MiningSettings
import com.antijail.overlay.model.Point
import com.antijail.overlay.model.SetupLayout
import com.antijail.overlay.model.SpiderClusterResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

class MiningEngine {
    private var loopJob: Job? = null
    private var drillWatchJob: Job? = null
    private var hpWatchJob: Job? = null
    private var referenceSpotWatchJob: Job? = null
    private var fireWatchJob: Job? = null
    private var textWatchJob: Job? = null
    private var engineScope: CoroutineScope? = null
    private var referenceSpotDetector: (() -> Boolean)? = null
    /** Set by the background fire watcher; consumed by the mining loop (instant read). */
    @Volatile private var fireSeen = false
    /** Set by the background OCR text watcher; consumed by the mining loop (instant read). */
    @Volatile private var batteryDeadSeen = false
    @Volatile private var noMineSeen = false
    @Volatile private var stopRequested = false
    /**
     * Allows no-drill / reference-spot recovery gestures after [stopRequested] is set,
     * so the mining loop can be cancelled before taunt without aborting the taunt itself.
     */
    @Volatile private var recoveryGesturesAllowed = false
    @Volatile private var spiderCombatActive = false
    /** Player HP hit confirmed — no-drill must stay paused until clear / hard timeout. */
    @Volatile private var spiderThreatActive = false
    @Volatile private var fireSequenceActive = false
    @Volatile private var batterySequenceActive = false
    @Volatile private var foodPauseActive = false
    @Volatile private var foodSequenceRunning = false
    @Volatile private var detectionCooldownUntilMs = 0L
    @Volatile private var initialStartDelayPending = false
    @Volatile private var skipPreAttackDelayOnce = false
    /** Rare sticky suppress — prefer [drillPauseUntilMs] / [spiderThreatActive]. */
    @Volatile private var drillChecksSuppressed = false
    /**
     * Timed no-drill pause (combat grace, location resume). Auto-lifts; never used for rock mines.
     */
    @Volatile private var drillPauseUntilMs = 0L
    private var drillMissStreak = 0
    private var onFoodResumingToast: (() -> Unit)? = null
    private var onSpiderCombatToast: (() -> Unit)? = null
    private var onSpiderEatToast: (() -> Unit)? = null
    private var onFireExtinguishToast: (() -> Unit)? = null
    private var onBatteryDeadToast: (() -> Unit)? = null
    private var batteryShopImageLocator: (suspend (BatteryShopImage) -> Point?)? = null
    private var gameTextTapLocator: (suspend (String) -> Point?)? = null
    private var batteryLandmarkDetector: (() -> Boolean)? = null
    private var thriftShopDetector: (() -> Boolean)? = null
    private var onBatteryLandmarkFailed: (() -> Unit)? = null
    private var onMineCollapseFailed: (() -> Unit)? = null
    private var onPlayDrillAlarm: (() -> Unit)? = null
    private var miningTextScanner: (suspend () -> TextDetection.MiningTextScanResult)? = null
    private var activeSettings: MiningSettings = MiningSettings()
    /** Up/Down Graal-return flick duration (Fix Setup + Loc 4–6). */
    @Volatile private var graalReturnFlickMs: Long = GRAAL_RETURN_DPAD_FLICK_DEFAULT_MS
    /**
     * After return-to-mine for Loc 4–6: count attacks until a rock is mined.
     * Armed on resume after collapse / battery / home; cleared on first rock.
     */
    @Volatile private var postReturnNoRockWatchActive = false
    @Volatile private var postReturnAttacksWithoutRock = 0

    private companion object {
        private const val ANTIJAIL_DRAG_DURATION_MS = 350L
        private const val ANTIJAIL_BETWEEN_DRAG_MS = 300L
        private const val GRB_SWIPE_LEFT_PX = 100f
        /** Tool/GRB swipe duration — fast drag (not a hold). */
        private const val TOOL_SWIPE_DURATION_MS = 250L
        /** Universal delay after tool/GRB swipe before food, fire, or taunt tap. */
        private const val GESTURE_STEP_DELAY_MS = 700L
        private const val MINE_COLLAPSE_PRE_SWIPE_MS = 2_000L
        private const val BATTERY_DEAD_INITIAL_MS = 2_000L
        /** After Right→Left 5s: approach shop, then battery/Buy/Yes/OK. */
        private const val BATTERY_PRE_SHOP_STEP_GAP_MS = 150L
        private const val BATTERY_PRE_SHOP_DOWN_UP_LONG_MS = 18_000L
        private const val BATTERY_PRE_SHOP_LEFT_RIGHT_MS = 5_000L
        private const val BATTERY_PRE_SHOP_DOWN_UP_SHORT_MS = 6_000L
        private const val BATTERY_IMAGE_POLL_MS = 500L
        private const val BATTERY_IMAGE_TIMEOUT_MS = 15_000L
        private const val BATTERY_STEP4_LONG_DRAG_MS = 18_000L
        private const val BATTERY_STEP4_RETRY_DRAG_MS = 5_000L
        private const val BATTERY_STEP4_MAX_RETRY_DRAGS = 4
        private const val BATTERY_LANDMARK_DIAGONAL_DRAG_MS = 6_000L
        private const val EAT_FOOD_BETWEEN_MS = 2_000L
        /** T2 / direction taps after eating — shorter than between food cycles. */
        private const val EAT_RESUME_STEP_MS = 300L
        private const val FOOD_POST_RESUME_DETECTION_DELAY_MS = 2_000L
        private const val FOOD_PAUSE_SETTLE_MS = 200L
        private const val TAUNT_TAP_HOLD_MS = 200L
        private const val TAUNT_TAP_SETTLE_MS = 300L
        private const val SPIDER_T1_GAP_MS = 300L
        /** After spider detected + health shown, delay before the first T1 tap. */
        private const val SPIDER_PRE_T1_DELAY_MS = 100L
        /** After locating the spider, delay before the first attack burst. */
        private const val SPIDER_PRE_ATTACK_DELAY_MS = 100L
        private const val SPIDER_ATTACK_GAP_MS = 100L
        private const val SPIDER_ATTACK_COUNT = 10
        /** One-time pause after Start before the first mining attack (resume paths skip this). */
        private const val MINING_INITIAL_START_MS = 100L
        private const val SPIDER_POST_ATTACK_MS = 100L
        private const val SPIDER_BURST_SETTLE_MS = 350L
        private const val SPIDER_MAX_ATTACK_BURSTS = 48
        private const val SPIDER_POST_KILL_SETTLE_MS = 500L
        private const val SPIDER_RESUME_GAP_MS = 200L
        private const val MINE_COLLAPSE_POST_FIRE_MS = 2_000L
        private const val MINE_COLLAPSE_POST_CENTER_TAP_MS = 1_000L
        private const val MINE_COLLAPSE_UP_LEFT_MS = 8_000L
        private const val MINE_COLLAPSE_THRIFT_INITIAL_MS = 10_000L
        private const val MINE_COLLAPSE_THRIFT_RETRY_MS = 5_000L
        private const val MINE_COLLAPSE_THRIFT_MAX_RETRIES = 3
        private const val MINE_COLLAPSE_RIGHT_LEFT_SHORT_MS = 500L
        private const val MINE_COLLAPSE_STEP_DELAY_MS = GESTURE_STEP_DELAY_MS
        /** Gap between mining-location walk steps after return-to-mine. */
        private const val MINING_LOCATION_STEP_DELAY_MS = 100L
        /** Loc 4–6 / Fix Setup: default Up/Down flick if prefs not synced yet. */
        private const val GRAAL_RETURN_DPAD_FLICK_DEFAULT_MS = 150L
        private const val GRAAL_RETURN_PRE_FLICK_WAIT_MS = 1_000L
        /** Before the short Up flick: hold Down for 1s, then settle. */
        private const val GRAAL_RETURN_PRE_DOWN_DRAG_MS = 1_000L
        private const val GRAAL_RETURN_POST_DOWN_DRAG_WAIT_MS = 500L
        private const val GRAAL_RETURN_POST_UP_FLICK_WAIT_MS = 1_000L
        private const val GRAAL_RETURN_POST_BACK_WAIT_MS = 1_000L
        /** After tapping Graal: poll this long for a Reconnect button. */
        private const val GRAAL_RETURN_RECONNECT_WINDOW_MS = 7_000L
        private const val GRAAL_RETURN_RECONNECT_POLL_MS = 700L
        /** Extra settle after tapping Reconnect (if it appeared). */
        private const val GRAAL_RETURN_POST_RECONNECT_WAIT_MS = 5_000L
        /** After reconnect wait (7s or 5s): L↔R adjust before the final Down flick. */
        private const val GRAAL_RETURN_PRE_DOWN_FLICK_GAP_MS = 100L
        private const val GRAAL_RETURN_LEFT_TO_RIGHT_MS = 300L
        private const val GRAAL_RETURN_RIGHT_TO_LEFT_MS = 500L
        private const val GRAAL_RETURN_TEXT_POLL_MS = 250L
        private const val GRAAL_RETURN_TEXT_TIMEOUT_MS = 5_000L
        private const val GRAAL_RETURN_PHRASE = "graal"
        private const val GRAAL_RETURN_RECONNECT_PHRASE = "reconnect"
        private const val MOVEMENT_SETTLE_MS = 300L
        private const val ATTACK_HOLD_MS = 200L
        private const val MOVEMENT_HOLD_MS = 50L
        private const val DEFAULT_ATTACKS_PER_PHASE = 4
        /** Seconds subtracted from base click interval per mining level (7.50 → 7.43 → … → 0.075 @ 99, 0 @ 100). */
        private const val MINING_LEVEL_CLICK_DEDUCT_SEC = 0.075f
        /** Safety cap if rock boxes are misaligned and template never matches. */
        private const val MAX_ATTACKS_WITHOUT_ROCK = 50
        /** Loc 4–6: after return-to-mine, re-run Graal setup if no rock in this many attacks. */
        private const val POST_RETURN_NO_ROCK_ATTACK_LIMIT = 10
        /** Tight rock poll — mining loop only; keep near real-time after each click. */
        private const val ROCK_DETECTION_POLL_MS = 40L
        /** Short settle after attack before rock colour can appear (was 700ms — too late vs spiders). */
        private const val ROCK_POST_ATTACK_DELAY_MS = 200L
        /** How long to poll for mined rock after [ROCK_POST_ATTACK_DELAY_MS]. */
        private const val ROCK_DETECTION_WINDOW_MS = 2_000L
        private const val NO_MINE_POLL_MS = 500L
        /** Let accessibility finish cancelling before the next real gesture. */
        private const val GESTURE_CANCEL_SETTLE_MS = 400L
        private const val ANTIJAIL_POST_DRAG_MS = 200L
        /** Standalone drill watch cadence — poll the drill box this often, independent of attacks. */
        private const val DRILL_POLL_INTERVAL_MS = 300L
        /** Quick re-check interval while confirming a suspected no-drill. */
        private const val DRILL_PRE_TAP_POLL_MS = 50L
        /** No-drill must persist this long (quick re-confirm) before recovery fires. */
        private const val DRILL_LOSS_CONFIRM_MS = 50L
        /** Always-on player HP poll — pauses no-drill and starts spider kill (not rock-gated). */
        private const val HP_WATCH_POLL_MS = 500L
        /** Hard cap so false HP cannot pause no-drill forever. */
        private const val SPIDER_THREAT_MAX_MS = 8_000L
        /** Brief settle after combat before no-drill resumes. */
        private const val SPIDER_POST_COMBAT_GRACE_MS = 400L
        /** Consecutive HP hits required before starting combat (filters flaky frames). */
        private const val HP_CONFIRM_HITS = 2
        /** Consecutive HP misses required before ending combat / lifting threat pause. */
        private const val HP_CLEAR_MISSES = 3
        private const val HP_CONFIRM_GAP_MS = 40L
        /** Reference-spot background check cadence — independent of mining timings. */
        private const val REFERENCE_SPOT_POLL_MS = 3_000L
        /** Short re-confirm delay so a transient frame can't false-trigger a stop. */
        private const val REFERENCE_SPOT_CONFIRM_MS = 300L
        /** Background fire watch cadence — independent of mining timings. */
        private const val FIRE_WATCH_POLL_MS = 1_000L
        /** Background battery/no-mine OCR watch cadence — independent of mining timings. */
        private const val TEXT_WATCH_POLL_MS = 1_000L
    }

    fun isRunning() = loopJob?.isActive == true

    /** Sync Up/Down Graal flick duration from settings (seconds, e.g. 0.15). */
    fun setGraalReturnFlickSeconds(seconds: Float) {
        graalReturnFlickMs = (seconds.coerceIn(0.05f, 5f) * 1000f).toLong()
            .coerceAtLeast(50L)
    }

    private fun graalFlickMs(): Long = graalReturnFlickMs.coerceAtLeast(50L)

    fun start(
        settings: MiningSettings,
        layout: SetupLayout,
        layoutProvider: () -> SetupLayout = { layout },
        canRunProvider: () -> Boolean,
        onStopped: () -> Unit,
        drillDetector: () -> Boolean = { true },
        rockDetector: (side: String) -> Boolean = { true },
        healthHitDetector: () -> Boolean = { false },
        spiderClusterDetector: () -> SpiderClusterResult? = { null },
        spiderAlive: () -> Boolean = { false },
        spiderDirection: () -> String = { "UP" },
        miningTextScanner: suspend () -> TextDetection.MiningTextScanResult = {
            TextDetection.MiningTextScanResult(false, false)
        },
        useBatteryDetection: Boolean = false,
        fireDetector: () -> Boolean = { false },
        useRockDetection: Boolean = false,
        useNoMineDetection: Boolean = false,
        referenceSpotDetector: () -> Boolean = { true },
        onDrillLost: () -> Unit = {},
        onPlayDrillAlarm: () -> Unit = {},
        onFoodResumingToast: () -> Unit = {},
        onSpiderCombatToast: () -> Unit = {},
        onSpiderEatToast: () -> Unit = {},
        onFireExtinguishToast: () -> Unit = {},
        onBatteryDeadToast: () -> Unit = {},
        batteryShopImageLocator: suspend (BatteryShopImage) -> Point? = { null },
        gameTextTapLocator: suspend (String) -> Point? = { null },
        batteryLandmarkDetector: () -> Boolean = { false },
        onBatteryLandmarkFailed: () -> Unit = {},
        thriftShopDetector: () -> Boolean = { false },
        onMineCollapseFailed: () -> Unit = {}
    ) {
        if (isRunning()) return
        stopRequested = false
        recoveryGesturesAllowed = false
        activeSettings = settings
        detectionCooldownUntilMs = 0L
        initialStartDelayPending = true
        skipPreAttackDelayOnce = true
        drillChecksSuppressed = false
        drillPauseUntilMs = 0L
        spiderCombatActive = false
        spiderThreatActive = false
        fireSequenceActive = false
        batterySequenceActive = false
        fireSeen = false
        batteryDeadSeen = false
        noMineSeen = false
        drillMissStreak = 0
        clearPostReturnNoRockWatch()
        this.onFoodResumingToast = onFoodResumingToast
        this.onSpiderCombatToast = onSpiderCombatToast
        this.onSpiderEatToast = onSpiderEatToast
        this.onFireExtinguishToast = onFireExtinguishToast
        this.onBatteryDeadToast = onBatteryDeadToast
        this.batteryShopImageLocator = batteryShopImageLocator
        this.gameTextTapLocator = gameTextTapLocator
        this.batteryLandmarkDetector = batteryLandmarkDetector
        this.onBatteryLandmarkFailed = onBatteryLandmarkFailed
        this.thriftShopDetector = thriftShopDetector
        this.onMineCollapseFailed = onMineCollapseFailed
        this.onPlayDrillAlarm = onPlayDrillAlarm
        this.miningTextScanner = miningTextScanner
        this.referenceSpotDetector = referenceSpotDetector
        val scope = CoroutineScope(Dispatchers.Default)
        engineScope = scope
        loopJob = scope.launch {
            try {
                runMiningLoop(
                    settings,
                    layoutProvider,
                    canRunProvider,
                    drillDetector,
                    rockDetector,
                    useRockDetection,
                    useNoMineDetection,
                    miningTextScanner,
                    useBatteryDetection,
                    healthHitDetector,
                    spiderClusterDetector,
                    spiderAlive,
                    spiderDirection,
                    fireDetector,
                    onDrillLost,
                    onPlayDrillAlarm
                )
            } finally {
                loopJob = null
                onStopped()
            }
        }
        hpWatchJob = scope.launch {
            try {
                runHpWatchLoop(
                    settings,
                    layoutProvider,
                    canRunProvider,
                    healthHitDetector,
                    spiderClusterDetector
                )
            } finally {
                hpWatchJob = null
            }
        }
        drillWatchJob = scope.launch {
            try {
                runDrillWatchLoop(
                    settings,
                    layoutProvider,
                    canRunProvider,
                    drillDetector,
                    onDrillLost,
                    onPlayDrillAlarm
                )
            } finally {
                drillWatchJob = null
            }
        }
        referenceSpotWatchJob = scope.launch {
            try {
                runReferenceSpotWatchLoop(
                    layoutProvider,
                    canRunProvider,
                    onDrillLost,
                    onPlayDrillAlarm
                )
            } finally {
                referenceSpotWatchJob = null
            }
        }
        fireWatchJob = scope.launch {
            try {
                runFireWatchLoop(layoutProvider, canRunProvider, fireDetector)
            } finally {
                fireWatchJob = null
            }
        }
        if (useBatteryDetection || useNoMineDetection) {
            textWatchJob = scope.launch {
                try {
                    runTextWatchLoop(
                        layoutProvider,
                        canRunProvider,
                        miningTextScanner,
                        useBatteryDetection,
                        useNoMineDetection
                    )
                } finally {
                    textWatchJob = null
                }
            }
        }
    }

    fun stop() {
        stopRequested = true
        recoveryGesturesAllowed = false
        foodPauseActive = false
        foodSequenceRunning = false
        fireSequenceActive = false
        batterySequenceActive = false
        spiderCombatActive = false
        spiderThreatActive = false
        drillChecksSuppressed = false
        drillPauseUntilMs = 0L
        detectionCooldownUntilMs = 0L
        initialStartDelayPending = false
        skipPreAttackDelayOnce = false
        fireSeen = false
        batteryDeadSeen = false
        noMineSeen = false
        clearPostReturnNoRockWatch()
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        hpWatchJob?.cancel()
        hpWatchJob = null
        drillWatchJob?.cancel()
        drillWatchJob = null
        referenceSpotWatchJob?.cancel()
        referenceSpotWatchJob = null
        fireWatchJob?.cancel()
        fireWatchJob = null
        textWatchJob?.cancel()
        textWatchJob = null
        loopJob?.cancel()
        loopJob = null
        engineScope = null
    }

    /** Mining work, or no-drill/reference recovery after the loop has already been halted. */
    private fun allowsEngineWork(): Boolean = !stopRequested || recoveryGesturesAllowed

    private fun ensureRunning(): Boolean = allowsEngineWork()

    private suspend fun delayUnlessStopped(ms: Long): Boolean {
        if (!allowsEngineWork()) return false
        delay(ms)
        return allowsEngineWork()
    }

    /**
     * True while spider HP threat / combat, timed combat grace, sticky suppress,
     * or fire/battery escape is active — never paused solely because a rock was mined.
     */
    private fun isDrillWatchPaused(): Boolean =
        drillChecksSuppressed ||
            spiderThreatActive ||
            spiderCombatActive ||
            fireSeen ||
            batteryDeadSeen ||
            fireSequenceActive ||
            batterySequenceActive ||
            SystemClock.elapsedRealtime() < drillPauseUntilMs

    /** Timed no-drill pause only — auto-lifts at [untilMs] (no sticky forever flag). */
    private fun pauseDrillUntil(untilMs: Long) {
        drillPauseUntilMs = maxOf(drillPauseUntilMs, untilMs)
        drillChecksSuppressed = false
    }

    /** Always lift timed/sticky no-drill pause (does not clear [spiderThreatActive]). */
    private fun clearDrillPause() {
        drillChecksSuppressed = false
        drillPauseUntilMs = 0L
    }

    private suspend fun cancelGesturesAndSettle() {
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        delayUnlessStopped(GESTURE_CANCEL_SETTLE_MS)
    }

    private suspend fun awaitGesture(onDispatch: (onDone: () -> Unit) -> Unit) {
        if (!allowsEngineWork()) return
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

    private suspend fun awaitDrag(
        service: AutoClickAccessibilityService,
        start: Point,
        end: Point,
        durationMs: Long
    ) {
        awaitGesture { onDone -> service.drag(start, end, durationMs, onDone) }
    }

    private suspend fun awaitTap(
        service: AutoClickAccessibilityService,
        point: Point,
        holdMs: Long = ATTACK_HOLD_MS
    ) {
        awaitGesture { onDone -> service.tap(point, holdMs, onDone) }
    }

    /**
     * Overlay food button — pauses mining/detections, eats ×3, re-equips drill, resumes where it left off.
     */
    suspend fun runManualEatFood(
        layoutProvider: () -> SetupLayout,
        eatFoodCount: Int = activeSettings.eatFoodCount,
        onResumingToast: (() -> Unit)? = null
    ) {
        if (foodSequenceRunning) return
        stopRequested = false
        foodSequenceRunning = true
        detectionCooldownUntilMs = 0L
        val wasMining = isRunning()
        try {
            if (wasMining) {
                foodPauseActive = true
                drillMissStreak = 0
                cancelGesturesAndSettle()
                if (!delayUnlessStopped(FOOD_PAUSE_SETTLE_MS)) return
            }
            val service = AutoClickAccessibilityService.instance ?: return
            val layout = layoutProvider()
            runEatFoodSequence(service, layout, eatFoodCount)
            if (stopRequested) return
            runResumeMiningAfterFood(service, layout, onResumingToast)
        } finally {
            drillMissStreak = 0
            foodPauseActive = false
            foodSequenceRunning = false
            if (!stopRequested) {
                detectionCooldownUntilMs =
                    SystemClock.elapsedRealtime() + FOOD_POST_RESUME_DETECTION_DELAY_MS
            }
        }
    }

    /**
     * Overlay home button — pauses mining/detections, runs mine-collapse escape,
     * then walks to the configured mining location and resumes (same as fire / battery return).
     */
    suspend fun runManualMineCollapseEscape(
        layoutProvider: () -> SetupLayout
    ) {
        if (fireSequenceActive || foodSequenceRunning || batterySequenceActive) return
        stopRequested = false
        detectionCooldownUntilMs = 0L
        val wasMining = isRunning()
        try {
            if (wasMining) {
                foodPauseActive = true
                drillMissStreak = 0
                cancelGesturesAndSettle()
                if (!delayUnlessStopped(FOOD_PAUSE_SETTLE_MS)) return
            }
            val service = AutoClickAccessibilityService.instance ?: return
            runMineCollapseEscapeSequence(service, layoutProvider())
        } finally {
            if (!fireSequenceActive) {
                drillMissStreak = 0
                foodPauseActive = false
            }
        }
    }

    /** Eating, food gestures, and post-eat cooldown — no drill/rock/no-mine scans. */
    private fun detectionsSuppressed(): Boolean =
        foodPauseActive ||
            foodSequenceRunning ||
            spiderCombatActive ||
            fireSequenceActive ||
            batterySequenceActive ||
            SystemClock.elapsedRealtime() < detectionCooldownUntilMs

    private suspend fun waitIfFoodPaused() {
        while (
            (foodPauseActive || foodSequenceRunning || spiderCombatActive ||
                fireSequenceActive || batterySequenceActive) &&
            !stopRequested
        ) {
            delay(50)
        }
    }

    private data class PostAttackPollResult(
        val rockFound: Boolean,
        val noMineAtEnd: Boolean,
        val batteryDeadAtEnd: Boolean
    )

    /**
     * After each attack: poll rock for direction switch only. Does not pause no-drill —
     * spider/HP ownership lives in [runHpWatchLoop].
     */
    private suspend fun pollAfterAttack(
        totalMs: Long,
        rockDetector: (attackDir: String) -> Boolean,
        attackDir: String,
        useRockDetection: Boolean,
        canRunProvider: () -> Boolean
    ): PostAttackPollResult {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + totalMs
        val detectStart = startedAt + ROCK_POST_ATTACK_DELAY_MS
        val rockScanEnd = detectStart + ROCK_DETECTION_WINDOW_MS

        while (SystemClock.elapsedRealtime() < deadline && !stopRequested && canRunProvider()) {
            waitIfFoodPaused()
            val now = SystemClock.elapsedRealtime()
            if (now >= detectStart) {
                if (batteryDeadSeen) {
                    return PostAttackPollResult(
                        rockFound = false,
                        noMineAtEnd = noMineSeen,
                        batteryDeadAtEnd = true
                    )
                }
                if (useRockDetection && now < rockScanEnd) {
                    if (rockDetector(attackDir)) {
                        drillMissStreak = 0
                        return PostAttackPollResult(
                            rockFound = true,
                            noMineAtEnd = false,
                            batteryDeadAtEnd = false
                        )
                    }
                }
            }
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            delay(min(ROCK_DETECTION_POLL_MS, left))
        }
        return PostAttackPollResult(
            rockFound = false,
            noMineAtEnd = noMineSeen,
            batteryDeadAtEnd = batteryDeadSeen
        )
    }

    private fun totalIntervalMs(settings: MiningSettings): Long {
        val level = settings.mineLevel.coerceAtLeast(0)
        val clickSeconds = (settings.baseClickIntervalSeconds - level * MINING_LEVEL_CLICK_DEDUCT_SEC)
            .coerceAtLeast(0f)
        val totalSec = clickSeconds + settings.additionalDelaySeconds.coerceAtLeast(0f)
        return (totalSec * 1000f).toLong()
    }

    private fun rockMinedDelayMs(settings: MiningSettings): Long =
        (settings.rockMinedDelaySeconds.coerceAtLeast(0f) * 1000f).toLong()

    private fun resolvePhases(dir: MiningDirection): List<Pair<String, String>> = when (dir) {
        MiningDirection.DOWN_LEFT          -> listOf("LEFT" to "LEFT", "DOWN" to "DOWN")
        MiningDirection.DOWN_RIGHT         -> listOf("RIGHT" to "RIGHT", "DOWN" to "DOWN")
        MiningDirection.UP_LEFT            -> listOf("LEFT" to "LEFT", "UP" to "UP")
        MiningDirection.UP_RIGHT           -> listOf("RIGHT" to "RIGHT", "UP" to "UP")
        MiningDirection.UP_ONLY_MOVE_LR    -> listOf("LEFT" to "UP", "RIGHT" to "UP")
        MiningDirection.DOWN_ONLY_MOVE_LR  -> listOf("LEFT" to "DOWN", "RIGHT" to "DOWN")
        MiningDirection.LEFT_ONLY_MOVE_UD  -> listOf("UP" to "LEFT", "DOWN" to "LEFT")
        MiningDirection.RIGHT_ONLY_MOVE_UD -> listOf("UP" to "RIGHT", "DOWN" to "RIGHT")
    }

    private fun oppositeSide(attackDir: String): String = when (attackDir.uppercase()) {
        "DOWN" -> "UP"
        "UP" -> "DOWN"
        "LEFT" -> "RIGHT"
        "RIGHT" -> "LEFT"
        else -> "UP"
    }

    private fun dpadPoint(label: String, layout: SetupLayout): Point = when (label) {
        "UP"    -> layout.dpadUp
        "DOWN"  -> layout.dpadDown
        "LEFT"  -> layout.dpadLeft
        "RIGHT" -> layout.dpadRight
        else    -> layout.dpadDown
    }

    private fun dpadCenter(layout: SetupLayout): Point = Point(
        (layout.dpadLeft.x + layout.dpadRight.x) / 2f,
        (layout.dpadUp.y + layout.dpadDown.y) / 2f
    )

    private fun attackPoint(label: String, layout: SetupLayout): Point = when (label) {
        "UP"    -> layout.attackUp
        "DOWN"  -> layout.attackDown
        "LEFT"  -> layout.attackLeft
        "RIGHT" -> layout.attackRight
        else    -> layout.attackDown
    }

    private suspend fun antijailSwipe(
        service: AutoClickAccessibilityService,
        start: Point,
        end: Point,
        durationMs: Long = ANTIJAIL_DRAG_DURATION_MS
    ) {
        if (!ensureRunning()) return
        awaitDrag(service, start, end, durationMs)
        if (!delayUnlessStopped(ANTIJAIL_POST_DRAG_MS)) return
    }

    /**
     * Fast left flick from a tool or GRB button (same motion as no-drill grab swipe),
     * then [GESTURE_STEP_DELAY_MS] before the next tap.
     */
    private suspend fun toolSwipeLeft(
        service: AutoClickAccessibilityService,
        toolPoint: Point
    ) {
        if (!ensureRunning()) return
        val swipeEnd = Point(toolPoint.x - GRB_SWIPE_LEFT_PX, toolPoint.y)
        awaitDrag(service, toolPoint, swipeEnd, TOOL_SWIPE_DURATION_MS)
        if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return
    }

    /** D-pad drag for mine collapse — same gesture style as no-drill antijail movement. */
    private suspend fun dpadDirectionMove(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        fromDir: String,
        toDir: String,
        durationMs: Long
    ) {
        if (!ensureRunning()) return
        antijailSwipe(
            service,
            dpadPoint(fromDir, layout),
            dpadPoint(toDir, layout),
            durationMs
        )
    }

    private suspend fun runAntijailMovement(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        onPlayDrillAlarm: () -> Unit,
        canRun: () -> Boolean
    ) {
        val down = dpadPoint("DOWN", layout)
        val up = dpadPoint("UP", layout)
        val right = dpadPoint("RIGHT", layout)
        val drags = listOf(down to right, down to up, up to right)
        for ((index, pair) in drags.withIndex()) {
            if (!canRun()) return
            val (start, end) = pair
            if (index == 0) onPlayDrillAlarm()
            antijailSwipe(service, start, end)
            if (index < drags.lastIndex) delay(ANTIJAIL_BETWEEN_DRAG_MS)
        }
    }

    private suspend fun runNoDrillRecovery(
        service: AutoClickAccessibilityService,
        layoutProvider: () -> SetupLayout,
        onPlayDrillAlarm: () -> Unit,
        canRun: () -> Boolean
    ) {
        val layout = layoutProvider()
        if (!canRun()) return
        runAntijailMovement(service, layout, onPlayDrillAlarm, canRun)
        if (!canRun()) return

        toolSwipeLeft(service, layout.grabButton)
        if (!canRun()) return
        awaitTap(service, layoutProvider().tauntButton, TAUNT_TAP_HOLD_MS)
        delay(TAUNT_TAP_SETTLE_MS)
    }

    private suspend fun handleDrillLost(
        layoutProvider: () -> SetupLayout,
        onDrillLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        if (detectionsSuppressed() || isDrillWatchPaused()) return
        // Halt mining first so an in-flight attack can't land after the taunt pause.
        stopRequested = true
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        hpWatchJob?.cancel()
        hpWatchJob = null
        fireWatchJob?.cancel()
        fireWatchJob = null
        textWatchJob?.cancel()
        textWatchJob = null
        loopJob?.cancel()
        loopJob = null
        recoveryGesturesAllowed = true
        try {
            val service = AutoClickAccessibilityService.instance
            if (service != null) {
                runNoDrillRecovery(service, layoutProvider, onPlayDrillAlarm) { recoveryGesturesAllowed }
            }
        } finally {
            recoveryGesturesAllowed = false
        }
        onDrillLost()
        drillWatchJob?.cancel()
        drillWatchJob = null
        referenceSpotWatchJob?.cancel()
        referenceSpotWatchJob = null
    }

    /**
     * Standalone no-drill watch. Polls every [DRILL_POLL_INTERVAL_MS]. Skips while HP threat,
     * spider combat, eat, fire, or battery own the screen — never paused for rock mines.
     */
    private suspend fun runDrillWatchLoop(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        drillDetector: () -> Boolean,
        onDrillLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        if (!settings.drillDetection) return
        while (!stopRequested && canRunProvider()) {
            if (!delayUnlessStopped(DRILL_POLL_INTERVAL_MS)) return
            if (stopRequested || !canRunProvider()) return
            if (detectionsSuppressed() || isDrillWatchPaused()) continue
            if (drillDetector()) continue
            if (!confirmDrillMissing(drillDetector)) continue
            if (detectionsSuppressed() || isDrillWatchPaused()) continue
            handleDrillLost(layoutProvider, onDrillLost, onPlayDrillAlarm)
            return
        }
    }

    /**
     * Background reference-spot watch. Every [REFERENCE_SPOT_POLL_MS] it checks whether the captured
     * spot still matches (detector true = intact). Runs on its own coroutine so it never affects
     * mining timings or other detections, and is suppressed during spider/eat/fire/battery/home/cooldown
     * Skips while HP threat / combat / eat / fire / battery (same gates as no-drill).
     * On a re-confirmed mismatch it runs the no-drill recovery, then stops.
     */
    private suspend fun runReferenceSpotWatchLoop(
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        onReferenceLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        val detector = referenceSpotDetector ?: return
        while (!stopRequested && canRunProvider()) {
            if (!delayUnlessStopped(REFERENCE_SPOT_POLL_MS)) return
            if (stopRequested || !canRunProvider()) return
            if (detectionsSuppressed() || isDrillWatchPaused()) continue
            if (detector()) continue
            if (!delayUnlessStopped(REFERENCE_SPOT_CONFIRM_MS)) return
            if (detectionsSuppressed() || isDrillWatchPaused()) continue
            if (detector()) continue
            handleReferenceSpotLost(layoutProvider, onReferenceLost, onPlayDrillAlarm)
            return
        }
    }

    /**
     * Background fire watch. On hit: pause no-drill/reference immediately, then run the full
     * escape → return-to-mine sequence here (does not wait for the mining loop). Detection
     * itself stays off the attack timing path.
     */
    private suspend fun runFireWatchLoop(
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        fireDetector: () -> Boolean
    ) {
        while (!stopRequested && canRunProvider()) {
            if (!delayUnlessStopped(FIRE_WATCH_POLL_MS)) return
            if (stopRequested || !canRunProvider()) return
            if (detectionsSuppressed() || fireSequenceActive || batterySequenceActive) continue
            if (!fireDetector()) continue
            fireSeen = true
            val service = AutoClickAccessibilityService.instance ?: continue
            runMineCollapseEscapeSequence(service, layoutProvider())
            fireSeen = false
        }
    }

    /**
     * Background battery/no-mine OCR watch. Battery-dead starts escape+buy+return immediately
     * here so no-drill/reference cannot stop the macro first. No-mine only sets a flag for
     * the mining loop (direction switch).
     */
    private suspend fun runTextWatchLoop(
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        miningTextScanner: suspend () -> TextDetection.MiningTextScanResult,
        useBatteryDetection: Boolean,
        useNoMineDetection: Boolean
    ) {
        while (!stopRequested && canRunProvider()) {
            if (!delayUnlessStopped(TEXT_WATCH_POLL_MS)) return
            if (stopRequested || !canRunProvider()) return
            if (detectionsSuppressed() || fireSequenceActive || batterySequenceActive) continue
            if (batteryDeadSeen && noMineSeen) continue
            val scan = miningTextScanner()
            if (useNoMineDetection && scan.noMine) {
                noMineSeen = true
            }
            if (useBatteryDetection && scan.batteryDead && !batteryDeadSeen) {
                batteryDeadSeen = true
                val service = AutoClickAccessibilityService.instance
                if (service != null) {
                    runBatteryDeadSequence(service, layoutProvider())
                    batteryDeadSeen = false
                }
            }
        }
    }

    private suspend fun handleReferenceSpotLost(
        layoutProvider: () -> SetupLayout,
        onReferenceLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        if (detectionsSuppressed() || isDrillWatchPaused()) return
        // Halt mining first so an in-flight attack can't land after the taunt pause.
        stopRequested = true
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        hpWatchJob?.cancel()
        hpWatchJob = null
        fireWatchJob?.cancel()
        fireWatchJob = null
        textWatchJob?.cancel()
        textWatchJob = null
        loopJob?.cancel()
        loopJob = null
        recoveryGesturesAllowed = true
        try {
            val service = AutoClickAccessibilityService.instance
            if (service != null) {
                runNoDrillRecovery(service, layoutProvider, onPlayDrillAlarm) { recoveryGesturesAllowed }
            }
        } finally {
            recoveryGesturesAllowed = false
        }
        onReferenceLost()
        drillWatchJob?.cancel()
        drillWatchJob = null
        referenceSpotWatchJob?.cancel()
        referenceSpotWatchJob = null
    }

    /** Quick re-check that the drill is really gone (guards against a single bad frame). */
    private suspend fun confirmDrillMissing(drillDetector: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + DRILL_LOSS_CONFIRM_MS
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested) {
            if (detectionsSuppressed() || isDrillWatchPaused()) return false
            if (drillDetector()) return false
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            if (!delayUnlessStopped(min(DRILL_PRE_TAP_POLL_MS, left))) return false
        }
        return !stopRequested && !detectionsSuppressed() && !isDrillWatchPaused()
    }

    private suspend fun performAttack(
        service: AutoClickAccessibilityService,
        settings: MiningSettings,
        layout: SetupLayout,
        movementDir: String,
        attackDir: String
    ): Boolean {
        if (stopRequested) return false
        val skipPreDelay = skipPreAttackDelayOnce
        if (skipPreDelay) skipPreAttackDelayOnce = false
        try {
            if (settings.moveWhenStationary) {
                if (!skipPreDelay && !delayUnlessStopped(GestureDelays.PRE_ACTION_MS)) return false
                service.tap(dpadPoint(movementDir, layout), holdMs = MOVEMENT_HOLD_MS)
                if (!delayUnlessStopped(MOVEMENT_SETTLE_MS)) return false
            }
            if (!skipPreDelay && !delayUnlessStopped(GestureDelays.PRE_ACTION_MS)) return false
            service.tap(attackPoint(attackDir, layout), holdMs = ATTACK_HOLD_MS)
            return true
        } finally {
            if (drillChecksSuppressed &&
                !spiderThreatActive &&
                !spiderCombatActive &&
                SystemClock.elapsedRealtime() >= drillPauseUntilMs
            ) {
                drillChecksSuppressed = false
            }
        }
    }

    private suspend fun runResumeMiningAfterFood(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        onResumingToast: (() -> Unit)? = null
    ) {
        if (!ensureRunning()) return
        if (!delayUnlessStopped(EAT_FOOD_BETWEEN_MS)) return
        service.tap(layout.tool2Button, holdMs = ATTACK_HOLD_MS)
        if (!delayUnlessStopped(EAT_RESUME_STEP_MS)) return
        service.tap(layout.tool2Button, holdMs = ATTACK_HOLD_MS)
        if (!delayUnlessStopped(EAT_RESUME_STEP_MS)) return
        service.tap(dpadPoint("UP", layout), holdMs = MOVEMENT_HOLD_MS)
        (onResumingToast ?: onFoodResumingToast)?.invoke()
        delayUnlessStopped(EAT_RESUME_STEP_MS)
    }

    /** T1 swipe left → 2s → food tap (repeat [eatFoodCount] times, 2s before each next swipe). */
    private suspend fun runEatFoodSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        eatFoodCount: Int
    ) {
        val food = layout.resolvedFoodButton()
        val tool1 = layout.tool1Button
        val swipeEnd = Point(tool1.x - GRB_SWIPE_LEFT_PX, tool1.y)
        val cycles = eatFoodCount.coerceAtLeast(1)
        repeat(cycles) { i ->
            if (stopRequested) return
            if (i > 0 && !delayUnlessStopped(EAT_FOOD_BETWEEN_MS)) return
            if (!ensureRunning()) return
            awaitDrag(service, tool1, swipeEnd, TOOL_SWIPE_DURATION_MS)
            if (!delayUnlessStopped(EAT_FOOD_BETWEEN_MS)) return
            awaitTap(service, food, holdMs = 50L)
        }
    }

    private suspend fun runPostKillEatAndResume(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        eatFoodCount: Int
    ) {
        foodPauseActive = true
        foodSequenceRunning = true
        drillMissStreak = 0
        onSpiderEatToast?.invoke()
        try {
            delay(EAT_FOOD_BETWEEN_MS)
            runEatFoodSequence(service, layout, eatFoodCount)
            if (stopRequested) return
            runResumeMiningAfterFood(service, layout, onFoodResumingToast)
        } finally {
            drillMissStreak = 0
            foodSequenceRunning = false
            foodPauseActive = false
            detectionCooldownUntilMs =
                SystemClock.elapsedRealtime() + FOOD_POST_RESUME_DETECTION_DELAY_MS
        }
    }

    private suspend fun runSpiderKillSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        autoEatAfterKill: Boolean,
        eatFoodCount: Int,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?
    ) {
        suspend fun tapT1Twice() {
            if (!ensureRunning()) return
            service.tap(layout.tool1Button, holdMs = ATTACK_HOLD_MS)
            if (!delayUnlessStopped(SPIDER_T1_GAP_MS)) return
            service.tap(layout.tool1Button, holdMs = ATTACK_HOLD_MS)
        }
        suspend fun tapT2Twice() {
            if (!ensureRunning()) return
            service.tap(layout.tool2Button, holdMs = ATTACK_HOLD_MS)
            if (!delayUnlessStopped(SPIDER_T1_GAP_MS)) return
            service.tap(layout.tool2Button, holdMs = ATTACK_HOLD_MS)
        }
        suspend fun attackBurst(dir: String) {
            repeat(SPIDER_ATTACK_COUNT) { i ->
                if (stopRequested) return
                service.tap(attackPoint(dir, layout), holdMs = ATTACK_HOLD_MS)
                if (i < SPIDER_ATTACK_COUNT - 1) {
                    if (!delayUnlessStopped(SPIDER_ATTACK_GAP_MS)) return
                }
            }
        }

        onSpiderCombatToast?.invoke()
        if (!delayUnlessStopped(SPIDER_PRE_T1_DELAY_MS)) return
        tapT1Twice()

        var bursts = 0
        // Down & Left mining: spiders usually spawn on RIGHT/UP — prefer RIGHT, then UP.
        val downLeftPreferred = activeSettings.direction == MiningDirection.DOWN_LEFT
        while (!stopRequested && bursts < SPIDER_MAX_ATTACK_BURSTS) {
            // Sticky HP: one flaky false frame must not end combat and jump to eat.
            if (confirmHealthGone(healthHitDetector)) break

            if (downLeftPreferred) {
                // RIGHT then UP while HP is still up — colour gate removed (misses RIGHT spiders).
                if (bursts == 0 && !delayUnlessStopped(SPIDER_PRE_ATTACK_DELAY_MS)) break
                attackBurst("RIGHT")
                bursts++
                if (stopRequested) break
                if (!delayUnlessStopped(SPIDER_POST_ATTACK_MS + SPIDER_BURST_SETTLE_MS)) break
                if (confirmHealthGone(healthHitDetector)) break
                attackBurst("UP")
                bursts++
                if (stopRequested) break
                if (!delayUnlessStopped(SPIDER_POST_ATTACK_MS + SPIDER_BURST_SETTLE_MS)) break
                continue
            }

            // Aim at cluster when seen; otherwise cycle RIGHT/UP so a missed colour frame
            // doesn't leave combat idle until HP falsely clears.
            val cluster = spiderClusterDetector()
            val aimDir = when {
                cluster != null && cluster.detected -> cluster.direction
                bursts % 2 == 0 -> "RIGHT"
                else -> "UP"
            }

            if (bursts == 0 && !delayUnlessStopped(SPIDER_PRE_ATTACK_DELAY_MS)) break

            attackBurst(aimDir)
            if (stopRequested) break
            bursts++
            if (!delayUnlessStopped(SPIDER_POST_ATTACK_MS + SPIDER_BURST_SETTLE_MS)) break
        }

        if (!delayUnlessStopped(SPIDER_POST_KILL_SETTLE_MS)) return

        if (autoEatAfterKill && !stopRequested) {
            runPostKillEatAndResume(service, layout, eatFoodCount)
        } else if (!stopRequested) {
            tapT2Twice()
            if (!delayUnlessStopped(SPIDER_RESUME_GAP_MS)) return
            service.tap(dpadPoint("UP", layout), holdMs = MOVEMENT_HOLD_MS)
            delayUnlessStopped(SPIDER_RESUME_GAP_MS)
        }
    }

    private suspend fun runMineCollapseMovements(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ): Boolean {
        if (!delayUnlessStopped(MINE_COLLAPSE_POST_FIRE_MS)) return false
        if (!ensureRunning()) return false
        service.tap(dpadCenter(layout), holdMs = ATTACK_HOLD_MS)
        if (!delayUnlessStopped(MINE_COLLAPSE_POST_CENTER_TAP_MS)) return false
        dpadDirectionMove(service, layout, "UP", "RIGHT", 3_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "UP", "LEFT", MINE_COLLAPSE_UP_LEFT_MS)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "DOWN", "UP", 1_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "UP", "LEFT", MINE_COLLAPSE_THRIFT_INITIAL_MS)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        if (!runUpLeftUntilMarker(
                service,
                layout,
                { isThriftShopVisible() },
                MINE_COLLAPSE_THRIFT_RETRY_MS,
                MINE_COLLAPSE_THRIFT_MAX_RETRIES
            )
        ) {
            haltMiningMacro { onMineCollapseFailed?.invoke() }
            return false
        }
        dpadDirectionMove(service, layout, "DOWN", "LEFT", 2_500L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "RIGHT", "LEFT", MINE_COLLAPSE_RIGHT_LEFT_SHORT_MS)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "DOWN", "LEFT", 2_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "LEFT", "RIGHT", 1_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        dpadDirectionMove(service, layout, "UP", "DOWN", 2_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
        if (stopRequested) return false
        return finishMineReturnWithLocation(service, layout)
    }

    private fun isThriftShopVisible(): Boolean =
        thriftShopDetector?.invoke() == true

    /** UP→LEFT retry drags until [markerDetected] or [maxRetries] exhausted. */
    private suspend fun runUpLeftUntilMarker(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        markerDetected: () -> Boolean,
        retryDragMs: Long,
        maxRetries: Int
    ): Boolean {
        if (markerDetected()) return true
        repeat(maxRetries) {
            if (stopRequested) return false
            dpadDirectionMove(service, layout, "UP", "LEFT", retryDragMs)
            if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return false
            if (markerDetected()) return true
        }
        return false
    }

    private suspend fun pollShopImageTapPoint(kind: BatteryShopImage): Point? {
        val locator = batteryShopImageLocator ?: return null
        val deadline = SystemClock.elapsedRealtime() + BATTERY_IMAGE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested) {
            val point = locator(kind)
            if (point != null) return point
            if (!delayUnlessStopped(BATTERY_IMAGE_POLL_MS)) return null
        }
        return null
    }

    private suspend fun detectAndTapShopImage(
        service: AutoClickAccessibilityService,
        kind: BatteryShopImage
    ): Boolean {
        val point = pollShopImageTapPoint(kind) ?: return false
        if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return false
        service.tap(point, holdMs = ATTACK_HOLD_MS)
        if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return false
        return true
    }

    private fun isBatteryLandmarkVisible(): Boolean =
        batteryLandmarkDetector?.invoke() == true

    private fun haltMiningMacro(onHalt: () -> Unit) {
        stopRequested = true
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        loopJob?.cancel()
        loopJob = null
        onHalt()
    }

    /** Successful return-to-mine with no location walk — alarm + stop (no resume). */
    private fun stopMacroAfterMineReturn() {
        stopRequested = true
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        onPlayDrillAlarm?.invoke()
    }

    /**
     * After return-to-mine movements: walk to [MiningSettings.miningLocation] and resume mining.
     * Locations without a defined walk still stop. No-drill recovery is unaffected.
     */
    private suspend fun finishMineReturnWithLocation(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ): Boolean {
        if (stopRequested) return false
        return when (activeSettings.miningLocation.coerceIn(1, 6)) {
            1, 2, 3, 4, 5, 6 -> runMiningLocationSequence(
                service,
                layout,
                activeSettings.miningLocation.coerceIn(1, 6)
            )
            else -> {
                stopMacroAfterMineReturn()
                false
            }
        }
    }

    /** D-pad drag for location walks — same style as return-to-mine, 100ms between steps. */
    private suspend fun locationDpadMove(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        fromDir: String,
        toDir: String,
        durationMs: Long
    ): Boolean {
        if (!ensureRunning()) return false
        awaitDrag(
            service,
            dpadPoint(fromDir, layout),
            dpadPoint(toDir, layout),
            durationMs
        )
        return delayUnlessStopped(MINING_LOCATION_STEP_DELAY_MS)
    }

    private suspend fun resumeMiningAfterLocation(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ): Boolean {
        if (!ensureRunning()) return false
        service.tap(dpadPoint("UP", layout), holdMs = MOVEMENT_HOLD_MS)
        skipPreAttackDelayOnce = true
        pauseDrillUntil(SystemClock.elapsedRealtime() + SPIDER_RESUME_GAP_MS)
        armPostReturnNoRockWatch()
        return delayUnlessStopped(SPIDER_RESUME_GAP_MS)
    }

    /** Loc 4–6 only: start counting attacks until the first successful rock mine. */
    private fun armPostReturnNoRockWatch() {
        val loc = activeSettings.miningLocation.coerceIn(1, 6)
        if (loc in 4..6) {
            postReturnNoRockWatchActive = true
            postReturnAttacksWithoutRock = 0
        } else {
            clearPostReturnNoRockWatch()
        }
    }

    private fun clearPostReturnNoRockWatch() {
        postReturnNoRockWatchActive = false
        postReturnAttacksWithoutRock = 0
    }

    private suspend fun runMiningLocationSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        location: Int
    ): Boolean {
        if (!ensureRunning()) return false
        when (location) {
            1, 2 -> {
                // Shared approach steps for locations 1–2.
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 5_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 7_000L)) return false
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 2_500L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 3_000L)) return false
                if (location >= 2) {
                    if (!locationDpadMove(service, layout, "LEFT", "RIGHT", 2_000L)) return false
                    if (!locationDpadMove(service, layout, "UP", "LEFT", 2_000L)) return false
                }
            }
            3 -> {
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 1_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 5_000L)) return false
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 1_000L)) return false
                if (!locationDpadMove(service, layout, "LEFT", "RIGHT", 2_200L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 1_500L)) return false
            }
            4 -> {
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 1_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 5_000L)) return false
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 1_000L)) return false
                if (!locationDpadMove(service, layout, "LEFT", "RIGHT", 2_500L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 2_000L)) return false
                return runGraalReturnToGame(service, layout, resumeMining = true)
            }
            5 -> {
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 5_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "LEFT", 2_000L)) return false
                return runGraalReturnToGame(service, layout, resumeMining = true)
            }
            6 -> {
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 800L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 1_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "LEFT", 5_000L)) return false
                return runGraalReturnToGame(service, layout, resumeMining = true)
            }
        }
        return resumeMiningAfterLocation(service, layout)
    }

    /**
     * Shared Back → tap "Graal" → optional Reconnect → flick sequence.
     * Loc 4–6 / Fix Setup / no-rock re-run: wait 1s → Down 1s → wait 0.5s → Up flick → …
     */
    private suspend fun runGraalReturnToGame(
        service: AutoClickAccessibilityService,
        layout: SetupLayout,
        resumeMining: Boolean
    ): Boolean {
        if (!delayUnlessStopped(GRAAL_RETURN_PRE_FLICK_WAIT_MS)) return false
        if (!locationDpadMove(service, layout, "UP", "DOWN", GRAAL_RETURN_PRE_DOWN_DRAG_MS)) return false
        if (!delayUnlessStopped(GRAAL_RETURN_POST_DOWN_DRAG_WAIT_MS)) return false
        if (!locationDpadMove(service, layout, "DOWN", "UP", graalFlickMs())) return false
        if (!delayUnlessStopped(GRAAL_RETURN_POST_UP_FLICK_WAIT_MS)) return false
        if (!ensureRunning()) return false
        service.pressBack()
        if (!delayUnlessStopped(GRAAL_RETURN_POST_BACK_WAIT_MS)) return false

        val tap = waitForGameTextTap(GRAAL_RETURN_PHRASE, GRAAL_RETURN_TEXT_TIMEOUT_MS)
        if (tap != null) {
            if (!ensureRunning()) return false
            awaitTap(service, tap, holdMs = 50L)
        }
        if (!waitAfterGraalForReconnectOrTimeout(service)) return false
        // After 7s reconnect poll (or 5s post-reconnect): L→R / R→L, then Down flick.
        if (!delayUnlessStopped(GRAAL_RETURN_PRE_DOWN_FLICK_GAP_MS)) return false
        if (!locationDpadMove(service, layout, "LEFT", "RIGHT", GRAAL_RETURN_LEFT_TO_RIGHT_MS)) return false
        if (!delayUnlessStopped(GRAAL_RETURN_PRE_DOWN_FLICK_GAP_MS)) return false
        if (!locationDpadMove(service, layout, "RIGHT", "LEFT", GRAAL_RETURN_RIGHT_TO_LEFT_MS)) return false
        if (!delayUnlessStopped(GRAAL_RETURN_PRE_DOWN_FLICK_GAP_MS)) return false
        if (!locationDpadMove(service, layout, "UP", "DOWN", graalFlickMs())) return false
        return if (resumeMining) {
            resumeMiningAfterLocation(service, layout)
        } else {
            true
        }
    }

    /**
     * For up to 7s after Graal: every 0.7s look for "reconnect".
     * If found → click → wait 5s. If never found → continue toward Down flick.
     */
    private suspend fun waitAfterGraalForReconnectOrTimeout(
        service: AutoClickAccessibilityService
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + GRAAL_RETURN_RECONNECT_WINDOW_MS
        while (SystemClock.elapsedRealtime() < deadline && allowsEngineWork()) {
            val reconnect = pollGameTextTapOnce(GRAAL_RETURN_RECONNECT_PHRASE)
            if (reconnect != null) {
                if (!ensureRunning()) return false
                awaitTap(service, reconnect, holdMs = 50L)
                return delayUnlessStopped(GRAAL_RETURN_POST_RECONNECT_WAIT_MS)
            }
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            if (!delayUnlessStopped(min(GRAAL_RETURN_RECONNECT_POLL_MS, left))) return false
        }
        return allowsEngineWork()
    }

    private suspend fun pollGameTextTapOnce(phrase: String): Point? {
        val locator = gameTextTapLocator ?: return null
        return runCatching { locator(phrase) }.getOrNull()
    }

    /**
     * Settings → Fix Setup: same Graal return flicks without resuming mining.
     * Safe while the mining loop is stopped (uses recovery gesture allowance).
     */
    suspend fun runFixSetupSequence(
        layoutProvider: () -> SetupLayout,
        gameTextTapLocator: suspend (String) -> Point?
    ): Boolean {
        val service = AutoClickAccessibilityService.instance ?: return false
        val previousLocator = this.gameTextTapLocator
        this.gameTextTapLocator = gameTextTapLocator
        recoveryGesturesAllowed = true
        try {
            return runGraalReturnToGame(service, layoutProvider(), resumeMining = false)
        } finally {
            this.gameTextTapLocator = previousLocator
            recoveryGesturesAllowed = false
        }
    }

    private suspend fun waitForGameTextTap(phrase: String, timeoutMs: Long): Point? {
        val locator = gameTextTapLocator ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline && allowsEngineWork()) {
            val point = runCatching { locator(phrase) }.getOrNull()
            if (point != null) return point
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            if (!delayUnlessStopped(min(GRAAL_RETURN_TEXT_POLL_MS, left))) return null
        }
        return null
    }

    /** Step 4: long RIGHT→LEFT drag, then colour-gated left/up holds with retry drags. */
    private suspend fun runBatteryDeadLandmarkPhase(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ): Boolean {
        dpadDirectionMove(service, layout, "RIGHT", "LEFT", BATTERY_STEP4_LONG_DRAG_MS)
        if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return false

        if (isBatteryLandmarkVisible()) {
            return runBatteryDeadAfterLandmark(service, layout)
        }

        repeat(BATTERY_STEP4_MAX_RETRY_DRAGS) {
            dpadDirectionMove(service, layout, "RIGHT", "LEFT", BATTERY_STEP4_RETRY_DRAG_MS)
            if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return false
            if (isBatteryLandmarkVisible()) {
                return runBatteryDeadAfterLandmark(service, layout)
            }
        }

        haltMiningMacro { onBatteryLandmarkFailed?.invoke() }
        return false
    }

    /** RIGHT→UP diagonal drag after the landmark colour is found. */
    private suspend fun runBatteryDeadAfterLandmark(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ): Boolean {
        dpadDirectionMove(service, layout, "RIGHT", "UP", BATTERY_LANDMARK_DIAGONAL_DRAG_MS)
        if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return false
        return true
    }

    private suspend fun runBatteryDeadMovements(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ) {
        if (!delayUnlessStopped(BATTERY_DEAD_INITIAL_MS)) return
        if (!ensureRunning()) return
        toolSwipeLeft(service, layout.tool2Button)
        if (!ensureRunning()) return
        awaitTap(service, layout.resolvedFireButton(), ATTACK_HOLD_MS)
        if (!delayUnlessStopped(MINE_COLLAPSE_POST_FIRE_MS)) return
        service.tap(dpadCenter(layout), holdMs = ATTACK_HOLD_MS)
        if (!delayUnlessStopped(MINE_COLLAPSE_POST_CENTER_TAP_MS)) return
        dpadDirectionMove(service, layout, "UP", "RIGHT", 3_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return
        dpadDirectionMove(service, layout, "UP", "LEFT", 3_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return
        dpadDirectionMove(service, layout, "RIGHT", "LEFT", 4_700L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return
        // Approach shop: D→U 18s → L→R 5s → D→U 6s, then battery/Buy/Yes/OK
        dpadDirectionMove(service, layout, "DOWN", "UP", BATTERY_PRE_SHOP_DOWN_UP_LONG_MS)
        if (!delayUnlessStopped(BATTERY_PRE_SHOP_STEP_GAP_MS)) return
        dpadDirectionMove(service, layout, "LEFT", "RIGHT", BATTERY_PRE_SHOP_LEFT_RIGHT_MS)
        if (!delayUnlessStopped(BATTERY_PRE_SHOP_STEP_GAP_MS)) return
        dpadDirectionMove(service, layout, "DOWN", "UP", BATTERY_PRE_SHOP_DOWN_UP_SHORT_MS)
        if (!delayUnlessStopped(BATTERY_PRE_SHOP_STEP_GAP_MS)) return
        if (!detectAndTapShopImage(service, BatteryShopImage.BATTERY)) return
        if (!detectAndTapShopImage(service, BatteryShopImage.BUY)) return
        if (!detectAndTapShopImage(service, BatteryShopImage.YES)) return
        if (!detectAndTapShopImage(service, BatteryShopImage.OK)) return
        toolSwipeLeft(service, layout.tool2Button)
        if (!ensureRunning()) return
        awaitTap(service, layout.resolvedFireButton(), ATTACK_HOLD_MS)
        if (!delayUnlessStopped(GESTURE_STEP_DELAY_MS)) return
        if (!runMineCollapseMovements(service, layout)) return
    }

    /** Pause mining, escape the collapse, walk back to mining location, resume. */
    private suspend fun runMineCollapseEscapeSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ) {
        if (fireSequenceActive || batterySequenceActive) return
        fireSequenceActive = true
        foodPauseActive = true
        fireSeen = true
        drillMissStreak = 0
        onFireExtinguishToast?.invoke()
        cancelGesturesAndSettle()
        try {
            if (!delayUnlessStopped(FOOD_PAUSE_SETTLE_MS)) return
            if (!delayUnlessStopped(MINE_COLLAPSE_PRE_SWIPE_MS)) return
            toolSwipeLeft(service, layout.tool2Button)
            if (!ensureRunning()) return
            awaitTap(service, layout.resolvedFireButton(), ATTACK_HOLD_MS)
            runMineCollapseMovements(service, layout)
        } finally {
            fireSequenceActive = false
            foodPauseActive = false
            drillMissStreak = 0
            // Game state changed completely — drop stale watcher flags.
            fireSeen = false
            batteryDeadSeen = false
            noMineSeen = false
        }
    }

    private suspend fun runBatteryDeadSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ) {
        if (batterySequenceActive || fireSequenceActive) return
        batterySequenceActive = true
        foodPauseActive = true
        batteryDeadSeen = true
        drillMissStreak = 0
        onBatteryDeadToast?.invoke()
        cancelGesturesAndSettle()
        try {
            if (!delayUnlessStopped(FOOD_PAUSE_SETTLE_MS)) return
            runBatteryDeadMovements(service, layout)
        } finally {
            batterySequenceActive = false
            foodPauseActive = false
            drillMissStreak = 0
            // Game state changed completely — drop stale watcher flags.
            fireSeen = false
            batteryDeadSeen = false
            noMineSeen = false
        }
    }

    /**
     * Backup: if a fire flag was set but the fire watcher hasn't escaped yet (or mining loop
     * woke first), run escape+return here. Primary path is [runFireWatchLoop].
     */
    private suspend fun maybeExtinguishFire(
        layoutProvider: () -> SetupLayout
    ): Boolean {
        if (!fireSeen || fireSequenceActive) return false
        val service = AutoClickAccessibilityService.instance ?: return false
        runMineCollapseEscapeSequence(service, layoutProvider())
        return !stopRequested
    }

    /**
     * Backup: battery escape is primarily started by [runTextWatchLoop]. Mining loop still
     * consumes a leftover flag so escape+buy+return can't be skipped.
     */
    private suspend fun maybeHandleBatteryDead(
        layoutProvider: () -> SetupLayout,
        useBatteryDetection: Boolean
    ): Boolean {
        if (!useBatteryDetection) return false
        if (!batteryDeadSeen || batterySequenceActive) return false
        val service = AutoClickAccessibilityService.instance ?: return false
        runBatteryDeadSequence(service, layoutProvider())
        return !stopRequested
    }

    /** True only when player HP hit colours AND spider cluster colour are both present. */
    private fun spiderConfirmed(
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?
    ): Boolean {
        if (!healthHitDetector()) return false
        val cluster = spiderClusterDetector() ?: return false
        return cluster.detected
    }

    /** Require [need] true reads so a single bad capture doesn't miss a real spider. */
    private suspend fun confirmHealthHit(
        healthHitDetector: () -> Boolean,
        need: Int = HP_CONFIRM_HITS
    ): Boolean {
        var hits = 0
        repeat(need) { i ->
            if (healthHitDetector()) hits++
            if (i < need - 1 && !delayUnlessStopped(HP_CONFIRM_GAP_MS)) return false
        }
        return hits >= need
    }

    /** Require [need] false reads so a single flicker doesn't end combat / lift no-drill pause. */
    private suspend fun confirmHealthGone(
        healthHitDetector: () -> Boolean,
        need: Int = HP_CLEAR_MISSES
    ): Boolean {
        var misses = 0
        repeat(need) { i ->
            if (!healthHitDetector()) misses++ else return false
            if (i < need - 1 && !delayUnlessStopped(HP_CONFIRM_GAP_MS)) return false
        }
        return misses >= need
    }

    /**
     * Fight when player HP is confirmed (from always-on HP watch).
     * When [alreadyConfirmed] is true, skip a second capture reconfirm so combat starts instantly.
     */
    private suspend fun maybeFightSpider(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?,
        alreadyConfirmed: Boolean = false
    ): Boolean {
        if (!settings.killSpiders || foodSequenceRunning || spiderCombatActive) return false
        if (!alreadyConfirmed && !spiderConfirmed(healthHitDetector, spiderClusterDetector)) {
            return false
        }

        val service = AutoClickAccessibilityService.instance ?: return false
        val layout = layoutProvider()
        spiderCombatActive = true
        spiderThreatActive = true
        foodPauseActive = true
        drillMissStreak = 0
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        if (stopRequested) {
            spiderCombatActive = false
            foodPauseActive = false
            return false
        }
        try {
            runSpiderKillSequence(
                service,
                layout,
                settings.autoEatAfterSpiderKill,
                settings.eatFoodCount,
                healthHitDetector,
                spiderClusterDetector
            )
        } finally {
            spiderCombatActive = false
            foodPauseActive = false
            drillMissStreak = 0
            pauseDrillUntil(SystemClock.elapsedRealtime() + SPIDER_POST_COMBAT_GRACE_MS)
            if (!foodSequenceRunning) {
                detectionCooldownUntilMs =
                    SystemClock.elapsedRealtime() + FOOD_POST_RESUME_DETECTION_DELAY_MS
            }
        }
        return !stopRequested
    }

    /**
     * Always-on HP watch (every [HP_WATCH_POLL_MS]):
     * - Confirmed player HP → pause no-drill immediately, then kill if enabled
     * - Not gated on rock mined — works at high mining levels without pause spam
     * - Hard-stops threat pause by [SPIDER_THREAT_MAX_MS]
     */
    private suspend fun runHpWatchLoop(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?
    ) {
        while (!stopRequested && canRunProvider()) {
            if (!delayUnlessStopped(HP_WATCH_POLL_MS)) return
            if (stopRequested || !canRunProvider()) return
            if (fireSequenceActive || batterySequenceActive || foodSequenceRunning) continue
            if (spiderCombatActive) continue

            val healthHit = runCatching { healthHitDetector() }.getOrDefault(false)
            if (!healthHit) {
                if (spiderThreatActive && confirmHealthGone(healthHitDetector)) {
                    spiderThreatActive = false
                    clearDrillPause()
                }
                continue
            }

            // HP seen — pause no-drill before drill watch can false-trigger.
            spiderThreatActive = true
            val threatStarted = SystemClock.elapsedRealtime()
            pauseDrillUntil(threatStarted + SPIDER_THREAT_MAX_MS)

            if (!confirmHealthHit(healthHitDetector, need = 1)) continue

            if (settings.killSpiders) {
                maybeFightSpider(
                    settings,
                    layoutProvider,
                    healthHitDetector,
                    spiderClusterDetector,
                    alreadyConfirmed = true
                )
            }

            while (
                !stopRequested &&
                canRunProvider() &&
                SystemClock.elapsedRealtime() < threatStarted + SPIDER_THREAT_MAX_MS
            ) {
                if (fireSequenceActive || batterySequenceActive || foodSequenceRunning) {
                    delay(HP_WATCH_POLL_MS)
                    continue
                }
                if (spiderCombatActive) {
                    delay(50)
                    continue
                }
                if (confirmHealthGone(healthHitDetector)) break
                if (settings.killSpiders &&
                    confirmHealthHit(healthHitDetector, need = 1)
                ) {
                    maybeFightSpider(
                        settings,
                        layoutProvider,
                        healthHitDetector,
                        spiderClusterDetector,
                        alreadyConfirmed = true
                    )
                    continue
                }
                delay(HP_WATCH_POLL_MS)
            }

            spiderThreatActive = false
            pauseDrillUntil(SystemClock.elapsedRealtime() + SPIDER_POST_COMBAT_GRACE_MS)
        }
    }

    /** Gear-only wait after a successful rock — no spider/no-drill ownership. */
    private suspend fun waitRockMinedDirectionSwitch(
        settings: MiningSettings,
        canRunProvider: () -> Boolean
    ) {
        val switchWaitMs = rockMinedDelayMs(settings)
        val switchDeadline = SystemClock.elapsedRealtime() + switchWaitMs
        while (SystemClock.elapsedRealtime() < switchDeadline && !stopRequested && canRunProvider()) {
            waitIfFoodPaused()
            if (stopRequested || !canRunProvider()) break
            val left = switchDeadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            delay(min(50L, left))
        }
    }

    private suspend fun runPhaseAttacks(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        drillDetector: () -> Boolean,
        rockDetector: (attackDir: String) -> Boolean,
        useRockDetection: Boolean,
        useNoMineDetection: Boolean,
        movementDir: String,
        attackDir: String,
        interval: Long,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?,
        spiderAlive: () -> Boolean,
        spiderDirection: () -> String,
        fireDetector: () -> Boolean,
        miningTextScanner: suspend () -> TextDetection.MiningTextScanResult,
        useBatteryDetection: Boolean,
        onDrillLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        var attacks = 0
        while (!stopRequested && canRunProvider()) {
            waitIfFoodPaused()
            // Instant flag reads — detection ran in the background watchers.
            if (maybeExtinguishFire(layoutProvider)) {
                continue
            }
            if (maybeHandleBatteryDead(layoutProvider, useBatteryDetection)) {
                continue
            }
            val service = AutoClickAccessibilityService.instance ?: return
            if (!performAttack(
                    service,
                    settings,
                    layoutProvider(),
                    movementDir,
                    attackDir
                )
            ) {
                return
            }
            attacks++

            val postAttack = pollAfterAttack(
                totalMs = interval,
                rockDetector = rockDetector,
                attackDir = attackDir,
                useRockDetection = useRockDetection,
                canRunProvider = canRunProvider
            )
            if (maybeExtinguishFire(layoutProvider)) {
                continue
            }
            if (postAttack.batteryDeadAtEnd &&
                maybeHandleBatteryDead(layoutProvider, useBatteryDetection)
            ) {
                continue
            }
            if (postAttack.rockFound) {
                // First successful rock after return-to-mine ends the Loc 4–6 watch.
                clearPostReturnNoRockWatch()
                // Direction switch only — spider kill is owned by the always-on HP watch.
                waitRockMinedDirectionSwitch(settings, canRunProvider)
                break
            }
            if (postReturnNoRockWatchActive) {
                postReturnAttacksWithoutRock++
                if (postReturnAttacksWithoutRock >= POST_RETURN_NO_ROCK_ATTACK_LIMIT) {
                    // No rock after 10 attacks — re-run Graal setup, then resume mining.
                    clearPostReturnNoRockWatch()
                    if (!runGraalReturnToGame(service, layoutProvider(), resumeMining = true)) {
                        return
                    }
                    // Watch re-armed in resumeMiningAfterLocation; keep mining.
                    continue
                }
            }
            if (postAttack.noMineAtEnd) {
                noMineSeen = false
                break
            }

            if (useRockDetection) {
                if (attacks >= MAX_ATTACKS_WITHOUT_ROCK) break
            } else if (attacks >= DEFAULT_ATTACKS_PER_PHASE) {
                break
            }
        }
    }

    private suspend fun runMiningLoop(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        drillDetector: () -> Boolean,
        rockDetector: (side: String) -> Boolean,
        useRockDetection: Boolean,
        useNoMineDetection: Boolean,
        miningTextScanner: suspend () -> TextDetection.MiningTextScanResult,
        useBatteryDetection: Boolean,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?,
        spiderAlive: () -> Boolean,
        spiderDirection: () -> String,
        fireDetector: () -> Boolean,
        onDrillLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        val interval = totalIntervalMs(settings)
        val phases = resolvePhases(settings.direction)

        if (initialStartDelayPending) {
            initialStartDelayPending = false
            if (!delayUnlessStopped(MINING_INITIAL_START_MS)) return
        }

        while (!stopRequested && canRunProvider()) {
            for (phase in phases) {
                waitIfFoodPaused()
                val (movementDir, attackDir) = phase

                runPhaseAttacks(
                    settings,
                    layoutProvider,
                    canRunProvider,
                    drillDetector,
                    rockDetector,
                    useRockDetection,
                    useNoMineDetection,
                    movementDir,
                    attackDir,
                    interval,
                    healthHitDetector,
                    spiderClusterDetector,
                    spiderAlive,
                    spiderDirection,
                    fireDetector,
                    miningTextScanner,
                    useBatteryDetection,
                    onDrillLost,
                    onPlayDrillAlarm
                )

                if (stopRequested || !canRunProvider()) return
            }
        }
    }
}
