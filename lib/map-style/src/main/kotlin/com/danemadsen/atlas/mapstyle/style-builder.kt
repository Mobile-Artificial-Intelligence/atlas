package com.danemadsen.atlas.mapstyle

/**
 * Builds a renderable, fully offline MapLibre style JSON from the shared
 * layer template plus a theme's palette:
 *
 *  - `{{token}}` markers resolve to theme colors
 *  - the remote sprite/glyph CDNs become bundled `asset://` files
 *  - font stacks map to the bundled space-free glyph directories
 *  - the `openmaptiles` source reads the imported PMTiles archive
 *    via MapLibre Native's built-in `pmtiles://file://` support
 *  - hidden layers (dark's sprite-pattern layers) get `visibility: none`
 */
object StyleBuilder {

    // The \{ \} escapes are load-bearing: Android's java.util.regex is
    // ICU-based, where a lone { or } is a syntax error — the unescaped
    // {{(\w+)}} form compiles on the host JVM (OpenJDK leniency) but
    // throws PatternSyntaxException on device. Same trap, with a full
    // write-up, in GraphBuildManager.parseState.
    private val TOKEN_PATTERN = Regex("""\{\{(\w+)\}\}""")

    /** Style parameters describing the imported archive. */
    data class SourceInfo(
        /** Absolute filesystem path of the cached .pmtiles archive. */
        val archivePath: String,
    )

    fun buildStyleJson(
        templateJson: String,
        theme: AtlasMapTheme,
        source: SourceInfo,
    ): String {
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

        // 4. the PMTiles vector source — MapLibre's pmtiles:// support reads
        // the archive header itself (zooms, bounds) and synthesizes tile
        // range requests internally, so the source URL must point at the
        // archive file, not a z/x/y template.
        style = style.replace(
            "\"sources\": {}",
            "\"sources\": {\"openmaptiles\": {\"type\": \"vector\", " +
                "\"url\": \"pmtiles://file://${jsonEscape(source.archivePath)}\"}}",
        )

        // 5. style name reflects the theme
        style = style.replace("\"name\": \"OSM Liberty\"", "\"name\": \"${jsonEscape(theme.name)}\"")

        // 6. hide the theme's sprite-pattern layers
        for (layerId in theme.hiddenLayers) {
            style = hideLayer(style, layerId)
        }

        return style
    }

    /** The background-only style shown before an archive is imported. */
    fun emptyStyleJson(theme: AtlasMapTheme): String =
        "{\n\"version\": 8,\n\"name\": \"${jsonEscape(theme.name)}\",\n\"sources\": {},\n" +
            "\"layers\": [\n{\n\"id\": \"background\",\n\"type\": \"background\",\n" +
            "\"paint\": {\"background-color\": \"${theme.colors.getValue("background")}\"}\n}\n]\n}"

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