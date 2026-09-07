package com.danemadsen.atlas

import android.app.Application
import android.content.Context
import com.danemadsen.atlas.data.PmtilesRepository
import com.danemadsen.atlas.data.RegionMigrator
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
        // Before anything reads the region registry (the ViewModel's
        // initialState, the :graph process's manager lookups): fold the
        // pre-multi-region single-archive layout into it. Renames only —
        // defers when a sticky running=true build status says a restarted
        // :graph service may still be mid-run over the segments dir.
        RegionMigrator.migrate(this)
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