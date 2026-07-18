package com.antijail.overlay.model

import kotlinx.serialization.Serializable

@Serializable
data class SetupProgress(
    val pointsAndRegionDone: Boolean = false,
    val zoomLevelDone: Boolean = false,
    val gameScreenAreaDone: Boolean = false
) {
    val allComplete: Boolean
        get() = pointsAndRegionDone && zoomLevelDone && gameScreenAreaDone

    fun pendingItems(): List<Pair<SetupTarget, String>> = buildList {
        if (!pointsAndRegionDone) add(SetupTarget.POINTS_AND_REGION to "Points and Region Setup")
        if (!zoomLevelDone) add(SetupTarget.ZOOM_LEVEL to "Zoom Level Setup")
        if (!gameScreenAreaDone) add(SetupTarget.GAME_SCREEN_AREA to "Game Screen Area")
    }

    companion object {
        /** Best-effort migration when no progress flags were saved yet. */
        fun inferFromLayout(layout: SetupLayout, hasSavedLayout: Boolean): SetupProgress {
            if (!hasSavedLayout) return SetupProgress()
            val hasGameArea = layout.gameAreaRight > layout.gameAreaLeft &&
                layout.gameAreaBottom > layout.gameAreaTop
            val hasDrill = layout.drillRight > layout.drillLeft &&
                layout.drillBottom > layout.drillTop
            return SetupProgress(
                pointsAndRegionDone = hasDrill,
                zoomLevelDone = hasSavedLayout && hasGameArea && hasDrill,
                gameScreenAreaDone = hasGameArea
            )
        }
    }
}
