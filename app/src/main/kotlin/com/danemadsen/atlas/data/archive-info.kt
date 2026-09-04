package com.danemadsen.atlas.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Everything the UI needs to know about the imported PMTiles archive.
 * Persisted as JSON next to the archive file so cold starts skip re-parsing
 * the (potentially multi-GB) archive.
 */
data class ArchiveInfo(
    val fileName: String,
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
)

object ArchiveStore {
    private const val INFO_FILE = "archive-info.json"

    fun archiveFile(context: Context): File =
        File(File(context.filesDir, "map"), "atlas.pmtiles")

    fun infoFile(context: Context): File =
        File(context.filesDir, INFO_FILE)

    fun load(context: Context): ArchiveInfo? {
        val file = infoFile(context)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            ArchiveInfo(
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
        }.getOrNull()
    }

    fun save(context: Context, info: ArchiveInfo) {
        val json = JSONObject().apply {
            put("fileName", info.fileName)
            put("sizeBytes", info.sizeBytes)
            put("minZoom", info.minZoom)
            put("maxZoom", info.maxZoom)
            put("west", info.west)
            put("south", info.south)
            put("east", info.east)
            put("north", info.north)
            put("centerLon", info.centerLon)
            put("centerLat", info.centerLat)
            put("centerZoom", info.centerZoom)
        }
        infoFile(context).writeText(json.toString())
    }
}