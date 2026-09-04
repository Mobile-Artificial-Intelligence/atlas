package com.danemadsen.atlas.routing

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import com.danemadsen.atlas.pmtiles.mvt.GeoPoint as TileLatLon
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Street names for the turn banner and TTS. The routing engine can never
 * supply them: a `.rd5` description bitmap only carries the `lookups.dat`
 * enumerations (bridge, tunnel, costfactor, …) and there is no "name"
 * lookup, so `VoiceHint.goodWay.wayTags` never holds one.
 *
 * The archive can: the route polyline itself identifies the way a turn
 * leads onto — the stretch just past the turn junction lies on it — so a
 * turn's name is "which named road in the z14 `transportation_name` layer
 * runs closest to that stretch". That is the same layer the map renders
 * road labels from, so the banner text and the on-map text agree.
 */
object StreetNameResolver {

    /** The zoom the archive keeps per-road `transportation_name` names at. */
    private const val NAME_ZOOM = 14

    /** How far past the turn junction the route is sampled. */
    private const val SAMPLE_AHEAD_METERS = 60.0

    /**
     * How close a named road must run to the samples to own the turn. z14
     * linework is tile-clipped and vertex-simplified, so exact overlap is
     * too strict — but a whole 30 m keeps the parallel street out.
     */
    private const val MAX_MATCH_METERS = 30.0

    /**
     * Returns [turns] with `streetName` filled in where a named road
     * matches. [tileAt] answers "the z14 tile at x, y" (null when the
     * archive has no tile there); the reader-backed call site supplies it,
     * tests supply synthetic tiles. Names that resolve to nothing stay
     * null — the banner and TTS already handle a nameless maneuver.
     */
    fun resolveNames(
        points: List<GeoPoint>,
        turns: List<TurnPoint>,
        tileAt: (x: Int, y: Int) -> ByteArray?,
    ): List<TurnPoint> {
        if (points.size < 2 || turns.isEmpty()) return turns
        val tile_cache = HashMap<TileKey, List<NamedRoad>>()
        return turns.mapIndexed { turn_index, turn ->
            if (turn.command == TurnCommand.ARRIVE) return@mapIndexed turn
            val samples = samplesAhead(points, turns, turn_index)
            if (samples.isEmpty()) return@mapIndexed turn
            val name = closestName(samples, tileAt, tile_cache) ?: return@mapIndexed turn
            turn.copy(streetName = name)
        }
    }

    /** Convenience overload over a live archive; callers own the reader. */
    fun resolveNames(
        reader: PmtilesReader,
        points: List<GeoPoint>,
        turns: List<TurnPoint>,
    ): List<TurnPoint> =
        resolveNames(points, turns) { x, y -> reader.tile(NAME_ZOOM, x, y) }

    /**
     * The route points past the turn junction, up to [SAMPLE_AHEAD_METERS]
     * — and, crucially, only up to (and not past) the NEXT turn's junction:
     * sampling past it reads the street after the next maneuver, which can
     * sit closer to the samples and win the match with the wrong name.
     *
     * The junction point itself is excluded: it lies on the INCOMING
     * street, so sampling it would let the street the user is turning off
     * of claim the maneuver at distance ~0.
     */
    private fun samplesAhead(
        points: List<GeoPoint>,
        turns: List<TurnPoint>,
        turn_index: Int,
    ): List<GeoPoint> {
        val cursor_start = turns[turn_index].pointIndex
        if (cursor_start < 0 || cursor_start >= points.size - 1) return emptyList()
        val next_junction = turns.getOrNull(turn_index + 1)?.pointIndex ?: points.size - 1
        val samples = ArrayList<GeoPoint>()
        var cursor = cursor_start
        var meters = 0.0
        while (cursor < next_junction && meters < SAMPLE_AHEAD_METERS) {
            meters += metersBetween(
                points[cursor].lon, points[cursor].lat,
                points[cursor + 1].lon, points[cursor + 1].lat,
            )
            cursor++
            samples.add(points[cursor])
        }
        return samples
    }

