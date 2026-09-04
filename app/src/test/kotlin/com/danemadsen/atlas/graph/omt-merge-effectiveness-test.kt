package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.beerouter.map.generator.DiffCoderDataInputStream
import com.danemadsen.atlas.beerouter.map.generator.NodeData
import com.danemadsen.atlas.beerouter.map.generator.NodeFilter
import com.danemadsen.atlas.beerouter.map.generator.WayCutter
import com.danemadsen.atlas.beerouter.map.generator.WayData
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Discriminates the two candidate causes of the still-shattered OURS graph
 * after the fragment-relink fix:
 *
 *  A. the endpoint merge itself is buggy — same-wid fragment endpoints
 *     within the ~10 m threshold would still carry different node ids in
 *     the emitted `.wtl` files;
 *  B. planetiler's per-way simplification drops junction vertices — way Y
 *     ends on way X's middle (a ramp meeting a motorway), but X has no
 *     vertex within quantization distance of Y's endpoint, so no snap cell
 *     is shared and nothing links. Those endpoints show up as "dangling on
 *     a foreign segment".
 *
 * Not an assertion test: output is read by a human.
 */
class OmtMergeEffectivenessTest {

    @Test
    fun measureMergeEffectiveness() {
        val archive = File("src/test/fixtures/melbourne.pmtiles")
        if (!archive.isFile) {
            println("skipping: no fixture")
            return
        }
        val profile_dir = findProfileDir()
        val lookup_file = File(profile_dir, "lookups.dat")
        val profile_all = File(profile_dir, "all.brf")

        // One CBD window (dense) and one M1-corridor window (sparse) — the
        // corridor is where long routes die.
        for ((label, bounds) in listOf(
            "cbd" to TileBounds(144.55, -38.05, 144.75, -37.9),
            "m1-corridor" to TileBounds(144.40, -37.95, 144.60, -37.75),
        )) {
            val work = createTempDirectory("atlas-merge-").toFile()
            val nodes45 = File(work, "nodes45").apply { mkdirs() }
            val ways45 = File(work, "ways45").apply { mkdirs() }
            val wayCutter = WayCutter().also { it.init(ways45) }
            PmtilesCutter().also {
                it.wayCutter = wayCutter
                it.nodeFilter = NodeFilter().also { it.init() }
            }.process(
                reader = PmtilesReader(archive.absolutePath),
                lookupContent = lookup_file.readText(),
                profileContent = profile_all.readText(),
                nodeDir = nodes45,
                scanBounds = bounds,
                zoom = 14,
            )
            wayCutter.finish()
            measure(label, nodes45, ways45)
            work.deleteRecursively()
        }
    }

