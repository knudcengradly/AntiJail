package com.antijail.overlay.model

import kotlin.math.max
import kotlin.math.min

/**
 * Maps a [SetupLayout] saved on the reference emulator (1280×720 landscape) onto the current device.
 *
 * **Buttons** (tap points) are scaled to fit the screen only — they never move when the zoom slider
 * changes. **Detection regions** get an extra [detectionScale] factor (Setup → Zoom Level) so image
 * matching matches in-game zoom.
 *
 * Reference emulators (1280×720) keep exact saved pixels.
 */
object LayoutScaler {
    const val REFERENCE_WIDTH = 1280f
    const val REFERENCE_HEIGHT = 720f

    /** Danger-sign overlay at 100% reference size (visual guide only — not tied to detection slider). */
    const val ZOOM_SIGN_REF_WIDTH_PX = 120f
    const val ZOOM_SIGN_REF_HEIGHT_PX = 72f

    /** thriftshop.png template size at reference (scales with detection slider in zoom setup). */
    const val THRIFT_SHOP_REF_WIDTH_PX = 96f
    const val THRIFT_SHOP_REF_HEIGHT_PX = 42f

    private const val PHONE_MARGIN_PX = 24f

    fun isReferenceScreen(width: Int, height: Int): Boolean =
        width == REFERENCE_WIDTH.toInt() && height == REFERENCE_HEIGHT.toInt()

    fun landscapeDimensions(displayWidth: Int, displayHeight: Int): Pair<Int, Int> =
        if (displayWidth >= displayHeight) displayWidth to displayHeight else displayHeight to displayWidth

    fun isPortraitDisplay(displayWidth: Int, displayHeight: Int): Boolean =
        displayWidth < displayHeight

    /** Runtime detection scale — emulators always use 1.0 regardless of saved preference. */
    fun effectiveDetectionScale(displayWidth: Int, displayHeight: Int, detectionScale: Float): Float =
        if (isReferenceScreen(displayWidth, displayHeight)) 1.0f
        else detectionScale.coerceIn(0.5f, 2.0f)

    /** Device fit for buttons + base region positions (Points & Region setup). No detection zoom. */
    fun scaleForDevice(layout: SetupLayout, displayWidth: Int, displayHeight: Int): SetupLayout {
        if (isReferenceScreen(displayWidth, displayHeight)) return layout
        val (landW, landH) = landscapeDimensions(displayWidth, displayHeight)
        var scaled = scaleToLandscapeCanvas(layout, landW, landH)
        scaled = centerOnLandscapeCanvas(scaled, landW.toFloat(), landH.toFloat())
        if (isPortraitDisplay(displayWidth, displayHeight)) {
            scaled = mapLandscapeLayoutToPortraitDisplay(scaled, landW, landH, displayWidth, displayHeight)
            scaled = clampLayoutToScreen(scaled, displayWidth.toFloat(), displayHeight.toFloat())
        } else {
            scaled = clampLayoutToScreen(scaled, landW.toFloat(), landH.toFloat())
        }
        return scaled
    }

    /** Runtime — buttons from [scaleForDevice], detection regions also get [detectionScale]. */
    fun scaleForRuntime(
        layout: SetupLayout,
        displayWidth: Int,
        displayHeight: Int,
        detectionScale: Float = 1.0f
    ): SetupLayout {
        if (isReferenceScreen(displayWidth, displayHeight)) return layout
        val device = scaleForDevice(layout, displayWidth, displayHeight)
        val zoom = effectiveDetectionScale(displayWidth, displayHeight, detectionScale)
        return if (zoom == 1.0f) device else applyDetectionScaleOnly(device, zoom)
    }

    /** Zoom setup preview — scales detection regions only (buttons are not shown here). */
    fun scaleForPreview(
        layout: SetupLayout,
        displayWidth: Int,
        displayHeight: Int,
        detectionScale: Float = 1.0f
    ): SetupLayout {
        val zoom = detectionScale.coerceIn(0.5f, 2.0f)
        if (isReferenceScreen(displayWidth, displayHeight)) {
            return if (zoom == 1.0f) layout else applyDetectionScaleOnly(layout, zoom)
        }
        val device = scaleForDevice(layout, displayWidth, displayHeight)
        return if (zoom == 1.0f) device else applyDetectionScaleOnly(device, zoom)
    }