    /**
     * The named road closest to [samples] (within [MAX_MATCH_METERS]),
     * across every tile the samples touch. The samples of one turn almost
     * always share a tile, so the cache holds across a route's turns.
     */
    private fun closestName(
        samples: List<GeoPoint>,
        tileAt: (x: Int, y: Int) -> ByteArray?,
        tile_cache: MutableMap<TileKey, List<NamedRoad>>,
    ): String? {
        var best_name: String? = null
        var best_distance = MAX_MATCH_METERS
        var min_lon = Double.MAX_VALUE
        var min_lat = Double.MAX_VALUE
        var max_lon = -Double.MAX_VALUE
        var max_lat = -Double.MAX_VALUE
        for (sample in samples) {
            min_lon = minOf(min_lon, sample.lon)
            max_lon = maxOf(max_lon, sample.lon)
            min_lat = minOf(min_lat, sample.lat)
            max_lat = maxOf(max_lat, sample.lat)
        }
        // MAX_MATCH_METERS expressed in degrees for the per-road bbox
        // prefilter; the longitude term widens with latitude as meridians
        // converge.
        val lat_margin = MAX_MATCH_METERS / 110_540.0
        val lon_margin = MAX_MATCH_METERS / (111_320.0 * cos(Math.toRadians((min_lat + max_lat) / 2)))

        val tiles_seen = LinkedHashSet<TileKey>()
        for (sample in samples) {
            val key = TileKey(
                PmtilesReader.lonToTileX(sample.lon, NAME_ZOOM),
                PmtilesReader.latToTileY(sample.lat, NAME_ZOOM),
            )
            if (!tiles_seen.add(key)) continue
            for (road in namedRoads(tileAt, tile_cache, key)) {
                if (road.maxLon + lon_margin < min_lon || road.minLon - lon_margin > max_lon ||
                    road.maxLat + lat_margin < min_lat || road.minLat - lat_margin > max_lat
                ) {
                    continue
                }
                for (path in road.paths) {
                    for (i in 0 until path.size - 1) {
                        for (probe in samples) {
                            val distance = distanceToSegmentMeters(
                                probe.lon, probe.lat,
                                path[i].lon, path[i].lat,
                                path[i + 1].lon, path[i + 1].lat,
                            )
                            if (distance < best_distance) {
                                best_distance = distance
                                best_name = road.name
                            }
                        }
                    }
                }
            }
        }
        return best_name
    }

    /** Decodes (and caches) one tile's `transportation_name` linework. */
    private fun namedRoads(
        tileAt: (x: Int, y: Int) -> ByteArray?,
        tile_cache: MutableMap<TileKey, List<NamedRoad>>,
        key: TileKey,
    ): List<NamedRoad> {
        tile_cache[key]?.let { return it }
        val roads = tileAt(key.x, key.y)?.let { bytes ->
            val layer = MvtTile.decode(bytes).layer("transportation_name")
                ?: return@let emptyList()
            layer.features.mapNotNull { feature ->
                if (feature.geomType != MvtGeomType.LINESTRING) return@mapNotNull null
                val props = layer.properties(feature)
                val name = (props["name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (props["ref"] as? String)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val paths = layer.pathsLonLat(feature, NAME_ZOOM, key.x, key.y)
                if (paths.none { it.size >= 2 }) return@mapNotNull null
                var min_lon = Double.MAX_VALUE
                var min_lat = Double.MAX_VALUE
                var max_lon = -Double.MAX_VALUE
                var max_lat = -Double.MAX_VALUE
                for (path in paths) {
                    for (point in path) {
                        min_lon = minOf(min_lon, point.lon)
                        max_lon = maxOf(max_lon, point.lon)
                        min_lat = minOf(min_lat, point.lat)
                        max_lat = maxOf(max_lat, point.lat)
                    }
                }
                NamedRoad(name, paths, min_lon, min_lat, max_lon, max_lat)
            }
        } ?: emptyList()
        tile_cache[key] = roads
        return roads
    }

    /**
     * Point-to-segment distance in meters. The projection parameter is a
     * ratio computed in degrees (the flat-plane scale of [metersBetween]
     * cancels), exactly like the navigation code's snap projection.
     */
    private fun distanceToSegmentMeters(
        probe_lon: Double,
        probe_lat: Double,
        a_lon: Double,
        a_lat: Double,
        b_lon: Double,
        b_lat: Double,
    ): Double {
        val ab_lon = b_lon - a_lon
        val ab_lat = b_lat - a_lat
        val denominator = ab_lon * ab_lon + ab_lat * ab_lat
        val t = if (denominator > 0.0) {
            (((probe_lon - a_lon) * ab_lon + (probe_lat - a_lat) * ab_lat) / denominator)
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return metersBetween(probe_lon, probe_lat, a_lon + ab_lon * t, a_lat + ab_lat * t)
    }

    /** Flat local-plane meters — the convention the whole app uses. */
    private fun metersBetween(
        lon1: Double,
        lat1: Double,
        lon2: Double,
        lat2: Double,
    ): Double {
        val mean_lat = Math.toRadians((lat1 + lat2) / 2)
        val dx = (lon2 - lon1) * 111_320.0 * cos(mean_lat)
        val dy = (lat2 - lat1) * 110_540.0
        return sqrt(dx * dx + dy * dy)
    }

    private data class TileKey(val x: Int, val y: Int)

    /** One named road's linework (possibly several paths per feature). */
    private class NamedRoad(
        val name: String,
        val paths: List<List<TileLatLon>>,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
    )
}