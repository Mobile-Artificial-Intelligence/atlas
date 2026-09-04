package com.danemadsen.atlas.mapstyle

import kotlin.math.abs

/**
 * CSS-ish color parsing and hue surgery for the map themes. The template's
 * tokens arrived from the web prototype in whatever notation felt natural
 * there (`#fc8`, `rgb(198,218,255)`, `hsla(98, 61%, 72%, 0.7)`), so the
 * parser accepts the full mixed bag; everything it emits is `#rrggbb` (or
 * `rgba(...)` when an alpha survives), which MapLibre takes verbatim.
 */
object MapColors {

    private val HEX_PATTERN = Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$""")
    private val RGB_PATTERN = Regex(
        """^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$""",
    )
    private val HSL_PATTERN = Regex(
        """^hsla?\(\s*(-?[\d.]+)(?:deg)?\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%\s*(?:,\s*([\d.]+)\s*)?\)$""",
    )

    /** Parses any notation used by the theme palettes; null if unrecognized. */
    fun parse(color: String): Int? {
        val value = color.trim()
        HEX_PATTERN.matchEntire(value)?.let { match ->
            val hex = match.groupValues[1]
            return if (hex.length == 3) {
                val (r, g, b) = List(3) { hex.substring(it, it + 1).repeat(2) }
                argb(1f, r, g, b)
            } else {
                argb(1f, hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6))
            }
        }
        RGB_PATTERN.matchEntire(value)?.let { match ->
            val (r, g, b) = List(3) { match.groupValues[it + 1].toInt().coerceIn(0, 255) }
            val alpha = match.groupValues[4].ifEmpty { "1" }.toFloat().coerceIn(0f, 1f)
            return (alpha.toIntColor() shl 24) or (r shl 16) or (g shl 8) or b
        }
        HSL_PATTERN.matchEntire(value)?.let { match ->
            val h = ((match.groupValues[1].toFloat() % 360f) + 360f) % 360f
            val s = (match.groupValues[2].toFloat() / 100f).coerceIn(0f, 1f)
            val l = (match.groupValues[3].toFloat() / 100f).coerceIn(0f, 1f)
            val alpha = match.groupValues[4].ifEmpty { "1" }.toFloat().coerceIn(0f, 1f)
            return hslToArgb(h, s, l, alpha)
        }
        return null
    }

    /** `#rrggbb` for opaque colors, `rgba(r, g, b, a)` when an alpha survives. */
    fun format(argb: Int): String {
        val alpha = (argb ushr 24) / 255f
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return if (alpha >= 0.999f) {
            "#%02x%02x%02x".format(r, g, b)
        } else {
            "rgba(%d, %d, %d, %s)".format(r, g, b, trimmed(alpha))
        }
    }

    /**
     * Re-hues [color] toward [accent]: takes the accent's hue, keeps the
     * color's own lightness and alpha, and keeps its saturation — unless the
     * color is near-gray (dark's motorways sit at s≈0, where a hue swap is a
     * no-op), in which case it adopts a restrained slice of the accent's
     * saturation so the accent stays legible on dark too. Returns null when
     * [color] is in an unknown notation — callers fall back to the original.
     */
    fun rehue(color: String, accent: Int): String? {
        val argb = parse(color) ?: return null
        val (_, token_s, token_l) = argbToHsl(argb)
        val (accent_h, accent_s, _) = argbToHsl(accent)
        val saturation =
            if (token_s < NEAR_GRAY_S) minOf(accent_s, ACCENT_S_ON_GRAY) else token_s
        return format(hslToArgb(accent_h, saturation, token_l, (argb ushr 24) / 255f))
    }

    private const val NEAR_GRAY_S = 0.15f
    private const val ACCENT_S_ON_GRAY = 0.40f

    private fun argb(alpha: Float, rHex: String, gHex: String, bHex: String): Int {
        val alpha_int = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha_int shl 24) or
            (rHex.toInt(16) shl 16) or (gHex.toInt(16) shl 8) or bHex.toInt(16)
    }

    private fun Float.toIntColor(): Int = (this * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun trimmed(alpha: Float): String = "%.2f".format(alpha)

    /** ARGB → (hue 0..<360, saturation 0..1, lightness 0..1). */
    internal fun argbToHsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val delta = max - min
        val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
        val h = when (max) {
            r -> ((g - b) / delta + (if (g < b) 6f else 0f))
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
        return Triple(h, s, l)
    }

    /** (hue, saturation, lightness, alpha) → ARGB. */
    internal fun hslToArgb(h: Float, s: Float, l: Float, alpha: Float = 1f): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        val alpha_int = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha_int shl 24) or
            (((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            (((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}