    /** Danger sign visual size — always 100% reference (not affected by detection slider). */
    fun zoomSignSizePx(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val sx = deviceUniformScale(displayWidth, displayHeight)
        val w = (ZOOM_SIGN_REF_WIDTH_PX * sx).toInt().coerceAtLeast(24)
        val h = (ZOOM_SIGN_REF_HEIGHT_PX * sx).toInt().coerceAtLeast(16)
        return w to h
    }

    /** Thrift shop template overlay — scales with detection slider for in-game zoom matching. */
    fun thriftShopSizePx(displayWidth: Int, displayHeight: Int, detectionScale: Float): Pair<Int, Int> {
        val zoom = detectionScale.coerceIn(0.5f, 2.0f)
        val sx = deviceUniformScale(displayWidth, displayHeight)
        val w = (THRIFT_SHOP_REF_WIDTH_PX * sx * zoom).toInt().coerceAtLeast(16)
        val h = (THRIFT_SHOP_REF_HEIGHT_PX * sx * zoom).toInt().coerceAtLeast(8)
        return w to h
    }

    private fun deviceUniformScale(displayWidth: Int, displayHeight: Int): Float {
        if (isReferenceScreen(displayWidth, displayHeight)) return 1f
        val (landW, landH) = landscapeDimensions(displayWidth, displayHeight)
        return min(landW / REFERENCE_WIDTH, landH / REFERENCE_HEIGHT)
    }

    private fun scaleToLandscapeCanvas(layout: SetupLayout, landW: Int, landH: Int): SetupLayout {
        val uniformS = min(landW / REFERENCE_WIDTH, landH / REFERENCE_HEIGHT)
        return applyUniformScaleFromCenter(layout, uniformS)
    }

    /** Scales entire layout uniformly around game centre (device fit — detectionScale = 1). */
    private fun applyUniformScaleFromCenter(layout: SetupLayout, uniformS: Float): SetupLayout {
        val s = uniformS
        if (!layout.hasGameArea()) return scaleUniform(layout, s, s)

        val refCx = layout.gameCenterX()
        val refCy = layout.gameCenterY()
        val curGameLeft = layout.gameAreaLeft * s
        val curGameTop = layout.gameAreaTop * s
        val curGameRight = layout.gameAreaRight * s
        val curGameBottom = layout.gameAreaBottom * s
        val curCx = (curGameLeft + curGameRight) / 2f
        val curCy = (curGameTop + curGameBottom) / 2f

        fun scaleX(x: Float): Float = curCx + (x - refCx) * s
        fun scaleY(y: Float): Float = curCy + (y - refCy) * s
        fun scalePoint(p: Point): Point = Point(scaleX(p.x), scaleY(p.y))

        return layout.copy(
            dpadUp = scalePoint(layout.dpadUp),
            dpadDown = scalePoint(layout.dpadDown),
            dpadLeft = scalePoint(layout.dpadLeft),
            dpadRight = scalePoint(layout.dpadRight),
            attackUp = scalePoint(layout.attackUp),
            attackDown = scalePoint(layout.attackDown),
            attackLeft = scalePoint(layout.attackLeft),
            attackRight = scalePoint(layout.attackRight),
            tool1Button = scalePoint(layout.tool1Button),
            tool2Button = scalePoint(layout.tool2Button),
            foodButton = scalePoint(layout.foodButton),
            grabButton = scalePoint(layout.grabButton),
            fireButton = scalePoint(layout.fireButton),
            buyButton = scalePoint(layout.buyButton),
            okButton = scalePoint(layout.okButton),
            tauntButton = scalePoint(layout.tauntButton),
            fishStopButton = scalePoint(layout.fishStopButton),
            gameAreaLeft = curGameLeft,
            gameAreaTop = curGameTop,
            gameAreaRight = curGameRight,
            gameAreaBottom = curGameBottom,
            drillLeft = scaleX(layout.drillLeft),
            drillTop = scaleY(layout.drillTop),
            drillRight = scaleX(layout.drillRight),
            drillBottom = scaleY(layout.drillBottom),
            rockUpLeft = scaleX(layout.rockUpLeft),
            rockUpTop = scaleY(layout.rockUpTop),
            rockUpRight = scaleX(layout.rockUpRight),
            rockUpBottom = scaleY(layout.rockUpBottom),
            rockDownLeft = scaleX(layout.rockDownLeft),
            rockDownTop = scaleY(layout.rockDownTop),
            rockDownRight = scaleX(layout.rockDownRight),
            rockDownBottom = scaleY(layout.rockDownBottom),
            rockLeftLeft = scaleX(layout.rockLeftLeft),
            rockLeftTop = scaleY(layout.rockLeftTop),
            rockLeftRight = scaleX(layout.rockLeftRight),
            rockLeftBottom = scaleY(layout.rockLeftBottom),
            rockRightLeft = scaleX(layout.rockRightLeft),
            rockRightTop = scaleY(layout.rockRightTop),
            rockRightRight = scaleX(layout.rockRightRight),
            rockRightBottom = scaleY(layout.rockRightBottom),
            spiderAreaLeft = scaleX(layout.spiderAreaLeft),
            spiderAreaTop = scaleY(layout.spiderAreaTop),
            spiderAreaRight = scaleX(layout.spiderAreaRight),
            spiderAreaBottom = scaleY(layout.spiderAreaBottom),
            spiderHealthLeft = scaleX(layout.spiderHealthLeft),
            spiderHealthTop = scaleY(layout.spiderHealthTop),
            spiderHealthRight = scaleX(layout.spiderHealthRight),
            spiderHealthBottom = scaleY(layout.spiderHealthBottom),
            noMineTextLeft = scaleX(layout.noMineTextLeft),
            noMineTextTop = scaleY(layout.noMineTextTop),
            noMineTextRight = scaleX(layout.noMineTextRight),
            noMineTextBottom = scaleY(layout.noMineTextBottom),
            fireAreaLeft = scaleX(layout.fireAreaLeft),
            fireAreaTop = scaleY(layout.fireAreaTop),
            fireAreaRight = scaleX(layout.fireAreaRight),
            fireAreaBottom = scaleY(layout.fireAreaBottom),
            fishPirateHpLeft = scaleX(layout.fishPirateHpLeft),
            fishPirateHpTop = scaleY(layout.fishPirateHpTop),
            fishPirateHpRight = scaleX(layout.fishPirateHpRight),
            fishPirateHpBottom = scaleY(layout.fishPirateHpBottom)
        )
    }