    private fun measure(label: String, nodes45: File, ways45: File) {
        // nid -> (ilon shl 32) or ilat
        val positions = HashMap<Long, Long>()
        nodes45.listFiles()!!.filter { it.name.endsWith(".ntl") }.forEach { f ->
            DiffCoderDataInputStream(BufferedInputStream(FileInputStream(f))).use { dis ->
                while (true) {
                    val data = try {
                        NodeData(dis)
                    } catch (_: EOFException) {
                        break
                    }
                    positions[data.nid] =
                        (data.position.longitude.toLong() shl 32) or
                        (data.position.latitude.toLong() and 0xFFFFFFFFL)
                }
            }
        }

        // wid -> ways (each WayData is one emitted fragment)
        val ways_by_wid = HashMap<Long, MutableList<WayData>>()
        var way_count = 0
        ways45.listFiles()!!.filter { it.name.endsWith(".wtl") }.forEach { f ->
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { dis ->
                while (true) {
                    val way = try {
                        WayData(dis)
                    } catch (_: EOFException) {
                        break
                    }
                    way_count++
                    ways_by_wid.getOrPut(way.wid) { mutableListOf() }.add(way)
                }
            }
        }

        var multi_fragment_wids = 0
        var relinked_via_shared_node = 0
        var merge_miss_within_10m = 0
        var merge_miss_within_50m = 0
        var fragment_pairs = 0
        val miss_examples = ArrayList<String>()

        // hypothesis B: endpoint -> is it dangling on a foreign segment?
        // Build a coarse segment index first (cells of ~100 m).
        val grid = 900L // E6 units per index cell (~100 m)
        // cell -> (node a, node b, wid) of every segment crossing the cell
        val seg_index = HashMap<Long, MutableList<Triple<Long, Long, Long>>>()
        val all_ways = ways_by_wid.values.flatten()
        for (way in all_ways) {
            for (i in 0 until way.nodes.size - 1) {
                val a = way.nodes.get(i)
                val b = way.nodes.get(i + 1)
                val pa = positions[a] ?: continue
                val pb = positions[b] ?: continue
                for (cell in cellsBetween(pa, pb, grid)) {
                    seg_index.getOrPut(cell) { mutableListOf() }.add(Triple(a, b, way.wid))
                }
            }
        }

        var endpoints_on_foreign_segment = 0
        var endpoints_isolated = 0
        var endpoints_total = 0
        var endpoints_stitched = 0

        for ((wid, ways) in ways_by_wid) {
            val endpoints = ways.flatMap { w ->
                listOf(w.nodes.get(0), w.nodes.get(w.nodes.size - 1))
            }
            if (ways.size > 1) {
                multi_fragment_wids++
                val shared = endpoints.toSet().size < endpoints.size
                if (shared) relinked_via_shared_node++

                for (i in ways.indices) {
                    for (j in i + 1 until ways.size) {
                        fragment_pairs++
                        val best = bestEndpointDist(ways[i], ways[j], positions)
                        if (best != null && best < 10.0) {
                            merge_miss_within_10m++
                            if (miss_examples.size < 8) {
                                val na = ways[i].nodes.get(0)
                                val nb = ways[j].nodes.get(0)
                                val pa = positions[na]
                                val pb = positions[nb]
                                if (pa != null && pb != null) {
                                    val dlon = (pa shr 32) - (pb shr 32)
                                    val dlat = (pa and 0xFFFFFFFFL) - (pb and 0xFFFFFFFFL)
                                    miss_examples.add(
                                        "wid=$wid d=${"%.1f".format(best)}m " +
                                            "dlon_e6=$dlon dlat_e6=$dlat " +
                                            "nids=$na/$nb nodes=${ways[i].nodes.size}+${ways[j].nodes.size}",
                                    )
                                }
                            }
                        }
                        if (best != null && best < 50.0) merge_miss_within_50m++
                    }
                }
            }

            // hypothesis B per endpoint: dangling = no other-way vertex and no
            // other-way endpoint within 2.4 m, but a foreign segment within 2.4 m.
            // An endpoint the stitch pass already repaired is visible as its own
            // node id inside some foreign way's node list — those count as
            // stitched, not dangling.
            for (nid in endpoints.toSet()) {
                endpoints_total++
                val p = positions[nid] ?: continue
                val stitched = all_ways.any { w ->
                    w.wid != wid && (0 until w.nodes.size).any { w.nodes.get(it) == nid }
                }
                if (stitched) {
                    endpoints_stitched++
                    continue
                }
                val near_vertex = all_ways.any { w ->
                    w.wid != wid && (0 until w.nodes.size).any { i ->
                        val other = w.nodes.get(i)
                        other != nid && distMeters(positions[other] ?: return@any false, p) < 2.4
                    }
                }
                if (near_vertex) continue
                val on_segment = foreignSegmentWithin(p, 2.4, wid, positions, seg_index, grid)
                if (on_segment) endpoints_on_foreign_segment++ else endpoints_isolated++
            }
        }

        println(
            "$label: $way_count ways (${ways_by_wid.size} wids), " +
                "$multi_fragment_wids multi-fragment wids, " +
                "relinked-via-shared-node=$relinked_via_shared_node/$multi_fragment_wids",
        )
        println(
            "$label: fragment pairs=$fragment_pairs, " +
                "endpoint pairs still <10m apart with different nids=$merge_miss_within_10m, " +
                "<50m=$merge_miss_within_50m",
        )
        miss_examples.forEach { println("$label miss: $it") }
        println(
            "$label: endpoints=$endpoints_total, stitched-into-foreign-way=" +
                "$endpoints_stitched, dangling-on-foreign-segment=" +
                "$endpoints_on_foreign_segment, " +
                "isolated (bbox edge / truly dangling)=$endpoints_isolated",
        )
    }

