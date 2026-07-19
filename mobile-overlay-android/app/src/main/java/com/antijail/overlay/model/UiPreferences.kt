package com.antijail.overlay.model



import kotlinx.serialization.Serializable



@Serializable

data class UiPreferences(

    val lastAppMode: AppMode = AppMode.MINE,

    val lastSetupTarget: SetupTarget = SetupTarget.POINTS_AND_REGION,

    /**
     * Detection/image zoom calibration (0.5–2.0). Default 50% on phones — scales detection regions
     * and template matching only, not tap buttons. Adjust via Setup → Zoom Level slider.
     */
    val detectionScale: Float = 0.5f,

    /**
     * Last Reference Spot *position only* (box coords). Restored on next Proceed.
     * The pixels inside the box are NEVER saved here — they are captured fresh each Start
     * and discarded when the macro stops.
     */
    val lastReferenceSpot: DetectionRegion? = null

)



fun SetupTarget.spinnerIndex(): Int = when (this) {

    SetupTarget.POINTS_AND_REGION -> 0

    SetupTarget.ZOOM_LEVEL -> 1

    SetupTarget.GAME_SCREEN_AREA -> 2

    SetupTarget.SNAPSHOT -> 3

}



fun setupTargetFromSpinnerIndex(index: Int): SetupTarget = when (index) {

    1 -> SetupTarget.ZOOM_LEVEL

    2 -> SetupTarget.GAME_SCREEN_AREA

    3 -> SetupTarget.SNAPSHOT

    else -> SetupTarget.POINTS_AND_REGION

}


