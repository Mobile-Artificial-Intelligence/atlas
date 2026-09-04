package com.danemadsen.atlas.beerouter.map

public data class PoiQuery(
    val minLon: Int,
    val minLat: Int,
    val maxLon: Int,
    val maxLat: Int,
    val requiredTags: Map<String, Set<String>> = emptyMap(),
    val limit: Int? = null,
) {
    init {
        require(minLon <= maxLon) { "minLon must be <= maxLon" }
        require(minLat <= maxLat) { "minLat must be <= maxLat" }
        require(limit == null || limit > 0) { "limit must be > 0 when specified" }
    }
}
