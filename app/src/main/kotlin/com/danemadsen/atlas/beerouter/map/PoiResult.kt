package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position

public data class PoiResult(
    val nodeId: Long,
    val position: Position,
    val tags: Map<String, String>,
    val sourceFile: String?,
)