    /**
     * Nearest distance in meters between any endpoint pair of two fragments,
     * or null when they share a node (i.e. they ARE relinked — counting
     * those as misses was a measurement bug in the first version of this
     * test; a shared node must read as success, not as "0 m apart with
     * different ids").
     */
    private fun bestEndpointDist(a: WayData, b: WayData, positions: HashMap<Long, Long>): Double? {
        for (na in listOf(a.nodes.get(0), a.nodes.get(a.nodes.size - 1))) {
            for (nb in listOf(b.nodes.get(0), b.nodes.get(b.nodes.size - 1))) {
                if (na == nb) return null // relinked: not a miss
            }
        }
        var best: Double? = null
        for (na in listOf(a.nodes.get(0), a.nodes.get(a.nodes.size - 1))) {
            for (nb in listOf(b.nodes.get(0), b.nodes.get(b.nodes.size - 1))) {
                val pa = positions[na] ?: continue
                val pb = positions[nb] ?: continue
                val d = distMeters(pa, pb)
                if (best == null || d < best) best = d
            }
        }
        return best
    }

    /** Is [p] within [limit_m] meters of some other way's segment? */
    private fun foreignSegmentWithin(
        p: Long,
        limit_m: Double,
        own_wid: Long,
        positions: HashMap<Long, Long>,
        seg_index: HashMap<Long, MutableList<Triple<Long, Long, Long>>>,
        grid: Long,
    ): Boolean {
        val lon = p shr 32
        val lat = p and 0xFFFFFFFFL
        val cell_lon = lon / grid
        val cell_lat = lat / grid
        for (dl in -1..1) {
            for (dn in -1..1) {
                val segs = seg_index[(cell_lon + dl shl 32) or (cell_lat + dn)] ?: continue
                for ((a, b, wid) in segs) {
                    if (wid == own_wid) continue
                    val pa = positions[a] ?: continue
                    val pb = positions[b] ?: continue
                    if (pointSegmentDistMeters(p, pa, pb) < limit_m) return true
                }
            }
        }
        return false
    }

    /** Point-to-segment distance in meters (E6 space, lon shrunk by cos 38). */
    private fun pointSegmentDistMeters(p: Long, pa: Long, pb: Long): Double {
        val px = (p shr 32) * LON_SHRINK
        val py = (p and 0xFFFFFFFFL).toDouble()
        val ax = (pa shr 32) * LON_SHRINK
        val ay = (pa and 0xFFFFFFFFL).toDouble()
        val bx = (pb shr 32) * LON_SHRINK
        val by = (pb and 0xFFFFFFFFL).toDouble()
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) {
            0.0
        } else {
            (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        }
        val ex = ax + t * dx - px
        val ey = ay + t * dy - py
        return sqrt(ex * ex + ey * ey) * METERS_PER_E6
    }

    private fun distMeters(a: Long, b: Long): Double {
        val dx = ((a shr 32) - (b shr 32)) * LON_SHRINK
        val dy = ((a and 0xFFFFFFFFL) - (b and 0xFFFFFFFFL)).toDouble()
        return sqrt(dx * dx + dy * dy) * METERS_PER_E6
    }

    /** All index cells a segment's bounding box touches. */
    private fun cellsBetween(pa: Long, pb: Long, grid: Long): List<Long> {
        val lon0 = ((pa shr 32) / grid).coerceAtMost((pb shr 32) / grid)
        val lon1 = ((pa shr 32) / grid).coerceAtLeast((pb shr 32) / grid)
        val lat0 = ((pa and 0xFFFFFFFFL) / grid).coerceAtMost((pb and 0xFFFFFFFFL) / grid)
        val lat1 = ((pa and 0xFFFFFFFFL) / grid).coerceAtLeast((pb and 0xFFFFFFFFL) / grid)
        val cells = ArrayList<Long>(((lon1 - lon0 + 1) * (lat1 - lat0 + 1)).toInt())
        for (cl in lon0..lon1) {
            for (cn in lat0..lat1) {
                cells.add(cl shl 32 or cn)
            }
        }
        return cells
    }

    private fun findProfileDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File(System.getProperty("user.dir"), "misc/profiles2"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("could not find misc/profiles2")
    }

    private companion object {
        const val METERS_PER_E6 = 0.111
        const val LON_SHRINK = 0.788 // cos(38 deg), fixture latitude
    }
}