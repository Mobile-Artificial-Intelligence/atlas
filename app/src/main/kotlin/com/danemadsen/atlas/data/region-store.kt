package com.danemadsen.atlas.data

import android.content.Context
import com.danemadsen.atlas.graph.archiveFingerprintOf
import com.danemadsen.atlas.search.SearchIndexer
import com.danemadsen.atlas.pmtiles.PmtilesReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * One installed map region: a PMTiles archive plus the metadata the UI and
 * the data stores key on. Atlas supports any number of regions side by side
 * (see .github/workflows/pmtiles.yml — one archive per Geofabrik region
 * extract); every other store (routing segments, search index) is namespaced
 * by [id].
 *
 * [routingFingerprint]/[searchFingerprint] pin the paired CI artifacts: a
 * routing ZIP's manifest must equal [routingFingerprint], a search ZIP's
 * manifest must equal [searchFingerprint]. They are content hashes of the
 * archive itself, so a re-imported newer build of the same region carries
 * different fingerprints — which is how a region update is detected without
 * comparing display names.
 */
data class RegionInfo(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val centerLon: Double,
    val centerLat: Double,
    val centerZoom: Int,
    val routingFingerprint: String,
    val searchFingerprint: String,
    val importedAtMs: Long,
)

/**
 * The registry of installed regions. The JSON index (`map/regions.json`) is
 * authoritative; if it is missing or corrupt it is rebuilt by scanning the
 * per-region directories and re-parsing archive headers, so a lost index
 * costs nothing but a few header reads.
 *
 * The core functions take the `map/` directory as a [File] (no [Context]),
 * which keeps them unit-testable on the JVM; the Context overloads are the
 * app's entry points.
 */
object RegionStore {
    const val MAP_DIR = "map"
    const val REGIONS_FILE = "regions.json"
    const val ARCHIVE_NAME = "map.pmtiles"

    fun mapDir(context: Context): File = File(context.filesDir, MAP_DIR)

    fun regionDir(mapDir: File, id: String): File = File(mapDir, id)

    fun archiveFile(mapDir: File, id: String): File = File(regionDir(mapDir, id), ARCHIVE_NAME)

    fun indexFile(mapDir: File): File = File(mapDir, REGIONS_FILE)

    fun loadAll(context: Context): List<RegionInfo> = loadAll(mapDir(context))

    fun load(context: Context, id: String): RegionInfo? = load(mapDir(context), id)

    fun save(context: Context, regions: List<RegionInfo>) = save(mapDir(context), regions)

    fun add(context: Context, info: RegionInfo) = add(mapDir(context), info)

    fun remove(context: Context, id: String) = remove(mapDir(context), id)

    fun loadAll(mapDir: File): List<RegionInfo> {
        val index = indexFile(mapDir)
        if (index.isFile) {
            val regions = runCatching { parseIndex(index.readText()) }.getOrNull()
            if (regions != null && regions.none { stale(mapDir, it) }) return regions
        }
        // No index, a corrupt one, or one that names regions whose files are
        // gone: rebuild from what is actually on disk.
        return rebuild(mapDir)
    }

    fun load(mapDir: File, id: String): RegionInfo? =
        loadAll(mapDir).firstOrNull { it.id == id }

