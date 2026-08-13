package com.antijail.overlay.overlay

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.antijail.overlay.R
import com.antijail.overlay.model.ActionProfile
import com.antijail.overlay.model.AppMode
import com.antijail.overlay.model.DetectionRegion
import com.antijail.overlay.model.DragPath
import com.antijail.overlay.model.EditTarget
import com.antijail.overlay.model.FishSettings
import com.antijail.overlay.model.LayoutScaler
import com.antijail.overlay.model.MiningDirection
import com.antijail.overlay.model.MiningSettings
import com.antijail.overlay.model.Point
import com.antijail.overlay.model.SetupLayout
import com.antijail.overlay.model.SetupProgress
import com.antijail.overlay.model.SetupTarget
import com.antijail.overlay.model.UiPreferences
import com.antijail.overlay.model.setupTargetFromSpinnerIndex
import com.antijail.overlay.model.spinnerIndex

/** Toolbar button accent while a macro is active (idle stays blue). */
enum class OverlayPlayAccent {
    IDLE,
    MINE,
    FISH,
    DIG
}

class OverlayController(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onHome: () -> Unit,
    private val onFood: () -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onEdit: (EditTarget) -> Unit,
    private val onSaveEdit: () -> Unit,
    private val onCloseOverlay: () -> Unit,
    private val isRunningProvider: () -> Boolean,
    private val playAccentProvider: () -> OverlayPlayAccent = { OverlayPlayAccent.IDLE },
    private val isSoundMuted: () -> Boolean,
    private val onToggleSoundMuted: () -> Unit,
    private val onFixSetupRequested: () -> Unit,
    private val onPlayPressed: () -> Unit,
    private val onMineStart: (MiningSettings, DetectionRegion?) -> Unit,
    private val onFishStart: (FishSettings) -> Unit,
    private val onSetupLayoutSaved: (SetupLayout) -> Unit,
    private val setupLayoutProvider: () -> SetupLayout,
    private val setupProgressProvider: () -> SetupProgress,
    private val onSetupStepCompleted: (SetupTarget) -> Unit,
    private val uiPreferencesProvider: () -> UiPreferences,
    private val onUiPreferencesChanged: (UiPreferences) -> Unit,
    /** Returns user-facing save message on success, null on failure. */
    private val onCaptureSnapshot: (DetectionRegion) -> String?
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private var toolbarView: View? = null
    private var settingsPopupView: View? = null
    private var settingsBackdropView: View? = null
    private val markerViews: MutableList<View> = mutableListOf()
    private val lineViews: MutableList<View> = mutableListOf()
    private var drillRegionView: View? = null
    private var drillRegionParams: WindowManager.LayoutParams? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var macroToastView: View? = null
    private var miningAlertPopupView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var macroToastHideRunnable: Runnable? = null
    private var isEditMode: Boolean = false
    private var selectedEditTarget: EditTarget = EditTarget.NONE
    private var toolbarOpacity: Float = 1f
    /** Yellow toolbar shade while Settings → Fix Setup sequence runs. */
    @Volatile private var fixSetupHighlightActive: Boolean = false
    private val fixedDrillWidth = SetupLayout.DRILL_BOX_WIDTH_PX.toFloat()
    private val fixedDrillHeight = SetupLayout.DRILL_BOX_HEIGHT_PX.toFloat()

    /** Reference Spot box side in raw screen px — about half the previous size and a bit smaller. */
    private val REFERENCE_SPOT_SIZE_PX = 100
    private fun markerLabelHeightPx(): Int = dpToPx(42)
    private fun circleMarkerSizePx(): Int = dpToPx(76)
    private fun squareMarkerWidthPx(): Int = dpToPx(96)
    private fun squareMarkerHeightPx(): Int = dpToPx(68)
    private fun markerContainerWidthPx(): Int = dpToPx(160)

    /** Toolbar circles — compact so they don't cover much of the game. */
    private fun toolbarButtonSizePx(): Int = dpToPx(34)
    private fun toolbarButtonMarginPx(): Int = dpToPx(3)
    private fun toolbarIconPaddingPx(): Int = dpToPx(6)

    private data class ToolbarButtonColors(
        val fill: Int,
        val stroke: Int,
        val pressedFill: Int,
        val pressedStroke: Int
    )

    /**
     * Idle = blue. Mine running = green. Fish / Fix Setup = yellow. Dig = red.
     * Matches outline+fill shade (not toolbar background).
     */
    private fun toolbarButtonColors(): ToolbarButtonColors {
        if (fixSetupHighlightActive) {
            return ToolbarButtonColors(
                fill = Color.parseColor("#E03A3210"),
                stroke = Color.parseColor("#F5D76E"),
                pressedFill = Color.parseColor("#FF2A2208"),
                pressedStroke = Color.parseColor("#FFE9A0")
            )
        }
        if (!isRunningProvider()) {
            return ToolbarButtonColors(
                fill = Color.parseColor("#E00E2C57"),
                stroke = Color.parseColor("#74BCFF"),
                pressedFill = Color.parseColor("#FF0A2345"),
                pressedStroke = Color.parseColor("#9FD2FF")
            )
        }
        return when (playAccentProvider()) {
            OverlayPlayAccent.FISH -> ToolbarButtonColors(
                fill = Color.parseColor("#E03A3210"),
                stroke = Color.parseColor("#F5D76E"),
                pressedFill = Color.parseColor("#FF2A2208"),
                pressedStroke = Color.parseColor("#FFE9A0")
            )
            OverlayPlayAccent.DIG -> ToolbarButtonColors(
                fill = Color.parseColor("#E03A1418"),
                stroke = Color.parseColor("#FF6B6B"),
                pressedFill = Color.parseColor("#FF2A0A0E"),
                pressedStroke = Color.parseColor("#FF9A9A")
            )
            OverlayPlayAccent.MINE -> ToolbarButtonColors(
                fill = Color.parseColor("#E0144030"),
                stroke = Color.parseColor("#66E088"),
                pressedFill = Color.parseColor("#FF0A2E1F"),
                pressedStroke = Color.parseColor("#9AF0AA")
            )
            OverlayPlayAccent.IDLE -> ToolbarButtonColors(
                fill = Color.parseColor("#E00E2C57"),
                stroke = Color.parseColor("#74BCFF"),
                pressedFill = Color.parseColor("#FF0A2345"),
                pressedStroke = Color.parseColor("#9FD2FF")
            )
        }
    }

    private fun circleButtonBackground(colors: ToolbarButtonColors = toolbarButtonColors()): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colors.fill)
            setStroke(dpToPx(2), colors.stroke)
        }

    /** Fixed width for all centered popup overlays; height uses [popupWindowHeightPx]. */
    private val popupWidthDp = 360

    // Mode selection / setup menu / mining settings / dig popups
    private var modeSelectionBackdropView: View? = null
    private var modeSelectionPopupView: View? = null
    private var setupMenuBackdropView: View? = null
    private var setupMenuPopupView: View? = null
    private var miningSettingsBackdropView: View? = null
    private var miningSettingsPopupView: View? = null
    private var miningIntervalSettingsPopupView: View? = null
    private var digBackdropView: View? = null
    private var digPopupView: View? = null
    private var fishSettingsBackdropView: View? = null
    private var fishSettingsPopupView: View? = null
    private var toolbarConfirmBackdropView: View? = null
    private var toolbarConfirmPopupView: View? = null

    // Points & Region setup floating overlays
    private var dpadOverlayView: View? = null
    private var attackOverlayView: View? = null
    private var toolsOverlayView: View? = null
    private var saveOverlayView: View? = null
    private var setupActionBarView: View? = null
    private var setupActionBarParams: WindowManager.LayoutParams? = null
    private var setupFilterView: View? = null
    private var setupFilterParams: WindowManager.LayoutParams? = null
    private var drillSetupBoxView: View? = null
    private var tauntSetupBoxView: View? = null
    private var fireSetupBoxView: View? = null
    private var buySetupBoxView: View? = null
    private var okSetupBoxView: View? = null
    private val rockSetupBoxViews = mutableListOf<View>()
    private var dpadParams: WindowManager.LayoutParams? = null
    private var attackParams: WindowManager.LayoutParams? = null
    private var toolsParams: WindowManager.LayoutParams? = null
    private var saveParams: WindowManager.LayoutParams? = null

    // Zoom + Game Screen Area overlays
    private var zoomSignView: ImageView? = null
    private var zoomSignParams: WindowManager.LayoutParams? = null
    private var zoomThriftView: ImageView? = null
    private var zoomThriftParams: WindowManager.LayoutParams? = null
    private var zoomControlPanelView: View? = null
    private var zoomControlPanelParams: WindowManager.LayoutParams? = null
    private val zoomPreviewBoxes = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private var zoomFilterView: View? = null
    private var zoomFilterParams: WindowManager.LayoutParams? = null
    private var gameAreaOverlayView: View? = null
    private var gameAreaHintView: TextView? = null
    private var gameAreaSaveView: View? = null
    private val gameAreaHandleViews = mutableListOf<View>()
    private var referenceSpotBoxView: View? = null
    private var referenceSpotBoxParams: WindowManager.LayoutParams? = null
    private var referenceStartButtonView: View? = null
    private var spiderAreaBoxView: View? = null
    private var spiderHealthBoxView: View? = null
    private var noMineTextBoxView: View? = null
    private var fishStopSetupView: View? = null
    private var fishPirateHpBoxView: View? = null
    private val floatingHintOverlays = mutableListOf<FloatingHint>()
    private var activeSetupTarget: SetupTarget? = null
    private var activeSetupSaveAction: (() -> Unit)? = null
    private var pointsSetupOnSaved: ((SetupLayout) -> Unit)? = null

    // Snapshot capture overlay
    private var snapshotOverlayView: View? = null
    private var snapshotActionBarView: View? = null

    // Bottom-left back navigation on popup screens (removed — back lives inside popup windows)

    fun showToolbar() {
        if (toolbarView != null) return
        if (toolbarParams == null) {
            toolbarParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(50)
                y = dpToPx(50)
            }
        }
        rebuildToolbar()
    }

    fun hideToolbar() {
        hideEditOverlayPopup()
        hideAllPopupsAndSetupOverlays()
        hideMacroToast()
        toolbarView?.let { runCatching { windowManager.removeView(it) } }
        toolbarView = null
    }

    /** Closes every mode/setup popup and floating setup overlay (used on close/cleanup). */
    fun hideAllPopupsAndSetupOverlays() {
        hideEditOverlayPopup()
        hideModeSelectionOverlay()
        hideSetupMenuOverlay()
        hideMiningSettingsOverlay()
        hideFishSettingsOverlay()
        hideDigPlaceholderOverlay()
        hideToolbarActionConfirm()
        hidePointsSetupOverlays()
        hideZoomLevelOverlay()
        hideGameScreenAreaOverlay()
        hideSnapshotOverlay()
        hideMacroToast()
        setToolbarTouchBlocked(false)
    }

    fun enterEditMode(target: EditTarget) {
        selectedEditTarget = target
        isEditMode = true
        rebuildToolbar()
    }

    fun exitEditMode() {
        isEditMode = false
        selectedEditTarget = EditTarget.NONE
        hideEditOverlayPopup()
        clearMarkers()
        rebuildToolbar()
    }

    fun refreshPlayState() {
        if (!isEditMode) rebuildToolbar()
        updateReferenceSpotRunningColor()
    }

    /** Yellow shade on the overlay toolbar while Fix Setup is running (not for loc return). */
    fun setFixSetupHighlight(active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setFixSetupHighlight(active) }
            return
        }
        fixSetupHighlightActive = active
        if (!isEditMode) rebuildToolbar()
    }

    /** Match toolbar accent: green mine, yellow fish, red dig, blue idle. */
    private fun updateReferenceSpotRunningColor() {
        val view = referenceSpotBoxView ?: return
        val stroke = when {
            !isRunningProvider() -> Color.parseColor("#74BCFF")
            playAccentProvider() == OverlayPlayAccent.FISH -> Color.parseColor("#F5D76E")
            playAccentProvider() == OverlayPlayAccent.DIG -> Color.parseColor("#FF6B6B")
            else -> Color.parseColor("#66E088")
        }
        val bg = view.background as? GradientDrawable
            ?: GradientDrawable().also {
                it.shape = GradientDrawable.RECTANGLE
                it.setColor(Color.TRANSPARENT)
                view.background = it
            }
        bg.setStroke(dpToPx(2), stroke)
    }

    fun setToolbarOpacity(@Suppress("UNUSED_PARAMETER") opacity: Float) {
        toolbarOpacity = 1f
        toolbarView?.alpha = 1f
    }

    fun showMacroToast(message: String) {
        // Fire-and-forget: engine/detector threads post the toast and return instantly,
        // so popup view work never blocks mining timings.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showMacroToast(message) }
            return
        }
        hideMacroToast()
        val tv = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(28, 18, 28, 18)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#EE0E2C57"))
                setStroke(2, Color.parseColor("#74BCFF"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        macroToastView = tv
        runCatching { windowManager.addView(tv, params) }
        macroToastHideRunnable = Runnable { hideMacroToast() }
        mainHandler.postDelayed(macroToastHideRunnable!!, 2000L)
    }

    fun hideMacroToast() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideMacroToast() }
            return
        }
        macroToastHideRunnable?.let { mainHandler.removeCallbacks(it) }
        macroToastHideRunnable = null
        macroToastView?.let { runCatching { windowManager.removeView(it) } }
        macroToastView = null
    }

    /** Centered overlay when mining cannot start (e.g. drill not on tool slot 2). */
    fun showMiningAlert(message: String) {
        hideMiningAlert()
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(buildActionChip("OK", isPrimary = true) { hideMiningAlert() })
        }
        val popup = buildPopupWithScrollBody({ body ->
            body.addView(buildPopupTitle("Cannot start mining"))
            body.addView(buildDivider())
            body.addView(TextView(context).apply {
                text = message
                setTextColor(Color.parseColor("#E8F4FF"))
                textSize = 14f
                setPadding(0, dpToPx(8), 0, dpToPx(8))
            })
        }, footer)
        addOverlay(popup, centeredPopupParams(focusable = true))
        miningAlertPopupView = popup
        bringToolbarToFront()
    }

    fun hideMiningAlert() {
        miningAlertPopupView?.let { runCatching { windowManager.removeView(it) } }
        miningAlertPopupView = null
    }

    fun showEditMarkers(target: EditTarget, profile: ActionProfile, onProfileChanged: (ActionProfile) -> Unit) {
        clearMarkers()
        var latest = profile
        fun pushProfile(transform: ActionProfile.() -> ActionProfile) {
            latest = latest.run(transform)
            onProfileChanged(latest)
        }
        when (target) {
            EditTarget.PAUSE_DETECTION_MOVEMENT -> {
                showDetectionRegionBox(latest.drillRegion) { transform ->
                    pushProfile(transform)
                }
                showClickMarker("PAUSE", latest.pausePoint, latest.markerOpacity) { updated ->
                    pushProfile { copy(pausePoint = updated) }
                }
                latest.movementPoints.forEachIndexed { index, click ->
                    showClickMarker(click.label, click.point, latest.markerOpacity) { updated ->
                        pushProfile {
                            val next = movementPoints.toMutableList()
                            next[index] = click.copy(point = updated)
                            copy(movementPoints = next)
                        }
                    }
                }
                showSquareMarker(latest.meleePoint.label, latest.meleePoint.point, latest.markerOpacity) { updated ->
                    pushProfile { copy(meleePoint = meleePoint.copy(point = updated)) }
                }
                showSquareMarker(latest.glovesPoint.label, latest.glovesPoint.point, latest.markerOpacity) { updated ->
                    pushProfile { copy(glovesPoint = glovesPoint.copy(point = updated)) }
                }
            }
            EditTarget.HOME -> {
                val drag = latest.homeDrags.firstOrNull()
                    ?: DragPath("Home", Point(860f, 400f), Point(760f, 400f), 350L)
                showLine(drag.start, drag.end, latest.markerOpacity)
                showClickMarker("1", drag.start, latest.markerOpacity) { updatedStart ->
                    pushProfile {
                        val d = homeDrags.firstOrNull()
                            ?: DragPath("Home", Point(860f, 400f), Point(760f, 400f), 350L)
                        copy(homeDrags = listOf(d.copy(start = updatedStart)), homeClicks = emptyList())
                    }
                }
                showClickMarker("2", drag.end, latest.markerOpacity) { updatedEnd ->
                    pushProfile {
                        val d = homeDrags.firstOrNull()
                            ?: DragPath("Home", Point(860f, 400f), Point(760f, 400f), 350L)
                        copy(homeDrags = listOf(d.copy(end = updatedEnd)), homeClicks = emptyList())
                    }
                }
            }
            EditTarget.FOOD -> {
                latest.foodClicks.forEachIndexed { index, click ->
                    showClickMarker("${index + 1}", click.point, latest.markerOpacity) { updated ->
                        pushProfile {
                            val next = foodClicks.toMutableList()
                            next[index] = click.copy(point = updated)
                            copy(foodClicks = next)
                        }
                    }
                }
                latest.foodDrags.forEachIndexed { index, drag ->
                    showLine(drag.start, drag.end, latest.markerOpacity)
                    showClickMarker("${index + 1}A", drag.start, latest.markerOpacity) { updated ->
                        pushProfile {
                            val next = foodDrags.toMutableList()
                            next[index] = drag.copy(start = updated)
                            copy(foodDrags = next)
                        }
                    }
                    showClickMarker("${index + 1}B", drag.end, latest.markerOpacity) { updated ->
                        pushProfile {
                            val next = foodDrags.toMutableList()
                            next[index] = drag.copy(end = updated)
                            copy(foodDrags = next)
                        }
                    }
                }
            }
            EditTarget.NONE -> Unit
        }
    }

    fun clearMarkers() {
        markerViews.forEach { runCatching { windowManager.removeView(it) } }
        lineViews.forEach { runCatching { windowManager.removeView(it) } }
        drillRegionView?.let { runCatching { windowManager.removeView(it) } }
        markerViews.clear()
        lineViews.clear()
        drillRegionView = null
        drillRegionParams = null
    }

    private fun rebuildToolbar() {
        toolbarView?.let { runCatching { windowManager.removeView(it) } }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(2, 2, 2, 2)
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1f
        }
        if (isEditMode) {
            bar.addView(createCircleSaveIconButton { onSaveEdit() })
        } else {
            if (isRunningProvider()) {
                // Pause keeps the instant-stop path (onPlayPause -> panic stop).
                bar.addView(createCircleIconButton(R.drawable.ic_pause_overlay) { onPlayPause() })
            } else {
                // During setup, Play saves the current setup screen and closes it.
                bar.addView(createCircleIconButton(R.drawable.ic_play_overlay) {
                    if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                        return@createCircleIconButton
                    }
                    if (!saveAndCloseActiveSetup()) onPlayPressed()
                })
            }
            bar.addView(createCircleButton("⌂") {
                showToolbarActionConfirm("Are you sure you want to go back to the mines?") { onHome() }
            })
            bar.addView(createCircleButton("☰") {
                showToolbarActionConfirm("Are you sure you want to eat?") { onFood() }
            })
            bar.addView(createCircleButton("⚙") {
                onSettingsRequested()
                showEditOverlayPopup()
            })
            bar.addView(createCircleButton("✕") { onCloseOverlay() })
        }
        val params = toolbarParams ?: return
        runCatching { windowManager.addView(bar, params) }
        toolbarView = bar
    }

    private fun showEditOverlayPopup() {
        hideEditOverlayPopup()

        val backdrop = View(context).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
        }
        val backdropParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        backdrop.setOnClickListener { hideEditOverlayPopup() }
        runCatching { windowManager.addView(backdrop, backdropParams) }
        settingsBackdropView = backdrop

        val popup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(Color.parseColor("#EE0E2C57"))
                setStroke(3, Color.parseColor("#74BCFF"))
            }
            alpha = 1f
        }

        popup.addView(TextView(context).apply {
            text = "Sound"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setPadding(0, 0, 0, 10)
        })

        val muteBtn = TextView(context).apply {
            text = if (isSoundMuted()) "🔇 Muted" else "🔊 Unmuted"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(Color.parseColor("#88324863"))
                setStroke(2, Color.parseColor("#74BCFF"))
            }
            setOnClickListener {
                onToggleSoundMuted()
                text = if (isSoundMuted()) "🔇 Muted" else "🔊 Unmuted"
            }
        }
        popup.addView(muteBtn)

        val fixSetupBtn = TextView(context).apply {
            text = "Fix Setup"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(Color.parseColor("#88324863"))
                setStroke(2, Color.parseColor("#74BCFF"))
            }
        }
        popup.addView(fixSetupBtn)

        popup.addView(TextView(context).apply {
            text = "Flick (s)"
            setTextColor(Color.parseColor("#B0CFFF"))
            textSize = 12f
            setPadding(0, 12, 0, 4)
        })
        var draftFlickText = formatDecimalSeconds(
            uiPreferencesProvider().graalReturnFlickSeconds.coerceAtLeast(0.05f)
        )
        fun commitFlickFromDraft() {
            val seconds = parseDecimalSeconds(draftFlickText, 0.15f).coerceIn(0.05f, 5f)
            draftFlickText = formatDecimalSeconds(seconds)
            onUiPreferencesChanged(
                uiPreferencesProvider().copy(graalReturnFlickSeconds = seconds)
            )
        }
        popup.addView(
            buildNumberInput("0.15", draftFlickText, allowDecimal = true) { text ->
                draftFlickText = text
                if (text.trim().replace(',', '.').toFloatOrNull() != null) {
                    commitFlickFromDraft()
                }
            }
        )
        fixSetupBtn.setOnClickListener {
            commitFlickFromDraft()
            hideEditOverlayPopup()
            onFixSetupRequested()
        }
        backdrop.setOnClickListener {
            commitFlickFromDraft()
            hideEditOverlayPopup()
        }

        val anchor = toolbarParams
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (anchor?.x ?: dpToPx(50)) + 20
            y = (anchor?.y ?: dpToPx(50)) + 130
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { windowManager.addView(popup, params) }
        settingsPopupView = popup
    }

    private fun hideEditOverlayPopup() {
        settingsBackdropView?.let { runCatching { windowManager.removeView(it) } }
        settingsBackdropView = null
        settingsPopupView?.let { runCatching { windowManager.removeView(it) } }
        settingsPopupView = null
    }

    /** Yes/No confirm for overlay toolbar Home / Food only. */
    private fun showToolbarActionConfirm(message: String, onConfirm: () -> Unit) {
        hideToolbarActionConfirm()
        setToolbarTouchBlocked(true)

        val backdrop = buildBackdrop { hideToolbarActionConfirm() }
        addOverlay(backdrop, fullscreenParams())
        toolbarConfirmBackdropView = backdrop

        val popup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = dpToPx(12)
            val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)
            background = buildPopupBackground()
            addView(TextView(context).apply {
                text = message
                setTextColor(Color.parseColor("#E8F4FF"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(6))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                addView(buildActionChip("No", isPrimary = false, textSizeSp = 14f, onClick = { hideToolbarActionConfirm() }))
                addView(buildHSpacer(10))
                addView(buildActionChip("Yes", isPrimary = true, textSizeSp = 14f, onClick = {
                    hideToolbarActionConfirm()
                    onConfirm()
                }))
            })
        }
        addOverlay(popup, compactCenteredPopupParams())
        toolbarConfirmPopupView = popup
        bringToolbarToFront()
    }

    private fun hideToolbarActionConfirm() {
        toolbarConfirmBackdropView?.let { runCatching { windowManager.removeView(it) } }
        toolbarConfirmBackdropView = null
        toolbarConfirmPopupView?.let { runCatching { windowManager.removeView(it) } }
        toolbarConfirmPopupView = null
        setToolbarTouchBlocked(false)
    }

    private fun showDetectionRegionBox(
        region: DetectionRegion,
        applyDelta: (ActionProfile.() -> ActionProfile) -> Unit
    ) {
        drillRegionView?.let { runCatching { windowManager.removeView(it) } }
        // Outline only: a filled tint would blend into MediaProjection pixels and block drill color reads.
        val box = View(context).apply {
            alpha = 1f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(4, Color.parseColor("#FFFF4C4C"))
            }
        }
        val params = WindowManager.LayoutParams(
            fixedDrillWidth.toInt(),
            fixedDrillHeight.toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val centerX = (region.left + region.right) / 2f
            val centerY = (region.top + region.bottom) / 2f
            x = (centerX - fixedDrillWidth / 2f).toInt()
            y = (centerY - fixedDrillHeight / 2f).toInt()
        }
        val syncDrillRegionFromScreen = Runnable {
            val loc = IntArray(2)
            box.getLocationOnScreen(loc)
            val w = (if (box.width > 0) box.width else params.width).coerceAtLeast(1)
            val h = (if (box.height > 0) box.height else params.height).coerceAtLeast(1)
            applyDelta {
                copy(
                    drillRegion = DetectionRegion(
                        left = loc[0].toFloat(),
                        top = loc[1].toFloat(),
                        right = (loc[0] + w).toFloat(),
                        bottom = (loc[1] + h).toFloat()
                    )
                )
            }
        }
        makeDraggable(box, params) {
            box.removeCallbacks(syncDrillRegionFromScreen)
            box.post(syncDrillRegionFromScreen)
        }
        runCatching { windowManager.addView(box, params) }
        box.post(syncDrillRegionFromScreen)
        drillRegionView = box
        drillRegionParams = params
    }

    private fun showLine(start: Point, end: Point, @Suppress("UNUSED_PARAMETER") opacity: Float) {
        val width = kotlin.math.abs(end.x - start.x).toInt().coerceAtLeast(4)
        val yDelta = kotlin.math.abs(end.y - start.y).toInt()
        val line = View(context).apply {
            setBackgroundColor(Color.parseColor("#7AA8D5FF"))
            alpha = 1f
        }
        val params = WindowManager.LayoutParams(
            width,
            yDelta.coerceAtLeast(3),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = minOf(start.x, end.x).toInt()
            y = minOf(start.y, end.y).toInt()
        }
        runCatching { windowManager.addView(line, params) }
        lineViews.add(line)
    }

    private fun showClickMarker(
        label: String,
        point: Point,
        @Suppress("UNUSED_PARAMETER") opacity: Float,
        onMoved: (Point) -> Unit
    ) {
        val markerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 1f
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFE4F3FF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                markerLabelHeightPx()
            )
        }
        val dot = TextView(context).apply {
            text = "•"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFFFF"))
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E00E2C57"))
                setStroke(3, Color.parseColor("#74BCFF"))
            }
            layoutParams = LinearLayout.LayoutParams(circleMarkerSizePx(), circleMarkerSizePx())
        }
        markerContainer.addView(labelView)
        markerContainer.addView(dot)
        val containerHeightPx = markerLabelHeightPx() + circleMarkerSizePx()
        val centerOffsetXPx = markerContainerWidthPx() / 2f
        val centerOffsetYPx = markerLabelHeightPx() + (circleMarkerSizePx() / 2f)
        val params = WindowManager.LayoutParams(
            markerContainerWidthPx(),
            containerHeightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (point.x - centerOffsetXPx).toInt()
            y = (point.y - centerOffsetYPx).toInt()
        }
        val syncTapFromAnchor = Runnable {
            val loc = IntArray(2)
            dot.getLocationOnScreen(loc)
            val lp = dot.layoutParams as? LinearLayout.LayoutParams
            val w = (if (dot.width > 0) dot.width else (lp?.width ?: circleMarkerSizePx())).coerceAtLeast(1)
            val h = (if (dot.height > 0) dot.height else (lp?.height ?: circleMarkerSizePx())).coerceAtLeast(1)
            onMoved(Point(loc[0] + w / 2f, loc[1] + h / 2f))
        }
        makeDraggable(markerContainer, params) {
            dot.removeCallbacks(syncTapFromAnchor)
            dot.post(syncTapFromAnchor)
        }
        runCatching { windowManager.addView(markerContainer, params) }
        val alignToSavedPoint = Runnable {
            val loc = IntArray(2)
            dot.getLocationOnScreen(loc)
            val lp = dot.layoutParams as? LinearLayout.LayoutParams
            val w = (if (dot.width > 0) dot.width else (lp?.width ?: circleMarkerSizePx())).coerceAtLeast(1)
            val h = (if (dot.height > 0) dot.height else (lp?.height ?: circleMarkerSizePx())).coerceAtLeast(1)
            val cx = loc[0] + w / 2f
            val cy = loc[1] + h / 2f
            val dx = point.x - cx
            val dy = point.y - cy
            if (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f) {
                params.x += dx.toInt()
                params.y += dy.toInt()
                runCatching { windowManager.updateViewLayout(markerContainer, params) }
            }
        }
        dot.post(alignToSavedPoint)
        markerViews.add(markerContainer)
    }

    private fun showSquareMarker(
        label: String,
        point: Point,
        @Suppress("UNUSED_PARAMETER") opacity: Float,
        onMoved: (Point) -> Unit
    ) {
        val markerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 1f
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFE4F3FF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                markerLabelHeightPx()
            )
        }
        val square = TextView(context).apply {
            text = "•"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFFFF"))
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(Color.parseColor("#E00E2C57"))
                setStroke(3, Color.parseColor("#74BCFF"))
            }
            layoutParams = LinearLayout.LayoutParams(squareMarkerWidthPx(), squareMarkerHeightPx())
        }
        markerContainer.addView(labelView)
        markerContainer.addView(square)
        val containerHeightPx = markerLabelHeightPx() + squareMarkerHeightPx()
        val centerOffsetXPx = markerContainerWidthPx() / 2f
        val centerOffsetYPx = markerLabelHeightPx() + (squareMarkerHeightPx() / 2f)
        val params = WindowManager.LayoutParams(
            markerContainerWidthPx(),
            containerHeightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (point.x - centerOffsetXPx).toInt()
            y = (point.y - centerOffsetYPx).toInt()
        }
        val syncTapFromAnchor = Runnable {
            val loc = IntArray(2)
            square.getLocationOnScreen(loc)
            val lp = square.layoutParams as? LinearLayout.LayoutParams
            val w = (if (square.width > 0) square.width else (lp?.width ?: squareMarkerWidthPx())).coerceAtLeast(1)
            val h = (if (square.height > 0) square.height else (lp?.height ?: squareMarkerHeightPx())).coerceAtLeast(1)
            onMoved(Point(loc[0] + w / 2f, loc[1] + h / 2f))
        }
        makeDraggable(markerContainer, params) {
            square.removeCallbacks(syncTapFromAnchor)
            square.post(syncTapFromAnchor)
        }
        runCatching { windowManager.addView(markerContainer, params) }
        val alignToSavedPoint = Runnable {
            val loc = IntArray(2)
            square.getLocationOnScreen(loc)
            val lp = square.layoutParams as? LinearLayout.LayoutParams
            val w = (if (square.width > 0) square.width else (lp?.width ?: squareMarkerWidthPx())).coerceAtLeast(1)
            val h = (if (square.height > 0) square.height else (lp?.height ?: squareMarkerHeightPx())).coerceAtLeast(1)
            val cx = loc[0] + w / 2f
            val cy = loc[1] + h / 2f
            val dx = point.x - cx
            val dy = point.y - cy
            if (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f) {
                params.x += dx.toInt()
                params.y += dy.toInt()
                runCatching { windowManager.updateViewLayout(markerContainer, params) }
            }
        }
        square.post(alignToSavedPoint)
        markerViews.add(markerContainer)
    }

    private fun createCircleButton(label: String, onSave: Boolean = false, action: () -> Unit): View {
        val button = TextView(context).apply {
            text = label
            textSize = when {
                onSave -> 10f
                label == "||" -> 13f
                else -> 11f
            }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (label == "||") {
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = -0.08f
            }
            background = circleButtonBackground()
            val lp = LinearLayout.LayoutParams(toolbarButtonSizePx(), toolbarButtonSizePx())
            lp.marginStart = toolbarButtonMarginPx()
            layoutParams = lp
            alpha = 1f
        }
        attachButtonTouchInteraction(button) {
            if (onSave) {
                isEditMode = false
                action()
                rebuildToolbar()
            } else {
                action()
            }
        }
        return button
    }

    private fun createCircleSaveIconButton(action: () -> Unit): View {
        val button = ImageView(context).apply {
            setImageResource(R.drawable.ic_save_overlay)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF0E2C57"))
                setStroke(3, Color.parseColor("#74BCFF"))
            }
            val lp = LinearLayout.LayoutParams(toolbarButtonSizePx(), toolbarButtonSizePx())
            lp.marginStart = toolbarButtonMarginPx()
            layoutParams = lp
            alpha = 1f
            setPadding(
                toolbarIconPaddingPx(), toolbarIconPaddingPx(),
                toolbarIconPaddingPx(), toolbarIconPaddingPx()
            )
        }
        attachSaveButtonTouchInteraction(button) {
            isEditMode = false
            action()
            rebuildToolbar()
        }
        return button
    }

    private fun createCircleIconButton(iconRes: Int, action: () -> Unit): View {
        val button = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = circleButtonBackground()
            val lp = LinearLayout.LayoutParams(toolbarButtonSizePx(), toolbarButtonSizePx())
            lp.marginStart = toolbarButtonMarginPx()
            layoutParams = lp
            alpha = 1f
            setPadding(
                toolbarIconPaddingPx(), toolbarIconPaddingPx(),
                toolbarIconPaddingPx(), toolbarIconPaddingPx()
            )
        }
        attachButtonTouchInteraction(button, action)
        return button
    }

    private fun attachSaveButtonTouchInteraction(view: View, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            val pressedBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF071D3A"))
                setStroke(3, Color.parseColor("#9FD2FF"))
            }
            val normalBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF0E2C57"))
                setStroke(3, Color.parseColor("#74BCFF"))
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = toolbarParams ?: return false
                val toolbar = toolbarView ?: return false
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        view.alpha = 0.9f
                        view.scaleX = 0.97f
                        view.scaleY = 0.97f
                        view.background = pressedBg
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
                        if (moved) {
                            params.x = startX + dx
                            params.y = startY + dy
                            runCatching { windowManager.updateViewLayout(toolbar, params) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.background = normalBg
                        if (!moved) onTap()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.background = normalBg
                        return true
                    }
                    else -> return false
                }
            }
        })
    }

    private fun attachButtonTouchInteraction(view: View, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val colors = toolbarButtonColors()
        view.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            val pressedBg = circleButtonBackground(
                ToolbarButtonColors(
                    fill = colors.pressedFill,
                    stroke = colors.pressedStroke,
                    pressedFill = colors.pressedFill,
                    pressedStroke = colors.pressedStroke
                )
            )
            val normalBg = circleButtonBackground(colors)

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = toolbarParams ?: return false
                val toolbar = toolbarView ?: return false
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        view.alpha = 0.9f
                        view.scaleX = 0.97f
                        view.scaleY = 0.97f
                        view.background = pressedBg
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
                        if (moved) {
                            params.x = startX + dx
                            params.y = startY + dy
                            runCatching { windowManager.updateViewLayout(toolbar, params) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.background = normalBg
                        if (!moved) onTap()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.background = normalBg
                        return true
                    }
                    else -> return false
                }
            }
        })
    }

    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        onDragUpdated: (() -> Unit)? = null
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    clampWindowParamsToScreen(params, view)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    onDragUpdated?.invoke()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    clampWindowParamsToScreen(params, view)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    onDragUpdated?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun saveAndCloseActiveSetup(): Boolean {
        val saveAction = activeSetupSaveAction ?: return false
        saveAction()
        return true
    }

    private fun setActiveSetupSaveAction(target: SetupTarget, saveAction: () -> Unit) {
        activeSetupTarget = target
        activeSetupSaveAction = saveAction
    }

    private fun clearActiveSetupSaveAction(target: SetupTarget) {
        if (activeSetupTarget == target) {
            activeSetupTarget = null
            activeSetupSaveAction = null
        }
    }

    // ---------------------------------------------------------------------
    // Z-order: keep toolbar above popups, optionally block its touches.
    // ---------------------------------------------------------------------

    private fun bringToolbarToFront() {
        toolbarView?.let { runCatching { windowManager.removeView(it) } }
        val params = toolbarParams ?: return
        val bar = toolbarView ?: return
        runCatching { windowManager.addView(bar, params) }
    }

    private fun setToolbarTouchBlocked(blocked: Boolean) {
        val params = toolbarParams ?: return
        val bar = toolbarView ?: return
        params.flags = if (blocked) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(bar, params) }
        if (blocked) bringToolbarToFront()
    }

    // ---------------------------------------------------------------------
    // Popup building helpers (shared dark-blue theme).
    // ---------------------------------------------------------------------

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    private fun screenWidthPx(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeightPx(): Int = context.resources.displayMetrics.heightPixels

    private fun clampWindowParamsToScreen(params: WindowManager.LayoutParams, view: View? = null) {
        val sw = screenWidthPx()
        val sh = screenHeightPx()
        val w = when {
            params.width > 0 -> params.width
            view != null && view.width > 0 -> view.width
            else -> dpToPx(48)
        }
        val h = when {
            params.height > 0 -> params.height
            view != null && view.height > 0 -> view.height
            else -> dpToPx(48)
        }
        params.x = params.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    private fun buildPopupBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(16).toFloat()
        setColor(Color.parseColor("#EE0E2C57"))
        setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
    }

    private fun popupWindowHeightPx(): Int {
        val maxH = screenHeightPx() - dpToPx(12)
        val preferred = (screenHeightPx() * 0.75f).toInt()
        val minH = dpToPx(260).coerceAtMost(maxH)
        return preferred.coerceIn(minH, maxH)
    }

    private fun popupWidthPx(): Int =
        dpToPx(popupWidthDp).coerceAtMost(screenWidthPx() - dpToPx(24))

    /** Full-height popup root; pair with [pinFooterToBottom] so actions sit on the bottom-right. */
    private fun buildPopupRoot(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dpToPx(18)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        background = buildPopupBackground()
    }

    private fun pinFooterToBottom(root: LinearLayout, footer: LinearLayout) {
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        })
        root.addView(footer)
    }

    /** Scrollable body + footer pinned to the bottom of a fixed-height popup. */
    private fun buildPopupWithScrollBody(
        configureBody: (LinearLayout) -> Unit,
        footer: LinearLayout
    ): LinearLayout {
        val root = buildPopupRoot()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        configureBody(body)
        scroll.addView(body)
        root.addView(scroll)
        root.addView(footer)
        return root
    }

    private fun buildBackdrop(onTap: () -> Unit): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#88000000"))
        setOnClickListener { onTap() }
    }

    private fun formatDecimalSeconds(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun parseDecimalSeconds(text: String, fallback: Float): Float =
        text.trim().replace(',', '.').toFloatOrNull()?.let { if (it.isNaN()) fallback else it } ?: fallback

    private fun buildPopupTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#B0CFFF"))
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(6))
    }

    private fun buildPopupTitleRow(title: String, onGearClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(buildPopupTitle(title).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (onGearClick != null) {
                addView(TextView(context).apply {
                    text = "\u2699"
                    contentDescription = "Mining interval settings"
                    textSize = 20f
                    setTextColor(Color.parseColor("#74BCFF"))
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(6), dpToPx(2), dpToPx(2), dpToPx(8))
                    setOnClickListener { onGearClick() }
                })
            }
        }

    private fun buildSectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#EAF4FF"))
        setPadding(0, dpToPx(6), 0, dpToPx(2))
    }

    private fun buildBodyText(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#B0CFFF"))
        setPadding(0, dpToPx(2), 0, dpToPx(2))
    }

    private fun buildPendingSetupList(progress: SetupProgress): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(4))
        }
        val pending = progress.pendingItems()
        if (pending.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "All setup steps complete."
                textSize = 11f
                setTextColor(Color.parseColor("#8FE89A"))
                setPadding(0, dpToPx(2), 0, dpToPx(2))
            })
            return container
        }
        container.addView(TextView(context).apply {
            text = "Still required before mining or digging:"
            textSize = 11f
            setTextColor(Color.parseColor("#FFB0B0"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dpToPx(2), 0, dpToPx(4))
        })
        pending.forEach { (_, label) ->
            container.addView(TextView(context).apply {
                text = "• $label"
                textSize = 11f
                setTextColor(Color.parseColor("#FF9E9E"))
                setPadding(dpToPx(8), dpToPx(2), 0, dpToPx(2))
            })
        }
        return container
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#337AA8D5"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        ).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }
    }

    /**
     * Adapter whose closed value shows white text (over the dark overlay) and whose dropdown
     * list shows dark text on a white background for readability.
     */
    private fun whiteDropdownAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#EAF4FF"))
                tv.textSize = 14f
                tv.setPadding(dpToPx(4), dpToPx(10), dpToPx(4), dpToPx(10))
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#0A1324"))
                tv.setBackgroundColor(Color.WHITE)
                tv.textSize = 14f
                tv.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
                return tv
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun buildSpinnerOutlineBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(10).toFloat()
        setColor(Color.parseColor("#660E2C57"))
        setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
    }

    private fun buildStyledSpinner(items: List<String>, onSelected: (Int) -> Unit): Spinner {
        val container = FrameLayout(context).apply {
            background = buildSpinnerOutlineBackground()
            setPadding(dpToPx(10), dpToPx(2), dpToPx(10), dpToPx(2))
        }
        val arrow = TextView(context).apply {
            text = "\u25BE"
            setTextColor(Color.parseColor("#74BCFF"))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }
        val spinner = Spinner(context).apply {
            adapter = whiteDropdownAdapter(items)
            background = null
            setPopupBackgroundDrawable(ColorDrawable(Color.WHITE))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSelected(pos) }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(spinner)
        container.addView(arrow)
        spinner.tag = container
        return spinner
    }

    /** Wraps a spinner in its outlined container for correct layout in popups. */
    private fun addOutlinedSpinner(parent: LinearLayout, spinner: Spinner) {
        val wrapper = spinner.tag as? FrameLayout
        if (wrapper != null) {
            parent.addView(wrapper, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        } else {
            parent.addView(spinner)
        }
    }

    private fun buildStyledCheckbox(label: String, initialChecked: Boolean, onChecked: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }
        val checkBox = CheckBox(context).apply {
            isChecked = initialChecked
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#74BCFF"))
            setOnCheckedChangeListener { _, checked -> onChecked(checked) }
        }
        val labelView = buildBodyText(label)
        row.addView(checkBox)
        row.addView(labelView)
        return row
    }

    private fun buildNumberInput(
        hint: String,
        initialValue: String,
        allowDecimal: Boolean,
        onChange: (String) -> Unit
    ): EditText = EditText(context).apply {
        setText(initialValue)
        setHint(hint)
        setHintTextColor(Color.parseColor("#88B0CFFF"))
        setTextColor(Color.parseColor("#EAF4FF"))
        textSize = 14f
        inputType = if (allowDecimal) {
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_VARIATION_NORMAL
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        if (allowDecimal) {
            keyListener = DigitsKeyListener.getInstance("0123456789.,")
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(10).toFloat()
            setColor(Color.parseColor("#440E2C57"))
            setStroke(dpToPx(1), Color.parseColor("#74BCFF"))
        }
        setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun buildLabeledNumberField(
        label: String,
        hint: String,
        initialValue: String,
        allowDecimal: Boolean,
        onChange: (String) -> Unit
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#B0CFFF"))
            setPadding(0, 0, 0, dpToPx(4))
        })
        column.addView(buildNumberInput(hint, initialValue, allowDecimal, onChange))
        return column
    }

    private fun buildActionChip(
        label: String,
        isPrimary: Boolean,
        textSizeSp: Float = 13f,
        onClick: () -> Unit
    ): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = textSizeSp
            gravity = Gravity.CENTER
            setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor(if (isPrimary) "#AA0E2C57" else "#88324863"))
                setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
            }
            setOnClickListener { onClick() }
        }

    /**
     * Single footer row: [← Back] on the bottom-left, [negative] [positive] on the bottom-right.
     */
    private fun buildPopupFooterRow(
        onBack: () -> Unit,
        negativeLabel: String,
        positiveLabel: String,
        onNegative: () -> Unit,
        onPositive: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(buildActionChip("\u2190 Back", isPrimary = false, onClick = onBack))
        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(buildActionChip(negativeLabel, isPrimary = false, onClick = onNegative))
        actions.addView(buildHSpacer(12))
        actions.addView(buildActionChip(positiveLabel, isPrimary = true, onClick = onPositive))
        row.addView(actions)
        return row
    }

    /** Footer without Back — Exit/Proceed (or similar) on the bottom-right of the popup. */
    private fun buildPopupFooterActions(
        negativeLabel: String,
        positiveLabel: String,
        onNegative: () -> Unit,
        onPositive: () -> Unit,
        actionTextSizeSp: Float = 13f
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(buildActionChip(negativeLabel, isPrimary = false, textSizeSp = actionTextSizeSp, onClick = onNegative))
        addView(buildHSpacer(12))
        addView(buildActionChip(positiveLabel, isPrimary = true, textSizeSp = actionTextSizeSp, onClick = onPositive))
    }

    /** Footer with Back left and one action button on the right (e.g. Close). */
    private fun buildPopupFooterRowSingleAction(
        onBack: () -> Unit,
        actionLabel: String,
        onAction: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(buildActionChip("\u2190 Back", isPrimary = false, onClick = onBack))
        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        row.addView(buildActionChip(actionLabel, isPrimary = true, onClick = onAction))
        return row
    }

    private fun buildHSpacer(widthDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp), 1)
    }

    private fun buildVSpacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dpToPx(heightDp))
    }

    private fun fullscreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

    /** Sized overlay window — touches outside its bounds reach the game underneath. */
    private fun boundedOverlayParams(x: Int, y: Int, width: Int, height: Int) =
        WindowManager.LayoutParams(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun compactCenteredPopupParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

    private fun centeredPopupParams(focusable: Boolean = false): WindowManager.LayoutParams {
        // Focusable popups can receive keyboard input (EditText typing). FLAG_NOT_TOUCH_MODAL keeps
        // the backdrop behind it tappable for dismiss. Non-focusable popups stay click-through-safe.
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            popupWidthPx(),
            popupWindowHeightPx(),
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (focusable) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun addOverlay(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.addView(view, params) }
    }

    /** Generic tap-or-drag handler that moves the view's own window params. */
    private fun attachDraggableTap(
        view: View,
        params: WindowManager.LayoutParams,
        onTap: () -> Unit
    ) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        clampWindowParamsToScreen(params, view)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------------
    // Mode Selection overlay (Mine / Dig / Setup).
    // ---------------------------------------------------------------------

    private data class ModeRadioRow(val circle: TextView, val mode: AppMode)

    fun showModeSelectionOverlay(
        onMine: () -> Unit,
        onDig: () -> Unit,
        onFish: () -> Unit,
        onSetup: () -> Unit
    ) {
        hideModeSelectionOverlay()
        setToolbarTouchBlocked(true)

        val backdrop = buildBackdrop { hideModeSelectionOverlay() }
        addOverlay(backdrop, fullscreenParams())
        modeSelectionBackdropView = backdrop

        var selectedMode = uiPreferencesProvider().lastAppMode
        val radioRows = mutableListOf<ModeRadioRow>()
        val labels = listOf(
            "Mine" to AppMode.MINE,
            "Dig" to AppMode.DIG,
            "Fish" to AppMode.FISH,
            "Setup" to AppMode.SETUP
        )
        fun persistModeChoice(mode: AppMode) {
            selectedMode = mode
            onUiPreferencesChanged(uiPreferencesProvider().copy(lastAppMode = mode))
        }
        fun refreshRadios() {
            radioRows.forEach { (circle, mode) ->
                circle.text = if (mode == selectedMode) "◉" else "○"
            }
        }

        val footer = buildPopupFooterActions(
            negativeLabel = "Exit",
            positiveLabel = "Proceed",
            onNegative = { hideModeSelectionOverlay() },
            onPositive = {
                persistModeChoice(selectedMode)
                val progress = setupProgressProvider()
                if (selectedMode != AppMode.SETUP && !progress.allComplete) {
                    val pending = progress.pendingItems().joinToString(", ") { it.second }
                    showMacroToast("Complete setup first: $pending")
                    return@buildPopupFooterActions
                }
                hideModeSelectionOverlay()
                when (selectedMode) {
                    AppMode.MINE -> onMine()
                    AppMode.DIG -> onDig()
                    AppMode.FISH -> onFish()
                    AppMode.SETUP -> onSetup()
                }
            },
            actionTextSizeSp = 15f
        )
        val popup = buildPopupWithScrollBody({ body ->
            body.addView(TextView(context).apply {
                text = "AntiJail"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#B0CFFF"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(2))
            })
            body.addView(TextView(context).apply {
                text = "by Gradly"
                textSize = 11f
                setTextColor(Color.parseColor("#8AA8CC"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(8))
            })
            body.addView(buildDivider())
            body.addView(TextView(context).apply {
                text = "Mining automation overlay — set up your controls once, " +
                    "then run mining, fishing, or digging macros with gesture taps."
                textSize = 13f
                setTextColor(Color.parseColor("#B0CFFF"))
                setPadding(0, dpToPx(4), 0, dpToPx(10))
            })
            body.addView(TextView(context).apply {
                text = "Select a mode"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#EAF4FF"))
                setPadding(0, dpToPx(2), 0, dpToPx(6))
            })
            val radioGroup = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpToPx(4), 0, dpToPx(8))
            }
            labels.forEach { (label, mode) ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(4), dpToPx(10), dpToPx(8), dpToPx(10))
                    setOnClickListener {
                        persistModeChoice(mode)
                        refreshRadios()
                    }
                }
                val circle = TextView(context).apply {
                    text = "○"
                    textSize = 26f
                    setTextColor(Color.parseColor("#EAF4FF"))
                    setPadding(0, 0, dpToPx(10), 0)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                val labelView = TextView(context).apply {
                    text = label
                    textSize = 17f
                    setTextColor(Color.parseColor("#EAF4FF"))
                    includeFontPadding = false
                }
                row.addView(circle)
                row.addView(labelView)
                radioRows.add(ModeRadioRow(circle, mode))
                radioGroup.addView(row)
            }
            refreshRadios()
            body.addView(radioGroup)
        }, footer)

        addOverlay(popup, centeredPopupParams())
        modeSelectionPopupView = popup
        bringToolbarToFront()
    }

    private fun hideModeSelectionOverlay() {
        modeSelectionBackdropView?.let { runCatching { windowManager.removeView(it) } }
        modeSelectionBackdropView = null
        modeSelectionPopupView?.let { runCatching { windowManager.removeView(it) } }
        modeSelectionPopupView = null
        setToolbarTouchBlocked(false)
    }

    // ---------------------------------------------------------------------
    // Setup Menu overlay.
    // ---------------------------------------------------------------------

    fun showSetupMenuOverlay() {
        hideSetupMenuOverlay()
        setToolbarTouchBlocked(true)

        val backdrop = buildBackdrop { hideSetupMenuOverlay() }
        addOverlay(backdrop, fullscreenParams())
        setupMenuBackdropView = backdrop

        val setupOptions = listOf(
            "Points and Region Setup",
            "Zoom Level Setup",
            "Game Screen Area",
            "Snapshot"
        )
        var selectedSetup = uiPreferencesProvider().lastSetupTarget
        val footer = buildPopupFooterActions(
            negativeLabel = "Exit",
            positiveLabel = "Setup",
            onNegative = { hideSetupMenuOverlay() },
            onPositive = {
                onUiPreferencesChanged(uiPreferencesProvider().copy(lastSetupTarget = selectedSetup))
                hideSetupMenuOverlay()
                when (selectedSetup) {
                    SetupTarget.POINTS_AND_REGION ->
                        showPointsSetupOverlays(setupLayoutProvider()) { layout ->
                            onSetupLayoutSaved(layout)
                            onSetupStepCompleted(SetupTarget.POINTS_AND_REGION)
                        }
                    SetupTarget.ZOOM_LEVEL -> showZoomLevelOverlay()
                    SetupTarget.GAME_SCREEN_AREA ->
                        showGameScreenAreaOverlay(setupLayoutProvider()) { layout ->
                            onSetupLayoutSaved(layout)
                            onSetupStepCompleted(SetupTarget.GAME_SCREEN_AREA)
                        }
                    SetupTarget.SNAPSHOT -> showSnapshotOverlay()
                }
            }
        )
        val popup = buildPopupWithScrollBody({ body ->
            body.addView(buildPopupTitle("Setup Settings"))
            body.addView(buildDivider())
            body.addView(buildBodyText("Select which of the setup you want to configure."))
            val spinner = buildStyledSpinner(setupOptions) { idx ->
                selectedSetup = setupTargetFromSpinnerIndex(idx)
                onUiPreferencesChanged(uiPreferencesProvider().copy(lastSetupTarget = selectedSetup))
            }
            addOutlinedSpinner(body, spinner)
            spinner.setSelection(selectedSetup.spinnerIndex())
            body.addView(buildPendingSetupList(setupProgressProvider()))
        }, footer)

        addOverlay(popup, centeredPopupParams())
        setupMenuPopupView = popup
        bringToolbarToFront()
    }

    private fun hideSetupMenuOverlay() {
        setupMenuBackdropView?.let { runCatching { windowManager.removeView(it) } }
        setupMenuBackdropView = null
        setupMenuPopupView?.let { runCatching { windowManager.removeView(it) } }
        setupMenuPopupView = null
        setToolbarTouchBlocked(false)
    }

    // ---------------------------------------------------------------------
    // Dig placeholder overlay.
    // ---------------------------------------------------------------------

    fun showDigPlaceholderOverlay() {
        hideDigPlaceholderOverlay()
        setToolbarTouchBlocked(true)

        val backdrop = buildBackdrop { hideDigPlaceholderOverlay() }
        addOverlay(backdrop, fullscreenParams())
        digBackdropView = backdrop

        val footer = buildPopupFooterRowSingleAction(
            onBack = { hideDigPlaceholderOverlay() },
            actionLabel = "Close",
            onAction = { hideDigPlaceholderOverlay() }
        )
        val popup = buildPopupWithScrollBody({ body ->
            body.addView(buildPopupTitle("Dig Mode"))
            body.addView(buildDivider())
            body.addView(buildBodyText("Dig automation coming soon. Configure your setup positions first."))
        }, footer)

        addOverlay(popup, centeredPopupParams())
        digPopupView = popup
        bringToolbarToFront()
    }

    private fun hideDigPlaceholderOverlay() {
        digBackdropView?.let { runCatching { windowManager.removeView(it) } }
        digBackdropView = null
        digPopupView?.let { runCatching { windowManager.removeView(it) } }
        digPopupView = null
        setToolbarTouchBlocked(false)
    }

    // ---------------------------------------------------------------------
    // Fish Settings overlay (scrollable).
    // ---------------------------------------------------------------------

    fun showFishSettingsOverlay(initialSettings: FishSettings) {
        hideFishSettingsOverlay()
        setToolbarTouchBlocked(true)

        val backdrop = buildBackdrop { hideFishSettingsOverlay() }
        addOverlay(backdrop, fullscreenParams())
        fishSettingsBackdropView = backdrop

        var killPirates = initialSettings.killPirates

        val footer = buildPopupFooterRow(
            onBack = { hideFishSettingsOverlay() },
            negativeLabel = "Exit",
            positiveLabel = "Start",
            onNegative = { hideFishSettingsOverlay() },
            onPositive = {
                hideFishSettingsOverlay()
                onFishStart(FishSettings(killPirates = killPirates))
            }
        )

        val popup = buildPopupWithScrollBody({ body ->
            body.addView(buildPopupTitle("Fish Settings"))
            body.addView(buildDivider())
            body.addView(buildBodyText(
                "Fish automation watches for pirates while you fish. " +
                    "Position the Stop button and Pirate HP detection box under " +
                    "Setup → Points and Region → Fishing Detections before starting."
            ))
            body.addView(buildVSpacer(8))
            body.addView(buildBodyText(
                "When a pirate attacks, the macro runs: Stop → T2 → T2 → hold Down attack 4s → T1 → Stop. " +
                    "Pirate + player HP colours must both appear inside the detection box."
            ))
            body.addView(buildVSpacer(12))
            body.addView(buildBodyText("Specify whether the macro should kill pirates while fishing."))
            body.addView(buildStyledCheckbox("Kill Pirates", killPirates) { killPirates = it })
        }, footer)

        addOverlay(popup, centeredPopupParams(focusable = true))
        fishSettingsPopupView = popup
        bringToolbarToFront()
    }

    private fun hideFishSettingsOverlay() {
        fishSettingsBackdropView?.let { runCatching { windowManager.removeView(it) } }
        fishSettingsBackdropView = null
        fishSettingsPopupView?.let { runCatching { windowManager.removeView(it) } }
        fishSettingsPopupView = null
        setToolbarTouchBlocked(false)
    }

    // ---------------------------------------------------------------------
    // Mining Settings overlay (scrollable).
    // ---------------------------------------------------------------------

    fun showMiningSettingsOverlay(initialSettings: MiningSettings) {
        hideMiningSettingsOverlay()
        setToolbarTouchBlocked(true)

        val backdrop = buildBackdrop { hideMiningSettingsOverlay() }
        addOverlay(backdrop, fullscreenParams())
        miningSettingsBackdropView = backdrop

        val directions = MiningDirection.values()
        var selectedDirection = initialSettings.direction
        var mineLevel = initialSettings.mineLevel
        var additionalDelay = initialSettings.additionalDelaySeconds
        var miningLocation = initialSettings.miningLocation.coerceIn(1, 6)
        var moveWhenStationary = initialSettings.moveWhenStationary
        var killSpiders = initialSettings.killSpiders
        var drillDetection = initialSettings.drillDetection
        var autoEat = initialSettings.autoEatAfterSpiderKill
        var autoMineCollapsed = initialSettings.autoMineCollapsed
        var exitHospital = initialSettings.exitHospital
        var noBattery = initialSettings.noBattery
        var baseClickInterval = initialSettings.baseClickIntervalSeconds
        var rockMinedDelay = initialSettings.rockMinedDelaySeconds
        var eatFoodCount = initialSettings.eatFoodCount

        fun showMiningIntervalSettingsOverlay() {
            hideMiningIntervalSettingsOverlay()
            var draftBaseIntervalText = formatDecimalSeconds(baseClickInterval)
            var draftRockDelayText = formatDecimalSeconds(rockMinedDelay)
            var draftEatCountText = eatFoodCount.toString()
            val intervalFooter = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(buildActionChip("Done", isPrimary = true) {
                    baseClickInterval = parseDecimalSeconds(draftBaseIntervalText, 7.5f).coerceAtLeast(0.5f)
                    rockMinedDelay = parseDecimalSeconds(draftRockDelayText, 2f).coerceAtLeast(0f)
                    eatFoodCount = (draftEatCountText.toIntOrNull() ?: 3).coerceAtLeast(1)
                    hideMiningIntervalSettingsOverlay()
                })
            }
            val intervalPopup = buildPopupWithScrollBody({ body ->
                body.addView(buildPopupTitle("Mining Intervals"))
                body.addView(buildDivider())
                body.addView(buildBodyText(
                    "Base time between mining clicks at level 0. Each mining level subtracts 0.075s. Decimals allowed."
                ))
                body.addView(buildLabeledNumberField(
                    label = "Click interval (seconds)",
                    hint = "7.5",
                    initialValue = draftBaseIntervalText,
                    allowDecimal = true
                ) { value -> draftBaseIntervalText = value }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                body.addView(buildVSpacer(10))
                body.addView(buildBodyText(
                    "How long to wait after a rock is detected before switching direction. Decimals allowed."
                ))
                body.addView(buildLabeledNumberField(
                    label = "Rock mined wait (seconds)",
                    hint = "2",
                    initialValue = draftRockDelayText,
                    allowDecimal = true
                ) { value -> draftRockDelayText = value }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                body.addView(buildVSpacer(10))
                body.addView(buildLabeledNumberField(
                    label = "How many times to eat food?",
                    hint = "3",
                    initialValue = draftEatCountText,
                    allowDecimal = false
                ) { value -> draftEatCountText = value }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }, intervalFooter)
            addOverlay(intervalPopup, centeredPopupParams(focusable = true))
            miningIntervalSettingsPopupView = intervalPopup
            bringToolbarToFront()
        }

        val footer = buildPopupFooterRow(
            onBack = { hideMiningSettingsOverlay() },
            negativeLabel = "Exit",
            positiveLabel = "Proceed",
            onNegative = { hideMiningSettingsOverlay() },
            onPositive = {
                hideMiningSettingsOverlay()
                showReferenceSpotOverlay(MiningSettings(
                    direction = selectedDirection,
                    mineLevel = mineLevel,
                    additionalDelaySeconds = additionalDelay,
                    baseClickIntervalSeconds = baseClickInterval.coerceAtLeast(0.5f),
                    rockMinedDelaySeconds = rockMinedDelay.coerceAtLeast(0f),
                    eatFoodCount = eatFoodCount.coerceAtLeast(1),
                    miningLocation = miningLocation.coerceIn(1, 6),
                    moveWhenStationary = moveWhenStationary,
                    killSpiders = killSpiders,
                    drillDetection = drillDetection,
                    autoEatAfterSpiderKill = autoEat,
                    autoMineCollapsed = autoMineCollapsed,
                    exitHospital = exitHospital,
                    noBattery = noBattery
                ))
            }
        )

        data class CheckOption(
            val description: String,
            val label: String,
            val initial: Boolean,
            val setter: (Boolean) -> Unit
        )
        val checkOptions = listOf(
            CheckOption(
                "Specify whether the macro should not move when mining stationary in two directions. " +
                    "The macro moves by default so it can mine rocks stationary; this option will disable that behavior.",
                "Move when mining stationary in two directions",
                moveWhenStationary
            ) { moveWhenStationary = it },
            CheckOption("Specify whether the macro should kill spiders.", "Kill spiders", killSpiders) {
                killSpiders = it
                if (it) autoEat = true
            },
            CheckOption("Enable drill detection using screen colour analysis.", "Drill detection", drillDetection) { drillDetection = it },
            CheckOption("Auto eat food after killing a spider.", "Auto eat after spider kill", autoEat) { autoEat = it },
            CheckOption("Automatically mine collapsed rocks.", "Auto mine collapsed", autoMineCollapsed) { autoMineCollapsed = it },
            CheckOption("Automatically exit the hospital when respawned.", "Exit hospital", exitHospital) { exitHospital = it },
            CheckOption("Run escape sequence when \"Battery is dead\" appears.", "Battery dead", noBattery) { noBattery = it }
        )

        val popup = buildPopupWithScrollBody({ body ->
            body.addView(buildPopupTitleRow("Mining Settings", onGearClick = {
                showMiningIntervalSettingsOverlay()
            }))
            body.addView(buildDivider())
            body.addView(buildSectionLabel("Select the directions for mining rocks."))
            val directionSpinner = buildStyledSpinner(directions.map { it.displayLabel }) { idx ->
                selectedDirection = directions[idx]
            }
            addOutlinedSpinner(body, directionSpinner)
            directionSpinner.setSelection(
                directions.indexOfFirst { it == initialSettings.direction }.coerceAtLeast(0)
            )
            body.addView(buildSectionLabel(
                "Specify your mining level so the macro can determine the correct tap duration. " +
                    "You can also provide an additional delay on top of the mine level delay."
            ))
            val levelRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            levelRow.addView(buildLabeledNumberField(
                label = "Mining Level",
                hint = "0",
                initialValue = mineLevel.toString(),
                allowDecimal = false
            ) { value -> mineLevel = value.toIntOrNull() ?: 0 })
            levelRow.addView(buildHSpacer(16))
            levelRow.addView(buildLabeledNumberField(
                label = "Additional interval second",
                hint = "0.5",
                initialValue = additionalDelay.toString(),
                allowDecimal = true
            ) { value -> additionalDelay = value.toFloatOrNull() ?: 0.5f })
            body.addView(levelRow)
            body.addView(buildSectionLabel(
                "Mining location after returning to the mine (fire, battery dead, or Home). " +
                    "Walks to the chosen spot and resumes mining. Does not affect no-drill."
            ))
            val locationSpinner = buildStyledSpinner((1..6).map { it.toString() }) { idx ->
                miningLocation = idx + 1
            }
            addOutlinedSpinner(body, locationSpinner)
            locationSpinner.setSelection(miningLocation - 1)
            checkOptions.forEach { opt ->
                body.addView(buildBodyText(opt.description))
                body.addView(buildStyledCheckbox(opt.label, opt.initial) { checked -> opt.setter(checked) })
                body.addView(buildVSpacer(8))
            }
        }, footer)

        addOverlay(popup, centeredPopupParams(focusable = true))
        miningSettingsPopupView = popup
        bringToolbarToFront()
    }

    private fun hideMiningIntervalSettingsOverlay() {
        miningIntervalSettingsPopupView?.let { runCatching { windowManager.removeView(it) } }
        miningIntervalSettingsPopupView = null
    }

    private fun hideMiningSettingsOverlay() {
        hideMiningIntervalSettingsOverlay()
        miningSettingsBackdropView?.let { runCatching { windowManager.removeView(it) } }
        miningSettingsBackdropView = null
        miningSettingsPopupView?.let { runCatching { windowManager.removeView(it) } }
        miningSettingsPopupView = null
        setToolbarTouchBlocked(false)
    }

    // ---------------------------------------------------------------------
    // Reference Spot — movable light-blue outline box captured on Start.
    // Persists (outline only) while mining; removed only when the macro stops.
    // ---------------------------------------------------------------------

    private fun regionOfView(
        view: View,
        params: WindowManager.LayoutParams
    ): DetectionRegion {
        if (!view.isAttachedToWindow) {
            val w = params.width.coerceAtLeast(1)
            val h = params.height.coerceAtLeast(1)
            return DetectionRegion(
                params.x.toFloat(), params.y.toFloat(),
                (params.x + w).toFloat(), (params.y + h).toFloat()
            )
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val w = (if (view.width > 0) view.width else params.width).coerceAtLeast(1)
        val h = (if (view.height > 0) view.height else params.height).coerceAtLeast(1)
        return DetectionRegion(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + w).toFloat(), (loc[1] + h).toFloat()
        )
    }

    fun showReferenceSpotOverlay(settings: MiningSettings) {
        hideReferenceSpot()
        val sizePx = REFERENCE_SPOT_SIZE_PX
        val box = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
            }
        }
        val saved = uiPreferencesProvider().lastReferenceSpot
        val boxParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (saved != null) {
                x = saved.left.toInt().coerceIn(0, (screenWidthPx() - sizePx).coerceAtLeast(0))
                y = saved.top.toInt().coerceIn(0, (screenHeightPx() - sizePx).coerceAtLeast(0))
            } else {
                x = ((screenWidthPx() - sizePx) / 2).coerceAtLeast(0)
                y = ((screenHeightPx() - sizePx) / 2).coerceAtLeast(0)
            }
        }
        makeDraggable(box, boxParams)
        addOverlay(box, boxParams)
        referenceSpotBoxView = box
        referenceSpotBoxParams = boxParams

        val startBtn = buildActionChip("\u25B6 Start", isPrimary = true, textSizeSp = 15f) {
            val params = referenceSpotBoxParams
            val view = referenceSpotBoxView
            val region = if (view != null && params != null) regionOfView(view, params) else null
            // Persist box position only — the image inside is captured later in MainActivity
            // for this Start, then discarded on Stop (never stored in prefs).
            if (region != null) {
                onUiPreferencesChanged(uiPreferencesProvider().copy(lastReferenceSpot = region))
            }
            referenceStartButtonView?.let { runCatching { windowManager.removeView(it) } }
            referenceStartButtonView = null
            // Box stays as a visual outline but becomes pass-through so it never blocks game touches.
            if (view != null && params != null) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            // Turn green immediately; refreshPlayState also syncs once mining isRunning.
            view?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(2), Color.parseColor("#66E088"))
            }
            // Compare only pixels INSIDE the outline — the stroke itself is drawn over the
            // region and changes colour (blue→green), which would poison the baseline.
            val insetPx = dpToPx(4).toFloat()
            val detectionRegion = region?.let {
                if (it.right - it.left > insetPx * 2 && it.bottom - it.top > insetPx * 2) {
                    DetectionRegion(
                        it.left + insetPx,
                        it.top + insetPx,
                        it.right - insetPx,
                        it.bottom - insetPx
                    )
                } else {
                    it
                }
            }
            onMineStart(settings, detectionRegion)
        }
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val tp = toolbarParams
            if (tp != null) {
                x = tp.x
                y = tp.y + (toolbarView?.height ?: toolbarButtonSizePx()) + dpToPx(12)
            } else {
                x = ((screenWidthPx() - dpToPx(120)) / 2).coerceAtLeast(0)
                y = dpToPx(90)
            }
        }
        addOverlay(startBtn, btnParams)
        referenceStartButtonView = startBtn
        bringToolbarToFront()
        showMacroToast("Drag the light-blue Reference Spot, then tap Start")
    }

    /** Remove the reference spot box + Start button. Called when the macro stops. */
    fun hideReferenceSpot() {
        referenceStartButtonView?.let { runCatching { windowManager.removeView(it) } }
        referenceStartButtonView = null
        referenceSpotBoxView?.let { runCatching { windowManager.removeView(it) } }
        referenceSpotBoxView = null
        referenceSpotBoxParams = null
    }

    // ---------------------------------------------------------------------
    // Points & Region setup overlays (D-pad, Attack, Tools, Save).
    // Coordinate accuracy uses getLocationOnScreen() — NEVER params.x/y.
    // ---------------------------------------------------------------------

    private fun centerOf(v: View): Point {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val w = if (v.width > 0) v.width else 1
        val h = if (v.height > 0) v.height else 1
        return Point(loc[0] + w / 2f, loc[1] + h / 2f)
    }

    private fun buildSetupButton(
        label: String,
        sizePx: Int,
        textSizeSp: Float,
        strokeColor: String = "#74BCFF"
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = textSizeSp
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#CC0E2C57"))
                setStroke(dpToPx(2), Color.parseColor(strokeColor))
            }
            layoutParams = RelativeLayout.LayoutParams(sizePx, sizePx)
        }

    private fun buildSetupHintLabel(text: String, textColor: String = "#B0FFFFFF"): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textColor))
            textSize = 8f
            setPadding(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
        }

    private data class FloatingHint(val view: TextView, val params: WindowManager.LayoutParams)

    private fun createFloatingHint(text: String, textColor: String = "#B0FFFFFF"): FloatingHint {
        val view = buildSetupHintLabel(text, textColor)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return FloatingHint(view, params)
    }

    private fun syncHintBelowAnchor(hint: FloatingHint, anchor: View, gapPx: Int = dpToPx(2)) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorW = anchor.width.coerceAtLeast(1)
        val anchorH = anchor.height.coerceAtLeast(1)
        hint.view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val hintW = hint.view.measuredWidth.coerceAtLeast(1)
        hint.params.x = loc[0] + (anchorW - hintW) / 2
        hint.params.y = loc[1] + anchorH + gapPx
        runCatching { windowManager.updateViewLayout(hint.view, hint.params) }
    }

    private fun addFloatingHint(hint: FloatingHint) {
        addOverlay(hint.view, hint.params)
        floatingHintOverlays.add(hint)
    }

    private fun clearFloatingHintOverlays() {
        floatingHintOverlays.forEach { runCatching { windowManager.removeView(it.view) } }
        floatingHintOverlays.clear()
    }

    /** A 4-direction D-pad cluster (used by both movement and directional attack). */
    private class DpadWidget(
        val root: RelativeLayout,
        val up: TextView,
        val down: TextView,
        val left: TextView,
        val right: TextView,
        val params: WindowManager.LayoutParams
    )

    /**
     * Builds a draggable 4-button D-pad. [cell] is the button size, [span] is the full cluster
     * size (larger span = directions spread further apart). [upCenter] is the saved center of the
     * UP button, used to anchor the whole cluster so buttons land where they were saved.
     */
    private fun buildDpadWidget(cell: Int, span: Int, upCenter: Point, strokeColor: String): DpadWidget {
        val root = RelativeLayout(context)
        val up = buildSetupButton("\u25B2", cell, 13f, strokeColor)
        val down = buildSetupButton("\u25BC", cell, 13f, strokeColor)
        val left = buildSetupButton("\u25C4", cell, 13f, strokeColor)
        val right = buildSetupButton("\u25BA", cell, 13f, strokeColor)
        up.layoutParams = RelativeLayout.LayoutParams(cell, cell).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL); addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        down.layoutParams = RelativeLayout.LayoutParams(cell, cell).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL); addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        left.layoutParams = RelativeLayout.LayoutParams(cell, cell).apply {
            addRule(RelativeLayout.ALIGN_PARENT_LEFT); addRule(RelativeLayout.CENTER_VERTICAL)
        }
        right.layoutParams = RelativeLayout.LayoutParams(cell, cell).apply {
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT); addRule(RelativeLayout.CENTER_VERTICAL)
        }
        root.addView(up); root.addView(down); root.addView(left); root.addView(right)
        val params = WindowManager.LayoutParams(
            span, span, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (upCenter.x - span / 2f).toInt()
            y = (upCenter.y - cell / 2f).toInt()
        }
        return DpadWidget(root, up, down, left, right, params)
    }

    fun showPointsSetupOverlays(savedLayout: SetupLayout, onSaved: (SetupLayout) -> Unit) {
        hidePointsSetupOverlays()
        pointsSetupOnSaved = onSaved
        // Only one setup overlay may be on screen at a time.
        hideZoomLevelOverlay()
        hideGameScreenAreaOverlay()

        val layout = layoutForSetupDisplay(savedLayout)
        val cell = dpToPx(36)
        val span = cell * 4   // wider cluster => directions spread further apart

        // ----- Movement D-pad (blue) -----
        val moveDpad = buildDpadWidget(cell, span, layout.dpadUp, "#74BCFF")

        // ----- Attack D-pad (red, directional swing) -----
        val atkDpad = buildDpadWidget(cell, span, layout.attackUp, "#FF9E9E")

        // ----- Tools: hotkey circles (Sword/Drill/Food) left of T1 & T2, then GRB + Taunt rows -----
        val toolSize = dpToPx(34)
        val hotkeySize = dpToPx(14)
        val hotkeyGapPx = 30
        val hotkeyToToolGapPx = 40
        val toolRowGapPx = 28
        fun toolButton(label: String): TextView = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#CC0E2C57"))
                setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
            }
            layoutParams = LinearLayout.LayoutParams(toolSize, toolSize)
        }
        fun hotkeyCircle(shortLabel: String, marginEndPx: Int = hotkeyGapPx): TextView = TextView(context).apply {
            text = shortLabel
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 7f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#AA1A3D5C"))
                setStroke(dpToPx(1), Color.parseColor("#88A8D5FF"))
            }
            layoutParams = LinearLayout.LayoutParams(hotkeySize, hotkeySize).apply {
                marginEnd = marginEndPx
            }
        }
        fun toolRowWithHotkeys(
            toolLabel: String,
            bottomMargin: Int
        ): Triple<LinearLayout, TextView, TextView> {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { this.bottomMargin = bottomMargin }
            }
            row.addView(hotkeyCircle("S"))
            row.addView(hotkeyCircle("D"))
            val foodHotkey = hotkeyCircle("F", hotkeyToToolGapPx)
            row.addView(foodHotkey)
            val tool = toolButton(toolLabel)
            row.addView(tool)
            return Triple(row, tool, foodHotkey)
        }
        val toolsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rowLeadingWidth = 3 * hotkeySize + 2 * hotkeyGapPx + hotkeyToToolGapPx
        val t1CenterInContainer = rowLeadingWidth + toolSize / 2f
        val (t1Row, t1, _) = toolRowWithHotkeys("T1", toolRowGapPx)
        val (t2Row, t2, _) = toolRowWithHotkeys("T2", toolRowGapPx)
        val grbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = toolRowGapPx }
        }
        val grb = toolButton("GRB")
        grbRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(rowLeadingWidth, 1)
        })
        grbRow.addView(grb)
        toolsContainer.addView(t1Row)
        toolsContainer.addView(t2Row)
        toolsContainer.addView(grbRow)
        val toolsP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (layout.tool1Button.x - t1CenterInContainer).toInt()
            y = (layout.tool1Button.y - toolSize / 2f).toInt()
        }

        val taunt = toolButton("TNT").apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#CC3D1A4A"))
                setStroke(dpToPx(2), Color.parseColor("#FF9EC4"))
            }
        }
        val tauntP = WindowManager.LayoutParams(
            toolSize, toolSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (layout.tauntButton.x - toolSize / 2f).toInt()
            y = (layout.tauntButton.y - toolSize / 2f).toInt()
        }
        val tauntHint = createFloatingHint("Taunt", "#FFB0D4")

        val fire = toolButton("FIR").apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#CC5C2E0A"))
                setStroke(dpToPx(2), Color.parseColor("#FFFF9800"))
            }
        }
        val fireCenter = layout.resolvedFireButton()
        val fireP = WindowManager.LayoutParams(
            toolSize, toolSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (fireCenter.x - toolSize / 2f).toInt()
            y = (fireCenter.y - toolSize / 2f).toInt()
        }
        val fireHint = createFloatingHint("Fire", "#FFFFB060")

        val buy = toolButton("BUY").apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#CC1B5E20"))
                setStroke(dpToPx(2), Color.parseColor("#FF81C784"))
            }
        }
        val buyCenter = layout.resolvedBuyButton()
            ?: Point(screenWidthPx() / 2f, screenHeightPx() / 2f - dpToPx(40))
        val buyP = WindowManager.LayoutParams(
            toolSize, toolSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (buyCenter.x - toolSize / 2f).toInt()
            y = (buyCenter.y - toolSize / 2f).toInt()
        }
        val buyHint = createFloatingHint("Buy", "#FF81C784")

        val ok = toolButton("OK").apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#CC0D47A1"))
                setStroke(dpToPx(2), Color.parseColor("#FF64B5F6"))
            }
        }
        val okCenter = layout.resolvedOkButton()
            ?: Point(screenWidthPx() / 2f, screenHeightPx() / 2f + dpToPx(40))
        val okP = WindowManager.LayoutParams(
            toolSize, toolSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (okCenter.x - toolSize / 2f).toInt()
            y = (okCenter.y - toolSize / 2f).toInt()
        }
        val okHint = createFloatingHint("OK", "#FF64B5F6")

        // ----- Drill detection box — size/position from scaled layout on phone, raw px on emulator -----
        val drillBox = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#44FF3B30"))
                setStroke(1, Color.parseColor("#FFFF3B30"))
            }
        }
        val hasDrill = layout.drillRight > layout.drillLeft && layout.drillBottom > layout.drillTop
        val drillP = WindowManager.LayoutParams(
            SetupLayout.DRILL_BOX_WIDTH_PX, SetupLayout.DRILL_BOX_HEIGHT_PX,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (hasDrill) {
                applyRegionToWindowParams(
                    DetectionRegion(layout.drillLeft, layout.drillTop, layout.drillRight, layout.drillBottom),
                    this
                )
            } else {
                val cx = screenWidthPx() / 2
                val cy = screenHeightPx() / 2
                x = cx - SetupLayout.DRILL_BOX_WIDTH_PX / 2
                y = cy - SetupLayout.DRILL_BOX_HEIGHT_PX / 2
            }
        }
        val drillHint = createFloatingHint("Drill detect", "#FFAAAA")

        // ----- Rock detection boxes (tight colour scan, one per side of player) -----
        val gameCx = if (layout.hasGameArea()) layout.gameCenterX() else screenWidthPx() / 2f
        val gameCy = if (layout.hasGameArea()) layout.gameCenterY() else screenHeightPx() / 2f
        data class RockBoxEntry(
            val side: String,
            val box: View,
            val params: WindowManager.LayoutParams,
            val hint: FloatingHint
        )
        fun makeRockBox(side: String): RockBoxEntry {
            val bounds = layout.rockRegion(side)
                ?: layout.defaultRockBounds(side, gameCx, gameCy)
            val box = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#55FF9800"))
                    setStroke(1, Color.parseColor("#FFFF9800"))
                }
            }
            val hintText = when (side.uppercase()) {
                "UP" -> "Rock up"
                "DOWN" -> "Rock down"
                "LEFT" -> "Rock left"
                "RIGHT" -> "Rock right"
                else -> "Rock"
            }
            val p = WindowManager.LayoutParams(
                SetupLayout.ROCK_BOX_SIZE_PX, SetupLayout.ROCK_BOX_SIZE_PX,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                applyRegionToWindowParams(bounds, this)
            }
            return RockBoxEntry(side, box, p, createFloatingHint(hintText, "#FFE0B060"))
        }
        val rockBoxes = listOf("UP", "DOWN", "LEFT", "RIGHT").map { makeRockBox(it) }
        fun regionFromView(view: View, params: WindowManager.LayoutParams): com.antijail.overlay.model.DetectionRegion {
            if (!view.isAttachedToWindow) {
                val w = params.width.coerceAtLeast(1)
                val h = params.height.coerceAtLeast(1)
                return com.antijail.overlay.model.DetectionRegion(
                    params.x.toFloat(), params.y.toFloat(),
                    (params.x + w).toFloat(), (params.y + h).toFloat()
                )
            }
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val w = (if (view.width > 0) view.width else params.width).coerceAtLeast(1)
            val h = (if (view.height > 0) view.height else params.height).coerceAtLeast(1)
            return com.antijail.overlay.model.DetectionRegion(
                loc[0].toFloat(), loc[1].toFloat(),
                (loc[0] + w).toFloat(), (loc[1] + h).toFloat()
            )
        }

        val spiderBounds = layout.spiderAreaRegion()
            ?: layout.defaultSpiderAreaBounds(gameCx, gameCy)
        val healthBounds = layout.spiderHealthRegion()
            ?: layout.defaultSpiderHealthBounds(gameCx, gameCy)
        val noMineBounds = layout.noMineTextRegion()
            ?: layout.defaultNoMineTextBounds(gameCx, gameCy)
        val spiderBox = object : View(context) {
            private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFF0000")
                strokeWidth = dpToPx(3).toFloat()
                style = Paint.Style.STROKE
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                canvas.drawLine(0f, 0f, w, h, xPaint)
                canvas.drawLine(0f, h, w, 0f, xPaint)
            }
        }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#6600FF00"))
                setStroke(dpToPx(2), Color.parseColor("#AA00FF00"))
            }
        }
        val spiderP = WindowManager.LayoutParams(
            SetupLayout.SPIDER_AREA_SIZE_PX, SetupLayout.SPIDER_AREA_SIZE_PX,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyRegionToWindowParams(spiderBounds, this)
        }
        val spiderHint = createFloatingHint("Spider colours", "#B0FFB0")

        val healthBox = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#664FC3F7"))
                setStroke(1, Color.parseColor("#AA4FC3F7"))
            }
        }
        val healthP = WindowManager.LayoutParams(
            SetupLayout.SPIDER_HEALTH_WIDTH_PX, SetupLayout.SPIDER_HEALTH_HEIGHT_PX,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyRegionToWindowParams(healthBounds, this)
        }
        val healthHint = createFloatingHint("Health hit", "#AAE3F2FD")

        val noMineBox = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#66FFD54F"))
                setStroke(1, Color.parseColor("#AAFFD54F"))
            }
        }
        val noMineP = WindowManager.LayoutParams(
            SetupLayout.NO_MINE_TEXT_WIDTH_PX, SetupLayout.NO_MINE_TEXT_HEIGHT_PX,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyRegionToWindowParams(noMineBounds, this)
        }
        val noMineHint = createFloatingHint("No mine text", "#FFE0A020")

        // ----- Fish stop button (Fishing Detections filter) -----
        val fishStop = toolButton("STP").apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#CC4A148C"))
                setStroke(dpToPx(2), Color.parseColor("#FFCE93D8"))
            }
        }
        val fishStopCenter = layout.resolvedFishStopButton()
        val fishStopP = WindowManager.LayoutParams(
            toolSize, toolSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (fishStopCenter.x - toolSize / 2f).toInt()
            y = (fishStopCenter.y - toolSize / 2f).toInt()
        }
        val fishStopHint = createFloatingHint("Fish stop", "#FFE1BEE7")

        // ----- Pirate HP detection (smaller than drill box) -----
        val fishHpBounds = layout.fishPirateHpRegion()
            ?: layout.defaultFishPirateHpBounds(gameCx, gameCy)
        val fishPirateHpBox = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#44AB47BC"))
                setStroke(1, Color.parseColor("#FFAB47BC"))
            }
        }
        val fishPirateHpP = WindowManager.LayoutParams(
            SetupLayout.FISH_PIRATE_HP_WIDTH_PX, SetupLayout.FISH_PIRATE_HP_HEIGHT_PX,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyRegionToWindowParams(fishHpBounds, this)
        }
        val fishPirateHpHint = createFloatingHint("Pirate HP", "#FFE1BEE7")

        fun savePointsSetup() {
            fun dpadUpPoint(widget: DpadWidget): Point =
                Point(widget.params.x + span / 2f, widget.params.y + cell / 2f)
            fun dpadDownPoint(widget: DpadWidget): Point =
                Point(widget.params.x + span / 2f, widget.params.y + span - cell / 2f)
            fun dpadLeftPoint(widget: DpadWidget): Point =
                Point(widget.params.x + cell / 2f, widget.params.y + span / 2f)
            fun dpadRightPoint(widget: DpadWidget): Point =
                Point(widget.params.x + span - cell / 2f, widget.params.y + span / 2f)
            fun centerFromViewOrParams(view: View, params: WindowManager.LayoutParams): Point {
                if (!view.isAttachedToWindow) {
                    return Point(params.x + params.width / 2f, params.y + params.height / 2f)
                }
                return centerOf(view)
            }
            val t2CenterY = toolSize + toolRowGapPx + toolSize / 2f
            val grbCenterY = 2 * (toolSize + toolRowGapPx) + toolSize / 2f
            val foodCenterX = 2 * hotkeySize + 2 * hotkeyGapPx + hotkeySize / 2f
            val drillRegion = regionFromView(drillBox, drillP)
            val rockUp = regionFromView(rockBoxes[0].box, rockBoxes[0].params)
            val rockDown = regionFromView(rockBoxes[1].box, rockBoxes[1].params)
            val rockLeft = regionFromView(rockBoxes[2].box, rockBoxes[2].params)
            val rockRight = regionFromView(rockBoxes[3].box, rockBoxes[3].params)
            val spiderRegion = regionFromView(spiderBox, spiderP)
            val healthRegion = regionFromView(healthBox, healthP)
            val noMineRegion = regionFromView(noMineBox, noMineP)
            val fishPirateHpRegion = regionFromView(fishPirateHpBox, fishPirateHpP)
            val newLayout = setupLayoutProvider().copy(
                dpadUp = dpadUpPoint(moveDpad),
                dpadDown = dpadDownPoint(moveDpad),
                dpadLeft = dpadLeftPoint(moveDpad),
                dpadRight = dpadRightPoint(moveDpad),
                attackUp = dpadUpPoint(atkDpad),
                attackDown = dpadDownPoint(atkDpad),
                attackLeft = dpadLeftPoint(atkDpad),
                attackRight = dpadRightPoint(atkDpad),
                tool1Button = Point(toolsP.x + t1CenterInContainer, toolsP.y + toolSize / 2f),
                tool2Button = Point(toolsP.x + t1CenterInContainer, toolsP.y + t2CenterY),
                foodButton = Point(
                    toolsP.x + foodCenterX,
                    toolsP.y + hotkeySize / 2f
                ),
                grabButton = Point(toolsP.x + t1CenterInContainer, toolsP.y + grbCenterY),
                fireButton = centerFromViewOrParams(fire, fireP),
                tauntButton = centerFromViewOrParams(taunt, tauntP),
                buyButton = centerFromViewOrParams(buy, buyP),
                okButton = centerFromViewOrParams(ok, okP),
                drillLeft = drillRegion.left,
                drillTop = drillRegion.top,
                drillRight = drillRegion.right,
                drillBottom = drillRegion.bottom,
                rockUpLeft = rockUp.left, rockUpTop = rockUp.top, rockUpRight = rockUp.right, rockUpBottom = rockUp.bottom,
                rockDownLeft = rockDown.left, rockDownTop = rockDown.top, rockDownRight = rockDown.right, rockDownBottom = rockDown.bottom,
                rockLeftLeft = rockLeft.left, rockLeftTop = rockLeft.top, rockLeftRight = rockLeft.right, rockLeftBottom = rockLeft.bottom,
                rockRightLeft = rockRight.left, rockRightTop = rockRight.top, rockRightRight = rockRight.right, rockRightBottom = rockRight.bottom,
                spiderAreaLeft = spiderRegion.left,
                spiderAreaTop = spiderRegion.top,
                spiderAreaRight = spiderRegion.right,
                spiderAreaBottom = spiderRegion.bottom,
                spiderHealthLeft = healthRegion.left,
                spiderHealthTop = healthRegion.top,
                spiderHealthRight = healthRegion.right,
                spiderHealthBottom = healthRegion.bottom,
                noMineTextLeft = noMineRegion.left,
                noMineTextTop = noMineRegion.top,
                noMineTextRight = noMineRegion.right,
                noMineTextBottom = noMineRegion.bottom,
                fishStopButton = centerFromViewOrParams(fishStop, fishStopP),
                fishPirateHpLeft = fishPirateHpRegion.left,
                fishPirateHpTop = fishPirateHpRegion.top,
                fishPirateHpRight = fishPirateHpRegion.right,
                fishPirateHpBottom = fishPirateHpRegion.bottom
            )
            onSaved(newLayout)
            hidePointsSetupOverlays()
            showMacroToast("Setup saved!")
        }

        // ----- Cancel + Save (centered, draggable together) -----
        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancelBtn = buildActionChip("Cancel", isPrimary = false) {
            hidePointsSetupOverlays()
            showSetupMenuOverlay()
        }
        val saveBtn = buildActionChip("\u2714 SAVE", isPrimary = true) { savePointsSetup() }
        actionBar.addView(cancelBtn)
        actionBar.addView(buildHSpacer(12))
        actionBar.addView(saveBtn)
        val actionBarP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx() - dpToPx(260)) / 2
            y = screenHeightPx() - dpToPx(100)
        }

        val t1Hint = createFloatingHint("Tool 1", "#B0D4FF")
        val t2Hint = createFloatingHint("Tool 2", "#B0D4FF")
        val grbHint = createFloatingHint("Grab", "#B0D4FF")
        fun syncToolHints() {
            syncHintBelowAnchor(t1Hint, t1)
            syncHintBelowAnchor(t2Hint, t2)
            syncHintBelowAnchor(grbHint, grb)
        }
        fun syncMiningDetectionHints() {
            syncHintBelowAnchor(spiderHint, spiderBox)
            syncHintBelowAnchor(healthHint, healthBox)
            syncHintBelowAnchor(noMineHint, noMineBox)
        }

        data class SetupOverlayItem(
            val view: View,
            val params: WindowManager.LayoutParams,
            val hints: List<FloatingHint> = emptyList(),
            val syncHints: (() -> Unit)? = null
        )

        val setupAttachedViews = mutableSetOf<View>()
        fun attachSetupView(view: View, params: WindowManager.LayoutParams) {
            if (setupAttachedViews.add(view)) addOverlay(view, params)
        }
        fun detachSetupView(view: View) {
            if (setupAttachedViews.remove(view)) runCatching { windowManager.removeView(view) }
        }
        fun setGroupVisible(items: List<SetupOverlayItem>, visible: Boolean) {
            items.forEach { item ->
                if (visible) {
                    attachSetupView(item.view, item.params)
                    item.hints.forEach { attachSetupView(it.view, it.params) }
                    item.view.post { item.syncHints?.invoke() }
                } else {
                    item.hints.forEach { detachSetupView(it.view) }
                    detachSetupView(item.view)
                }
            }
        }

        fun buildSetupFilterPanel(
            onDefaultButtonsChanged: (Boolean) -> Unit,
            onMiningButtonsChanged: (Boolean) -> Unit,
            onMiningDetectionsChanged: (Boolean) -> Unit,
            onFishingDetectionsChanged: (Boolean) -> Unit
        ): LinearLayout {
            fun filterSwitch(label: String, enabled: Boolean, onChanged: (Boolean) -> Unit): Switch =
                Switch(context).apply {
                    text = label
                    isChecked = enabled
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
                    setOnCheckedChangeListener { _, checked -> onChanged(checked) }
                }
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(14).toFloat()
                    setColor(Color.parseColor("#DD0E2C57"))
                    setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
                }
                addView(TextView(context).apply {
                    text = "Setup filters"
                    setTextColor(Color.parseColor("#B0D4FF"))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
                addView(filterSwitch("Default Buttons", true, onDefaultButtonsChanged))
                addView(filterSwitch("Mining Buttons", false, onMiningButtonsChanged))
                addView(filterSwitch("Mining Detections", false, onMiningDetectionsChanged))
                addView(filterSwitch("Fishing Detections", false, onFishingDetectionsChanged))
                addView(filterSwitch("Digging Detections", false) { })
            }
        }

        // Drag the whole clusters to position them.
        makeDraggable(moveDpad.root, moveDpad.params)
        makeDraggable(atkDpad.root, atkDpad.params)
        makeDraggable(toolsContainer, toolsP) { syncToolHints() }
        makeDraggable(drillBox, drillP) { syncHintBelowAnchor(drillHint, drillBox) }
        makeDraggable(taunt, tauntP) { syncHintBelowAnchor(tauntHint, taunt) }
        makeDraggable(fire, fireP) { syncHintBelowAnchor(fireHint, fire) }
        makeDraggable(buy, buyP) { syncHintBelowAnchor(buyHint, buy) }
        makeDraggable(ok, okP) { syncHintBelowAnchor(okHint, ok) }
        rockBoxes.forEach { entry ->
            makeDraggable(entry.box, entry.params) { syncHintBelowAnchor(entry.hint, entry.box) }
        }
        makeDraggable(spiderBox, spiderP) { syncHintBelowAnchor(spiderHint, spiderBox) }
        makeDraggable(healthBox, healthP) { syncHintBelowAnchor(healthHint, healthBox) }
        makeDraggable(noMineBox, noMineP) { syncHintBelowAnchor(noMineHint, noMineBox) }
        makeDraggable(fishStop, fishStopP) { syncHintBelowAnchor(fishStopHint, fishStop) }
        makeDraggable(fishPirateHpBox, fishPirateHpP) { syncHintBelowAnchor(fishPirateHpHint, fishPirateHpBox) }
        makeDraggable(actionBar, actionBarP)

        val defaultButtonItems = listOf(
            SetupOverlayItem(moveDpad.root, moveDpad.params),
            SetupOverlayItem(atkDpad.root, atkDpad.params),
            SetupOverlayItem(toolsContainer, toolsP, listOf(t1Hint, t2Hint, grbHint)) { syncToolHints() }
        )
        val miningButtonItems = buildList {
            add(SetupOverlayItem(drillBox, drillP, listOf(drillHint)) { syncHintBelowAnchor(drillHint, drillBox) })
            add(SetupOverlayItem(taunt, tauntP, listOf(tauntHint)) { syncHintBelowAnchor(tauntHint, taunt) })
            add(SetupOverlayItem(fire, fireP, listOf(fireHint)) { syncHintBelowAnchor(fireHint, fire) })
            add(SetupOverlayItem(buy, buyP, listOf(buyHint)) { syncHintBelowAnchor(buyHint, buy) })
            add(SetupOverlayItem(ok, okP, listOf(okHint)) { syncHintBelowAnchor(okHint, ok) })
            rockBoxes.forEach { entry ->
                add(SetupOverlayItem(entry.box, entry.params, listOf(entry.hint)) {
                    syncHintBelowAnchor(entry.hint, entry.box)
                })
            }
        }
        val miningDetectionItems = listOf(
            SetupOverlayItem(spiderBox, spiderP, listOf(spiderHint)) { syncHintBelowAnchor(spiderHint, spiderBox) },
            SetupOverlayItem(healthBox, healthP, listOf(healthHint)) { syncHintBelowAnchor(healthHint, healthBox) },
            SetupOverlayItem(noMineBox, noMineP, listOf(noMineHint)) { syncHintBelowAnchor(noMineHint, noMineBox) }
        )
        val fishingDetectionItems = listOf(
            SetupOverlayItem(fishStop, fishStopP, listOf(fishStopHint)) {
                syncHintBelowAnchor(fishStopHint, fishStop)
            },
            SetupOverlayItem(fishPirateHpBox, fishPirateHpP, listOf(fishPirateHpHint)) {
                syncHintBelowAnchor(fishPirateHpHint, fishPirateHpBox)
            }
        )
        floatingHintOverlays.addAll(
            listOf(t1Hint, t2Hint, grbHint, tauntHint, drillHint, fireHint, buyHint, okHint) +
                rockBoxes.map { it.hint } +
                listOf(spiderHint, healthHint, noMineHint, fishStopHint, fishPirateHpHint)
        )

        setGroupVisible(defaultButtonItems, true)
        setGroupVisible(miningButtonItems, false)
        setGroupVisible(miningDetectionItems, false)
        setGroupVisible(fishingDetectionItems, false)
        dpadOverlayView = moveDpad.root; dpadParams = moveDpad.params
        attackOverlayView = atkDpad.root; attackParams = atkDpad.params
        toolsOverlayView = toolsContainer; toolsParams = toolsP
        drillSetupBoxView = drillBox
        tauntSetupBoxView = taunt
        fireSetupBoxView = fire
        buySetupBoxView = buy
        okSetupBoxView = ok
        spiderAreaBoxView = spiderBox
        spiderHealthBoxView = healthBox
        noMineTextBoxView = noMineBox
        fishStopSetupView = fishStop
        fishPirateHpBoxView = fishPirateHpBox
        rockSetupBoxViews.clear()
        rockBoxes.forEach { entry ->
            rockSetupBoxViews.add(entry.box)
        }
        addOverlay(actionBar, actionBarP)
        setupActionBarView = actionBar
        setupActionBarParams = actionBarP
        val filterPanel = buildSetupFilterPanel(
            onDefaultButtonsChanged = { setGroupVisible(defaultButtonItems, it) },
            onMiningButtonsChanged = { setGroupVisible(miningButtonItems, it) },
            onMiningDetectionsChanged = { setGroupVisible(miningDetectionItems, it) },
            onFishingDetectionsChanged = { setGroupVisible(fishingDetectionItems, it) }
        )
        val filterP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(50)
            y = dpToPx(100)
        }
        makeDraggable(filterPanel, filterP)
        addOverlay(filterPanel, filterP)
        setupFilterView = filterPanel
        setupFilterParams = filterP
        actionBar.post {
            val w = actionBar.width.coerceAtLeast(dpToPx(220))
            actionBarP.x = (screenWidthPx() - w) / 2
            runCatching { windowManager.updateViewLayout(actionBar, actionBarP) }
        }
        toolsContainer.post {
            syncToolHints()
            syncHintBelowAnchor(drillHint, drillBox)
            syncHintBelowAnchor(tauntHint, taunt)
            syncHintBelowAnchor(fireHint, fire)
            syncHintBelowAnchor(buyHint, buy)
            syncHintBelowAnchor(okHint, ok)
            rockBoxes.forEach { syncHintBelowAnchor(it.hint, it.box) }
            syncHintBelowAnchor(fishStopHint, fishStop)
            syncHintBelowAnchor(fishPirateHpHint, fishPirateHpBox)
            syncMiningDetectionHints()
        }
        setActiveSetupSaveAction(SetupTarget.POINTS_AND_REGION) { savePointsSetup() }
        showMacroToast("Drag to position. Hint labels are overlays only — SAVE when done.")
        bringToolbarToFront()
    }

    fun hidePointsSetupOverlays() {
        listOf(
            dpadOverlayView, attackOverlayView, toolsOverlayView,
            drillSetupBoxView, tauntSetupBoxView, fireSetupBoxView, buySetupBoxView, okSetupBoxView,
            spiderAreaBoxView, spiderHealthBoxView, noMineTextBoxView,
            fishStopSetupView, fishPirateHpBoxView,
            setupActionBarView, setupFilterView, saveOverlayView
        )
            .forEach { it?.let { v -> runCatching { windowManager.removeView(v) } } }
        rockSetupBoxViews.forEach { runCatching { windowManager.removeView(it) } }
        rockSetupBoxViews.clear()
        clearFloatingHintOverlays()
        dpadOverlayView = null; attackOverlayView = null; toolsOverlayView = null
        drillSetupBoxView = null; tauntSetupBoxView = null; fireSetupBoxView = null
        buySetupBoxView = null; okSetupBoxView = null
        spiderAreaBoxView = null; spiderHealthBoxView = null; noMineTextBoxView = null
        fishStopSetupView = null; fishPirateHpBoxView = null
        setupActionBarView = null; setupFilterView = null; saveOverlayView = null
        dpadParams = null; attackParams = null; toolsParams = null; saveParams = null
        setupActionBarParams = null; setupFilterParams = null
        clearActiveSetupSaveAction(SetupTarget.POINTS_AND_REGION)
        pointsSetupOnSaved = null
    }

    // ---------------------------------------------------------------------
    // Zoom Level — draggable danger sign overlay + movable control panel + live detection preview.
    // ---------------------------------------------------------------------

    fun onDisplayChanged() {
        mainHandler.post {
            captureServiceRefreshProvider?.invoke()
            if (dpadOverlayView != null) {
                val onSaved = pointsSetupOnSaved ?: return@post
                hidePointsSetupOverlays()
                showPointsSetupOverlays(setupLayoutProvider(), onSaved)
            } else if (zoomSignView != null) {
                hideZoomLevelOverlay()
                showZoomLevelOverlay()
            }
            if (toolbarView != null) rebuildToolbar()
        }
    }

    /** Optional hook so display rotation can refresh screen capture before relayouting overlays. */
    var captureServiceRefreshProvider: (() -> Unit)? = null

    private fun layoutForSetupDisplay(base: SetupLayout): SetupLayout {
        val w = screenWidthPx()
        val h = screenHeightPx()
        return LayoutScaler.scaleForDevice(base, w, h)
    }

    private fun layoutForZoomPreview(base: SetupLayout, detectionScale: Float): SetupLayout =
        LayoutScaler.scaleForPreview(
            base,
            screenWidthPx(),
            screenHeightPx(),
            detectionScale
        )

    private fun applyRegionToWindowParams(region: DetectionRegion, params: WindowManager.LayoutParams) {
        val w = (region.right - region.left).toInt().coerceAtLeast(4)
        val h = (region.bottom - region.top).toInt().coerceAtLeast(4)
        params.width = w
        params.height = h
        params.x = region.left.toInt().coerceAtLeast(0)
        params.y = region.top.toInt().coerceAtLeast(0)
    }

    private data class ZoomPreviewSlot(
        val view: View,
        val params: WindowManager.LayoutParams,
        val category: ZoomPreviewCategory,
        val regionOf: (SetupLayout) -> DetectionRegion?
    )

    private enum class ZoomPreviewCategory {
        MINING, DIGGING, FISHING
    }

    private val zoomPreviewSlots = mutableListOf<ZoomPreviewSlot>()

    private fun createZoomPreviewBox(fillColor: String, strokeColor: String): View =
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor(fillColor))
                setStroke(1, Color.parseColor(strokeColor))
            }
        }

    private fun clearZoomPreviewBoxes() {
        for (slot in zoomPreviewSlots) {
            runCatching { windowManager.removeView(slot.view) }
        }
        zoomPreviewSlots.clear()
        zoomPreviewBoxes.clear()
    }

    private fun buildZoomPreviewBoxes(baseLayout: SetupLayout, detectionScale: Float) {
        clearZoomPreviewBoxes()
        val specs = listOf(
            ZoomPreviewSpec("#66FF3B30", "#FFFF3B30", ZoomPreviewCategory.MINING) { l: SetupLayout ->
                if (l.drillRight > l.drillLeft && l.drillBottom > l.drillTop) {
                    DetectionRegion(l.drillLeft, l.drillTop, l.drillRight, l.drillBottom)
                } else null
            },
            ZoomPreviewSpec("#55FF9800", "#FFFF9800", ZoomPreviewCategory.MINING) { l -> l.rockRegion("UP") },
            ZoomPreviewSpec("#55FF9800", "#FFFF9800", ZoomPreviewCategory.MINING) { l -> l.rockRegion("DOWN") },
            ZoomPreviewSpec("#55FF9800", "#FFFF9800", ZoomPreviewCategory.MINING) { l -> l.rockRegion("LEFT") },
            ZoomPreviewSpec("#55FF9800", "#FFFF9800", ZoomPreviewCategory.MINING) { l -> l.rockRegion("RIGHT") },
            ZoomPreviewSpec("#6600FF00", "#AA00FF00", ZoomPreviewCategory.MINING) { l -> l.spiderAreaRegion() },
            ZoomPreviewSpec("#664FC3F7", "#AA4FC3F7", ZoomPreviewCategory.MINING) { l -> l.spiderHealthRegion() },
            ZoomPreviewSpec("#66FFD54F", "#AAFFD54F", ZoomPreviewCategory.MINING) { l -> l.noMineTextRegion() },
            ZoomPreviewSpec("#66FF5722", "#FFFF5722", ZoomPreviewCategory.MINING) { l -> l.fireAreaRegion() },
            ZoomPreviewSpec("#44AB47BC", "#FFAB47BC", ZoomPreviewCategory.FISHING) { l -> l.fishPirateHpRegion() }
        )
        for (spec in specs) {
            val box = createZoomPreviewBox(spec.fill, spec.stroke)
            val params = WindowManager.LayoutParams(
                1, 1, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            addOverlay(box, params)
            zoomPreviewSlots.add(ZoomPreviewSlot(box, params, spec.category, spec.regionOf))
            zoomPreviewBoxes.add(box to params)
        }
        refreshZoomPreviewBoxes(baseLayout, detectionScale, showMining = false, showDigging = false, showFishing = false)
    }

    private data class ZoomPreviewSpec(
        val fill: String,
        val stroke: String,
        val category: ZoomPreviewCategory,
        val regionOf: (SetupLayout) -> DetectionRegion?
    )

    private fun refreshZoomPreviewBoxes(
        baseLayout: SetupLayout,
        detectionScale: Float,
        showMining: Boolean,
        showDigging: Boolean,
        showFishing: Boolean
    ) {
        val scaled = layoutForZoomPreview(baseLayout, detectionScale)
        for (slot in zoomPreviewSlots) {
            val categoryVisible = when (slot.category) {
                ZoomPreviewCategory.MINING -> showMining
                ZoomPreviewCategory.DIGGING -> showDigging
                ZoomPreviewCategory.FISHING -> showFishing
            }
            val region = slot.regionOf(scaled)
            if (!categoryVisible || region == null) {
                slot.view.visibility = View.GONE
                continue
            }
            slot.view.visibility = View.VISIBLE
            applyRegionToWindowParams(region, slot.params)
            runCatching { windowManager.updateViewLayout(slot.view, slot.params) }
        }
    }

    private fun buildZoomFilterPanel(
        onZoomSignChanged: (Boolean) -> Unit,
        onThriftShopChanged: (Boolean) -> Unit,
        onMiningChanged: (Boolean) -> Unit,
        onDiggingChanged: (Boolean) -> Unit,
        onFishingChanged: (Boolean) -> Unit
    ): LinearLayout {
        fun filterSwitch(label: String, enabled: Boolean, onChanged: (Boolean) -> Unit): Switch =
            Switch(context).apply {
                text = label
                isChecked = enabled
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                setOnCheckedChangeListener { _, checked -> onChanged(checked) }
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#DD0E2C57"))
                setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
            }
            addView(TextView(context).apply {
                text = "Zoom filters"
                setTextColor(Color.parseColor("#B0CFFF"))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(filterSwitch("Zoom Level (danger sign)", true, onZoomSignChanged))
            addView(filterSwitch("Thrift shop sign", false, onThriftShopChanged))
            addView(filterSwitch("Mining detections", false, onMiningChanged))
            addView(filterSwitch("Digging detections", false, onDiggingChanged))
            addView(filterSwitch("Fishing detections", false, onFishingChanged))
        }
    }

    fun showZoomLevelOverlay() {
        hideZoomLevelOverlay()
        hidePointsSetupOverlays()
        hideGameScreenAreaOverlay()

        val baseLayout = setupLayoutProvider()
        var detectionScale = uiPreferencesProvider().detectionScale.coerceIn(0.5f, 2.0f)
        val sw = screenWidthPx()
        val sh = screenHeightPx()

        // ----- Draggable danger sign at 100% — visual reference only, not scaled by slider -----
        val (signW, signH) = LayoutScaler.zoomSignSizePx(sw, sh)
        val sign = ImageView(context).apply {
            setImageResource(R.drawable.zoom_level_image)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.7f
        }
        val signP = WindowManager.LayoutParams(
            signW, signH, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - signW) / 2
            y = (sh - signH) / 2
        }
        clampWindowParamsToScreen(signP, sign)
        makeDraggable(sign, signP)
        addOverlay(sign, signP)
        zoomSignView = sign
        zoomSignParams = signP

        // ----- Draggable thrift shop template — scales with detection slider -----
        val (thriftW, thriftH) = LayoutScaler.thriftShopSizePx(sw, sh, detectionScale)
        val thrift = ImageView(context).apply {
            setImageResource(R.drawable.thriftshop)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.65f
        }
        val thriftP = WindowManager.LayoutParams(
            thriftW, thriftH, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - thriftW) / 2
            y = (sh - thriftH) / 2 + dpToPx(80)
        }
        clampWindowParamsToScreen(thriftP, thrift)
        makeDraggable(thrift, thriftP)
        addOverlay(thrift, thriftP)
        zoomThriftView = thrift
        zoomThriftParams = thriftP

        buildZoomPreviewBoxes(baseLayout, detectionScale)

        var showDangerSign = true
        var showThriftShop = false
        var showMiningPreviews = false
        var showDiggingPreviews = false
        var showFishingPreviews = false

        var scaleLabel: TextView? = null

        fun applyFilterVisibility() {
            sign.visibility = if (showDangerSign) View.VISIBLE else View.GONE
            thrift.visibility = if (showThriftShop) View.VISIBLE else View.GONE
            refreshZoomPreviewBoxes(
                baseLayout,
                detectionScale,
                showMiningPreviews,
                showDiggingPreviews,
                showFishingPreviews
            )
        }

        fun applyDetectionScale(scale: Float) {
            detectionScale = scale.coerceIn(0.5f, 2.0f)
            val (tw, th) = LayoutScaler.thriftShopSizePx(sw, sh, detectionScale)
            thriftP.width = tw
            thriftP.height = th
            clampWindowParamsToScreen(thriftP, thrift)
            runCatching { windowManager.updateViewLayout(thrift, thriftP) }
            refreshZoomPreviewBoxes(
                baseLayout,
                detectionScale,
                showMiningPreviews,
                showDiggingPreviews,
                showFishingPreviews
            )
            scaleLabel?.text = "Detection scale: ${(detectionScale * 100).toInt()}%"
        }

        val title = TextView(context).apply {
            text = "Zoom calibration"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B0CFFF"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        scaleLabel = TextView(context).apply {
            text = "Detection scale: ${(detectionScale * 100).toInt()}%"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E4EFFF"))
            textSize = 11f
        }
        val slider = SeekBar(context).apply {
            max = 150
            progress = ((detectionScale * 100f).toInt() - 50).coerceIn(0, 150)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(220),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyDetectionScale((progress + 50) / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val hint = TextView(context).apply {
            text = "Slider scales detection areas & image templates only.\nButtons stay fixed from Points setup."
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B0CFFF"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(240),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
        }
        fun completeZoomSetup() {
            onUiPreferencesChanged(uiPreferencesProvider().copy(detectionScale = detectionScale))
            onSetupStepCompleted(SetupTarget.ZOOM_LEVEL)
            hideZoomLevelOverlay()
            showMacroToast("Zoom scale saved (${(detectionScale * 100).toInt()}%)")
        }
        val done = TextView(context).apply {
            text = "✔ Done"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#CC0E2C57"))
                setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
            }
            setOnClickListener { completeZoomSetup() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#EE0A1324"))
                setStroke(dpToPx(1), Color.parseColor("#4474BCFF"))
            }
            addView(title)
            addView(scaleLabel)
            addView(slider)
            addView(hint)
            addView(done)
        }
        val panelP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dpToPx(260)
            y = dpToPx(16)
        }
        makeDraggable(panel, panelP)
        addOverlay(panel, panelP)
        zoomControlPanelView = panel
        zoomControlPanelParams = panelP

        val filterPanel = buildZoomFilterPanel(
            onZoomSignChanged = {
                showDangerSign = it
                applyFilterVisibility()
            },
            onThriftShopChanged = {
                showThriftShop = it
                applyFilterVisibility()
            },
            onMiningChanged = {
                showMiningPreviews = it
                applyFilterVisibility()
            },
            onDiggingChanged = {
                showDiggingPreviews = it
                applyFilterVisibility()
            },
            onFishingChanged = {
                showFishingPreviews = it
                applyFilterVisibility()
            }
        )
        val filterP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(16)
        }
        makeDraggable(filterPanel, filterP)
        addOverlay(filterPanel, filterP)
        zoomFilterView = filterPanel
        zoomFilterParams = filterP

        setActiveSetupSaveAction(SetupTarget.ZOOM_LEVEL) { completeZoomSetup() }
        showMacroToast("Drag signs to match game — slider scales detections only")
        bringToolbarToFront()
    }

    fun hideZoomLevelOverlay() {
        zoomSignView?.let { runCatching { windowManager.removeView(it) } }
        zoomSignView = null
        zoomSignParams = null
        zoomThriftView?.let { runCatching { windowManager.removeView(it) } }
        zoomThriftView = null
        zoomThriftParams = null
        zoomControlPanelView?.let { runCatching { windowManager.removeView(it) } }
        zoomControlPanelView = null
        zoomControlPanelParams = null
        zoomFilterView?.let { runCatching { windowManager.removeView(it) } }
        zoomFilterView = null
        zoomFilterParams = null
        clearZoomPreviewBoxes()
        clearActiveSetupSaveAction(SetupTarget.ZOOM_LEVEL)
    }

    // ---------------------------------------------------------------------
    // Game Screen Area overlay (draggable + corner-resizable rectangle).
    // ---------------------------------------------------------------------

    fun showGameScreenAreaOverlay(savedLayout: SetupLayout, onSaved: (SetupLayout) -> Unit) {
        hideGameScreenAreaOverlay()
        // Only one setup overlay may be on screen at a time.
        hidePointsSetupOverlays()
        hideZoomLevelOverlay()

        val sw = screenWidthPx()
        val sh = screenHeightPx()
        val hasSaved = savedLayout.gameAreaRight > savedLayout.gameAreaLeft &&
            savedLayout.gameAreaBottom > savedLayout.gameAreaTop
        var left = if (hasSaved) savedLayout.gameAreaLeft else sw * 0.2f
        var top = if (hasSaved) savedLayout.gameAreaTop else sh * 0.2f
        var right = if (hasSaved) savedLayout.gameAreaRight else sw * 0.8f
        var bottom = if (hasSaved) savedLayout.gameAreaBottom else sh * 0.8f
        val minSize = dpToPx(80).toFloat()
        val handle = dpToPx(24)

        val box = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(4), Color.parseColor("#FF00FF00"))
            }
        }
        fun makeHandle(): View = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#FF00FF00"))
                setStroke(dpToPx(1), Color.parseColor("#FFFFFFFF"))
            }
        }
        val hTL = makeHandle()
        val hTR = makeHandle()
        val hBL = makeHandle()
        val hBR = makeHandle()
        val handles = listOf(hTL, hTR, hBL, hBR)
        val handleParams = handles.map { boundedOverlayParams(0, 0, handle, handle) }

        val gameAreaP = WindowManager.LayoutParams(
            1,
            1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val gameAreaHint = buildSetupHintLabel("Game screen", "#B0FF88")
        val gameAreaHintP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        fun relayout() {
            val boxW = (right - left).toInt().coerceAtLeast(1)
            val boxH = (bottom - top).toInt().coerceAtLeast(1)
            gameAreaP.x = left.toInt()
            gameAreaP.y = top.toInt()
            gameAreaP.width = boxW
            gameAreaP.height = boxH
            fun placeHandle(index: Int, cx: Float, cy: Float) {
                val params = handleParams[index]
                params.x = (cx - handle / 2f).toInt()
                params.y = (cy - handle / 2f).toInt()
                runCatching { windowManager.updateViewLayout(handles[index], params) }
            }
            runCatching { windowManager.updateViewLayout(box, gameAreaP) }
            placeHandle(0, left, top)
            placeHandle(1, right, top)
            placeHandle(2, left, bottom)
            placeHandle(3, right, bottom)
            gameAreaHint.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val hintW = gameAreaHint.measuredWidth.coerceAtLeast(1)
            gameAreaHintP.x = ((left + right) / 2f - hintW / 2f).toInt()
            gameAreaHintP.y = (bottom + dpToPx(4)).toInt()
            runCatching { windowManager.updateViewLayout(gameAreaHint, gameAreaHintP) }
        }

        fun handleDrag(v: View, isLeft: Boolean, isTop: Boolean) {
            var sx = 0f; var sy = 0f; var sLeft = 0f; var sTop = 0f; var sRight = 0f; var sBottom = 0f
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY
                        sLeft = left; sTop = top; sRight = right; sBottom = bottom; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx; val dy = e.rawY - sy
                        if (isLeft) left = (sLeft + dx).coerceAtMost(right - minSize)
                        else right = (sRight + dx).coerceAtLeast(left + minSize)
                        if (isTop) top = (sTop + dy).coerceAtMost(bottom - minSize)
                        else bottom = (sBottom + dy).coerceAtLeast(top + minSize)
                        relayout(); true
                    }
                    else -> true
                }
            }
        }
        handleDrag(hTL, isLeft = true, isTop = true)
        handleDrag(hTR, isLeft = false, isTop = true)
        handleDrag(hBL, isLeft = true, isTop = false)
        handleDrag(hBR, isLeft = false, isTop = false)

        addOverlay(box, gameAreaP)
        gameAreaHandleViews.clear()
        handles.zip(handleParams).forEach { (handleView, params) ->
            addOverlay(handleView, params)
            gameAreaHandleViews.add(handleView)
        }
        addOverlay(gameAreaHint, gameAreaHintP)
        gameAreaOverlayView = box
        gameAreaHintView = gameAreaHint
        box.post { relayout() }

        fun saveGameScreenSetup() {
            val newLayout = setupLayoutProvider().copy(
                gameAreaLeft = left,
                gameAreaTop = top,
                gameAreaRight = right,
                gameAreaBottom = bottom
            )
            onSaved(newLayout)
            showMacroToast("Game area saved!")
            hideGameScreenAreaOverlay()
        }

        val saveBtn = TextView(context).apply {
            text = "✔ SAVE"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#CC0E2C57"))
                setStroke(dpToPx(2), Color.parseColor("#74BCFF"))
            }
        }
        val saveBtnP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dpToPx(200)
            y = sh - dpToPx(120)
        }
        attachDraggableTap(saveBtn, saveBtnP) { saveGameScreenSetup() }
        addOverlay(saveBtn, saveBtnP)
        gameAreaSaveView = saveBtn

        setActiveSetupSaveAction(SetupTarget.GAME_SCREEN_AREA) { saveGameScreenSetup() }
        showMacroToast("Drag the corner handles to set the game screen, then SAVE.")
        bringToolbarToFront()
    }

    fun hideGameScreenAreaOverlay() {
        gameAreaOverlayView?.let { runCatching { windowManager.removeView(it) } }
        gameAreaOverlayView = null
        gameAreaHandleViews.forEach { runCatching { windowManager.removeView(it) } }
        gameAreaHandleViews.clear()
        gameAreaHintView?.let { runCatching { windowManager.removeView(it) } }
        gameAreaHintView = null
        spiderAreaBoxView?.let { runCatching { windowManager.removeView(it) } }
        spiderAreaBoxView = null
        spiderHealthBoxView?.let { runCatching { windowManager.removeView(it) } }
        spiderHealthBoxView = null
        noMineTextBoxView?.let { runCatching { windowManager.removeView(it) } }
        noMineTextBoxView = null
        clearFloatingHintOverlays()
        gameAreaSaveView?.let { runCatching { windowManager.removeView(it) } }
        gameAreaSaveView = null
        clearActiveSetupSaveAction(SetupTarget.GAME_SCREEN_AREA)
    }

    // ---------------------------------------------------------------------
    // Snapshot overlay — movable/resizable blue box, saves exact pixel crop.
    // ---------------------------------------------------------------------

    fun showSnapshotOverlay() {
        hideSnapshotOverlay()
        hidePointsSetupOverlays()
        hideZoomLevelOverlay()
        hideGameScreenAreaOverlay()

        val sw = screenWidthPx()
        val sh = screenHeightPx()
        val defaultSize = dpToPx(32).toFloat()
        val minSize = dpToPx(12).toFloat()
        val handle = dpToPx(18)
        var left = (sw - defaultSize) / 2f
        var top = (sh - defaultSize) / 2f
        var right = left + defaultSize
        var bottom = top + defaultSize

        val container = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }
        val box = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#220074FF"))
                setStroke(dpToPx(3), Color.parseColor("#FF2196F3"))
            }
        }
        container.addView(box, FrameLayout.LayoutParams(1, 1))
        fun makeHandle(): View = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#FF2196F3"))
                setStroke(dpToPx(1), Color.parseColor("#FFFFFFFF"))
            }
        }
        val hTL = makeHandle()
        val hTR = makeHandle()
        val hBL = makeHandle()
        val hBR = makeHandle()
        listOf(hTL, hTR, hBL, hBR).forEach { container.addView(it, FrameLayout.LayoutParams(handle, handle)) }

        val snapshotP = boundedOverlayParams(0, 0, 1, 1)

        fun relayout() {
            val boxW = (right - left).toInt().coerceAtLeast(1)
            val boxH = (bottom - top).toInt().coerceAtLeast(1)
            val pad = handle
            snapshotP.x = (left - pad / 2f).toInt()
            snapshotP.y = (top - pad / 2f).toInt()
            snapshotP.width = boxW + pad
            snapshotP.height = boxH + pad
            val ox = pad / 2
            (box.layoutParams as FrameLayout.LayoutParams).apply {
                width = boxW
                height = boxH
                leftMargin = ox
                topMargin = ox
            }
            fun placeHandle(v: View, cx: Float, cy: Float) {
                (v.layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = (cx - handle / 2f).toInt()
                    topMargin = (cy - handle / 2f).toInt()
                }
            }
            placeHandle(hTL, ox.toFloat(), ox.toFloat())
            placeHandle(hTR, ox + boxW.toFloat(), ox.toFloat())
            placeHandle(hBL, ox.toFloat(), ox + boxH.toFloat())
            placeHandle(hBR, ox + boxW.toFloat(), ox + boxH.toFloat())
            container.requestLayout()
            runCatching { windowManager.updateViewLayout(container, snapshotP) }
        }

        var mStartLeft = 0f
        var mStartTop = 0f
        var mStartRight = 0f
        var mStartBottom = 0f
        var mTouchX = 0f
        var mTouchY = 0f
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mStartLeft = left
                    mStartTop = top
                    mStartRight = right
                    mStartBottom = bottom
                    mTouchX = e.rawX
                    mTouchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - mTouchX
                    val dy = e.rawY - mTouchY
                    left = mStartLeft + dx
                    right = mStartRight + dx
                    top = mStartTop + dy
                    bottom = mStartBottom + dy
                    relayout()
                    true
                }
                else -> true
            }
        }

        fun handleDrag(v: View, isLeft: Boolean, isTop: Boolean) {
            var sx = 0f
            var sy = 0f
            var sLeft = 0f
            var sTop = 0f
            var sRight = 0f
            var sBottom = 0f
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        sLeft = left
                        sTop = top
                        sRight = right
                        sBottom = bottom
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (isLeft) left = (sLeft + dx).coerceAtMost(right - minSize)
                        else right = (sRight + dx).coerceAtLeast(left + minSize)
                        if (isTop) top = (sTop + dy).coerceAtMost(bottom - minSize)
                        else bottom = (sBottom + dy).coerceAtLeast(top + minSize)
                        relayout()
                        true
                    }
                    else -> true
                }
            }
        }
        handleDrag(hTL, isLeft = true, isTop = true)
        handleDrag(hTR, isLeft = false, isTop = true)
        handleDrag(hBL, isLeft = true, isTop = false)
        handleDrag(hBR, isLeft = false, isTop = false)

        addOverlay(container, snapshotP)
        snapshotOverlayView = container
        container.post { relayout() }

        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancelBtn = buildActionChip("Cancel", isPrimary = false) { hideSnapshotOverlay() }
        val snapBtn = buildActionChip("Snap", isPrimary = true) {
            val region = DetectionRegion(left, top, right, bottom)
            val overlay = snapshotOverlayView
            val bar = snapshotActionBarView
            overlay?.visibility = View.GONE
            bar?.visibility = View.GONE
            overlay?.postDelayed({
                val path = try {
                    onCaptureSnapshot(region)
                } finally {
                    overlay?.visibility = View.VISIBLE
                    bar?.visibility = View.VISIBLE
                }
                if (path != null) {
                    showMacroToast(path)
                } else {
                    showMacroToast("Snap failed — enable Screen Capture on the home screen first")
                }
            }, 100L)
        }
        actionBar.addView(cancelBtn)
        actionBar.addView(buildHSpacer(12))
        actionBar.addView(snapBtn)
        val actionBarP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - dpToPx(220)) / 2
            y = sh - dpToPx(100)
        }
        makeDraggable(actionBar, actionBarP)
        addOverlay(actionBar, actionBarP)
        snapshotActionBarView = actionBar
        actionBar.post {
            val barW = actionBar.width.coerceAtLeast(dpToPx(180))
            actionBarP.x = (sw - barW) / 2
            runCatching { windowManager.updateViewLayout(actionBar, actionBarP) }
        }

        showMacroToast("Blue box = snapshot area (tool size). Drag corners to resize, Snap to save PNG.")
        bringToolbarToFront()
    }

    fun hideSnapshotOverlay() {
        snapshotOverlayView?.let { runCatching { windowManager.removeView(it) } }
        snapshotOverlayView = null
        snapshotActionBarView?.let { runCatching { windowManager.removeView(it) } }
        snapshotActionBarView = null
    }
}