    /** Extra zoom for detection/image matching only — tap points are unchanged. */
    fun applyDetectionScaleOnly(layout: SetupLayout, detectionScale: Float): SetupLayout {
        val zoom = detectionScale.coerceIn(0.5f, 2.0f)
        if (zoom == 1.0f) return layout
        if (!layout.hasGameArea()) return layout

        val cx = layout.gameCenterX()
        val cy = layout.gameCenterY()
        fun scaleX(x: Float): Float = cx + (x - cx) * zoom
        fun scaleY(y: Float): Float = cy + (y - cy) * zoom

        return layout.copy(
            gameAreaLeft = scaleX(layout.gameAreaLeft),
            gameAreaTop = scaleY(layout.gameAreaTop),
            gameAreaRight = scaleX(layout.gameAreaRight),
            gameAreaBottom = scaleY(layout.gameAreaBottom),
            drillLeft = scaleX(layout.drillLeft),
            drillTop = scaleY(layout.drillTop),
            drillRight = scaleX(layout.drillRight),
            drillBottom = scaleY(layout.drillBottom),
            rockUpLeft = scaleX(layout.rockUpLeft),
            rockUpTop = scaleY(layout.rockUpTop),
            rockUpRight = scaleX(layout.rockUpRight),
            rockUpBottom = scaleY(layout.rockUpBottom),
            rockDownLeft = scaleX(layout.rockDownLeft),
            rockDownTop = scaleY(layout.rockDownTop),
            rockDownRight = scaleX(layout.rockDownRight),
            rockDownBottom = scaleY(layout.rockDownBottom),
            rockLeftLeft = scaleX(layout.rockLeftLeft),
            rockLeftTop = scaleY(layout.rockLeftTop),
            rockLeftRight = scaleX(layout.rockLeftRight),
            rockLeftBottom = scaleY(layout.rockLeftBottom),
            rockRightLeft = scaleX(layout.rockRightLeft),
            rockRightTop = scaleY(layout.rockRightTop),
            rockRightRight = scaleX(layout.rockRightRight),
            rockRightBottom = scaleY(layout.rockRightBottom),
            spiderAreaLeft = scaleX(layout.spiderAreaLeft),
            spiderAreaTop = scaleY(layout.spiderAreaTop),
            spiderAreaRight = scaleX(layout.spiderAreaRight),
            spiderAreaBottom = scaleY(layout.spiderAreaBottom),
            spiderHealthLeft = scaleX(layout.spiderHealthLeft),
            spiderHealthTop = scaleY(layout.spiderHealthTop),
            spiderHealthRight = scaleX(layout.spiderHealthRight),
            spiderHealthBottom = scaleY(layout.spiderHealthBottom),
            noMineTextLeft = scaleX(layout.noMineTextLeft),
            noMineTextTop = scaleY(layout.noMineTextTop),
            noMineTextRight = scaleX(layout.noMineTextRight),
            noMineTextBottom = scaleY(layout.noMineTextBottom),
            fireAreaLeft = scaleX(layout.fireAreaLeft),
            fireAreaTop = scaleY(layout.fireAreaTop),
            fireAreaRight = scaleX(layout.fireAreaRight),
            fireAreaBottom = scaleY(layout.fireAreaBottom),
            fishPirateHpLeft = scaleX(layout.fishPirateHpLeft),
            fishPirateHpTop = scaleY(layout.fishPirateHpTop),
            fishPirateHpRight = scaleX(layout.fishPirateHpRight),
            fishPirateHpBottom = scaleY(layout.fishPirateHpBottom)
        )
    }

