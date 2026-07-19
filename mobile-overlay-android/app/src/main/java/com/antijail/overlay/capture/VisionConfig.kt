package com.antijail.overlay.capture







/**



 * Vision tuning for rock / spider colour scans and drill colours.



 */



object VisionConfig {



    const val ROCK_SIMILARITY = 0.70







    /** Mined-rock pixels — any match inside the scan box triggers a direction switch. */



    val ROCK_MINED_COLORS = listOf(



        "#755a18", "#735611", "#7b6121",



        "#7b6731", "#7b6d42", "#735510"



    )



    /** Slightly looser than before so laggy/dark frames still catch mined rock. */
    const val ROCK_COLOR_TOLERANCE = 18



    const val ROCK_MIN_MATCH_PIXELS = 2



    val ROCK_TEMPLATE_SCALES = floatArrayOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f)

    val TEMPLATE_SCALES = floatArrayOf(0.75f, 0.85f, 1.0f, 1.15f, 1.25f)



    /** Spider body colours — must appear clustered (not one pixel each across the box). */



    val SPIDER_BODY_COLORS = listOf(
        "#4c4410", "#4b4310", "#574e11", "#4e4310", "#453e0f", "#48410e"
    )

    const val SPIDER_COLOR_TOLERANCE = 10

    /** Fast scans use the same tight tolerance — looser values matched ground/rocks. */
    const val SPIDER_COLOR_TOLERANCE_FAST = 10

    /** Distinct spider colours required in one local cluster to engage combat. */
    const val SPIDER_MIN_DISTINCT_COLORS = 2

    const val SPIDER_MIN_DISTINCT_COLORS_FAST = 2

    /** Still alive if at least this many spider colours remain clustered. */
    const val SPIDER_ALIVE_MIN_DISTINCT_COLORS = 1

    /** Local window (px) — spider shades must co-occur inside this square. */
    const val SPIDER_CLUSTER_WINDOW_PX = 56

    const val SPIDER_CLUSTER_WINDOW_PX_FAST = 56

    /** Need a few co-located pixels so dirt/rock flecks don't become a "spider". */
    const val SPIDER_MIN_CLUSTER_PIXELS = 3

    const val SPIDER_CLUSTER_STEP_PX = 8

    const val SPIDER_CLUSTER_STEP_PX_FAST = 8





    /** Player health hit signal (cyan box in Game Screen setup) — 4+ colours = spider still damaging player. */



    val HEALTH_SIGNAL_COLORS = listOf("#d97d76", "#a9cfce", "#e8e7a5", "#7bc4e7", "#ac1c19")



    const val HEALTH_SIGNAL_TOLERANCE = 12



    const val HEALTH_SIGNAL_MIN_COLORS = 4

    /** Fast/post-rock HP — 3 colours (2 was too loose; 4 missed real hits). */
    const val HEALTH_SIGNAL_MIN_COLORS_FAST = 3

    const val HEALTH_SIGNAL_MIN_PIXELS = 1

    /** Pirate HP bar colours while fishing — any one must appear with player HP red. */
    val PIRATE_HP_COLORS = listOf(
        "#505850", "#4f5952", "#4b4f50",
        "#233b29", "#2f5158", "#2a4653", "#1f3924", "#102f3a"
    )

    const val PLAYER_HP_COLOR = "#da151e"

    const val PIRATE_HP_TOLERANCE = 2

    const val PIRATE_HP_MIN_PIXELS = 1

    /** Orange fire blob colours (desktop AntiJail) — legacy colour scan. */

    val FIRE_COLORS = listOf("#ff961e", "#ff4312", "#fe941b")

    const val FIRE_COLOR_TOLERANCE = 12

    const val FIRE_MIN_MATCH_PIXELS = 80



    /** Fire sprite templates (fire1.png / fire2.png) scanned inside the game screen area. */

    const val FIRE_MIN_MATCH_SCORE = 0.65

    val FIRE_TEMPLATE_SCALES = floatArrayOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f)



    /** Drill icon on tool slot 2 — required before mining can start. */

    const val DRILL_ON_TOOL2_MIN_MATCH_SCORE = 0.60

    val TOOL_SLOT_TEMPLATE_SCALES = floatArrayOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f)



    /** Movement / navigation template matches (plant, thrift shop, battery shop, etc.). */

    const val MOVEMENT_MIN_MATCH_SCORE = 0.65



    /** Plant exit marker — scanned after mine-collapse to pick escape direction. */

    const val PLANT_MIN_MATCH_SCORE = MOVEMENT_MIN_MATCH_SCORE

    val PLANT_TEMPLATE_SCALES = floatArrayOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f)



    /** Battery shop item — scanned during battery-dead recovery. */

    const val BATTERY_MIN_MATCH_SCORE = MOVEMENT_MIN_MATCH_SCORE

    val BATTERY_TEMPLATE_SCALES = floatArrayOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f)



    /** Shop UI buttons (yes / buy / ok) during battery-dead recovery. */

    const val SHOP_UI_MIN_MATCH_SCORE = 0.60

    val SHOP_UI_TEMPLATE_SCALES = BATTERY_TEMPLATE_SCALES



    /** Mine-collapse route markers on the game screen (thrift shop, etc.). */

    const val COLLAPSE_MARKER_MIN_MATCH_SCORE = MOVEMENT_MIN_MATCH_SCORE

    val COLLAPSE_MARKER_TEMPLATE_SCALES = floatArrayOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f)



    /** Thrift shop sign (thriftshop.png) during mine-collapse escape — lower threshold for easier match. */

    const val THRIFT_SHOP_MIN_MATCH_SCORE = 0.30



    /** Brown shop-entrance landmark on the game screen during battery-dead step 4. */

    const val BATTERY_LANDMARK_COLOR = "#9c7952"

    const val BATTERY_LANDMARK_TOLERANCE = 15

    const val BATTERY_LANDMARK_CLUSTER_WINDOW_PX = 48

    const val BATTERY_LANDMARK_MIN_CLUSTER_PIXELS = 6

    const val BATTERY_LANDMARK_CLUSTER_STEP_PX = 8



    const val ROCK_MIN_MATCH_SCORE = ROCK_SIMILARITY



}







