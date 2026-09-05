package com.danemadsen.atlas.ui.savedlocations

/** Default name for a save that came from the map (long-press or map center). */
const val DEFAULT_PIN_NAME = "Dropped pin"

/**
 * The pinned one-tap slots. Exactly one location per slot: saving a new
 * [SavedLocation] with a non-null slot replaces the old occupant.
 */
enum class SavedSlot { HOME, WORK }

fun SavedSlot.defaultLabel(): String = when (this) {
    SavedSlot.HOME -> "Home"
    SavedSlot.WORK -> "Work"
}

/**
 * A user-saved destination. A null [slot] means a place in the arbitrary
 * saved list; a non-null slot is the Home/Work pin (and only one location
 * per slot exists).
 */
data class SavedLocation(
    val id: String,
    val name: String,
    val lon: Double,
    val lat: Double,
    val slot: SavedSlot? = null,
)

/** Pure list operations on [SavedLocation]s — JSON-free, so JVM-testable. */
object SavedLocationStore {
    private const val FORMAT_VERSION = 1

    /** Adds [location]; a slot location first removes the old occupant of that slot. */
    fun upsert(locations: List<SavedLocation>, location: SavedLocation): List<SavedLocation> {
        val list = locations.toMutableList()
        location.slot?.let { slot -> list.removeAll { it.slot == slot } }
        list.add(location)
        return list
    }

    fun rename(locations: List<SavedLocation>, id: String, name: String): List<SavedLocation> =
        locations.map { if (it.id == id) it.copy(name = name) else it }

    fun delete(locations: List<SavedLocation>, id: String): List<SavedLocation> =
        locations.filterNot { it.id == id }

    /** Demotes the location out of its pinned slot without deleting it. */
    fun clearSlot(locations: List<SavedLocation>, id: String): List<SavedLocation> =
        locations.map { if (it.id == id) it.copy(slot = null) else it }

    fun newId(): String = java.util.UUID.randomUUID().toString()

    /**
     * One versioned JSON string under prefs key "saved.locations". A single
     * key keeps the rewrite atomic and the importArchive wipe policy
     * unambiguous; the version wrapper lets a future per-location field
     * migrate without a second key. org.json is on Android by default and
     * already used by archive-info.
     */
    fun encode(locations: List<SavedLocation>): String {
        val array = org.json.JSONArray()
        for (location in locations) {
            val item = org.json.JSONObject()
            item.put("id", location.id)
            item.put("name", location.name)
            // JSONObject.put(String, Double) serializes via Double.toString,
            // so the full-precision convention (a Float round-trip loses
            // ~0.2 m) holds with no extra work.
            item.put("lon", location.lon)
            item.put("lat", location.lat)
            location.slot?.let { item.put("slot", it.name) }
            array.put(item)
        }
        return org.json.JSONObject()
            .put("version", FORMAT_VERSION)
            .put("locations", array)
            .toString()
    }

    /**
     * Tolerant decode: null/blank/garbage → an empty list (a corrupt key
     * must never crash-loop the app, same spirit as readPersistedDouble),
     * bad coordinates drop their row, unknown slot strings drop the slot.
     */
    fun decode(json: String?): List<SavedLocation> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = org.json.JSONObject(json)
            val array = root.optJSONArray("locations") ?: return emptyList()
            val locations = mutableListOf<SavedLocation>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val lon = item.optDouble("lon", Double.NaN)
                val lat = item.optDouble("lat", Double.NaN)
                if (lon.isNaN() || lat.isNaN()) continue
                val slot = item.optString("slot", "").takeIf { it.isNotBlank() }?.let { name ->
                    runCatching { SavedSlot.valueOf(name) }.getOrNull()
                }
                locations.add(
                    SavedLocation(
                        id = item.optString("id", SavedLocationStore.newId()),
                        name = item.optString("name", ""),
                        lon = lon,
                        lat = lat,
                        slot = slot,
                    ),
                )
            }
            locations
        } catch (_: Exception) {
            emptyList()
        }
    }
}