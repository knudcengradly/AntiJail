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
    private var referenceSpotWatchJob: Job? = null
    private var referenceSpotDetector: (() -> Boolean)? = null
    @Volatile private var stopRequested = false
    @Volatile private var spiderCombatActive = false
    @Volatile private var fireSequenceActive = false
    @Volatile private var batterySequenceActive = false
    @Volatile private var foodPauseActive = false
    @Volatile private var foodSequenceRunning = false
    @Volatile private var detectionCooldownUntilMs = 0L
    @Volatile private var initialStartDelayPending = false
    @Volatile private var skipPreAttackDelayOnce = false
    /** Post-rock / spider resume — skip one normal attack cycle's drill checks. */
    @Volatile private var drillChecksSuppressed = false
    private var drillMissStreak = 0
    private var onFoodResumingToast: (() -> Unit)? = null
    private var onSpiderCombatToast: (() -> Unit)? = null
    private var onSpiderEatToast: (() -> Unit)? = null
    private var onFireExtinguishToast: (() -> Unit)? = null
    private var onBatteryDeadToast: (() -> Unit)? = null
    private var batteryShopImageLocator: (suspend (BatteryShopImage) -> Point?)? = null
    private var batteryLandmarkDetector: (() -> Boolean)? = null
    private var thriftShopDetector: (() -> Boolean)? = null
    private var onBatteryLandmarkFailed: (() -> Unit)? = null
    private var onMineCollapseFailed: (() -> Unit)? = null
    private var onPlayDrillAlarm: (() -> Unit)? = null
    private var miningTextScanner: (suspend () -> TextDetection.MiningTextScanResult)? = null
    private var activeSettings: MiningSettings = MiningSettings()

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
        private const val BATTERY_PRE_SHOP_DRAG_MS = 1_000L
        private const val BATTERY_PRE_SHOP_CLICK_DELAY_MS = 100L
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
        private const val MOVEMENT_SETTLE_MS = 300L
        private const val ATTACK_HOLD_MS = 200L
        private const val MOVEMENT_HOLD_MS = 50L
        private const val DEFAULT_ATTACKS_PER_PHASE = 4
        /** Seconds subtracted from base click interval per mining level (7.50 → 7.43 → … → 0.075 @ 99, 0 @ 100). */
        private const val MINING_LEVEL_CLICK_DEDUCT_SEC = 0.075f
        /** Safety cap if rock boxes are misaligned and template never matches. */
        private const val MAX_ATTACKS_WITHOUT_ROCK = 50
        private const val ROCK_DETECTION_POLL_MS = 100L
        /** Wait after attack tap before rock colour scan starts. */
        private const val ROCK_POST_ATTACK_DELAY_MS = GESTURE_STEP_DELAY_MS
        /** How long to poll for mined rock after [ROCK_POST_ATTACK_DELAY_MS]. */
        private const val ROCK_DETECTION_WINDOW_MS = 2_000L
        private const val NO_MINE_POLL_MS = 500L
        /** Let accessibility finish cancelling before the next real gesture. */
        private const val GESTURE_CANCEL_SETTLE_MS = 400L
        private const val ANTIJAIL_POST_DRAG_MS = 200L
        /** Standalone drill watch cadence — poll the drill box this often, independent of attacks. */
        private const val DRILL_POLL_INTERVAL_MS = 1_000L
        /** Quick re-check interval while confirming a suspected no-drill. */
        private const val DRILL_PRE_TAP_POLL_MS = 50L
        /** No-drill must persist this long (quick re-confirm) before recovery fires. */
        private const val DRILL_LOSS_CONFIRM_MS = 200L
        /**
         * Minimum post-rock spider poll window when Kill spiders is on — independent floor so
         * instant-spawn spiders are caught even if gear rock-mined wait is 0 or very low.
         * Actual watch = max(this, gear [rockMinedDelaySeconds]) when kill spiders is enabled.
         */
        private const val POST_ROCK_SPIDER_WATCH_MIN_MS = 1_500L
        private const val POST_ROCK_SPIDER_POLL_MS = 100L
        /** Reference-spot background check cadence — independent of mining timings. */
        private const val REFERENCE_SPOT_POLL_MS = 3_000L
        /** Short re-confirm delay so a transient frame can't false-trigger a stop. */
        private const val REFERENCE_SPOT_CONFIRM_MS = 300L
    }

    fun isRunning() = loopJob?.isActive == true

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
        batteryLandmarkDetector: () -> Boolean = { false },
        onBatteryLandmarkFailed: () -> Unit = {},
        thriftShopDetector: () -> Boolean = { false },
        onMineCollapseFailed: () -> Unit = {}
    ) {
        if (isRunning()) return
        stopRequested = false
        activeSettings = settings
        detectionCooldownUntilMs = 0L
        initialStartDelayPending = true
        skipPreAttackDelayOnce = true
        drillChecksSuppressed = false
        spiderCombatActive = false
        fireSequenceActive = false
        batterySequenceActive = false
        drillMissStreak = 0
        this.onFoodResumingToast = onFoodResumingToast
        this.onSpiderCombatToast = onSpiderCombatToast
        this.onSpiderEatToast = onSpiderEatToast
        this.onFireExtinguishToast = onFireExtinguishToast
        this.onBatteryDeadToast = onBatteryDeadToast
        this.batteryShopImageLocator = batteryShopImageLocator
        this.batteryLandmarkDetector = batteryLandmarkDetector
        this.onBatteryLandmarkFailed = onBatteryLandmarkFailed
        this.thriftShopDetector = thriftShopDetector
        this.onMineCollapseFailed = onMineCollapseFailed
        this.onPlayDrillAlarm = onPlayDrillAlarm
        this.miningTextScanner = miningTextScanner
        this.referenceSpotDetector = referenceSpotDetector
        val scope = CoroutineScope(Dispatchers.Default)
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
    }

    fun stop() {
        stopRequested = true
        foodPauseActive = false
        foodSequenceRunning = false
        fireSequenceActive = false
        batterySequenceActive = false
        spiderCombatActive = false
        drillChecksSuppressed = false
        detectionCooldownUntilMs = 0L
        initialStartDelayPending = false
        skipPreAttackDelayOnce = false
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        drillWatchJob?.cancel()
        drillWatchJob = null
        referenceSpotWatchJob?.cancel()
        referenceSpotWatchJob = null
        loopJob?.cancel()
        loopJob = null
    }

    private fun ensureRunning(): Boolean = !stopRequested

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
     * After each attack: wait 0.7s, poll rock up to 2s (then 2s settle if found → switch).
     * If no rock, wait out [totalMs] while polling no-mine.
     */
    private suspend fun pollAfterAttack(
        totalMs: Long,
        rockDetector: (attackDir: String) -> Boolean,
        attackDir: String,
        useRockDetection: Boolean,
        useNoMineDetection: Boolean,
        miningTextScanner: suspend () -> TextDetection.MiningTextScanResult,
        useBatteryDetection: Boolean,
        canRunProvider: () -> Boolean
    ): PostAttackPollResult {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + totalMs
        val detectStart = startedAt + ROCK_POST_ATTACK_DELAY_MS
        val rockScanEnd = detectStart + ROCK_DETECTION_WINDOW_MS
        var noMineSeen = false
        var batteryDeadSeen = false
        var lastTextPollMs = 0L

        while (SystemClock.elapsedRealtime() < deadline && !stopRequested && canRunProvider()) {
            waitIfFoodPaused()
            val now = SystemClock.elapsedRealtime()
            if (!detectionsSuppressed() && now >= detectStart) {
                if (useRockDetection && now < rockScanEnd) {
                    if (rockDetector(attackDir)) {
                        drillChecksSuppressed = true
                        drillMissStreak = 0
                        return PostAttackPollResult(
                            rockFound = true,
                            noMineAtEnd = false,
                            batteryDeadAtEnd = false
                        )
                    }
                }
                if ((useNoMineDetection || useBatteryDetection) &&
                    now - lastTextPollMs >= NO_MINE_POLL_MS
                ) {
                    lastTextPollMs = now
                    val scan = miningTextScanner()
                    if (useNoMineDetection && scan.noMine) {
                        noMineSeen = true
                    }
                    if (useBatteryDetection && scan.batteryDead) {
                        batteryDeadSeen = true
                        return PostAttackPollResult(
                            rockFound = false,
                            noMineAtEnd = noMineSeen,
                            batteryDeadAtEnd = true
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
        if (detectionsSuppressed() || drillChecksSuppressed) return
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        if (detectionsSuppressed() || drillChecksSuppressed) return
        val service = AutoClickAccessibilityService.instance
        if (service != null) {
            runNoDrillRecovery(service, layoutProvider, onPlayDrillAlarm) { true }
        }
        stopRequested = true
        onDrillLost()
        loopJob?.cancel()
    }

    /**
     * Standalone no-drill watch. Independent of the attack loop — polls the drill box every
     * [DRILL_POLL_INTERVAL_MS]. On a suspected no-drill it re-confirms over [DRILL_LOSS_CONFIRM_MS]
     * then runs recovery immediately (no waiting for the next attack tap). Always respects
     * suppression (post-rock spider window, spider combat, eating, fire, battery, cooldown) so a
     * spider covering the player after a successful rock can't false-trigger it during the watch window.
     * When the watch ends with no spider signals, suppression clears so no-drill runs in parallel with
     * the direction switch.
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
            if (detectionsSuppressed() || drillChecksSuppressed) continue
            if (drillDetector()) continue
            if (!confirmDrillMissing(drillDetector)) continue
            if (detectionsSuppressed() || drillChecksSuppressed) continue
            handleDrillLost(layoutProvider, onDrillLost, onPlayDrillAlarm)
            return
        }
    }

    /**
     * Background reference-spot watch. Every [REFERENCE_SPOT_POLL_MS] it checks whether the captured
     * spot still matches (detector true = intact). Runs on its own coroutine so it never affects
     * mining timings or other detections, and is suppressed during spider/eat/fire/battery/home/cooldown
     * exactly like no-drill. On a re-confirmed mismatch it runs the no-drill recovery, then stops.
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
            if (detectionsSuppressed() || drillChecksSuppressed) continue
            if (detector()) continue
            if (!delayUnlessStopped(REFERENCE_SPOT_CONFIRM_MS)) return
            if (detectionsSuppressed() || drillChecksSuppressed) continue
            if (detector()) continue
            handleReferenceSpotLost(layoutProvider, onReferenceLost, onPlayDrillAlarm)
            return
        }
    }

    private suspend fun handleReferenceSpotLost(
        layoutProvider: () -> SetupLayout,
        onReferenceLost: () -> Unit,
        onPlayDrillAlarm: () -> Unit
    ) {
        if (detectionsSuppressed() || drillChecksSuppressed) return
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        if (detectionsSuppressed() || drillChecksSuppressed) return
        val service = AutoClickAccessibilityService.instance
        if (service != null) {
            runNoDrillRecovery(service, layoutProvider, onPlayDrillAlarm) { true }
        }
        stopRequested = true
        onReferenceLost()
        loopJob?.cancel()
    }

    /** Quick re-check that the drill is really gone (guards against a single bad frame). */
    private suspend fun confirmDrillMissing(drillDetector: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + DRILL_LOSS_CONFIRM_MS
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested) {
            if (detectionsSuppressed() || drillChecksSuppressed) return false
            if (drillDetector()) return false
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            if (!delayUnlessStopped(min(DRILL_PRE_TAP_POLL_MS, left))) return false
        }
        return !stopRequested && !detectionsSuppressed() && !drillChecksSuppressed
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
        val clearDrillSuppressionAfterAttack = drillChecksSuppressed

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
            if (clearDrillSuppressionAfterAttack) {
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
        while (!stopRequested && bursts < SPIDER_MAX_ATTACK_BURSTS) {
            if (!healthHitDetector()) break

            val cluster = spiderClusterDetector()
            if (cluster == null || !cluster.detected) {
                if (!delayUnlessStopped(SPIDER_BURST_SETTLE_MS)) break
                continue
            }

            if (bursts == 0 && !delayUnlessStopped(SPIDER_PRE_ATTACK_DELAY_MS)) break

            attackBurst(cluster.direction)
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
            1, 2, 3 -> runMiningLocationSequence(service, layout, activeSettings.miningLocation.coerceIn(1, 6))
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
        drillChecksSuppressed = true
        return delayUnlessStopped(SPIDER_RESUME_GAP_MS)
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
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 3_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 4_000L)) return false
                if (location >= 2) {
                    if (!locationDpadMove(service, layout, "LEFT", "RIGHT", 3_000L)) return false
                    if (!locationDpadMove(service, layout, "UP", "LEFT", 2_000L)) return false
                }
            }
            3 -> {
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 1_000L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 5_000L)) return false
                if (!locationDpadMove(service, layout, "RIGHT", "LEFT", 1_000L)) return false
                if (!locationDpadMove(service, layout, "LEFT", "RIGHT", 2_500L)) return false
                if (!locationDpadMove(service, layout, "UP", "DOWN", 2_000L)) return false
            }
        }
        return resumeMiningAfterLocation(service, layout)
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
        dpadDirectionMove(service, layout, "RIGHT", "LEFT", 5_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return
        dpadDirectionMove(service, layout, "DOWN", "UP", 2_000L)
        if (!delayUnlessStopped(MINE_COLLAPSE_STEP_DELAY_MS)) return
        if (!runBatteryDeadLandmarkPhase(service, layout)) return

        dpadDirectionMove(service, layout, "UP", "RIGHT", BATTERY_PRE_SHOP_DRAG_MS)
        if (!delayUnlessStopped(BATTERY_PRE_SHOP_CLICK_DELAY_MS)) return
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

    /** Pause mining, run mine-collapse escape gestures, then stop the macro. */
    private suspend fun runMineCollapseEscapeSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ) {
        fireSequenceActive = true
        foodPauseActive = true
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
        }
    }

    private suspend fun runBatteryDeadSequence(
        service: AutoClickAccessibilityService,
        layout: SetupLayout
    ) {
        batterySequenceActive = true
        foodPauseActive = true
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
        }
    }

    private suspend fun maybeExtinguishFire(
        layoutProvider: () -> SetupLayout,
        fireDetector: () -> Boolean
    ): Boolean {
        if (!fireDetector()) return false
        val service = AutoClickAccessibilityService.instance ?: return false
        runMineCollapseEscapeSequence(service, layoutProvider())
        return !stopRequested
    }

    private suspend fun maybeHandleBatteryDead(
        layoutProvider: () -> SetupLayout,
        useBatteryDetection: Boolean,
        alreadyDetected: Boolean = false
    ): Boolean {
        if (!useBatteryDetection) return false
        if (!alreadyDetected) {
            val scan = miningTextScanner?.invoke() ?: return false
            if (!scan.batteryDead) return false
        }
        val service = AutoClickAccessibilityService.instance ?: return false
        runBatteryDeadSequence(service, layoutProvider())
        return !stopRequested
    }

    /**
     * After rock is mined: if player health hit colours show, pause drill/mining and fight spider.
     * Returns true when spider combat ran (mining stays on same direction).
     */
    private suspend fun maybeFightSpiderAfterRock(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?
    ): Boolean {
        if (!settings.killSpiders || foodSequenceRunning) return false
        if (!healthHitDetector()) return false
        val cluster = spiderClusterDetector() ?: return false
        if (!cluster.detected) return false

        val service = AutoClickAccessibilityService.instance ?: return false
        val layout = layoutProvider()
        spiderCombatActive = true
        foodPauseActive = true
        drillChecksSuppressed = true
        drillMissStreak = 0
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        if (stopRequested) {
            spiderCombatActive = false
            foodPauseActive = false
            return false
        }
        var combatRan = false
        try {
            combatRan = true
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
            drillChecksSuppressed = true
            drillMissStreak = 0
            if (!foodSequenceRunning) {
                detectionCooldownUntilMs =
                    SystemClock.elapsedRealtime() + FOOD_POST_RESUME_DETECTION_DELAY_MS
            }
        }
        return combatRan && !stopRequested
    }

    /**
     * Post-rock window: keep no-drill suppressed and actively poll for the spider that often
     * spawns the instant a rock is mined (it covers the player, hiding the drill). Polls health-hit
     * for the rock-mined switch window; on a real hit it fights via [maybeFightSpiderAfterRock].
     * Returns true if a spider fight ran. The caller always switches direction afterwards (the rock
     * in this direction is already mined). When Kill spiders is on, the watch runs at least
     * [POST_ROCK_SPIDER_WATCH_MIN_MS] (1.5s floor) or longer if gear rock-mined wait is higher —
     * the floor is for spider detection only; gear can extend the stand-still window but not
     * shorten it below 1.5s while spider killing is enabled.
     */
    private suspend fun watchForSpiderAfterRock(
        settings: MiningSettings,
        layoutProvider: () -> SetupLayout,
        canRunProvider: () -> Boolean,
        healthHitDetector: () -> Boolean,
        spiderClusterDetector: () -> SpiderClusterResult?
    ): Boolean {
        drillChecksSuppressed = true
        drillMissStreak = 0
        val windowMs = if (settings.killSpiders) {
            maxOf(rockMinedDelayMs(settings), POST_ROCK_SPIDER_WATCH_MIN_MS)
        } else {
            rockMinedDelayMs(settings)
        }
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested && canRunProvider()) {
            waitIfFoodPaused()
            if (stopRequested || !canRunProvider()) break
            if (settings.killSpiders && !foodSequenceRunning && healthHitDetector()) {
                if (maybeFightSpiderAfterRock(
                        settings,
                        layoutProvider,
                        healthHitDetector,
                        spiderClusterDetector
                    )
                ) {
                    return true
                }
            }
            drillChecksSuppressed = true
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            delay(min(POST_ROCK_SPIDER_POLL_MS, left))
        }
        drillChecksSuppressed = false
        return false
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
            if (maybeExtinguishFire(layoutProvider, fireDetector)) {
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
                useNoMineDetection = useNoMineDetection,
                miningTextScanner = miningTextScanner,
                useBatteryDetection = useBatteryDetection,
                canRunProvider = canRunProvider
            )
            if (maybeExtinguishFire(layoutProvider, fireDetector)) {
                continue
            }
            if (postAttack.batteryDeadAtEnd &&
                maybeHandleBatteryDead(
                    layoutProvider,
                    useBatteryDetection,
                    alreadyDetected = true
                )
            ) {
                continue
            }
            if (postAttack.rockFound) {
                // Fight any spider that spawned on the freshly mined rock, then always switch
                // direction. The rock in this direction is already mined, so resuming on the same
                // direction after a spider kill would hit "nothing here to mine".
                watchForSpiderAfterRock(
                    settings,
                    layoutProvider,
                    canRunProvider,
                    healthHitDetector,
                    spiderClusterDetector
                )
                break
            }
            if (postAttack.noMineAtEnd) {
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
