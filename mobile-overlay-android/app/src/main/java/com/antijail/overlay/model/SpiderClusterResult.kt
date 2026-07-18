package com.antijail.overlay.model

/** Clustered spider colour scan inside the spider detection area. */
data class SpiderClusterResult(
    val detected: Boolean,
    val distinctColors: Int,
    /** Attack direction relative to player centre: UP, DOWN, LEFT, or RIGHT. */
    val direction: String = "UP"
)