    private fun centerOnLandscapeCanvas(layout: SetupLayout, canvasW: Float, canvasH: Float): SetupLayout {
        val b = layoutBounds(layout)
        if (b.isEmpty()) return layout
        val targetLeft = PHONE_MARGIN_PX
        val targetTop = PHONE_MARGIN_PX
        val targetRight = canvasW - PHONE_MARGIN_PX
        val targetBottom = canvasH - PHONE_MARGIN_PX
        val boxW = b.right - b.left
        val boxH = b.bottom - b.top
        val availW = targetRight - targetLeft
        val availH = targetBottom - targetTop
        val ox = targetLeft + (availW - boxW) / 2f - b.left
        val oy = targetTop + (availH - boxH) / 2f - b.top
        return translateLayout(layout, ox, oy)
    }

    /** Keep all tap points and detection boxes inside the visible screen on phones. */
    private fun clampLayoutToScreen(layout: SetupLayout, screenW: Float, screenH: Float): SetupLayout {
        fun clampX(x: Float) = x.coerceIn(PHONE_MARGIN_PX, screenW - PHONE_MARGIN_PX)
        fun clampY(y: Float) = y.coerceIn(PHONE_MARGIN_PX, screenH - PHONE_MARGIN_PX)
        fun clampPoint(p: Point) = Point(clampX(p.x), clampY(p.y))
        fun clampRegion(l: Float, t: Float, r: Float, b: Float): FloatArray {
            var left = min(l, r).coerceIn(PHONE_MARGIN_PX, screenW - PHONE_MARGIN_PX)
            var top = min(t, b).coerceIn(PHONE_MARGIN_PX, screenH - PHONE_MARGIN_PX)
            var right = max(l, r).coerceIn(PHONE_MARGIN_PX, screenW - PHONE_MARGIN_PX)
            var bottom = max(t, b).coerceIn(PHONE_MARGIN_PX, screenH - PHONE_MARGIN_PX)
            if (right <= left) right = (left + 1f).coerceAtMost(screenW - PHONE_MARGIN_PX)
            if (bottom <= top) bottom = (top + 1f).coerceAtMost(screenH - PHONE_MARGIN_PX)
            return floatArrayOf(left, top, right, bottom)
        }
        fun mapRegion(l: Float, t: Float, r: Float, b: Float) = clampRegion(l, t, r, b)

        val drill = mapRegion(layout.drillLeft, layout.drillTop, layout.drillRight, layout.drillBottom)
        val rockUp = mapRegion(layout.rockUpLeft, layout.rockUpTop, layout.rockUpRight, layout.rockUpBottom)
        val rockDown = mapRegion(layout.rockDownLeft, layout.rockDownTop, layout.rockDownRight, layout.rockDownBottom)
        val rockLeft = mapRegion(layout.rockLeftLeft, layout.rockLeftTop, layout.rockLeftRight, layout.rockLeftBottom)
        val rockRight = mapRegion(layout.rockRightLeft, layout.rockRightTop, layout.rockRightRight, layout.rockRightBottom)
        val spider = mapRegion(layout.spiderAreaLeft, layout.spiderAreaTop, layout.spiderAreaRight, layout.spiderAreaBottom)
        val health = mapRegion(layout.spiderHealthLeft, layout.spiderHealthTop, layout.spiderHealthRight, layout.spiderHealthBottom)
        val noMine = mapRegion(layout.noMineTextLeft, layout.noMineTextTop, layout.noMineTextRight, layout.noMineTextBottom)
        val fire = mapRegion(layout.fireAreaLeft, layout.fireAreaTop, layout.fireAreaRight, layout.fireAreaBottom)
        val fishHp = mapRegion(layout.fishPirateHpLeft, layout.fishPirateHpTop, layout.fishPirateHpRight, layout.fishPirateHpBottom)
        val game = mapRegion(layout.gameAreaLeft, layout.gameAreaTop, layout.gameAreaRight, layout.gameAreaBottom)

        return layout.copy(
            dpadUp = clampPoint(layout.dpadUp),
            dpadDown = clampPoint(layout.dpadDown),
            dpadLeft = clampPoint(layout.dpadLeft),
            dpadRight = clampPoint(layout.dpadRight),
            attackUp = clampPoint(layout.attackUp),
            attackDown = clampPoint(layout.attackDown),
            attackLeft = clampPoint(layout.attackLeft),
            attackRight = clampPoint(layout.attackRight),
            tool1Button = clampPoint(layout.tool1Button),
            tool2Button = clampPoint(layout.tool2Button),
            foodButton = clampPoint(layout.foodButton),
            grabButton = clampPoint(layout.grabButton),
            fireButton = clampPoint(layout.fireButton),
            buyButton = clampPoint(layout.buyButton),
            okButton = clampPoint(layout.okButton),
            tauntButton = clampPoint(layout.tauntButton),
            fishStopButton = clampPoint(layout.fishStopButton),
            gameAreaLeft = game[0], gameAreaTop = game[1], gameAreaRight = game[2], gameAreaBottom = game[3],
            drillLeft = drill[0], drillTop = drill[1], drillRight = drill[2], drillBottom = drill[3],
            rockUpLeft = rockUp[0], rockUpTop = rockUp[1], rockUpRight = rockUp[2], rockUpBottom = rockUp[3],
            rockDownLeft = rockDown[0], rockDownTop = rockDown[1], rockDownRight = rockDown[2], rockDownBottom = rockDown[3],
            rockLeftLeft = rockLeft[0], rockLeftTop = rockLeft[1], rockLeftRight = rockLeft[2], rockLeftBottom = rockLeft[3],
            rockRightLeft = rockRight[0], rockRightTop = rockRight[1], rockRightRight = rockRight[2], rockRightBottom = rockRight[3],
            spiderAreaLeft = spider[0], spiderAreaTop = spider[1], spiderAreaRight = spider[2], spiderAreaBottom = spider[3],
            spiderHealthLeft = health[0], spiderHealthTop = health[1], spiderHealthRight = health[2], spiderHealthBottom = health[3],
            noMineTextLeft = noMine[0], noMineTextTop = noMine[1], noMineTextRight = noMine[2], noMineTextBottom = noMine[3],
            fireAreaLeft = fire[0], fireAreaTop = fire[1], fireAreaRight = fire[2], fireAreaBottom = fire[3],
            fishPirateHpLeft = fishHp[0], fishPirateHpTop = fishHp[1], fishPirateHpRight = fishHp[2], fishPirateHpBottom = fishHp[3]
        )
    }

