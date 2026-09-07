package com.danemadsen.atlas.mapstyle

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

/**
 * Builds a renderable, fully offline MapLibre style JSON from the shared
 * layer template plus a theme's palette:
 *
 *  - `{{token}}` markers resolve to theme colors
 *  - the remote sprite/glyph CDNs become bundled `asset://` files
 *  - font stacks map to the bundled space-free glyph directories
 *  - each installed region archive becomes one vector source reading its
 *    PMTiles file via MapLibre Native's built-in `pmtiles://file://` support
 *  - hidden layers (dark's sprite-pattern layers) get `visibility: none`
 */
object StyleBuilder {

    // The \{ \} escapes are load-bearing: Android's java.util.regex is
    // ICU-based, where a lone { or } is a syntax error — the unescaped
    // {{(\w+)}} form compiles on the host JVM (OpenJDK leniency) but
    // throws PatternSyntaxException on device. Same trap, with a full
    // write-up, in GraphBuildManager.parseState.
    private val TOKEN_PATTERN = Regex("""\{\{(\w+)\}\}""")
    private const val TEMPLATE_SOURCE = "openmaptiles"

    // Pretty output keeps the style diff-able and the tests' string probes
    // readable; MapLibre accepts either.
    private val json = Json { prettyPrint = true }

    /** One installed region's contribution to the style. */
    data class RegionSource(
        /** Region id from [com.danemadsen.atlas.data.RegionStore]. */
        val regionId: String,
        /** Absolute filesystem path of the region's .pmtiles archive. */
        val archivePath: String,
    )

    fun buildStyleJson(
        templateJson: String,
        theme: AtlasMapTheme,
        sources: List<RegionSource>,
    ): String {
        if (sources.isEmpty()) return emptyStyleJson(theme)

        var style = templateJson

        // 1. theme colors
        style = TOKEN_PATTERN.replace(style) { match ->
            val token = match.groupValues[1]
            requireValue(token in theme.colors) { "theme ${theme.name} has no color for '$token'" }
            theme.colors.getValue(token)
        }

        // 2. offline sprite + glyphs (bundled as app assets)
        style = style.replace(
            "\"sprite\": \"https://maputnik.github.io/osm-liberty/sprites/osm-liberty\"",
            "\"sprite\": \"asset://sprites/osm-liberty\"",
        )
        style = style.replace(
            "\"glyphs\": \"https://orangemug.github.io/font-glyphs/glyphs/{fontstack}/{range}.pbf\"",
            "\"glyphs\": \"asset://glyphs/{fontstack}/{range}.pbf\"",
        )

        // 3. font stacks -> bundled space-free glyph directory names
        for (font in FONT_STACKS) {
            style = style.replace("\"$font\"", "\"${font.replace(' ', '-')}\"")
        }

        // 4. hide the theme's sprite-pattern layers — string surgery while
        // the layer ids are still the template's own (duplication suffixes
        // them, which this regex would no longer match).
        for (layerId in theme.hiddenLayers) {
            style = hideLayer(style, layerId)
        }

        // 5. style name reflects the theme
        style = style.replace("\"name\": \"OSM Liberty\"", "\"name\": \"${jsonEscape(theme.name)}\"")

        // 6. one vector source per region; every sourced layer duplicated
        // once per source. Tile coverage is disjoint per archive (each is a
        // Planetiler extract clipped to its bbox), so no feature draws
        // twice; style-wide symbol collision keeps border labels single.
        // The layer rewrite is a JSON tree transform — splicing nested
        // layers by regex is the trap the escaping note above documents.
        val root = json.parseToJsonElement(style).jsonObject
        val style_with_sources = root.with(
            "sources",
            buildJsonObject {
                for (region in sources) {
                    put(region.regionId, buildJsonObject {
                        put("type", "vector")
                        put("url", "pmtiles://file://" + region.archivePath)
                    })
                }
            },
        )
        val split = splitLayersAcrossSources(style_with_sources, sources)
        return json.encodeToString(split)
    }

    /** The background-only style shown before an archive is imported. */
    fun emptyStyleJson(theme: AtlasMapTheme): String =
        "{\n\"version\": 8,\n\"name\": \"${jsonEscape(theme.name)}\",\n\"sources\": {},\n" +
            "\"layers\": [\n{\n\"id\": \"background\",\n\"type\": \"background\",\n" +
            "\"paint\": {\"background-color\": \"${theme.colors.getValue("background")}\"}\n}\n]\n}"

    /**
     * Duplicates every layer bound to [TEMPLATE_SOURCE] once per region,
     * rewriting each copy's `source` and suffixing its `id`. The regions'
     * list order is the render order (the last region's layers draw on top
     * and win symbol collisions), so callers pass the primary region last.
     */
    private fun splitLayersAcrossSources(
        style: JsonObject,
        sources: List<RegionSource>,
    ): JsonObject {
        val layers = requireNotNull(style["layers"]?.jsonArray) { "style template has no layers array" }
        val split_layers = buildJsonArray {
            for (layer in layers) {
                val obj = layer.jsonObject
                val source = obj["source"]?.jsonPrimitive?.contentOrNull
                if (source != TEMPLATE_SOURCE) {
                    add(layer)
                    continue
                }
                val base_id = requireNotNull(obj["id"]?.jsonPrimitive?.contentOrNull) {
                    "sourced layer without an id: $obj"
                }
                for (region in sources) {
                    add(
                        obj.with(
                            "source",
                            JsonPrimitive(sourceName(region.regionId)),
                        ).with(
                            "id",
                            JsonPrimitive("$base_id-${region.regionId}"),
                        ),
                    )
                }
            }
        }
        return style.with("layers", split_layers)
    }

    fun sourceName(regionId: String): String = "$TEMPLATE_SOURCE-$regionId"

    /** [JsonObject.plus] widens to Map — keep the element type. */
    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        JsonObject(this + mapOf(key to value))

    private fun hideLayer(styleJson: String, layerId: String): String {
        // Layer ids are unique in the template; inject a layout visibility
        // right after the layer's id declaration.
        val idPattern = Regex("""("id"\s*:\s*"$layerId"\s*,)""")
        var replaced = false
        val result = idPattern.replace(styleJson) { match ->
            replaced = true
            match.groupValues[1] + "\n  \"layout\": {\"visibility\": \"none\"},"
        }
        requireValue(replaced) { "layer '$layerId' not found in the style template" }
        return result
    }

    private val FONT_STACKS = listOf(
        "Roboto Regular",
        "Roboto Medium",
        "Roboto Condensed Italic",
    )

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private inline fun requireValue(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw IllegalArgumentException(lazyMessage())
    }
}