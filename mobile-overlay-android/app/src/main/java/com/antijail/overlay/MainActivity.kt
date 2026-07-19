package com.antijail.overlay

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.provider.Settings
import android.util.Log
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antijail.overlay.accessibility.AutoClickAccessibilityService
import com.antijail.overlay.audio.AlarmSoundPlayer
import com.antijail.overlay.capture.ScreenCaptureService
import com.antijail.overlay.capture.TextDetection
import com.antijail.overlay.engine.AutomationEngine
import com.antijail.overlay.engine.FishEngine
import com.antijail.overlay.engine.MiningEngine
import com.antijail.overlay.model.ActionProfile
import com.antijail.overlay.model.DetectionRegion
import com.antijail.overlay.model.EditTarget
import com.antijail.overlay.model.FishSettings
import com.antijail.overlay.model.LayoutScaler
import com.antijail.overlay.model.MiningSettings
import com.antijail.overlay.model.SetupLayout
import com.antijail.overlay.model.SetupProgress
import com.antijail.overlay.model.BatteryShopImage
import com.antijail.overlay.model.SetupTarget
import com.antijail.overlay.model.UiPreferences
import com.antijail.overlay.overlay.OverlayController
import com.antijail.overlay.storage.ProfileStore
import com.antijail.overlay.storage.SnapshotStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object {
        private const val TAG = "AntiJail"
    }

    private lateinit var profileStore: ProfileStore
    private val automationEngine = AutomationEngine()
    private val miningEngine = MiningEngine()
    private val fishEngine = FishEngine()
    private var miningSettings: MiningSettings = MiningSettings()
    private var fishSettings: FishSettings = FishSettings()
    private var setupLayout: SetupLayout = SetupLayout()
    private var setupProgress: SetupProgress = SetupProgress()
    private var uiPreferences: UiPreferences = UiPreferences()
    private var overlayController: OverlayController? = null
    private var captureService: ScreenCaptureService? = null
    private var profileState: ActionProfile = ActionProfile()
    private var captureBound = false
    private var activeEditTarget: EditTarget = EditTarget.NONE
    private var editDraftProfile: ActionProfile? = null
    private var isRunningState: Boolean = false
    private var isBlockedByScreenState: Boolean = false
    /** Current-run reference box (may come from saved position prefs). */
    private var referenceSpotRegion: DetectionRegion? = null
    /**
     * Session-only snapshot of pixels inside the reference box.
     * Captured fresh on every Start; recycled on Stop. Never written to disk/prefs.
     */
    private var referenceSpotBaseline: android.graphics.Bitmap? = null
    @Volatile private var noMineToastShown = false
    private var overlayVisible: Boolean = false
    private var soundMuted: Boolean = false
    private var pendingProjectionResult: Pair<Int, Intent>? = null
    private var screenReceiverRegistered = false
    private var displayListenerRegistered = false
    private val displayChangeListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            handleDisplayOrientationChanged()
        }
    }
    private lateinit var overlayStatusView: TextView
    private lateinit var accessibilityStatusView: TextView
    private lateinit var screenCaptureStatusView: TextView
    private lateinit var alarmSoundPlayer: AlarmSoundPlayer

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            deliverProjectionIfReady(result.resultCode, result.data!!)
            refreshPermissionStates()
        } else {
            refreshPermissionStates()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notification permission helps screen capture stay active on Android 13+",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshPermissionStates()
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isBlockedByScreenState = true
                    panicStopAutomation()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Keep paused after unlock; manual Play is required.
                }
            }
        }
    }

    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ScreenCaptureService.LocalBinder
            captureService = binder?.getService()
            captureBound = true
            pendingProjectionResult?.let { (code, data) ->
                if (captureService?.beginProjection(code, Intent(data)) != true) {
                    pendingProjectionResult = null
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Screen capture failed — tap Enable Screen Capture again",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    pendingProjectionResult = null
                }
            }
            runOnUiThread { refreshPermissionStates() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            captureBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        try {
            profileStore = ProfileStore(this)

            if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                Toast.makeText(this, "Please rotate your device to landscape mode", Toast.LENGTH_LONG).show()
            }

            setContentView(R.layout.activity_main)
            alarmSoundPlayer = AlarmSoundPlayer(this)
            overlayStatusView = findViewById(R.id.overlayStatus)
            accessibilityStatusView = findViewById(R.id.accessibilityStatus)
            screenCaptureStatusView = findViewById(R.id.screenCaptureStatus)

            findViewById<android.view.View>(R.id.btnOverlaySettings).setOnClickListener {
                openOverlaySettings()
            }
            findViewById<android.view.View>(R.id.btnAccessibilitySettings).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            findViewById<android.view.View>(R.id.btnScreenCapture).setOnClickListener {
                requestNotificationPermissionIfNeeded()
                ensureCaptureServiceBound()
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
            findViewById<android.view.View>(R.id.btnPlay).setOnClickListener {
                val missing = collectMissingPlayPrerequisites()
                if (missing.isNotEmpty()) {
                    showMissingPrerequisitesDialog(missing)
                    return@setOnClickListener
                }
                ensureOverlayController(profileState)
            }

            lifecycleScope.launch {
                try {
                    val profile = profileStore.profileFlow.first().copy(
                        coordinateOrientation = "landscape",
                        markerOpacity = 1f
                    )
                    miningSettings = profileStore.miningSettingsFlow.first()
                    fishSettings = profileStore.fishSettingsFlow.first()
                    setupLayout = profileStore.setupLayoutFlow.first()
                    setupProgress = profileStore.setupProgressFlow.first()
                    uiPreferences = profileStore.uiPreferencesFlow.first()
                    profileState = profile
                    refreshPermissionStates()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load saved settings", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
            throw e
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught on ${thread.name}", error)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(this, "Crash: ${error.message}", Toast.LENGTH_LONG).show()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleDisplayOrientationChanged()
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE && isRunningState) {
            isBlockedByScreenState = true
            panicStopAutomation()
            overlayController?.showMacroToast("Paused — rotate to landscape for automation")
        } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            isBlockedByScreenState = false
        }
    }

    private fun handleDisplayOrientationChanged() {
        captureService?.refreshCaptureForCurrentDisplay()
        overlayController?.onDisplayChanged()
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        dm.registerDisplayListener(displayChangeListener, Handler(Looper.getMainLooper()))
        displayListenerRegistered = true
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayChangeListener)
        displayListenerRegistered = false
    }

    override fun onStart() {
        super.onStart()
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }
        registerDisplayListener()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        if (overlayVisible) {
            overlayController?.showToolbar()
        }
    }

    override fun onPause() {
        if (isRunningState) {
            isBlockedByScreenState = true
            panicStopAutomation()
        }
        super.onPause()
    }

    override fun onStop() {
        if (isRunningState) {
            panicStopAutomation()
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenReceiverRegistered = false
        }
        unregisterDisplayListener()
        super.onStop()
    }

    override fun onDestroy() {
        panicStopAutomation()
        if (::alarmSoundPlayer.isInitialized) alarmSoundPlayer.release()
        overlayController?.clearMarkers()
        overlayController?.hideToolbar()
        if (captureBound) unbindService(captureConnection)
        super.onDestroy()
    }

    private fun scaledSetupLayout(): SetupLayout {
        val dm = resources.displayMetrics
        return LayoutScaler.scaleForRuntime(
            setupLayout,
            dm.widthPixels,
            dm.heightPixels,
            uiPreferences.detectionScale
        )
    }

    private fun ensureCaptureServiceBound() {
        if (captureBound && captureService != null) return
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
        try {
            ContextCompat.startForegroundService(this, captureIntent)
            bindService(captureIntent, captureConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not start capture service: ${e.message ?: "unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deliverProjectionIfReady(resultCode: Int, data: Intent) {
        val projectionData = Intent(data)
        val service = captureService
        if (service != null) {
            if (!service.beginProjection(resultCode, projectionData)) {
                Toast.makeText(
                    this,
                    "Screen capture failed — tap Enable Screen Capture again",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingProjectionResult = null
        } else {
            pendingProjectionResult = resultCode to projectionData
            ensureCaptureServiceBound()
        }
    }

    private fun ensureOverlayController(profile: ActionProfile) {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayVisible) {
            overlayController?.setToolbarOpacity(profile.markerOpacity)
            editDraftProfile = profile
            return
        }
        if (overlayController == null) {
            overlayController = OverlayController(
                context = this,
                onPlayPause = { togglePlayPause() },
                onHome = { handleOverlayHomeButton() },
                onFood = { handleOverlayFoodButton() },
                onSettingsRequested = { panicStopAutomation() },
                onEdit = { target ->
                    activeEditTarget = target
                    startEditFlow(profileState)
                },
                onSaveEdit = { commitEditAndExit() },
                onCloseOverlay = {
                    overlayVisible = false
                    overlayController?.exitEditMode()
                    overlayController?.hideAllPopupsAndSetupOverlays()
                    overlayController?.hideToolbar()
                    panicStopAutomation()
                },
                isRunningProvider = { isRunningState },
                isSoundMuted = { soundMuted },
                onToggleSoundMuted = {
                    soundMuted = !soundMuted
                    if (soundMuted && ::alarmSoundPlayer.isInitialized) {
                        alarmSoundPlayer.release()
                    }
                },
                onPlayPressed = {
                    overlayController?.showModeSelectionOverlay(
                        onMine = { overlayController?.showMiningSettingsOverlay(miningSettings) },
                        onDig = { overlayController?.showDigPlaceholderOverlay() },
                        onFish = { overlayController?.showFishSettingsOverlay(fishSettings) },
                        onSetup = { overlayController?.showSetupMenuOverlay() }
                    )
                },
                onMineStart = { settings, referenceRegion ->
                    miningSettings = settings
                    // New run: drop any previous session image before capturing a fresh one.
                    discardReferenceSpotImage()
                    referenceSpotRegion = referenceRegion
                    // Position-only is persisted by OverlayController; never persist the image.
                    lifecycleScope.launch { profileStore.saveMiningSettings(settings) }
                    startMining(settings)
                },
                onFishStart = { settings ->
                    fishSettings = settings
                    lifecycleScope.launch { profileStore.saveFishSettings(settings) }
                    startFish(settings)
                },
                onSetupLayoutSaved = { layout ->
                    setupLayout = layout
                    lifecycleScope.launch { profileStore.saveSetupLayout(layout) }
                },
                setupLayoutProvider = { setupLayout },
                setupProgressProvider = { setupProgress },
                onSetupStepCompleted = { target ->
                    setupProgress = when (target) {
                        SetupTarget.POINTS_AND_REGION -> setupProgress.copy(pointsAndRegionDone = true)
                        SetupTarget.ZOOM_LEVEL -> setupProgress.copy(zoomLevelDone = true)
                        SetupTarget.GAME_SCREEN_AREA -> setupProgress.copy(gameScreenAreaDone = true)
                        SetupTarget.SNAPSHOT -> setupProgress
                    }
                    lifecycleScope.launch { profileStore.saveSetupProgress(setupProgress) }
                },
                uiPreferencesProvider = { uiPreferences },
                onUiPreferencesChanged = { prefs ->
                    uiPreferences = prefs
                    lifecycleScope.launch { profileStore.saveUiPreferences(prefs) }
                },
                onCaptureSnapshot = { region -> captureSnapshotRegion(region) }
            )
        }
        overlayController?.captureServiceRefreshProvider = {
            captureService?.refreshCaptureForCurrentDisplay()
        }
        overlayController?.showToolbar()
        overlayController?.setToolbarOpacity(profile.markerOpacity)
        overlayVisible = true
        editDraftProfile = profile
    }

    private fun togglePlayPause() {
        if (isRunningState) {
            panicStopAutomation()
            return
        }
        isBlockedByScreenState = false
        automationEngine.start(
            profileProvider = { profileState },
            captureServiceProvider = { captureService },
            canRunProvider = { !isBlockedByScreenState && isRunningState },
            onAutoPaused = {
                runOnUiThread {
                    panicStopAutomation()
                    overlayController?.refreshPlayState()
                }
            },
            onDetectionStatus = { hasGreen, willPause ->
                when {
                    willPause ->
                        overlayController?.showMacroToast("Green not detected — pausing macro")
                    hasGreen ->
                        overlayController?.showMacroToast("Green detected")
                }
            }
        )
        isRunningState = true
        overlayController?.refreshPlayState()
    }

    private fun startMining(settings: MiningSettings) {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            Toast.makeText(this, "Rotate to landscape before starting", Toast.LENGTH_SHORT).show()
            return
        }
        if (AutoClickAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                    "Enable accessibility service in Settings \u2192 Accessibility \u2192 AntiJail",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (settings.drillDetection) {
            val hasDrillRegion = setupLayout.drillRight > setupLayout.drillLeft &&
                setupLayout.drillBottom > setupLayout.drillTop
            if (!hasDrillRegion) {
                Toast.makeText(
                    this,
                    "Set the red DRILL box in Setup \u2192 Points and Region first",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        if (needsVisionCapture(settings)) {
            ensureCaptureServiceBound()
            captureService?.refreshCaptureForCurrentDisplay()
            if (captureService?.isCaptureReady() != true) {
                Toast.makeText(
                    this,
                    "Enable Screen Capture before mining (drill / rock / spider detection)",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        // Capture what is inside the box RIGHT NOW for this Start only.
        captureFreshReferenceSpotBaseline()
        isBlockedByScreenState = false
        isRunningState = true
        noMineToastShown = false
        overlayController?.refreshPlayState()
        miningEngine.start(
            settings = settings,
            layout = scaledSetupLayout(),
            layoutProvider = { scaledSetupLayout() },
            canRunProvider = { !isBlockedByScreenState && isRunningState },
            onStopped = {
                runOnUiThread {
                    isRunningState = false
                    clearReferenceSpot()
                    overlayController?.refreshPlayState()
                }
            },
            drillDetector = { isDrillVisible() },
            rockDetector = { attackDir -> isRockPresentForMining(attackDir) },
            healthHitDetector = { isPlayerHealthHitSignal() },
            spiderClusterDetector = { detectSpiderCluster() },
            spiderAlive = { isSpiderClusterAlive() },
            spiderDirection = { currentSpiderClusterDirection() },
            miningTextScanner = { scanMiningTextRegion() },
            fireDetector = { isFireDetected() },
            useRockDetection = hasRockRegions(),
            useNoMineDetection = hasNoMineRegion(),
            useBatteryDetection = settings.noBattery,
            referenceSpotDetector = { isReferenceSpotIntact() },
            onDrillLost = {
                runOnUiThread {
                    isRunningState = false
                    overlayController?.refreshPlayState()
                }
            },
            onPlayDrillAlarm = {
                runOnUiThread {
                    if (!soundMuted) alarmSoundPlayer.playDrillAlarm()
                }
            },
            onFoodResumingToast = {
                runOnUiThread { overlayController?.showMacroToast("Resuming mining…") }
            },
            onSpiderCombatToast = {
                runOnUiThread { overlayController?.showMacroToast("Killing spider…") }
            },
            onSpiderEatToast = {
                runOnUiThread { overlayController?.showMacroToast("Eating food…") }
            },
            onFireExtinguishToast = {
                runOnUiThread { overlayController?.showMacroToast("Mine collapsing… escaping") }
            },
            onBatteryDeadToast = {
                runOnUiThread { overlayController?.showMacroToast("Battery dead — escaping") }
            },
            batteryShopImageLocator = { kind -> findBatteryShopImageTapPoint(kind) },
            batteryLandmarkDetector = { isBatteryLandmarkVisible() },
            onBatteryLandmarkFailed = {
                runOnUiThread {
                    isRunningState = false
                    overlayController?.refreshPlayState()
                    overlayController?.showMacroToast("Battery escape failed — macro stopped")
                }
            },
            thriftShopDetector = { isThriftShopVisible() },
            onMineCollapseFailed = {
                runOnUiThread {
                    isRunningState = false
                    overlayController?.refreshPlayState()
                    overlayController?.showMacroToast("Mine escape failed — macro stopped")
                }
            }
        )
    }

    private fun startFish(settings: FishSettings) {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            Toast.makeText(this, "Rotate to landscape before starting", Toast.LENGTH_SHORT).show()
            return
        }
        if (AutoClickAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "Enable accessibility service in Settings \u2192 Accessibility \u2192 AntiJail",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (settings.killPirates) {
            val hasHpRegion = setupLayout.fishPirateHpRight > setupLayout.fishPirateHpLeft &&
                setupLayout.fishPirateHpBottom > setupLayout.fishPirateHpTop
            if (!hasHpRegion) {
                Toast.makeText(
                    this,
                    "Set the Pirate HP box in Setup \u2192 Points and Region \u2192 Fishing Detections",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            ensureCaptureServiceBound()
            captureService?.refreshCaptureForCurrentDisplay()
            if (captureService?.isCaptureReady() != true) {
                Toast.makeText(
                    this,
                    "Enable Screen Capture before fish mode (pirate HP detection)",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        isBlockedByScreenState = false
        isRunningState = true
        overlayController?.refreshPlayState()
        overlayController?.showMacroToast("Fish mode — pirate watch active")
        fishEngine.start(
            settings = settings,
            layoutProvider = { scaledSetupLayout() },
            pirateDetector = { isPirateCombatDetected() },
            onPirateCombatToast = {
                runOnUiThread { overlayController?.showMacroToast("Killing pirate…") }
            }
        )
    }

    private fun isPirateCombatDetected(): Boolean {
        val region = scaledSetupLayout().fishPirateHpRegion() ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        return runCatching { capture.regionHasPirateCombatSignal(region) }.getOrDefault(false)
    }

    private fun captureSnapshotRegion(region: DetectionRegion): String? {
        ensureCaptureServiceBound()
        val capture = captureService ?: return null
        if (!capture.isCaptureReady()) return null
        val bitmap = runCatching { capture.captureRegionBitmap(region) }.getOrNull() ?: return null
        return try {
            SnapshotStorage.save(this, bitmap).toastMessage()
        } finally {
            bitmap.recycle()
        }
    }

    private fun drillDetectionRegion(): DetectionRegion {
        val layout = scaledSetupLayout()
        return DetectionRegion(
            left = layout.drillLeft,
            top = layout.drillTop,
            right = layout.drillRight,
            bottom = layout.drillBottom
        )
    }

    /**
     * True when the cyan drill (#338072 / #317d73) is visible inside the saved drill detection box.
     * When capture is unavailable or a scan throws, assume drill is present (avoid false alarms).
     */
    private fun isDrillVisible(): Boolean {
        val layout = scaledSetupLayout()
        val hasRegion = layout.drillRight > layout.drillLeft && layout.drillBottom > layout.drillTop
        if (!hasRegion) return true
        val capture = captureService ?: return true
        if (!capture.isCaptureReady()) return true
        repeat(3) { attempt ->
            val visible = runCatching {
                capture.regionShowsDrillCyan(drillDetectionRegion())
            }.getOrDefault(true)
            if (visible) return true
            if (attempt < 2) Thread.sleep(100)
        }
        return false
    }

    private fun needsVisionCapture(settings: MiningSettings): Boolean =
        settings.drillDetection || settings.killSpiders || hasRockRegions() ||
            hasNoMineRegion() || hasFireRegion() || referenceSpotRegion != null

    /** Drop the session reference image only — saved box position in prefs is untouched. */
    private fun discardReferenceSpotImage() {
        referenceSpotBaseline?.let { runCatching { it.recycle() } }
        referenceSpotBaseline = null
    }

    /**
     * Snapshot the current screen inside [referenceSpotRegion] for this Start instance.
     * Always replaces any previous baseline; never loads an image from storage.
     */
    private fun captureFreshReferenceSpotBaseline() {
        discardReferenceSpotImage()
        val region = referenceSpotRegion ?: return
        val capture = captureService ?: return
        if (!capture.isCaptureReady()) return
        // No display refresh here — restarting the virtual display can hand back a black
        // first frame, which would poison the baseline and false-trigger the watcher.
        repeat(3) { attempt ->
            val bmp = runCatching { capture.captureRegionBitmap(region) }.getOrNull()
            if (bmp != null) {
                referenceSpotBaseline = bmp
                return
            }
            if (attempt < 2) Thread.sleep(80)
        }
    }

    /** True when the reference spot still matches this Start's baseline (>= 90% similar). */
    private fun isReferenceSpotIntact(): Boolean {
        val region = referenceSpotRegion ?: return true
        val baseline = referenceSpotBaseline ?: return true
        val capture = captureService ?: return true
        if (!capture.isCaptureReady()) return true
        val similarity = runCatching {
            capture.regionSimilarity(region, baseline)
        }.getOrDefault(1f)
        return similarity >= 0.90f
    }

    private fun hasFireRegion(): Boolean = setupLayout.hasGameArea()

    private fun hasRockRegions(): Boolean =
        listOf("UP", "DOWN", "LEFT", "RIGHT").all { setupLayout.rockRegion(it) != null }

    private fun hasNoMineRegion(): Boolean =
        setupLayout.noMineTextRegion() != null || setupLayout.hasGameArea()

    private fun rockDetectionRegion(side: String): com.antijail.overlay.model.DetectionRegion? =
        scaledSetupLayout().rockRegion(side)

    private fun oppositeRockSide(attackDir: String): String = when (attackDir.uppercase()) {
        "DOWN" -> "UP"
        "UP" -> "DOWN"
        "LEFT" -> "RIGHT"
        "RIGHT" -> "LEFT"
        else -> "UP"
    }

    /** Attack DOWN → scan UP box, attack LEFT → scan RIGHT box (saved orange box, no padding). */
    private fun rockRegionForAttack(attackDir: String): com.antijail.overlay.model.DetectionRegion? {
        val scanSide = oppositeRockSide(attackDir)
        return rockDetectionRegion(scanSide)
    }

    private fun scanRockInRegion(region: com.antijail.overlay.model.DetectionRegion?): ScreenCaptureService.DrillScanResult? {
        if (region == null) return null
        val capture = captureService ?: return null
        if (!capture.isCaptureReady()) return null
        var last: ScreenCaptureService.DrillScanResult? = null
        repeat(3) { attempt ->
            val result = runCatching { capture.scanRockColorsRegion(region) }.getOrNull()
            last = result
            if (result?.detected == true) return result
            if (attempt < 2) Thread.sleep(100)
        }
        return last
    }

    /** Single fast frame — mining poll loop already retries every ~40ms. */
    private fun isRockPresentForMining(attackDir: String): Boolean {
        val region = rockRegionForAttack(attackDir) ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        return runCatching { capture.scanRockColorsRegionFast(region).detected }.getOrDefault(false)
    }

    private fun isRockPresent(side: String): Boolean =
        scanRockInRegion(rockDetectionRegion(side))?.detected == true

    private fun noMineDetectionRegion(): com.antijail.overlay.model.DetectionRegion? {
        val layout = scaledSetupLayout()
        layout.noMineTextRegion()?.let { return it }
        if (!layout.hasGameArea()) return null
        return layout.defaultNoMineTextBounds(layout.gameCenterX(), layout.gameCenterY())
    }

    private suspend fun scanMiningTextRegion(): TextDetection.MiningTextScanResult {
        val region = noMineDetectionRegion() ?: return TextDetection.MiningTextScanResult(false, false)
        val capture = captureService ?: return TextDetection.MiningTextScanResult(false, false)
        if (!capture.isCaptureReady()) return TextDetection.MiningTextScanResult(false, false)
        return runCatching { capture.regionScanMiningText(region) }
            .getOrDefault(TextDetection.MiningTextScanResult(false, false))
            .also { scan ->
                if (scan.noMine) {
                    if (!noMineToastShown) {
                        noMineToastShown = true
                        runOnUiThread { overlayController?.showMacroToast("Nothing here to mine — switching") }
                    }
                } else {
                    noMineToastShown = false
                }
                if (scan.batteryDead) {
                    runOnUiThread { overlayController?.showMacroToast("Battery dead — escaping") }
                }
            }
    }

    private suspend fun findBatteryShopImageTapPoint(kind: BatteryShopImage): com.antijail.overlay.model.Point? {
        val region = scaledSetupLayout().gameAreaRegion() ?: return null
        val capture = captureService ?: return null
        if (!capture.isCaptureReady()) return null
        return runCatching {
            when (kind) {
                BatteryShopImage.BATTERY -> capture.findBatteryImageTapPoint(region)
                BatteryShopImage.YES -> capture.findYesImageTapPoint(region)
                BatteryShopImage.BUY -> capture.findBuyImageTapPoint(region)
                BatteryShopImage.OK -> capture.findOkImageTapPoint(region)
            }
        }.getOrNull()
    }

    private fun playerHealthHitRegion(): com.antijail.overlay.model.DetectionRegion? =
        scaledSetupLayout().spiderHealthRegion()

    private fun isPlayerHealthHitSignal(): Boolean {
        val region = playerHealthHitRegion() ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        // Fast path — post-rock spider watch needs near-instant HP reads.
        return runCatching { capture.regionHasHealthHitSignalFast(region) }.getOrDefault(false)
    }

    private fun fireDetectionRegion(): com.antijail.overlay.model.DetectionRegion? =
        scaledSetupLayout().gameAreaRegion()

    private fun isFireDetected(): Boolean {
        val region = fireDetectionRegion() ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        // Toast comes from the escape sequence ("Mine collapsing… escaping") so a detect-only
        // frame never looks like the macro stopped without escaping.
        return runCatching { capture.regionHasFireSignal(region) }.getOrDefault(false)
    }

    private fun isBatteryLandmarkVisible(): Boolean {
        val region = fireDetectionRegion() ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        return runCatching { capture.regionHasBatteryLandmarkCluster(region) }.getOrDefault(false)
    }

    private fun isThriftShopVisible(): Boolean {
        val region = fireDetectionRegion() ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        return runCatching { capture.regionHasThriftShop(region) }.getOrDefault(false)
    }

    private fun spiderAreaRegion(): com.antijail.overlay.model.DetectionRegion? =
        scaledSetupLayout().spiderAreaRegion()

    /**
     * Slight upward expand so UP spiders near the top edge still count, without pulling in
     * so much ground that direction votes aim at dirt.
     */
    private fun spiderAreaRegionForDetect(): com.antijail.overlay.model.DetectionRegion? {
        val base = spiderAreaRegion() ?: return null
        val h = (base.bottom - base.top).coerceAtLeast(1f)
        val expandUp = h * 0.15f
        return DetectionRegion(
            left = base.left,
            top = (base.top - expandUp).coerceAtLeast(0f),
            right = base.right,
            bottom = base.bottom
        )
    }

    private fun detectSpiderCluster(): com.antijail.overlay.model.SpiderClusterResult? {
        val region = spiderAreaRegionForDetect() ?: return null
        val capture = captureService ?: return null
        if (!capture.isCaptureReady()) return null
        // Fast frame grab + strict cluster thresholds — real spider only, not ground flecks.
        return runCatching { capture.scanSpiderClusterFast(region) }.getOrNull()
    }

    private fun isSpiderClusterAlive(): Boolean {
        val region = spiderAreaRegionForDetect() ?: return false
        val capture = captureService ?: return false
        if (!capture.isCaptureReady()) return false
        return runCatching { capture.isSpiderAliveColor(region) }.getOrDefault(false)
    }

    private fun currentSpiderClusterDirection(): String {
        val region = spiderAreaRegion() ?: return "UP"
        val capture = captureService ?: return "UP"
        if (!capture.isCaptureReady()) return "UP"
        return runCatching { capture.spiderClusterDirection(region) }.getOrDefault("UP")
    }

    private fun handleOverlayHomeButton() {
        if (AutoClickAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                    "Enable accessibility service in Settings \u2192 Accessibility \u2192 AntiJail",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            overlayController?.showMacroToast("Returning to mine… walking to location")
            miningEngine.runManualMineCollapseEscape(
                layoutProvider = { scaledSetupLayout() }
            )
            if (miningEngine.isRunning()) {
                runOnUiThread { overlayController?.showMacroToast("Resuming mining…") }
            }
        }
    }

    private fun handleOverlayFoodButton() {
        if (AutoClickAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                    "Enable accessibility service in Settings \u2192 Accessibility \u2192 AntiJail",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            overlayController?.showMacroToast("Eating food…")
            miningEngine.runManualEatFood(
                layoutProvider = { scaledSetupLayout() },
                eatFoodCount = miningSettings.eatFoodCount,
                onResumingToast = {
                    runOnUiThread { overlayController?.showMacroToast("Resuming mining…") }
                }
            )
        }
    }

    private fun panicStopAutomation() {
        miningEngine.stop()
        fishEngine.stop()
        AutoClickAccessibilityService.instance?.cancelPendingGestures()
        automationEngine.cancelCurrentAction()
        automationEngine.stop()
        isRunningState = false
        clearReferenceSpot()
        overlayController?.hideMacroToast()
        if (overlayVisible) {
            overlayController?.refreshPlayState()
        }
    }

    /**
     * Macro fully stopped: hide the outline and discard this run's image.
     * Does NOT clear [UiPreferences.lastReferenceSpot] — position stays for next Proceed.
     */
    private fun clearReferenceSpot() {
        overlayController?.hideReferenceSpot()
        referenceSpotRegion = null
        discardReferenceSpotImage()
    }

    private fun startEditFlow(baseProfile: ActionProfile) {
        if (!Settings.canDrawOverlays(this)) return
        ensureOverlayController(baseProfile)
        editDraftProfile = baseProfile.copy(coordinateOrientation = "landscape")
        overlayController?.enterEditMode(activeEditTarget)
        overlayController?.showEditMarkers(
            target = activeEditTarget,
            profile = editDraftProfile ?: baseProfile,
            onProfileChanged = { updated ->
                editDraftProfile = updated.copy(coordinateOrientation = "landscape")
            }
        )
    }

    private fun commitEditAndExit() {
        lifecycleScope.launch {
            val draft = (editDraftProfile ?: profileState).copy(coordinateOrientation = "landscape")
            profileState = draft
            panicStopAutomation()
            overlayController?.exitEditMode()
            saveProfileLandscape(draft)
            if (activeEditTarget == EditTarget.PAUSE_DETECTION_MOVEMENT) {
                Toast.makeText(
                    this@MainActivity,
                    "Detection area saved (grass uses built-in HSV range)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun saveProfileLandscape(profile: ActionProfile) {
        profileStore.saveProfile(profile.copy(coordinateOrientation = "landscape"))
    }

    private fun collectMissingPlayPrerequisites(): List<String> {
        val missing = mutableListOf<String>()
        if (!Settings.canDrawOverlays(this)) {
            missing.add("Overlay permission — tap Open Settings on the landing page")
        }
        if (!isAccessibilityEnabled(this)) {
            missing.add("Accessibility service — enable AntiJail (on Android 13+: Apps → AntiJail → ⋮ → Allow restricted settings)")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add("Notifications — required on Android 13+ so screen capture stays active")
        }
        ensureCaptureServiceBound()
        if (captureService?.isCaptureReady() != true) {
            missing.add("Screen capture — tap Enable Screen Capture")
        }
        return missing
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showMissingPrerequisitesDialog(missing: List<String>) {
        val message = buildString {
            append("Complete all requirements before Play:\n\n")
            missing.forEach { append("\u2022 $it\n") }
        }.trimEnd()
        AlertDialog.Builder(this)
            .setTitle("Cannot Start")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun refreshPermissionStates() {
        try {
            val overlayGranted = Settings.canDrawOverlays(this)
            val accessibilityGranted = isAccessibilityEnabled(this)
            updatePermissionStatus(overlayStatusView, overlayGranted)
            updatePermissionStatus(accessibilityStatusView, accessibilityGranted)
            ensureCaptureServiceBound()
            val captureReady = captureService?.isCaptureReady() == true
            updatePermissionStatus(screenCaptureStatusView, captureReady)
        } catch (e: Exception) {
            Log.e(TAG, "refreshPermissionStates failed", e)
            updatePermissionStatus(overlayStatusView, false)
            updatePermissionStatus(accessibilityStatusView, false)
            updatePermissionStatus(screenCaptureStatusView, false)
        }
    }

    private fun updatePermissionStatus(view: TextView, granted: Boolean) {
        view.text = if (granted) "Status: Ready" else "Status: Missing"
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (granted) android.R.color.holo_green_light else android.R.color.holo_red_light
            )
        )
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    info.resolveInfo?.serviceInfo?.packageName == context.packageName
                }
        } catch (e: Exception) {
            Log.e(TAG, "isAccessibilityEnabled failed", e)
            false
        }
    }
}