    private fun mapLandscapeLayoutToPortraitDisplay(
        layout: SetupLayout,
        landW: Int,
        landH: Int,
        displayW: Int,
        displayH: Int
    ): SetupLayout {
        val lw = landW.toFloat()
        val lh = landH.toFloat()
        fun rotPoint(p: Point): Point = Point(
            p.y * displayW / lh,
            (lw - p.x) * displayH / lw
        )
        fun rotRegion(l: Float, t: Float, r: Float, b: Float): FloatArray {
            val p1 = rotPoint(Point(l, t))
            val p2 = rotPoint(Point(r, t))
            val p3 = rotPoint(Point(l, b))
            val p4 = rotPoint(Point(r, b))
            val xs = floatArrayOf(p1.x, p2.x, p3.x, p4.x)
            val ys = floatArrayOf(p1.y, p2.y, p3.y, p4.y)
            return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }
        fun mapRegion(l: Float, t: Float, r: Float, b: Float): List<Float> =
            rotRegion(l, t, r, b).toList()

        val drill = mapRegion(layout.drillLeft, layout.drillTop, layout.drillRight, layout.drillBottom)
        val rockUp = mapRegion(layout.rockUpLeft, layout.rockUpTop, layout.rockUpRight, layout.rockUpBottom)
        val rockDown = mapRegion(layout.rockDownLeft, layout.rockDownTop, layout.rockDownRight, layout.rockDownBottom)
        val rockLeft = mapRegion(layout.rockLeftLeft, layout.rockLeftTop, layout.rockLeftRight, layout.rockLeftBottom)
        val rockRight = mapRegion(layout.rockRightLeft, layout.rockRightTop, layout.rockRightRight, layout.rockRightBottom)
        val spider = mapRegion(layout.spiderAreaLeft, layout.spiderAreaTop, layout.spiderAreaRight, layout.spiderAreaBottom)
        val health = mapRegion(layout.spiderHealthLeft, layout.spiderHealthTop, layout.spiderHealthRight, layout.spiderHealthBottom)
        val noMine = mapRegion(layout.noMineTextLeft, layout.noMineTextTop, layout.noMineTextRight, layout.noMineTextBottom)
        val fire = mapRegion(layout.fireAreaLeft, layout.fireAreaTop, layout.fireAreaRight, layout.fireAreaBottom)
        val fishHp = mapRegion(layout.fishPirateHpLeft, layout.fishPirateHpTop, layout.fishPirateHpRight, layout.fishPirateHpBottom)
        val game = mapRegion(layout.gameAreaLeft, layout.gameAreaTop, layout.gameAreaRight, layout.gameAreaBottom)

        return layout.copy(
            dpadUp = rotPoint(layout.dpadUp),
            dpadDown = rotPoint(layout.dpadDown),
            dpadLeft = rotPoint(layout.dpadLeft),
            dpadRight = rotPoint(layout.dpadRight),
            attackUp = rotPoint(layout.attackUp),
            attackDown = rotPoint(layout.attackDown),
            attackLeft = rotPoint(layout.attackLeft),
            attackRight = rotPoint(layout.attackRight),
            tool1Button = rotPoint(layout.tool1Button),
            tool2Button = rotPoint(layout.tool2Button),
            foodButton = rotPoint(layout.foodButton),
            grabButton = rotPoint(layout.grabButton),
            fireButton = rotPoint(layout.fireButton),
            buyButton = rotPoint(layout.buyButton),
            okButton = rotPoint(layout.okButton),
            tauntButton = rotPoint(layout.tauntButton),
            fishStopButton = rotPoint(layout.fishStopButton),
            gameAreaLeft = game[0], gameAreaTop = game[1], gameAreaRight = game[2], gameAreaBottom = game[3],
            drillLeft = drill[0], drillTop = drill[1], drillRight = drill[2], drillBottom = drill[3],
            rockUpLeft = rockUp[0], rockUpTop = rockUp[1], rockUpRight = rockUp[2], rockUpBottom = rockUp[3],
            rockDownLeft = rockDown[0], rockDownTop = rockDown[1], rockDownRight = rockDown[2], rockDownBottom = rockDown[3],
            rockLeftLeft = rockLeft[0], rockLeftTop = rockLeft[1], rockLeftRight = rockLeft[2], rockLeftBottom = rockLeft[3],
            rockRightLeft = rockRight[0], rockRightTop = rockRight[1], rockRightRight = rockRight[2], rockRightBottom = rockRight[3],
            spiderAreaLeft = spider[0], spiderAreaTop = spider[1], spiderAreaRight = spider[2], spiderAreaBottom = spider[3],
            spiderHealthLeft = health[0], spiderHealthTop = health[1], spiderHealthRight = health[2], spiderHealthBottom = health[3],
            noMineTextLeft = noMine[0], noMineTextTop = noMine[1], noMineTextRight = noMine[2], noMineTextBottom = noMine[3],
            fireAreaLeft = fire[0], fireAreaTop = fire[1], fireAreaRight = fire[2], fireAreaBottom = fire[3],
            fishPirateHpLeft = fishHp[0], fishPirateHpTop = fishHp[1], fishPirateHpRight = fishHp[2], fishPirateHpBottom = fishHp[3]
        )
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun isEmpty(): Boolean = right <= left || bottom <= top
    }

