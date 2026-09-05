package com.danemadsen.atlas.services

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.routing.formatDistance
import com.danemadsen.atlas.routing.formatDuration
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import com.danemadsen.atlas.ui.nav.TurnBanner

/**
 * The floating turn banner: one TYPE_APPLICATION_OVERLAY window hosting a
 * ComposeView that reuses the in-app [TurnBanner] verbatim, owned by
 * [NavigationService].
 *
 * Two rules keep a service-hosted window honest:
 *
 * 1. Every WindowManager call runs on the main looper — the service's fix
 *    loop runs on Dispatchers.Default, and WindowManager is not
 *    thread-safe. [show]/[hide]/[destroy] post to [main_handler];
 *    drag updates run there too.
 * 2. uiState is the ONLY cross-thread surface: the fix loop writes it,
 *    the ComposeView collects it. No other field is touched off the main
 *    thread.
 */
class NavOverlayController(private val context: Context) {

    private val main_handler = Handler(Looper.getMainLooper())

    private var owner = OverlayLifecycleOwner()

    private var view: ComposeView? = null

    /** The live window params — drag nudges x/y against this anchor. */
    private var params: WindowManager.LayoutParams? = null

    /**
     * Written from the service's fix loop; collected inside the
     * ComposeView. The overlay renders exactly what the coordinator
     * published — render-one-fix-stale is shared with the notification by
     * construction.
     */
    val uiState = MutableStateFlow<NavigationCoordinator.NavState>(NavigationCoordinator.NavState.Idle)

    fun show() {
        main_handler.post { attach() }
    }

    fun hide() {
        main_handler.post { detach() }
    }

    /** Same as [hide] — the service's onDestroy funnel calls this. */
    fun destroy() {
        main_handler.post { detach() }
    }

    /** The fix loop's per-fix push; any thread. */
    fun update(state: NavigationCoordinator.NavState) {
        uiState.value = state
    }

    private fun attach() {
        if (view != null) return
        // The appop can be revoked since the toggle was set; guard every attach.
        if (!Settings.canDrawOverlays(context)) return
        // A fresh owner per attach: the previous owner was already
        // destroyed (detach dispatches ON_DESTROY) and its
        // SavedStateRegistryController refuses a second performRestore with
        // IllegalStateException — a hide()/show() toggle would crash the
        // main thread. Only attach() touches the owner.
        owner = OverlayLifecycleOwner()
        owner.onCreate()
        val compose_view = ComposeView(context)
        compose_view.setViewTreeLifecycleOwner(owner)
        compose_view.setViewTreeSavedStateRegistryOwner(owner)
        compose_view.setContent {
            MaterialTheme(colorScheme = OVERLAY_DARK_SCHEME) {
                val state by uiState.collectAsState()
                OverlayBanner(state, onDrag = ::onDrag)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Clears the status bar; drag nudges this anchor, never re-pins it.
            y = TOP_OFFSET_PX
        }
        try {
            context.getSystemService(WindowManager::class.java).addView(compose_view, lp)
        } catch (e: Exception) {
            // BadTokenException if the appop was flipped mid-flight: stay
            // honest, stay running — degrade to "no overlay", never crash.
            android.util.Log.w("NavOverlay", "overlay attach failed", e)
            runCatching { compose_view.disposeComposition() }
            return
        }
        view = compose_view
        params = lp
        owner.onResume()
    }

    private fun detach() {
        val compose_view = view ?: return
        view = null
        params = null
        runCatching { compose_view.disposeComposition() }
        runCatching { context.getSystemService(WindowManager::class.java).removeView(compose_view) }
        owner.onDestroy()
    }

    /**
     * The banner's drag callback, already on the main thread (pointerInput
     * runs on the UI thread). x/y are offsets from the TOP|CENTER
     * anchor — the banner can be nudged anywhere but never flung off-screen.
     */
    private fun onDrag(deltaX: Float, deltaY: Float) {
        val lp = params ?: return
        lp.x += deltaX.roundToInt()
        lp.y += deltaY.roundToInt()
        val current_view = view
        try {
            context.getSystemService(WindowManager::class.java).updateViewLayout(current_view, lp)
        } catch (_: Exception) {
        }
    }
}

/**
 * A fixed dark scheme — the overlay floats over arbitrary third-party apps
 * and light system surfaces, so it deliberately does NOT track the in-app
 * theme (a service-hosted window has no configuration to react to anyway).
 */
private val OVERLAY_DARK_SCHEME = darkColorScheme(
    primary = Color(0xFF7EB6FF),
    surfaceContainer = Color(0xFF1D222B),
    onSurface = Color(0xFFE3E7EF),
    onSurfaceVariant = Color(0xFFAAB3C2),
)

/** Clears the status bar from the TOP|CENTER anchor. */
private const val TOP_OFFSET_PX = 96

/**
 * The minimal lifecycle the ComposeView needs: LifecycleOwner +
 * SavedStateRegistryOwner, with ON_CREATE dispatched before setContent and
 * ON_RESUME after addView. Missing this stub makes AbstractComposeView
 * throw "ViewTreeLifecycleOwner not found" on attach; missing the
 * ON_DESTROY dispatch leaks the recomposer.
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = SavedStateRegistryController.create(this)
    private val lifecycle_registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycle_registry
    override val savedStateRegistry: SavedStateRegistry = registry.savedStateRegistry
    fun onCreate() {
        registry.performRestore(null)
        lifecycle_registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    fun onResume() {
        lifecycle_registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun onDestroy() {
        lifecycle_registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/**
 * The window's content: the in-app banner (which owns the null-snapshot
 * "Waiting for a GPS fix…" and past-the-last-turn ARRIVE states for free)
 * plus a remaining/ETA row. The 280.dp width keeps the touchable region
 * exactly the banner — FLAG_NOT_TOUCH_MODAL routes everything else to the
 * app underneath.
 */
@Composable
private fun OverlayBanner(state: NavigationCoordinator.NavState, onDrag: (Float, Float) -> Unit) {
    val snapshot = (state as? NavigationCoordinator.NavState.Navigating)?.snapshot
    Surface(
        modifier = Modifier
            .width(280.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        val delta = change.positionChange()
                        change.consume()
                        if (delta != Offset.Zero) onDrag(delta.x, delta.y)
                    },
                )
            },
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
    ) {
        Column {
            TurnBanner(snapshot = snapshot)
            if (snapshot != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        formatDistance(snapshot.remainingMeters.roundToInt()),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.padding(start = 10.dp))
                    Text(
                        "· ${formatDuration(snapshot.remainingSeconds)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}