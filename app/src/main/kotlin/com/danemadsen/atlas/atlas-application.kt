package com.danemadsen.atlas

import android.app.Application
import android.content.Context
import com.danemadsen.atlas.data.PmtilesRepository
import org.maplibre.android.MapLibre

/** Manual DI holder — Atlas is small enough to not want a DI framework. */
class AppContainer(
    context: Context,
) {
    val pmtilesRepository = PmtilesRepository(context.applicationContext)
}

class AtlasApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MapView refuses to construct until the MapLibre singleton is primed.
        MapLibre.getInstance(this)
        // Atlas has no ACCESS_NETWORK_STATE (fully offline), so MapLibre's
        // ConnectivityReceiver must never query ConnectivityManager — a
        // SecurityException there crashes the app. The manual override makes
        // isConnected() a constant; harmless, since nothing fetches remotely.
        MapLibre.setConnected(true)
        container = AppContainer(this)
    }
}