    /** Atomic write — a torn index is rebuilt from disk, but never gamble. */
    fun save(mapDir: File, regions: List<RegionInfo>) {
        mapDir.mkdirs()
        val file = indexFile(mapDir)
        val tmp = File(mapDir, "$REGIONS_FILE.tmp")
        tmp.writeText(renderIndex(regions))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not store the region index" }
        }
    }

    fun add(mapDir: File, info: RegionInfo) {
        save(mapDir, loadAll(mapDir).filter { it.id != info.id } + info)
    }

    fun remove(mapDir: File, id: String) {
        save(mapDir, loadAll(mapDir).filter { it.id != id })
    }

    fun routingFingerprintOf(archiveFile: File): String = archiveFingerprintOf(archiveFile)

    fun searchFingerprintOf(archiveFile: File): String = SearchIndexer.contentFingerprint(archiveFile)

    /**
     * The region whose bbox contains the point, or null. Safe against an
     * archive straddling the antimeridian (west > east means the bbox is the
     * union of both sides) — same rule as GraphBuildCoordinator.insideArchive.
     */
    fun regionForPoint(regions: List<RegionInfo>, lon: Double, lat: Double): RegionInfo? =
        regions.firstOrNull { insideRegion(it, lon, lat) }

    fun insideRegion(region: RegionInfo, lon: Double, lat: Double): Boolean {
        if (lat < region.south || lat > region.north) return false
        val lon_in = if (region.west <= region.east) {
            lon >= region.west && lon <= region.east
        } else {
            lon >= region.west || lon <= region.east
        }
        return lon_in
    }

    /**
     * The region search/routing/camera anchor to when one must be picked:
     * the nearest to [anchor] (lon, lat), or the most recently imported one
     * when no anchor exists.
     */
    fun primaryRegion(regions: List<RegionInfo>, anchor: Pair<Double, Double>?): RegionInfo? {
        if (regions.isEmpty()) return null
        if (anchor == null) {
            return regions.maxByOrNull { it.importedAtMs }
        }
        return regions.minByOrNull {
            val dlon = normalizedLonDelta(anchor.first, it.centerLon)
            val dlat = anchor.second - it.centerLat
            dlon * dlon + dlat * dlat
        }
    }

    /**
     * "atlas-us-georgia.pmtiles" -> "us-georgia": the CI artifact names are
     * stable across daily rebuilds, so the display name is the region's
     * stable identity (the fingerprints change on every rebuild — they are
     * the update-detection signal, not the identity).
     */
    fun deriveRegionId(displayName: String, existingIds: Set<String>): String {
        var base = displayName
            .substringBeforeLast('.')
            .removePrefix("atlas-")
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (base.isEmpty()) base = "map"
        if (base !in existingIds) return base
        var n = 2
        while ("$base-$n" in existingIds) n++
        return "$base-$n"
    }

    /** The recorded size no longer matches the archive on disk. */
    fun stale(mapDir: File, region: RegionInfo): Boolean {
        val file = archiveFile(mapDir, region.id)
        return !file.isFile || file.length() != region.sizeBytes
    }

    private fun parseIndex(text: String): List<RegionInfo> {
        val regions = mutableListOf<RegionInfo>()
        val array = JSONArray(text)
        for (i in 0 until array.length()) {
            regions.add(parseRegion(array.getJSONObject(i)))
        }
        return regions
    }

    private fun parseRegion(json: JSONObject): RegionInfo = RegionInfo(
        id = json.getString("id"),
        displayName = json.getString("displayName"),
        sizeBytes = json.getLong("sizeBytes"),
        minZoom = json.getInt("minZoom"),
        maxZoom = json.getInt("maxZoom"),
        west = json.getDouble("west"),
        south = json.getDouble("south"),
        east = json.getDouble("east"),
        north = json.getDouble("north"),
        centerLon = json.getDouble("centerLon"),
        centerLat = json.getDouble("centerLat"),
        centerZoom = json.getInt("centerZoom"),
        routingFingerprint = json.getString("routingFingerprint"),
        searchFingerprint = json.getString("searchFingerprint"),
        importedAtMs = json.getLong("importedAtMs"),
    )

    private fun renderIndex(regions: List<RegionInfo>): String {
        val array = JSONArray()
        for (region in regions) {
            array.put(
                JSONObject().apply {
                    put("id", region.id)
                    put("displayName", region.displayName)
                    put("sizeBytes", region.sizeBytes)
                    put("minZoom", region.minZoom)
                    put("maxZoom", region.maxZoom)
                    put("west", region.west)
                    put("south", region.south)
                    put("east", region.east)
                    put("north", region.north)
                    put("centerLon", region.centerLon)
                    put("centerLat", region.centerLat)
                    put("centerZoom", region.centerZoom)
                    put("routingFingerprint", region.routingFingerprint)
                    put("searchFingerprint", region.searchFingerprint)
                    put("importedAtMs", region.importedAtMs)
                },
            )
        }
        return array.toString()
    }

    private fun listRegionDirs(mapDir: File): List<File> =
        mapDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isDirectory && File(f, ARCHIVE_NAME).isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Last-resort recovery: re-derive every region from its archive header. */
    private fun rebuild(mapDir: File): List<RegionInfo> =
        listRegionDirs(mapDir).mapNotNull { dir ->
            runCatching {
                val archive = File(dir, ARCHIVE_NAME)
                val header = RandomAccessFile(archive, "r").use { raf ->
                    PmtilesReader(raf).header
                }
                RegionInfo(
                    id = dir.name,
                    displayName = dir.name,
                    sizeBytes = archive.length(),
                    minZoom = header.minZoom,
                    maxZoom = header.maxZoom,
                    west = header.minLon,
                    south = header.minLat,
                    east = header.maxLon,
                    north = header.maxLat,
                    centerLon = header.centerLon,
                    centerLat = header.centerLat,
                    centerZoom = header.centerZoom,
                    routingFingerprint = routingFingerprintOf(archive),
                    searchFingerprint = searchFingerprintOf(archive),
                    importedAtMs = archive.lastModified(),
                )
            }.getOrNull()
        }

    /** Shortest longitude delta in degrees, in [-180, 180] — anchors near the antimeridian. */
    private fun normalizedLonDelta(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}