package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.MapSource
import com.danemadsen.atlas.beerouter.map.RandomAccessReader
import com.danemadsen.atlas.beerouter.map.generator.DiffCoderDataInputStream
import com.danemadsen.atlas.beerouter.map.generator.NodeCutter
import com.danemadsen.atlas.beerouter.map.generator.NodeData
import com.danemadsen.atlas.beerouter.map.generator.NodeFilter
import com.danemadsen.atlas.beerouter.map.generator.OsmCutter
import com.danemadsen.atlas.beerouter.map.generator.PosUnifier
import com.danemadsen.atlas.beerouter.map.generator.WayCutter
import com.danemadsen.atlas.beerouter.map.generator.WayCutter5
import com.danemadsen.atlas.beerouter.map.generator.WayData
import com.danemadsen.atlas.beerouter.map.generator.WayLinker
import com.danemadsen.atlas.beerouter.router.OsmNodeNamed
import com.danemadsen.atlas.beerouter.router.RoutingContext
import com.danemadsen.atlas.beerouter.router.RoutingEngine
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Dev-machine diagnostic for the M5c differential failure ("no track found"
 * at pass=0" on the 55km CBD->Geelong route while short probes route fine).
 * Runs the same pipeline stages as GraphPipeline.buildBucket but analyzes
 * the intermediates BETWEEN stages (the vendored iterators delete tiles as
 * they consume them):
 *
 *  - phase-1 (.ntl/.wtl): union-find over way fragments — tests the snap
 *    grid's relinking of tile-clipped way fragments directly;
 *  - bucket (.n5d/.wt5): the graph that actually feeds WayLinker;
 *  - an ASCII map of which nodes are in the start node's connected
 *    component — shows WHERE the network is cut;
 *  - routing probes bisecting the CBD->Geelong corridor, on both the
 *    PMTiles graph and a PBF reference minted the same way.
 *
 * Not an assertion test: output is read by a human.
 */
class OmtPipelineDiagnosticTest {

    private val start_lon = 144.9669
    private val start_lat = -37.8183
    private val end_lon = 144.3680
    private val end_lat = -38.1493

    @Test
    fun stageStatsAndConnectivity() {
        val archive = File("src/test/fixtures/melbourne.pmtiles")
        val pbf = File("src/test/fixtures/melbourne.pbf")
        if (!archive.isFile || !pbf.isFile) {
            println("skipping: fixtures missing")
            return
        }
        val profile_dir = findProfileDir()
        val lookup_file = File(profile_dir, "lookups.dat")
        val profile_all = File(profile_dir, "all.brf")
        val car_profile = File(profile_dir, "car-vario.brf").readText()
        val car_meta = BExpressionMetaData()
        val car_ctx = BExpressionContextWay(car_meta)
        car_meta.readMetaData(lookup_file.readText())
        car_ctx.parseProfile(car_profile, "global")

        // ---- ours: PMTiles -> rd5 ----
        val work = createTempDirectory("atlas-diag-our-").toFile()
        val our_segments = File(work, "segments").apply { mkdirs() }
        val nodes45 = File(work, "nodes45").apply { mkdirs() }
        val ways45 = File(work, "ways45").apply { mkdirs() }
        val nodeFilter = NodeFilter().also { it.init() }
        val wayCutter = WayCutter().also { it.init(ways45) }
        PmtilesCutter().also {
            it.wayCutter = wayCutter
            it.nodeFilter = nodeFilter
        }.process(
            reader = PmtilesReader(archive.absolutePath),
            lookupContent = lookup_file.readText(),
            profileContent = profile_all.readText(),
            nodeDir = nodes45,
            scanBounds = TileBounds(140.0, -40.0, 145.0, -35.0),
            zoom = 14,
        )
        wayCutter.finish()
        analyzeWays("OURS-45", nodes45, ways45, mapOf(".ntl" to ".wtl"))

        val nodes55 = File(work, "nodes55").apply { mkdirs() }
        val ways55 = File(work, "ways55").apply { mkdirs() }
        val bordernids = File(work, "bordernids.dat")
        WayCutter5().apply {
            this.nodeFilter = nodeFilter
            nodeCutter = NodeCutter().also { it.init(nodes55) }
        }.process(nodes45, ways45, ways55, bordernids)
        analyzeWays("OURS-55", nodes55, ways55, mapOf(".n5d" to ".wt5"))
        analyzeCarGraph("OURS-55", nodes55, ways55, car_ctx)

        val unodes55 = File(work, "unodes55").apply { mkdirs() }
        val bordernodes = File(work, "bordernodes.dat")
        PosUnifier().process(nodes55, unodes55, bordernids, bordernodes, "", null)
        WayLinker().process(
            unodes55, ways55, bordernodes, File(work, "unused.dat"),
            lookup_file, profile_all, our_segments, "rd5",
        )
        lookup_file.copyTo(File(our_segments, "lookups.dat"), overwrite = true)
        println("OURS rd5: " + our_segments.listFiles()!!.joinToString { "${it.name}=${it.length()}" })
        for (probe in probes()) {
            printProbe("OURS", probe, our_segments, car_profile, lookup_file)
        }

        // ---- reference: PBF -> rd5, same stages (relations omitted) ----
        val refwork = createTempDirectory("atlas-diag-ref-").toFile()
        val ref_nodes45 = File(refwork, "nodes45").apply { mkdirs() }
        val ref_ways45 = File(refwork, "ways45").apply { mkdirs() }
        val ref_cutter = OsmCutter()
        val ref_filter = NodeFilter().also { it.init() }
        ref_cutter.wayCutter = WayCutter().also { it.init(ref_ways45) }
        ref_cutter.nodeFilter = ref_filter
        ref_cutter.process(
            lookup_file, ref_nodes45, null, File(refwork, "cycleways.dat"),
            null, profile_all, pbf,
        )
        ref_cutter.wayCutter!!.finish()
        analyzeWays("REF-45", ref_nodes45, ref_ways45, mapOf(".ntl" to ".wtl"))

        val ref_nodes55 = File(refwork, "nodes55").apply { mkdirs() }
        val ref_ways55 = File(refwork, "ways55").apply { mkdirs() }
        val ref_border = File(refwork, "bordernids.dat")
        WayCutter5().apply {
            this.nodeFilter = ref_filter
            nodeCutter = NodeCutter().also { it.init(ref_nodes55) }
        }.process(ref_nodes45, ref_ways45, ref_ways55, ref_border)
        analyzeWays("REF-55", ref_nodes55, ref_ways55, mapOf(".n5d" to ".wt5"))
        analyzeCarGraph("REF-55", ref_nodes55, ref_ways55, car_ctx)

        val ref_unodes = File(refwork, "unodes55").apply { mkdirs() }
        val ref_bordernodes = File(refwork, "bordernodes.dat")
        PosUnifier().process(ref_nodes55, ref_unodes, ref_border, ref_bordernodes, "", null)
        val ref_segments = File(refwork, "segments").apply { mkdirs() }
        WayLinker().process(
            ref_unodes, ref_ways55, ref_bordernodes, File(refwork, "unused.dat"),
            lookup_file, profile_all, ref_segments, "rd5",
        )
        lookup_file.copyTo(File(ref_segments, "lookups.dat"), overwrite = true)
        println("REF  rd5: " + ref_segments.listFiles()!!.joinToString { "${it.name}=${it.length()}" })
        for (probe in probes()) {
            printProbe("REF ", probe, ref_segments, car_profile, lookup_file)
        }
    }

    // ---- analysis ----

    /**
     * Union-find connectivity over the way files ([wayExt] -> node files
     * [nodeExt]) in [nodeDir]/[wayDir]; prints component stats and an ASCII
     * coverage map of the start node's component over the fixture area.
     */
    private fun analyzeWays(
        label: String,
        nodeDir: File,
        wayDir: File,
        exts: Map<String, String>,
    ) {
        val node_ext = exts.keys.first()
        val way_ext = exts.values.first()

        val positions = HashMap<Long, Long>() // nid -> (ilon shl 32) or ilat
        var node_count = 0
        nodeDir.listFiles()!!.filter { it.name.endsWith(node_ext) }.forEach { f ->
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
                    node_count++
                }
            }
        }

        val uf = UnionFind()
        var way_count = 0
        var two_node_ways = 0
        var node_refs = 0L
        var missing_nodes = 0L
        wayDir.listFiles()!!.filter { it.name.endsWith(way_ext) }.forEach { f ->
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { dis ->
                while (true) {
                    val way = try {
                        WayData(dis)
                    } catch (_: EOFException) {
                        break
                    }
                    way_count++
                    if (way.nodes.size == 2) two_node_ways++
                    for (i in 0 until way.nodes.size) {
                        val nid = way.nodes.get(i)
                        node_refs++
                        if (!positions.containsKey(nid)) missing_nodes++
                        if (i > 0) uf.union(way.nodes.get(i - 1), nid)
                    }
                }
            }
        }

        val component_sizes = HashMap<Long, Int>()
        for (nid in positions.keys) {
            component_sizes.merge(uf.find(nid), 1, Int::plus)
        }
        val sorted = component_sizes.values.sortedDescending()
        val start_nid = nearestNid(positions, start_lon, start_lat)
        val end_nid = nearestNid(positions, end_lon, end_lat)
        val start_root = uf.find(start_nid)

        println(
            "$label: $node_count nodes, $way_count ways ($two_node_ways two-node, " +
                "avg ${node_refs / way_count.coerceAtLeast(1)} nodes/way), " +
                "missing_node_refs=$missing_nodes, ${component_sizes.size} components, " +
                "largest=${sorted.take(5)}",
        )
        println(
            "$label: start comp=${component_sizes[start_root] ?: 0}, " +
                "end comp=${component_sizes[uf.find(end_nid)] ?: 0}, " +
                "same=${start_root == uf.find(end_nid)}",
        )
        printCoverageMap(label, positions, uf, start_root)
    }

    /**
     * The CAR routing graph the engine actually sees: ways whose encoded
     * description the car profile evaluates to costfactor < 10000 in either
     * direction. The all-ways [analyzeWays] connects through footpaths and
     * tracks, so it cannot see a cut that only affects cars — this one can,
     * and it names the ways around the Little River ladder rung that are
     * car-unroutable or stranded off the CBD component.
     */
    private fun analyzeCarGraph(
        label: String,
        nodeDir: File,
        wayDir: File,
        car_ctx: BExpressionContextWay,
    ) {
        val positions = HashMap<Long, Long>() // nid -> (ilon shl 32) or ilat
        nodeDir.listFiles()!!.filter { it.name.endsWith(".n5d") }.forEach { f ->
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

        val uf = UnionFind()
        var way_count = 0
        var car_routable = 0
        val car_nodes = HashSet<Long>()
        val car_wid = HashMap<Long, Long>()
        val near_lr = ArrayList<String>()
        wayDir.listFiles()!!.filter { it.name.endsWith(".wt5") }.forEach { f ->
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { dis ->
                while (true) {
                    val way = try {
                        WayData(dis)
                    } catch (_: EOFException) {
                        break
                    }
                    way_count++
                    val cf_fwd: Float
                    val cf_rev: Float
                    val description = way.description
                    if (description == null) {
                        cf_fwd = 10000f; cf_rev = 10000f
                    } else {
                        car_ctx.evaluate(false, description)
                        cf_fwd = car_ctx.costfactor
                        car_ctx.evaluate(true, description)
                        cf_rev = car_ctx.costfactor
                    }
                    val routable = cf_fwd < 10000f || cf_rev < 10000f

                    val bbox = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
                    var near = false
                    for (i in 0 until way.nodes.size) {
                        val packed = positions[way.nodes.get(i)] ?: continue
                        val x = packed shr 32
                        val y = packed and 0xFFFFFFFFL
                        if (x < bbox[0]) bbox[0] = x
                        if (x > bbox[2]) bbox[2] = x
                        if (y < bbox[1]) bbox[1] = y
                        if (y > bbox[3]) bbox[3] = y
                        val lon = x / 1_000_000.0 - 180.0
                        val lat = y / 1_000_000.0 - 90.0
                        if (lon in 144.515..144.545 && lat in -37.995..-37.965) near = true
                    }
                    if (near) {
                        near_lr.add(
                            "wid=${way.wid ushr 5} ${if (routable) "routable" else "UNROUTABLE"} " +
                                "cf=${"%.2f".format(cf_fwd)}/${"%.2f".format(cf_rev)} " +
                                "nodes=${way.nodes.size} " +
                                "bbox=%.4f..%.4f,%.4f..%.4f".format(
                                    bbox[0] / 1_000_000.0 - 180.0, bbox[2] / 1_000_000.0 - 180.0,
                                    bbox[1] / 1_000_000.0 - 90.0, bbox[3] / 1_000_000.0 - 90.0,
                                ),
                        )
                    }
                    if (routable) {
                        car_routable++
                        for (i in 0 until way.nodes.size) {
                            val node = way.nodes.get(i)
                            car_nodes.add(node)
                            car_wid.putIfAbsent(node, way.wid)
                        }
                        for (i in 1 until way.nodes.size) {
                            uf.union(way.nodes.get(i - 1), way.nodes.get(i))
                        }
                    }
                }
            }
        }

        val component_sizes = HashMap<Long, Int>()
        for (nid in car_nodes) component_sizes.merge(uf.find(nid), 1, Int::plus)
        val sorted = component_sizes.values.sortedDescending()
        val start_root = uf.find(nearestCarNid(positions, car_nodes, uf, start_lon, start_lat))
        val lr_root = uf.find(nearestCarNid(positions, car_nodes, uf, 144.53, -37.98))
        val geelong_root = uf.find(nearestCarNid(positions, car_nodes, uf, end_lon, end_lat))
        println(
            "$label car: $way_count ways, $car_routable car-routable, " +
                "${component_sizes.size} components, largest=${sorted.take(5)}",
        )
        println(
            "$label car: LR-in-CBD=${lr_root == start_root} " +
                "(LR-comp=${component_sizes[lr_root] ?: 0}), " +
                "Geelong-in-CBD=${geelong_root == start_root} " +
                "(Geelong-comp=${component_sizes[geelong_root] ?: 0})",
        )
        // Component map over the fixture area for the two roots that
        // matter (CBD vs the corridor side): the seam between the letter
        // regions is where the car graph is cut.
        val roots = listOf('A' to start_root, 'B' to lr_root)
        val map = Array(20) { CharArray(36) { ' ' } }
        for (nid in car_nodes) {
            val packed = positions[nid] ?: continue
            val lon = (packed shr 32).toInt() / 1_000_000.0 - 180.0
            val lat = (packed and 0xFFFFFFFFL).toInt() / 1_000_000.0 - 90.0
            if (lon !in 144.2..145.4 || lat !in -38.4..-37.4) continue
            val col = ((lon - 144.2) / 1.2 * 36).toInt().coerceIn(0, 35)
            val row = ((-37.4 - lat) / 1.0 * 20).toInt().coerceIn(0, 19)
            val ch = when (uf.find(nid)) {
                start_root -> 'A'
                lr_root -> 'B'
                else -> '.'
            }
            // Letters win over dots so the two components stay visible.
            if (map[row][col] == ' ' || map[row][col] == '.' || ch != '.') map[row][col] = ch
        }
        println("$label car component map (A=CBD comp, B=LR comp, .=other car comp):")
        for (r in 0 until 20) println("$label car |${map[r].concatToString()}|")

        // Seam junctions: the closest A-node for every B-node (only when
        // the two components are actually different). The frontier pairs
        // name the exact road junctions the linking stages failed to make.
        if (lr_root != start_root) {
            val seam_grid = 2_000L
            val a_cells = HashMap<Long, ArrayList<Long>>()
            for (nid in car_nodes) {
                if (uf.find(nid) != start_root) continue
                val packed = positions[nid] ?: continue
                val key = ((packed shr 32) / seam_grid shl 32) or
                    ((packed and 0xFFFFFFFFL) / seam_grid)
                a_cells.getOrPut(key) { ArrayList() }.add(nid)
            }
            val seams = ArrayList<Triple<Long, Long, Double>>()
            for (nid in car_nodes) {
                if (uf.find(nid) != lr_root) continue
                val packed = positions[nid] ?: continue
                val lon = packed shr 32
                val lat = packed and 0xFFFFFFFFL
                var best_a = -1L
                var best_d2 = Long.MAX_VALUE
                for (dl in -1..1) {
                    for (dt in -1..1) {
                        val list = a_cells[((lon / seam_grid + dl) shl 32) or
                            (lat / seam_grid + dt)] ?: continue
                        for (a in list) {
                            val ap = positions[a]!!
                            val dx = lon - (ap shr 32)
                            val dy = lat - (ap and 0xFFFFFFFFL)
                            val d2 = dx.toLong() * dx + dy.toLong() * dy
                            if (d2 < best_d2) {
                                best_d2 = d2
                                best_a = a
                            }
                        }
                    }
                }
                if (best_a != -1L && best_d2 < 6000.0 * 6000.0) {
                    seams.add(Triple(nid, best_a, Math.sqrt(best_d2.toDouble()) * 0.1))
                }
            }
            // One seam line per 0.05-degree cell — the closest pair in it.
            val cell_of = { nid: Long ->
                val packed = positions[nid]!!
                ((packed shr 32) / 50_000L shl 32) or (packed and 0xFFFFFFFFL) / 50_000L
            }
            val best_per_cell = HashMap<Long, Triple<Long, Long, Double>>()
            for (s in seams) {
                val cell = cell_of(s.first)
                val prev = best_per_cell[cell]
                if (prev == null || s.third < prev.third) best_per_cell[cell] = s
            }
            println(
                "$label seam: ${seams.size} B-nodes within 600m of comp A, " +
                    "${best_per_cell.size} distinct junction sites:",
            )
            best_per_cell.values.sortedBy { it.third }.take(40).forEach { s ->
                fun fmt(nid: Long) = positions[nid]!!.let {
                    "%.5f,%.5f wid=%d".format(
                        (it shr 32) / 1_000_000.0 - 180.0,
                        (it and 0xFFFFFFFFL) / 1_000_000.0 - 90.0,
                        car_wid[nid] ?: -1L,
                    )
                }
                println(
                    "$label seam d=${"%.1f".format(s.third)}m: " +
                        "B(${fmt(s.first)}) <-> A(${fmt(s.second)})",
                )
            }
            val best = best_per_cell.values.minByOrNull { it.third }
            if (best != null) {
                dumpSeamWays(label, wayDir, car_ctx, uf, start_root, lr_root, best, positions)
            }
        }
        println("$label car: ${near_lr.size} ways in the Little River window:")
        for (row in near_lr) println("$label LR $row")
    }

    /**
     * Second pass over the .wt5 files: every fragment that either shares the
     * seam way's wid or passes through one of the two seam nodes, with the
     * full coordinate chain and which side of the cut each endpoint sits on.
     */
    private fun dumpSeamWays(
        label: String,
        wayDir: File,
        car_ctx: BExpressionContextWay,
        uf: UnionFind,
        start_root: Long,
        lr_root: Long,
        seam: Triple<Long, Long, Double>,
        positions: HashMap<Long, Long>,
    ) {
        val seam_nids = setOf(seam.first, seam.second)
        val seam_wid_print = (seamNidWid(wayDir, seam.first) ?: -1L) ushr 5
        fun compTag(nid: Long): Char = when (uf.find(nid)) {
            start_root -> 'A'
            lr_root -> 'B'
            else -> '?'
        }
        var printed = 0
        wayDir.listFiles()!!.filter { it.name.endsWith(".wt5") }.forEach { f ->
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { dis ->
                while (true) {
                    val way = try {
                        WayData(dis)
                    } catch (_: EOFException) {
                        break
                    }
                    val through_seam = way.nodes.any { it in seam_nids }
                    val same_wid = (way.wid ushr 5) == seam_wid_print
                    // REF nids live in a different id space, so fall back to
                    // a bbox match around the seam coordinates there.
                    val near_seam = !through_seam && !same_wid && run {
                        val seam_pos = positions[seam.first]!!
                        val slon = (seam_pos shr 32) / 1_000_000.0 - 180.0
                        val slat = (seam_pos and 0xFFFFFFFFL) / 1_000_000.0 - 90.0
                        way.nodes.any { nid ->
                            val p = positions[nid] ?: return@any false
                            val lon = (p shr 32) / 1_000_000.0 - 180.0
                            val lat = (p and 0xFFFFFFFFL) / 1_000_000.0 - 90.0
                            lon in slon - 0.03..slon + 0.03 && lat in slat - 0.02..slat + 0.02
                        }
                    }
                    if (through_seam || same_wid || near_seam) {
                        val cf_fwd: Float
                        val cf_rev: Float
                        val description = way.description
                        if (description == null) {
                            cf_fwd = 10000f; cf_rev = 10000f
                        } else {
                            car_ctx.evaluate(false, description)
                            cf_fwd = car_ctx.costfactor
                            car_ctx.evaluate(true, description)
                            cf_rev = car_ctx.costfactor
                        }
                        val chain = way.nodes.joinToString(" ") { nid ->
                            val p = positions[nid] ?: return@joinToString "?"
                            "%.5f,%.5f".format(
                                (p shr 32) / 1_000_000.0 - 180.0,
                                (p and 0xFFFFFFFFL) / 1_000_000.0 - 90.0,
                            )
                        }
                        val end_tag = if (way.nodes.isEmpty()) "??" else
                            "${compTag(way.nodes.first())}${compTag(way.nodes.last())}"
                        println(
                            "$label seam-way wid=${way.wid ushr 5} ends=$end_tag " +
                                "cf=${"%.1f".format(cf_fwd)}/${"%.1f".format(cf_rev)} " +
                                "nodes=${way.nodes.size}: $chain",
                        )
                        printed++
                    }
                }
            }
        }
        println("$label seam-way dump: $printed fragments (wid=$seam_wid_print + seam nodes + 3km bbox)")
    }

    /** The wid of the first car way that contains [nid] — a second scan. */
    private fun seamNidWid(wayDir: File, nid: Long): Long? {
        wayDir.listFiles()!!.filter { it.name.endsWith(".wt5") }.forEach { f ->
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { dis ->
                while (true) {
                    val way = try {
                        WayData(dis)
                    } catch (_: EOFException) {
                        break
                    }
                    if (way.nodes.contains(nid)) return way.wid
                }
            }
        }
        return null
    }

    /** Nearest node that is actually IN the car graph (a car-routable way's node). */
    private fun nearestCarNid(
        positions: HashMap<Long, Long>,
        car_nodes: HashSet<Long>,
        uf: UnionFind,
        lon: Double,
        lat: Double,
    ): Long {
        val ilon = ((lon + 180.0) * 1_000_000).toInt()
        val ilat = ((lat + 90.0) * 1_000_000).toInt()
        var best = -1L
        var best_d2 = Long.MAX_VALUE
        for (nid in car_nodes) {
            val packed = positions[nid] ?: continue
            val dlon = (packed shr 32).toInt() - ilon
            val dlat = (packed and 0xFFFFFFFFL).toInt() - ilat
            val d2 = dlon.toLong() * dlon + dlat.toLong() * dlat
            if (d2 < best_d2) {
                best_d2 = d2
                best = nid
            }
        }
        return best
    }

    /** ASCII grid over the fixture area: membership of the start component. */
    private fun printCoverageMap(label: String, positions: HashMap<Long, Long>, uf: UnionFind, start_root: Long) {
        val west = 144.2; val east = 145.4; val south = -38.4; val north = -37.4
        val cols = 36; val rows = 20
        val in_comp = Array(rows) { IntArray(cols) }
        val total = Array(rows) { IntArray(cols) }
        for ((_, packed) in positions) {
            val lon = (packed shr 32).toInt() / 1_000_000.0 - 180.0
            val lat = (packed and 0xFFFFFFFFL).toInt() / 1_000_000.0 - 90.0
            val col = ((lon - west) / (east - west) * cols).toInt().coerceIn(0, cols - 1)
            val row = ((north - lat) / (north - south) * rows).toInt().coerceIn(0, rows - 1)
            total[row][col]++
        }
        for (nid in positions.keys) {
            if (uf.find(nid) != start_root) continue
            val packed = positions[nid]!!
            val lon = (packed shr 32).toInt() / 1_000_000.0 - 180.0
            val lat = (packed and 0xFFFFFFFFL).toInt() / 1_000_000.0 - 90.0
            val col = ((lon - west) / (east - west) * cols).toInt().coerceIn(0, cols - 1)
            val row = ((north - lat) / (north - south) * rows).toInt().coerceIn(0, rows - 1)
            in_comp[row][col]++
        }
        println("$label coverage of start component (# >=90%, + 10-90%, . <10%, X no nodes):")
        val start_col = ((start_lon - west) / (east - west) * cols).toInt().coerceIn(0, cols - 1)
        val start_row = ((north - start_lat) / (north - south) * rows).toInt().coerceIn(0, rows - 1)
        val end_col = ((end_lon - west) / (east - west) * cols).toInt().coerceIn(0, cols - 1)
        val end_row = ((north - end_lat) / (north - south) * rows).toInt().coerceIn(0, rows - 1)
        for (r in 0 until rows) {
            val sb = StringBuilder()
            for (c in 0 until cols) {
                sb.append(
                    when {
                        r == start_row && c == start_col -> 'S'
                        r == end_row && c == end_col -> 'G'
                        total[r][c] == 0 -> ' '
                        in_comp[r][c] * 10 >= total[r][c] * 9 -> '#'
                        in_comp[r][c] * 10 >= total[r][c] -> '+'
                        in_comp[r][c] > 0 -> '.'
                        else -> 'X'
                    }
                )
            }
            println("$label |$sb|")
        }
    }

    private fun nearestNid(positions: HashMap<Long, Long>, lon: Double, lat: Double): Long {
        val ilon = ((lon + 180.0) * 1_000_000).toInt()
        val ilat = ((lat + 90.0) * 1_000_000).toInt()
        var best = -1L
        var best_d2 = Long.MAX_VALUE
        for ((nid, packed) in positions) {
            val dlon = (packed shr 32).toInt() - ilon
            val dlat = (packed and 0xFFFFFFFFL).toInt() - ilat
            val d2 = dlon.toLong() * dlon + dlat.toLong() * dlat
            if (d2 < best_d2) {
                best_d2 = d2
                best = nid
            }
        }
        return best
    }

    private class UnionFind {
        private val parent = HashMap<Long, Long>()

        fun find(x: Long): Long {
            var r = x
            while (true) {
                val p = parent[r] ?: run {
                    parent[r] = r
                    return r
                }
                if (p == r) return r
                parent[r] = parent[p] ?: p // path halving
                r = parent[r]!!
            }
        }

        fun union(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }

    // ---- routing probes ----

    private class Probe(val name: String, val lon1: Double, val lat1: Double, val lon2: Double, val lat2: Double)

    private fun probes(): List<Probe> = listOf(
        Probe("150m-cbd", 144.9669, -37.8183, 144.9685, -37.8185),
        Probe("150m-geelong", 144.3680, -38.1493, 144.3700, -38.1500),
        Probe("9km-footscray", 144.9669, -37.8183, 144.8890, -37.7990),
        Probe("20km-werribee", 144.8890, -37.7990, 144.6600, -37.9000),
        Probe("25km-littleriver", 144.6600, -37.9000, 144.4700, -38.2200),
        Probe("12km-geelong", 144.4700, -38.2200, 144.3680, -38.1493),
        Probe("55km-geelong", 144.9669, -37.8183, 144.3680, -38.1493),
        // Corridor ladder along the west-shore Princes Fwy/Hwy path the
        // 55km route takes: local probes verify each rung is routable,
        // consecutive-leg probes localize the break that kills the three
        // long probes above (union-find says connected, the car-profile
        // graph says not).
        Probe("150m-l1", 144.6000, -37.9500, 144.6020, -37.9520),
        Probe("150m-l2", 144.5300, -37.9800, 144.5320, -37.9820),
        Probe("150m-l3", 144.4600, -38.0500, 144.4620, -38.0520),
        Probe("150m-l4", 144.4000, -38.0900, 144.4020, -38.0920),
        Probe("leg-w-l1", 144.6600, -37.9000, 144.6000, -37.9500),
        Probe("leg-l1-l2", 144.6000, -37.9500, 144.5300, -37.9800),
        Probe("leg-l2-l3", 144.5300, -37.9800, 144.4600, -38.0500),
        Probe("leg-l3-l4", 144.4600, -38.0500, 144.4000, -38.0900),
        Probe("leg-l4-g", 144.4000, -38.0900, 144.3680, -38.1493),
    )

    /** Probes whose track waypoints get dumped, to compare path shapes. */
    private val dump_track_probes = setOf("20km-werribee", "55km-geelong")

    private fun printProbe(
        label: String,
        probe: Probe,
        segments: File,
        profile_content: String,
        lookup_file: File,
    ) {
        val result = try {
            val context = RoutingContext(
                profileContent = profile_content,
                lookupContent = lookup_file.readText(),
                mapSource = FileMapSource(segments),
                generateTurns = true,
            )
            val track = kotlinx.coroutines.runBlocking {
                RoutingEngine(context).doRouting(
                    listOf(
                        OsmNodeNamed(Position.fromDegrees(probe.lon1, probe.lat1)).apply { name = "from" },
                        OsmNodeNamed(Position.fromDegrees(probe.lon2, probe.lat2)).apply { name = "to" },
                    ),
                )
            }
            "OK ${track?.distance}m/${track?.totalSeconds}s"
        } catch (e: Exception) {
            "FAIL ${e.message}"
        }
        println("$label ${probe.name}: $result")
        if (probe.name in dump_track_probes) {
            val track = runCatching {
                kotlinx.coroutines.runBlocking {
                    RoutingEngine(
                        RoutingContext(
                            profileContent = profile_content,
                            lookupContent = lookup_file.readText(),
                            mapSource = FileMapSource(segments),
                            generateTurns = true,
                        ),
                    ).doRouting(
                        listOf(
                            OsmNodeNamed(Position.fromDegrees(probe.lon1, probe.lat1)).apply { name = "from" },
                            OsmNodeNamed(Position.fromDegrees(probe.lon2, probe.lat2)).apply { name = "to" },
                        ),
                    )
                }
            }.getOrNull()
            if (track != null) {
                val nodes = track.nodes
                val step = (nodes.size / 20).coerceAtLeast(1)
                val waypoints = (0 until nodes.size step step).joinToString(" ") {
                    "%.5f,%.5f".format(
                        nodes[it].position.longitudeDegree,
                        nodes[it].position.latitudeDegree,
                    )
                }
                println("$label ${probe.name} track (${nodes.size} pts): $waypoints")
            }
        }
    }

    private fun findProfileDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File(System.getProperty("user.dir"), "misc/profiles2"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("could not find misc/profiles2")
    }

    private class FileMapSource(private val segmentDir: File) : MapSource {
        override fun exists(fileName: String): Boolean = File(segmentDir, fileName).isFile
        override fun open(fileName: String): RandomAccessReader =
            FileRandomAccessReader(File(segmentDir, fileName))
    }

    private class FileRandomAccessReader(file: File) : RandomAccessReader {
        private val delegate = RandomAccessFile(file, "r")
        override fun seek(position: Long) = delegate.seek(position)
        override fun readFully(buffer: ByteArray, offset: Int, length: Int) =
            delegate.readFully(buffer, offset, length)
        override fun length(): Long = delegate.length()
        override fun close() = delegate.close()
    }
}