    private fun layoutBounds(layout: SetupLayout): Bounds {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var bottom = Float.MIN_VALUE
        fun include(x: Float, y: Float) {
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        fun includePoint(p: Point) = include(p.x, p.y)
        fun includeRegion(l: Float, t: Float, r: Float, b: Float) {
            include(l, t); include(r, b)
        }
        includePoint(layout.dpadUp); includePoint(layout.dpadDown)
        includePoint(layout.dpadLeft); includePoint(layout.dpadRight)
        includePoint(layout.attackUp); includePoint(layout.attackDown)
        includePoint(layout.attackLeft); includePoint(layout.attackRight)
        includePoint(layout.tool1Button); includePoint(layout.tool2Button)
        includePoint(layout.grabButton); includePoint(layout.tauntButton)
        includePoint(layout.resolvedFireButton())
        includeRegion(layout.drillLeft, layout.drillTop, layout.drillRight, layout.drillBottom)
        listOf("UP", "DOWN", "LEFT", "RIGHT").forEach { side ->
            layout.rockRegion(side)?.let { includeRegion(it.left, it.top, it.right, it.bottom) }
        }
        if (left == Float.MAX_VALUE) return Bounds(0f, 0f, 0f, 0f)
        return Bounds(left, top, right, bottom)
    }

    private fun translateLayout(layout: SetupLayout, dx: Float, dy: Float): SetupLayout {
        fun tp(p: Point) = Point(p.x + dx, p.y + dy)
        fun te(v: Float, isX: Boolean) = v + if (isX) dx else dy
        return layout.copy(
            dpadUp = tp(layout.dpadUp),
            dpadDown = tp(layout.dpadDown),
            dpadLeft = tp(layout.dpadLeft),
            dpadRight = tp(layout.dpadRight),
            attackUp = tp(layout.attackUp),
            attackDown = tp(layout.attackDown),
            attackLeft = tp(layout.attackLeft),
            attackRight = tp(layout.attackRight),
            tool1Button = tp(layout.tool1Button),
            tool2Button = tp(layout.tool2Button),
            foodButton = tp(layout.foodButton),
            grabButton = tp(layout.grabButton),
            fireButton = tp(layout.fireButton),
            buyButton = tp(layout.buyButton),
            okButton = tp(layout.okButton),
            tauntButton = tp(layout.tauntButton),
            fishStopButton = tp(layout.fishStopButton),
            gameAreaLeft = te(layout.gameAreaLeft, true),
            gameAreaTop = te(layout.gameAreaTop, false),
            gameAreaRight = te(layout.gameAreaRight, true),
            gameAreaBottom = te(layout.gameAreaBottom, false),
            drillLeft = te(layout.drillLeft, true),
            drillTop = te(layout.drillTop, false),
            drillRight = te(layout.drillRight, true),
            drillBottom = te(layout.drillBottom, false),
            rockUpLeft = te(layout.rockUpLeft, true),
            rockUpTop = te(layout.rockUpTop, false),
            rockUpRight = te(layout.rockUpRight, true),
            rockUpBottom = te(layout.rockUpBottom, false),
            rockDownLeft = te(layout.rockDownLeft, true),
            rockDownTop = te(layout.rockDownTop, false),
            rockDownRight = te(layout.rockDownRight, true),
            rockDownBottom = te(layout.rockDownBottom, false),
            rockLeftLeft = te(layout.rockLeftLeft, true),
            rockLeftTop = te(layout.rockLeftTop, false),
            rockLeftRight = te(layout.rockLeftRight, true),
            rockLeftBottom = te(layout.rockLeftBottom, false),
            rockRightLeft = te(layout.rockRightLeft, true),
            rockRightTop = te(layout.rockRightTop, false),
            rockRightRight = te(layout.rockRightRight, true),
            rockRightBottom = te(layout.rockRightBottom, false),
            spiderAreaLeft = te(layout.spiderAreaLeft, true),
            spiderAreaTop = te(layout.spiderAreaTop, false),
            spiderAreaRight = te(layout.spiderAreaRight, true),
            spiderAreaBottom = te(layout.spiderAreaBottom, false),
            spiderHealthLeft = te(layout.spiderHealthLeft, true),
            spiderHealthTop = te(layout.spiderHealthTop, false),
            spiderHealthRight = te(layout.spiderHealthRight, true),
            spiderHealthBottom = te(layout.spiderHealthBottom, false),
            noMineTextLeft = te(layout.noMineTextLeft, true),
            noMineTextTop = te(layout.noMineTextTop, false),
            noMineTextRight = te(layout.noMineTextRight, true),
            noMineTextBottom = te(layout.noMineTextBottom, false),
            fireAreaLeft = te(layout.fireAreaLeft, true),
            fireAreaTop = te(layout.fireAreaTop, false),
            fireAreaRight = te(layout.fireAreaRight, true),
            fireAreaBottom = te(layout.fireAreaBottom, false),
            fishPirateHpLeft = te(layout.fishPirateHpLeft, true),
            fishPirateHpTop = te(layout.fishPirateHpTop, false),
            fishPirateHpRight = te(layout.fishPirateHpRight, true),
            fishPirateHpBottom = te(layout.fishPirateHpBottom, false)
        )
    }

    private fun scaleUniform(layout: SetupLayout, sx: Float, sy: Float): SetupLayout {
        fun scalePoint(p: Point): Point = Point(p.x * sx, p.y * sy)
        fun scaleEdgeX(v: Float): Float = v * sx
        fun scaleEdgeY(v: Float): Float = v * sy
        return layout.copy(
            dpadUp = scalePoint(layout.dpadUp),
            dpadDown = scalePoint(layout.dpadDown),
            dpadLeft = scalePoint(layout.dpadLeft),
            dpadRight = scalePoint(layout.dpadRight),
            attackUp = scalePoint(layout.attackUp),
            attackDown = scalePoint(layout.attackDown),
            attackLeft = scalePoint(layout.attackLeft),
            attackRight = scalePoint(layout.attackRight),
            tool1Button = scalePoint(layout.tool1Button),
            tool2Button = scalePoint(layout.tool2Button),
            foodButton = scalePoint(layout.foodButton),
            grabButton = scalePoint(layout.grabButton),
            fireButton = scalePoint(layout.fireButton),
            buyButton = scalePoint(layout.buyButton),
            okButton = scalePoint(layout.okButton),
            tauntButton = scalePoint(layout.tauntButton),
            fishStopButton = scalePoint(layout.fishStopButton),
            gameAreaLeft = scaleEdgeX(layout.gameAreaLeft),
            gameAreaTop = scaleEdgeY(layout.gameAreaTop),
            gameAreaRight = scaleEdgeX(layout.gameAreaRight),
            gameAreaBottom = scaleEdgeY(layout.gameAreaBottom),
            drillLeft = scaleEdgeX(layout.drillLeft),
            drillTop = scaleEdgeY(layout.drillTop),
            drillRight = scaleEdgeX(layout.drillRight),
            drillBottom = scaleEdgeY(layout.drillBottom),
            rockUpLeft = scaleEdgeX(layout.rockUpLeft),
            rockUpTop = scaleEdgeY(layout.rockUpTop),
            rockUpRight = scaleEdgeX(layout.rockUpRight),
            rockUpBottom = scaleEdgeY(layout.rockUpBottom),
            rockDownLeft = scaleEdgeX(layout.rockDownLeft),
            rockDownTop = scaleEdgeY(layout.rockDownTop),
            rockDownRight = scaleEdgeX(layout.rockDownRight),
            rockDownBottom = scaleEdgeY(layout.rockDownBottom),
            rockLeftLeft = scaleEdgeX(layout.rockLeftLeft),
            rockLeftTop = scaleEdgeY(layout.rockLeftTop),
            rockLeftRight = scaleEdgeX(layout.rockLeftRight),
            rockLeftBottom = scaleEdgeY(layout.rockLeftBottom),
            rockRightLeft = scaleEdgeX(layout.rockRightLeft),
            rockRightTop = scaleEdgeY(layout.rockRightTop),
            rockRightRight = scaleEdgeX(layout.rockRightRight),
            rockRightBottom = scaleEdgeY(layout.rockRightBottom),
            spiderAreaLeft = scaleEdgeX(layout.spiderAreaLeft),
            spiderAreaTop = scaleEdgeY(layout.spiderAreaTop),
            spiderAreaRight = scaleEdgeX(layout.spiderAreaRight),
            spiderAreaBottom = scaleEdgeY(layout.spiderAreaBottom),
            spiderHealthLeft = scaleEdgeX(layout.spiderHealthLeft),
            spiderHealthTop = scaleEdgeY(layout.spiderHealthTop),
            spiderHealthRight = scaleEdgeX(layout.spiderHealthRight),
            spiderHealthBottom = scaleEdgeY(layout.spiderHealthBottom),
            noMineTextLeft = scaleEdgeX(layout.noMineTextLeft),
            noMineTextTop = scaleEdgeY(layout.noMineTextTop),
            noMineTextRight = scaleEdgeX(layout.noMineTextRight),
            noMineTextBottom = scaleEdgeY(layout.noMineTextBottom),
            fireAreaLeft = scaleEdgeX(layout.fireAreaLeft),
            fireAreaTop = scaleEdgeY(layout.fireAreaTop),
            fireAreaRight = scaleEdgeX(layout.fireAreaRight),
            fireAreaBottom = scaleEdgeY(layout.fireAreaBottom),
            fishPirateHpLeft = scaleEdgeX(layout.fishPirateHpLeft),
            fishPirateHpTop = scaleEdgeY(layout.fishPirateHpTop),
            fishPirateHpRight = scaleEdgeX(layout.fishPirateHpRight),
            fishPirateHpBottom = scaleEdgeY(layout.fishPirateHpBottom)
        )
    }
}
