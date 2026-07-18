package com.antijail.overlay.model



import kotlinx.serialization.Serializable



/**

 * Absolute screen pixel coordinates (landscape) for all interactive automation buttons.

 * These are CENTER points of each button, computed via getLocationOnScreen() + width/2, height/2.

 */

@Serializable

data class SetupLayout(

    // D-pad directional buttons (each is a separate tappable area on the D-pad widget)

    val dpadUp: Point = Point(199f, 494f),

    val dpadDown: Point = Point(199f, 592f),

    val dpadLeft: Point = Point(121f, 567f),

    val dpadRight: Point = Point(277f, 567f),

    // Attack directional buttons (directional swing pad, mirrors the movement D-pad)

    val attackUp: Point = Point(1099f, 491f),

    val attackDown: Point = Point(1099f, 677f),

    val attackLeft: Point = Point(1006f, 584f),

    val attackRight: Point = Point(1192f, 584f),

    // Tools column (T1 top, T2 middle, GRB bottom — 28px gap between rows, 32px buttons)

    val tool1Button: Point = Point(1170f, 95f),

    val tool2Button: Point = Point(1170f, 171f),

    /** F hotkey (food) to the left of T1 — saved from Points and Region setup. */

    val foodButton: Point = Point(1087f, 95f),

    val grabButton: Point = Point(1170f, 247f),

    /** Fire extinguisher button — saved from Points setup (default ~200px left of GRB). */
    val fireButton: Point = Point(953f, 340f),

    /** Shop "Buy" button — saved from Points setup (battery dead recovery). */
    val buyButton: Point = Point(765f, 420f),

    /** Shop "OK" confirm button — saved from Points setup (battery dead recovery). */
    val okButton: Point = Point(641f, 460f),

    /** Taunt tap target used when drill colour is missing (no-drill recovery). */

    val tauntButton: Point = Point(1113f, 340f),

    // Game screen area (left, top, right, bottom in screen pixels)

    val gameAreaLeft: Float = 0f,

    val gameAreaTop: Float = 0f,

    val gameAreaRight: Float = 1280f,

    val gameAreaBottom: Float = 720f,

    // Drill detection area (left, top, right, bottom in screen pixels).

    val drillLeft: Float = 585f,

    val drillTop: Float = 360f,

    val drillRight: Float = 677f,

    val drillBottom: Float = 386f,

    // Rock detection boxes (tool-sized, one per side of player) — screen pixel bounds.

    val rockUpLeft: Float = 624f,

    val rockUpTop: Float = 284f,

    val rockUpRight: Float = 656f,

    val rockUpBottom: Float = 316f,

    val rockDownLeft: Float = 624f,

    val rockDownTop: Float = 404f,

    val rockDownRight: Float = 656f,

    val rockDownBottom: Float = 436f,

    val rockLeftLeft: Float = 564f,

    val rockLeftTop: Float = 344f,

    val rockLeftRight: Float = 596f,

    val rockLeftBottom: Float = 376f,

    val rockRightLeft: Float = 684f,

    val rockRightTop: Float = 344f,

    val rockRightRight: Float = 716f,

    val rockRightBottom: Float = 376f,

    /** Spider face scan area (300×300 px default) centred on the player. */

    val spiderAreaLeft: Float = 490f,

    val spiderAreaTop: Float = 210f,

    val spiderAreaRight: Float = 790f,

    val spiderAreaBottom: Float = 510f,

    /** Player health hit-signal box (cyan in Points and Region setup) — triggers spider scan after rock. */

    val spiderHealthLeft: Float = 530f,

    val spiderHealthTop: Float = 52f,

    val spiderHealthRight: Float = 750f,

    val spiderHealthBottom: Float = 112f,

    /** Text scan line above the player for "Nothing here to mine". */

    val noMineTextLeft: Float = 430f,

    val noMineTextTop: Float = 275f,

    val noMineTextRight: Float = 850f,

    val noMineTextBottom: Float = 335f,

    /** Orange fire blob scan area (top of game screen). */
    val fireAreaLeft: Float = 129f,
    val fireAreaTop: Float = 189f,
    val fireAreaRight: Float = 1034f,
    val fireAreaBottom: Float = 631f,

    /** Fishing stop button — pauses fishing / ends pirate combat (movable in Fishing Detections setup). */
    val fishStopButton: Point = Point(1113f, 400f),

    /** Pirate + player HP scan box (smaller than drill box) for kill-pirate detection while fishing. */
    val fishPirateHpLeft: Float = 600f,
    val fishPirateHpTop: Float = 48f,
    val fishPirateHpRight: Float = 664f,
    val fishPirateHpBottom: Float = 66f

) {

    companion object {

        /** Screen-pixel drill box size — setup overlay and capture scan must stay in sync. */

        const val DRILL_BOX_WIDTH_PX = 92

        const val DRILL_BOX_HEIGHT_PX = 26

        /** Rock / tool-slot detection box — tight scan area (32×32 px). */

        const val ROCK_BOX_SIZE_PX = 32

        const val TOOL_SLOT_SIZE_PX = 32

        const val SPIDER_AREA_SIZE_PX = 300

        const val SPIDER_HEALTH_WIDTH_PX = 220

        const val SPIDER_HEALTH_HEIGHT_PX = 60

        const val NO_MINE_TEXT_WIDTH_PX = 420

        const val NO_MINE_TEXT_HEIGHT_PX = 60

        const val FIRE_AREA_WIDTH_PX = 350
        const val FIRE_AREA_HEIGHT_PX = 200

        /** Pirate HP detection — smaller than drill box (92×26). */
        const val FISH_PIRATE_HP_WIDTH_PX = 64
        const val FISH_PIRATE_HP_HEIGHT_PX = 18

        /** Default offset of fire button left of GRB in Points setup. */
        const val FIRE_BUTTON_LEFT_OF_GRB_PX = 200f

    }



    fun hasGameArea(): Boolean =

        gameAreaRight > gameAreaLeft && gameAreaBottom > gameAreaTop

    fun gameAreaRegion(): DetectionRegion? {
        if (!hasGameArea()) return null
        return DetectionRegion(gameAreaLeft, gameAreaTop, gameAreaRight, gameAreaBottom)
    }



    fun gameCenterX(): Float = (gameAreaLeft + gameAreaRight) / 2f

    fun gameCenterY(): Float = (gameAreaTop + gameAreaBottom) / 2f

    /** F (food) hotkey — saved from setup, or estimated left of T1. */
    fun resolvedFoodButton(): Point =
        if (foodButton.x > 0f && foodButton.y > 0f) foodButton
        else Point(tool1Button.x - 63f, tool1Button.y)

    /** Fire button — saved from setup, or 200px left of GRB. */
    fun resolvedFireButton(): Point =
        if (fireButton.x > 0f && fireButton.y > 0f) fireButton
        else Point(grabButton.x - FIRE_BUTTON_LEFT_OF_GRB_PX, grabButton.y)

    fun resolvedBuyButton(): Point? =
        if (buyButton.x > 0f && buyButton.y > 0f) buyButton else null

    fun resolvedOkButton(): Point? =
        if (okButton.x > 0f && okButton.y > 0f) okButton else null

    /** 32×32 scan box centred on the T2 tool slot (drill must be here before mining starts). */
    fun tool2SlotRegion(): DetectionRegion {
        val half = TOOL_SLOT_SIZE_PX / 2f
        return DetectionRegion(
            tool2Button.x - half,
            tool2Button.y - half,
            tool2Button.x + half,
            tool2Button.y + half
        )
    }

    fun rockRegion(side: String): DetectionRegion? {

        val region = when (side.uppercase()) {

            "UP" -> DetectionRegion(rockUpLeft, rockUpTop, rockUpRight, rockUpBottom)

            "DOWN" -> DetectionRegion(rockDownLeft, rockDownTop, rockDownRight, rockDownBottom)

            "LEFT" -> DetectionRegion(rockLeftLeft, rockLeftTop, rockLeftRight, rockLeftBottom)

            "RIGHT" -> DetectionRegion(rockRightLeft, rockRightTop, rockRightRight, rockRightBottom)

            else -> return null

        }

        return if (region.right > region.left && region.bottom > region.top) region else null

    }



    fun spiderAreaRegion(): DetectionRegion? {

        if (spiderAreaRight <= spiderAreaLeft || spiderAreaBottom <= spiderAreaTop) return null

        return DetectionRegion(spiderAreaLeft, spiderAreaTop, spiderAreaRight, spiderAreaBottom)

    }



    fun spiderHealthRegion(): DetectionRegion? {

        if (spiderHealthRight <= spiderHealthLeft || spiderHealthBottom <= spiderHealthTop) return null

        return DetectionRegion(spiderHealthLeft, spiderHealthTop, spiderHealthRight, spiderHealthBottom)

    }



    fun noMineTextRegion(): DetectionRegion? {

        if (noMineTextRight <= noMineTextLeft || noMineTextBottom <= noMineTextTop) return null

        return DetectionRegion(noMineTextLeft, noMineTextTop, noMineTextRight, noMineTextBottom)

    }

    fun fireAreaRegion(): DetectionRegion? {
        if (fireAreaRight <= fireAreaLeft || fireAreaBottom <= fireAreaTop) return null
        return DetectionRegion(fireAreaLeft, fireAreaTop, fireAreaRight, fireAreaBottom)
    }

    fun fishPirateHpRegion(): DetectionRegion? {
        if (fishPirateHpRight <= fishPirateHpLeft || fishPirateHpBottom <= fishPirateHpTop) return null
        return DetectionRegion(fishPirateHpLeft, fishPirateHpTop, fishPirateHpRight, fishPirateHpBottom)
    }

    fun resolvedFishStopButton(): Point =
        if (fishStopButton.x > 0f && fishStopButton.y > 0f) fishStopButton
        else Point(tauntButton.x, tauntButton.y + 60f)

    fun defaultFishPirateHpBounds(centerX: Float, centerY: Float): DetectionRegion {
        val halfW = FISH_PIRATE_HP_WIDTH_PX / 2f
        val halfH = FISH_PIRATE_HP_HEIGHT_PX / 2f
        val cy = centerY - 120f
        return DetectionRegion(centerX - halfW, cy - halfH, centerX + halfW, cy + halfH)
    }

    /** Default fire scan box at the top-left inside the game area. */
    fun defaultFireAreaBounds(areaLeft: Float, areaTop: Float): DetectionRegion {
        val w = FIRE_AREA_WIDTH_PX.toFloat()
        val h = FIRE_AREA_HEIGHT_PX.toFloat()
        return DetectionRegion(areaLeft + 20f, areaTop + 20f, areaLeft + 20f + w, areaTop + 20f + h)
    }



    /** Default rock box bounds around [gameCenter] for setup UI when nothing saved yet. */

    fun defaultRockBounds(side: String, centerX: Float, centerY: Float, offsetPx: Float = 70f): DetectionRegion {

        val half = ROCK_BOX_SIZE_PX / 2f

        val (cx, cy) = when (side.uppercase()) {

            "UP" -> centerX to (centerY - offsetPx)

            "DOWN" -> centerX to (centerY + offsetPx)

            "LEFT" -> (centerX - offsetPx) to centerY

            "RIGHT" -> (centerX + offsetPx) to centerY

            else -> centerX to centerY

        }

        return DetectionRegion(cx - half, cy - half, cx + half, cy + half)

    }



    fun defaultSpiderAreaBounds(centerX: Float, centerY: Float): DetectionRegion {

        val half = SPIDER_AREA_SIZE_PX / 2f

        return DetectionRegion(centerX - half, centerY - half, centerX + half, centerY + half)

    }



    fun defaultSpiderHealthBounds(centerX: Float, centerY: Float): DetectionRegion {

        val halfW = SPIDER_HEALTH_WIDTH_PX / 2f

        val halfH = SPIDER_HEALTH_HEIGHT_PX / 2f

        val cy = centerY - SPIDER_AREA_SIZE_PX / 2f - halfH - 8f

        return DetectionRegion(centerX - halfW, cy - halfH, centerX + halfW, cy + halfH)

    }



    fun defaultNoMineTextBounds(centerX: Float, centerY: Float): DetectionRegion {

        val halfW = NO_MINE_TEXT_WIDTH_PX / 2f

        val halfH = NO_MINE_TEXT_HEIGHT_PX / 2f

        val cy = centerY - 90f

        return DetectionRegion(centerX - halfW, cy - halfH, centerX + halfW, cy + halfH)

    }

}


