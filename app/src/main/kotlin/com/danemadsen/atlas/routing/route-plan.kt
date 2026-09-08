package com.danemadsen.atlas.routing

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** A chosen place, an empty field, or a live-location placeholder. IDs survive reordering. */
data class RouteStop(
    val id: String = UUID.randomUUID().toString(),
    val point: GeoPoint? = null,
    val name: String = "",
    val isCurrentLocation: Boolean = false,
) {
    val isFilled: Boolean get() = isCurrentLocation || point != null
    val label: String get() = when {
        isCurrentLocation -> "Your location"
        name.isNotBlank() -> name
        point != null -> String.format(Locale.US, "%.5f, %.5f", point.lat, point.lon)
        else -> ""
    }
}

/** Ordered itinerary; the final row is always the destination. */
data class RoutePlan(
    val stops: List<RouteStop> = listOf(RouteStop(isCurrentLocation = true), RouteStop()),
    val profile: RouteProfile = RouteProfile.CAR,
) {
    init {
        require(stops.size in 2..MAX_ROUTE_STOPS)
        require(stops.map { it.id }.distinct().size == stops.size)
    }

    val isComplete: Boolean get() = stops.all { it.isFilled }
    val canAddStop: Boolean get() = stops.size < MAX_ROUTE_STOPS
    val startsAtCurrentLocation: Boolean get() = stops.first().isCurrentLocation

    fun replace(id: String, stop: RouteStop): RoutePlan =
        copy(stops = stops.map { if (it.id == id) stop.copy(id = id) else it })

    fun add(stop: RouteStop = RouteStop()): RoutePlan =
        if (canAddStop) copy(stops = stops + stop) else this

    fun remove(id: String): RoutePlan =
        if (stops.size > 2) copy(stops = stops.filterNot { it.id == id }) else this

    fun move(id: String, to: Int): RoutePlan {
        val from = stops.indexOfFirst { it.id == id }
        if (from < 0 || to !in stops.indices || from == to) return this
        val reordered = stops.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return copy(stops = reordered)
    }

    fun reversed(): RoutePlan = copy(stops = stops.reversed())

    /** Resolve GPS once for the entire request, only when a row uses it. */
    fun resolve(currentLocation: GeoPoint?): List<GeoPoint> = stops.map { stop ->
        if (stop.isCurrentLocation) requireNotNull(currentLocation) { "Your location is unavailable" }
        else requireNotNull(stop.point) { "Choose every stop" }
    }
}

/** Origin plus up to nine destinations, matching the familiar directions editor. */
const val MAX_ROUTE_STOPS = 10

/** One atomic preference preserves labels, precision, order and the live GPS placeholder. */
object RoutePlanStore {
    fun encode(plan: RoutePlan): String = JSONObject()
        .put("version", 1)
        .put("profile", plan.profile.name)
        .put("stops", JSONArray().apply {
            plan.stops.forEach { stop ->
                put(JSONObject().apply {
                    put("id", stop.id)
                    put("name", stop.name)
                    put("current", stop.isCurrentLocation)
                    stop.point?.let { put("lon", it.lon); put("lat", it.lat) }
                })
            }
        }).toString()

    fun decode(json: String?): RoutePlan? = runCatching {
        val root = JSONObject(json ?: return null)
        if (root.getInt("version") != 1) return null
        val array = root.getJSONArray("stops")
        if (array.length() !in 2..MAX_ROUTE_STOPS) return null
        val stops = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val current = item.optBoolean("current", false)
            val point = if (item.has("lon") || item.has("lat")) {
                GeoPoint(item.getDouble("lon"), item.getDouble("lat")).also {
                    require(it.lon.isFinite() && it.lon in -180.0..180.0)
                    require(it.lat.isFinite() && it.lat in -90.0..90.0)
                }
            } else null
            RouteStop(item.getString("id"), point, item.optString("name", ""), current)
        }
        RoutePlan(stops, RouteProfile.entries.firstOrNull { it.name == root.optString("profile") }
            ?: RouteProfile.CAR)
    }.getOrNull()
}
