package com.antijail.overlay.model

import kotlinx.serialization.Serializable

@Serializable
data class Point(
    val x: Float,
    val y: Float
)

@Serializable
data class DragPath(
    val label: String,
    val start: Point,
    val end: Point,
    val durationMs: Long = 250L
)

@Serializable
data class ClickPoint(
    val label: String,
    val point: Point
)

@Serializable
data class DetectionRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

@Serializable
data class ActionProfile(
    val profileName: String = "default",
    val coordinateOrientation: String = "landscape",
    val pausePoint: Point = Point(120f, 330f),
    val drillRegion: DetectionRegion = DetectionRegion(360f, 170f, 430f, 240f),
    val movementPoints: List<ClickPoint> = listOf(
        // Default cluster near bottom-left in landscape.
        ClickPoint("UP", Point(175f, 760f)),
        ClickPoint("DOWN", Point(175f, 920f)),
        ClickPoint("RIGHT", Point(255f, 840f)),
        ClickPoint("LEFT", Point(95f, 840f))
    ),
    val meleePoint: ClickPoint = ClickPoint("MELEE", Point(820f, 130f)),
    val glovesPoint: ClickPoint = ClickPoint("GLOVES", Point(820f, 210f)),
    val homeClicks: List<ClickPoint> = listOf(
        ClickPoint("1st", Point(820f, 280f)),
        ClickPoint("2nd", Point(870f, 320f))
    ),
    val homeDrags: List<DragPath> = listOf(
        DragPath("3rd", Point(860f, 400f), Point(760f, 400f), 350L)
    ),
    val foodClicks: List<ClickPoint> = listOf(
        ClickPoint("1st", Point(820f, 360f)),
        ClickPoint("2nd", Point(820f, 360f)),
        ClickPoint("3rd", Point(820f, 360f))
    ),
    val foodDrags: List<DragPath> = listOf(
        DragPath("4th", Point(860f, 400f), Point(760f, 400f), 350L)
    ),
    val markerOpacity: Float = 1f,
    val drillHexColor: String = "#638d32",
    val colorTolerance: Int = 25
)

enum class EditTarget {
    NONE,
    PAUSE_DETECTION_MOVEMENT,
    HOME,
    FOOD
}
