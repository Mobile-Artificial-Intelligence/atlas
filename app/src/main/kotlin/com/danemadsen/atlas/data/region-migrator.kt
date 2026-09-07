package com.danemadsen.atlas.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One-time migration of the pre-multi-region layout into the region
 * registry. Everything is renames plus small JSON writes, so existing
 * installs keep their camera, route prefs, built routing buckets (the
 * segments dir moves whole, its `build-state.json` fingerprint still
 * matches), and search index (already fingerprint-keyed, no move).
 *
 * Ordered so a crash mid-way leaves either the complete legacy layout
 * (retry next launch) or the complete new one: regions.json is written
 * LAST, and every rename is into a fresh directory.
 */
object RegionMigrator {

    private const val LEGACY_ARCHIVE_NAME = "atlas.pmtiles"
    private const val LEGACY_INFO_FILE = "archive-info.json"
    private const val LEGACY_SEGMENTS_DIR = "graph/segments"
    private const val STATUS_FILE = "graph/build-status.json"
    private const val LEGACY_REGION_ID = "atlas"

    fun migrate(context: Context) {
        migrate(
            filesDir = context.filesDir,
            infoFile = File(context.filesDir, LEGACY_INFO_FILE),
            statusFile = File(context.filesDir, STATUS_FILE),
        )
    }

    internal fun migrate(filesDir: File, infoFile: File, statusFile: File) {
        val map_dir = File(filesDir, RegionStore.MAP_DIR)
        if (RegionStore.indexFile(map_dir).isFile) return
        val legacy_archive = File(map_dir, LEGACY_ARCHIVE_NAME)
        if (!legacy_archive.isFile || !infoFile.isFile) return

        // A sticky `running=true` (the :graph process died mid-build) means
        // the status file is untrustworthy and a restarted service may be
        // mid-run over the segments dir: defer — the next launch after an
        // honest terminal status migrates.
        if (statusClaimsRunning(statusFile)) return

        val info = runCatching { parseLegacyInfo(infoFile.readText()) }.getOrNull() ?: return

        val region_dir = File(map_dir, LEGACY_REGION_ID)
        if (!region_dir.mkdirs() && !region_dir.isDirectory) return
        val new_archive = File(region_dir, RegionStore.ARCHIVE_NAME)
        if (!legacy_archive.renameTo(new_archive)) return

        val segments = File(filesDir, LEGACY_SEGMENTS_DIR)
        if (segments.isDirectory) {
            if (!segments.renameTo(File(segments.parentFile, "segments/$LEGACY_REGION_ID"))) {
                // Roll the archive back rather than half-migrate: the app
                // then stays on the legacy path and retries next launch.
                new_archive.renameTo(legacy_archive)
                return
            }
        }

        val region = RegionInfo(
            id = LEGACY_REGION_ID,
            displayName = info.fileName,
            sizeBytes = new_archive.length(),
            minZoom = info.minZoom,
            maxZoom = info.maxZoom,
            west = info.west,
            south = info.south,
            east = info.east,
            north = info.north,
            centerLon = info.centerLon,
            centerLat = info.centerLat,
            centerZoom = info.centerZoom,
            routingFingerprint = RegionStore.routingFingerprintOf(new_archive),
            searchFingerprint = RegionStore.searchFingerprintOf(new_archive),
            importedAtMs = new_archive.lastModified(),
        )
        // regions.json written LAST: a crash before this line leaves the
        // app on the legacy path (regions.json absent → migrate retries),
        // never on a half-built index.
        RegionStore.save(map_dir, listOf(region))
        infoFile.delete()
    }

    private fun statusClaimsRunning(statusFile: File): Boolean =
        statusFile.isFile &&
            runCatching {
                JSONObject(statusFile.readText()).optBoolean("running")
            }.getOrDefault(false)

    private fun parseLegacyInfo(text: String): ArchiveInfo {
        val json = JSONObject(text)
        return ArchiveInfo(
            fileName = json.getString("fileName"),
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
        )
    }
}