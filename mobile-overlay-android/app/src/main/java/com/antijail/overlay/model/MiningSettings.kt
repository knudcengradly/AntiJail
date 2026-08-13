package com.antijail.overlay.model

import kotlinx.serialization.Serializable

@Serializable
enum class MiningDirection(val displayLabel: String) {
    DOWN_LEFT("Down & Left"),
    DOWN_RIGHT("Down & Right"),
    UP_LEFT("Up & Left"),
    UP_RIGHT("Up & Right"),
    UP_ONLY_MOVE_LR("Up only, Moving left & right"),
    DOWN_ONLY_MOVE_LR("Down only, Moving left & right"),
    LEFT_ONLY_MOVE_UD("Left only, Moving up & down"),
    RIGHT_ONLY_MOVE_UD("Right only, Moving up & down")
}

@Serializable
data class MiningSettings(
    val direction: MiningDirection = MiningDirection.DOWN_LEFT,
    val mineLevel: Int = 0,
    val additionalDelaySeconds: Float = 0.5f,
    /** Base seconds between mining clicks at level 0 (each mine level −0.075s). */
    val baseClickIntervalSeconds: Float = 7.5f,
    /** Seconds to wait after a rock is detected before switching direction. */
    val rockMinedDelaySeconds: Float = 2f,
    /** T1 swipe + food tap cycles when eating (manual food or post-spider). */
    val eatFoodCount: Int = 3,
    /**
     * Spot to walk to after returning to the mine (fire, battery dead, or Home).
     * Values 1–6; all locations walk after return (4–6 include Graal return).
     */
    val miningLocation: Int = 1,
    val moveWhenStationary: Boolean = true,
    val killSpiders: Boolean = true,
    val drillDetection: Boolean = true,
    val autoEatAfterSpiderKill: Boolean = true,
    val autoMineCollapsed: Boolean = true,
    val exitHospital: Boolean = true,
    val noBattery: Boolean = true
)
