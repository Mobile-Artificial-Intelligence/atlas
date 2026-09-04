package com.danemadsen.atlas.routing

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The user-location dot, Google-Maps style: a blue dot with a white ring
 * and a soft halo, wrapped in an echo ring that pulses outward while fixes
 * arrive. When the GPS signal is lost the dot turns grey, the halo and
 * pulse stop, and it sits at the last fix until the stream returns.
 *
 * The blue/grey pair is fixed on purpose — the request named Google Maps'
 * colors. The Material You accent drives the route line, never the dot.
 *
 * Like RouteRenderer, the layers sit on a persistent GeoJson source so a
 * theme restyle (which rebuilds the style from JSON, silently dropping
 * ours) is survived by the render effect re-running [show] on the new
 * style — and [startPulse] re-acquiring the new style's pulse layer.
 */
object LocationPuck {

    private const val SOURCE_ID = "atlas-location-source"
    private const val PULSE_ID = "atlas-location-pulse"
    private const val HALO_ID = "atlas-location-halo"
    private const val DOT_ID = "atlas-location-dot"

    private const val ACTIVE_COLOR = 0xFF1A73E8.toInt()
    private const val LOST_COLOR = 0xFF9E9E9E.toInt()
    private const val STROKE_COLOR = 0xFFFFFFFF.toInt()

    private const val DOT_RADIUS_DP = 7f
    private const val HALO_EXTRA_DP = 3f
    private const val STROKE_WIDTH_DP = 2.5f
    private const val HALO_OPACITY = 0.25f
    private const val PULSE_MAX_DP = 26f
    private const val PULSE_PERIOD_MS = 2_400L
    private const val PULSE_MAX_OPACITY = 0.35f

    /** The running echo animator plus the style it talks to. */
    private var pulse_animator: ValueAnimator? = null
    private var pulse_style: Style? = null

    /**
     * Arms the three puck layers (pulse under halo under dot, on top of
     * everything else — the user's position is the one map element that
     * must never be painted over) and parks the dot at [point]; a null
     * [point] still arms them with an empty source so a restyle
     * mid-session re-adds them before the next fix lands. Idempotent.
     *
     * The active/grey color set is applied on EVERY call — including the
     * arm — so a style armed blue, gone grey, then restyled comes back
     * grey (a restyle lands here with [active]=false and must not resurrect
     * a blue dot for a silent GPS). An inactive puck also stops the pulse
     * and resets its layer: the echo must not freeze mid-ring at the loss
     * moment.
     */
    fun show(style: Style, point: GeoPoint?, active: Boolean) {
        if (!active) stopPulse()
        if (style.getLayer(DOT_ID) == null) {
            if (style.getSource(SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(SOURCE_ID, emptyCollection()))
            }
            // The colors applied right below override these arm-time
            // placeholders; they exist only so the layers are fully
            // specified between creation and that set.
            style.addLayer(CircleLayer(PULSE_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(DOT_RADIUS_DP),
                PropertyFactory.circleColor(ACTIVE_COLOR),
                PropertyFactory.circleOpacity(0f),
            ))
            style.addLayer(CircleLayer(HALO_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(DOT_RADIUS_DP + HALO_EXTRA_DP),
                PropertyFactory.circleColor(ACTIVE_COLOR),
                PropertyFactory.circleOpacity(HALO_OPACITY),
            ))
            style.addLayer(CircleLayer(DOT_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(DOT_RADIUS_DP),
                PropertyFactory.circleColor(ACTIVE_COLOR),
                PropertyFactory.circleStrokeWidth(STROKE_WIDTH_DP),
                PropertyFactory.circleStrokeColor(STROKE_COLOR),
            ))
        }
        val dot_color = if (active) ACTIVE_COLOR else LOST_COLOR
        style.getLayer(DOT_ID)?.setProperties(PropertyFactory.circleColor(dot_color))
        style.getLayer(HALO_ID)?.setProperties(
            PropertyFactory.circleColor(dot_color),
            // The grey puck is intentionally flat — no halo, no echo.
            PropertyFactory.circleOpacity(if (active) HALO_OPACITY else 0f),
        )
        if (active) {
            style.getLayer(PULSE_ID)?.setProperties(PropertyFactory.circleColor(dot_color))
        } else {
            style.getLayer(PULSE_ID)?.setProperties(
                PropertyFactory.circleColor(dot_color),
                // Reset the echo ring: an animator cancelled mid-ring
                // leaves its last radius/opacity painted, and a frozen
                // ghost ring around a "lost" dot contradicts the
                // flat-grey design. (Not on the active path — the
                // animator overwrites radius/opacity every frame anyway,
                // and resetting there would dim the ring for a frame on
                // every moving fix.)
                PropertyFactory.circleRadius(DOT_RADIUS_DP),
                PropertyFactory.circleOpacity(0f),
            )
        }
        val features = point?.let {
            listOf(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)))
        } ?: emptyList()
        (style.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    /**
     * Runs the echo ring: radius grows dot→max while opacity fades to
     * zero, repeating for as long as fixes arrive. Restarts (never
     * stacks) when the style instance changes — a restyle rebuilds the
     * layers and the animator must talk to the new ones.
     */
    fun startPulse(style: Style) {
        if (pulse_animator?.isRunning == true && pulse_style === style) return
        stopPulse()
        pulse_style = style
        pulse_animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                // getLayer per frame, not a held reference: a layer held
                // across a restyle goes stale the moment the old style is
                // destroyed. runCatching covers the window between a
                // restyle destroying the style and the render effect
                // re-arming the pulse on the new one (getLayer on a
                // destroyed style can throw in native).
                runCatching {
                    (style.getLayer(PULSE_ID) as? CircleLayer)?.setProperties(
                        PropertyFactory.circleRadius(
                            DOT_RADIUS_DP + (PULSE_MAX_DP - DOT_RADIUS_DP) * fraction,
                        ),
                        PropertyFactory.circleOpacity(PULSE_MAX_OPACITY * (1f - fraction)),
                    )
                }
            }
            start()
        }
    }

    /** Stops the echo; the dot and halo stay where they are. */
    fun stopPulse() {
        pulse_animator?.cancel()
        pulse_animator = null
        pulse_style = null
    }

    /**
     * The bottom-most puck layer currently on [style] (null when the puck
     * isn't armed). Overlays that must sit above the map but below the user
     * — the route's destination marker — insert themselves under this.
     */
    fun bottomLayerId(style: Style): String? =
        listOf(PULSE_ID, HALO_ID, DOT_ID).firstOrNull { style.getLayer(it) != null }

    private fun emptyCollection(): FeatureCollection =
        FeatureCollection.fromFeatures(emptyList())
}