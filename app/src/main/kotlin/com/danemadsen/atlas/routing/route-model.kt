package com.danemadsen.atlas.routing

/**
 * The profile the user picked for a route. [assetName] is the bundled
 * BRouter profile the engine consumes at route time; these differ from
 * `all.brf`, which exists only to drive the bucket builds (it retains
 * every tag any runtime profile might look at).
 *
 * `foot.brf` is deliberately NOT here: BeeRouter ships `hiking-mountain`
 * as its foot profile and it matches the vendored `lookups.dat`.
 */
enum class RouteProfile(val assetName: String, val label: String) {
    CAR("car-vario.brf", "Car"),
    BIKE("fastbike.brf", "Bike"),
    FOOT("hiking-mountain.brf", "Walk"),
}

/** A WGS84 position in degrees. */
data class GeoPoint(val lon: Double, val lat: Double)

/**
 * The maneuver kinds the banner/TTS speak, distilled from the engine's
 * own turn commands. `ARRIVE` is the terminal hint at the destination.
 */
enum class TurnCommand {
    STRAIGHT,
    TURN_LEFT, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT,
    TURN_RIGHT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT,
    U_TURN,
    ROUNDABOUT, ROUNDABOUT_LEFT,
    EXIT_LEFT, EXIT_RIGHT,
    ARRIVE,
}

/**
 * One announced maneuver on the route, mapped from the engine's
 * [com.danemadsen.atlas.beerouter.router.VoiceHint]: where it happens
 * ([lon]/[lat] and [pointIndex] into [RouteResult.points]), what kind
 * it is, the street it leads onto, and how far along the route it sits
 * from the previous turn (or the origin).
 */
data class TurnPoint(
    val command: TurnCommand,
    val lon: Double,
    val lat: Double,
    val pointIndex: Int,
    /** Meters of route between this turn and the previous one (or the origin). */
    val distanceFromPreviousMeters: Double,
    /** The street the maneuver leads onto; null when the engine has no name. */
    val streetName: String?,
)

/**
 * One calculated route, ready for rendering and navigation.
 * The PMTiles pipeline carries no elevation, so [ascendMeters] stays 0 —
 * it remains in the model so the navigation panel can grow into it
 * without a breaking change.
 */
data class RouteResult(
    val profile: RouteProfile,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val ascendMeters: Int,
    val points: List<GeoPoint>,
    /** The announced maneuvers, ordered origin -> destination. */
    val turns: List<TurnPoint> = emptyList(),
)

/** Distance the way Google Maps says it: "850 m", "73.5 km". */
fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m"
    else String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)

/** Duration rounded up to whole minutes: "58 min", "1 h 05 min". */
fun formatDuration(seconds: Int): String {
    val total_minutes = (seconds + 59) / 60
    val hours = total_minutes / 60
    val minutes = total_minutes % 60
    return if (hours == 0) "$minutes min"
    else "$hours h " + minutes.toString().padStart(2, '0') + " min"
}