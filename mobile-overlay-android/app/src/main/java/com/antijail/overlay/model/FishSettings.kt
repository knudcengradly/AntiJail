package com.antijail.overlay.model

import kotlinx.serialization.Serializable

@Serializable
data class FishSettings(
    /** When enabled, poll the pirate HP box and run the kill-pirate combat sequence. */
    val killPirates: Boolean = true
)
