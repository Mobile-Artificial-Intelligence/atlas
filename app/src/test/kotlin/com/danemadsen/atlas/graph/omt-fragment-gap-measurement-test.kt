package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.GeoPoint
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Dev-machine measurement backing the fragment-relink design: for every
 * transportation feature id appearing in multiple z14 tiles, how far apart
 * are the fragment endpoints that should reconnect, and do fragments ever
 * share an interior vertex (snap cell)? The distance histogram sets the
 * merge threshold; the shared-vertex count proves exact-cell relinking is
 * structurally insufficient on its own.
 */
class OmtFragmentGapMeasurementTest {

    @Test
    fun measureFragmentEndpointGaps() {
        val archive = java.io.File("src/test/fixtures/melbourne.pmtiles")
        if (!archive.isFile) {
            println("skipping: no fixture")
            return
        }

        // wid -> (endpoint list: position + which end + fragment index)
        val endpoints = HashMap<Long, MutableList<Endpoint>>()
        // wid -> set of interior snap cells (to measure shared vertices)
        val interior_cells = HashMap<Long, MutableSet<Long>>()
        var multi_tile_wids = 0

        PmtilesReader(archive.absolutePath).use { reader ->
            val bounds = TileBounds(west = 144.55, south = -38.05, east = 144.75, north = -37.9)
            reader.forEachTileInBounds(14, bounds) { z, x, y, bytes ->
                val tile = MvtTile.decode(bytes)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != MvtGeomType.LINESTRING) continue
                    val tags = TransportationTagSynthesis.synthesizeTags(layer.properties(feature))
                        ?: continue
                    val paths = layer.pathsLonLat(feature, z, x, y)
                    if (paths.isEmpty()) continue
                    val wid = feature.id
                    if (wid == 0L) continue
                    val list = endpoints.getOrPut(wid) { mutableListOf() }
                    val cells = interior_cells.getOrPut(wid) { mutableSetOf() }
                    if (list.isNotEmpty()) multi_tile_wids++
                    for (path in paths) {
                        if (path.size < 2) continue
                        list.add(Endpoint(snapCell(path.first()), true, list.size))
                        list.add(Endpoint(snapCell(path.last()), false, list.size))
                        for (i in 1 until path.size - 1) {
                            cells.add(snapCell(path[i]))
                        }
                    }
                }
            }
        }

        val buckets = linkedMapOf(
            "<1m" to 0, "1-2m" to 0, "2-5m" to 0, "5-10m" to 0, "10-20m" to 0,
            "20-50m" to 0, ">50m" to 0,
        )
        var nearest_pairs_checked = 0
        var same_cell_pairs = 0
        var shared_interior_wids = 0
        var pair_kind_last_first = 0
        var pair_kind_other = 0

        for ((wid, list) in endpoints) {
            if (list.size < 2) continue
            // nearest pair among distinct fragments
            var best_d2 = Double.MAX_VALUE
            var best: Pair<Endpoint, Endpoint>? = null
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (list[i].fragment == list[j].fragment) continue
                    val d2 = dist2(list[i].packed, list[j].packed)
                    if (d2 < best_d2) {
                        best_d2 = d2
                        best = list[i] to list[j]
                    }
                }
            }
            val pair = best ?: continue
            nearest_pairs_checked++
            if (pair.first.packed == pair.second.packed) same_cell_pairs++
            if ((pair.first.is_first && !pair.second.is_first) ||
                (!pair.first.is_first && pair.second.is_first)
            ) pair_kind_last_first++ else pair_kind_other++
            val meters = sqrt(best_d2) * meters_per_e6
            when {
                meters < 1 -> buckets["<1m"] = buckets["<1m"]!! + 1
                meters < 2 -> buckets["1-2m"] = buckets["1-2m"]!! + 1
                meters < 5 -> buckets["2-5m"] = buckets["2-5m"]!! + 1
                meters < 10 -> buckets["5-10m"] = buckets["5-10m"]!! + 1
                meters < 20 -> buckets["10-20m"] = buckets["10-20m"]!! + 1
                meters < 50 -> buckets["20-50m"] = buckets["20-50m"]!! + 1
                else -> buckets[">50m"] = buckets[">50m"]!! + 1
            }
            // does any OTHER fragment reuse this wid's interior cells?
            val endpoint_cells = list.map { it.packed }.toSet()
            var shared = false
            for (cell in interior_cells[wid]!!) {
                // (interior cell of one fragment being another fragment's
                // endpoint cell means relink via identical source vertex)
                if (cell in endpoint_cells) shared = true
            }
            if (shared) shared_interior_wids++
        }

        println("wids with fragments in >1 tile: $multi_tile_wids")
        println("nearest endpoint-pair distance histogram: $buckets")
        println("pairs already in same cell: $same_cell_pairs / $nearest_pairs_checked")
        println("pair orientation last/first (contiguous): $pair_kind_last_first, other: $pair_kind_other")
        println("wids relinking via shared interior vertex: $shared_interior_wids")
    }

    private class Endpoint(val packed: Long, val is_first: Boolean, val fragment: Int)

    /** Same packed key as PmtilesCutter.packCell: (ilon shl 25) or ilat. */
    private fun snapCell(p: GeoPoint): Long {
        val ilon = (((p.lon + 180.0) * 100_000).toInt()) * 10
        val ilat = (((p.lat + 90.0) * 100_000).toInt()) * 10
        return (ilon.toLong() shl 25) or ilat.toLong()
    }

    /** Squared distance in E6 units between two packed cells. */
    private fun dist2(a: Long, b: Long): Double {
        val dlon = (a shr 25) - (b shr 25) // E6 units
        val dlat = (a and 0x1FFFFFF) - (b and 0x1FFFFFF)
        // convert to meters-ish: 1 E6 unit lat = 0.111m; lon shrink by cos(38)
        val mlon = dlon * 0.111 * 0.788
        val mlat = dlat * 0.111
        return mlon * mlon + mlat * mlat
    }

    private companion object {
        const val meters_per_e6 = 0.111 // approx for latitude d2 in meters^2
    }
}