package com.danemadsen.atlas.routing

import android.content.Context
import android.content.Intent
import com.danemadsen.atlas.data.ArchiveStore
import com.danemadsen.atlas.graph.FileMapSource
import com.danemadsen.atlas.graph.GraphBuildManager
import com.danemadsen.atlas.graph.GraphPipeline
import com.danemadsen.atlas.services.GraphBuildService
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.router.OsmNodeNamed
import com.danemadsen.atlas.beerouter.router.OsmTrack
import com.danemadsen.atlas.beerouter.router.RoutingContext
import com.danemadsen.atlas.beerouter.router.RoutingEngine
import com.danemadsen.atlas.beerouter.router.RoutingIslandException
import com.danemadsen.atlas.beerouter.router.VoiceHint
import com.danemadsen.atlas.beerouter.router.exceptions.DataCorruptionException
import com.danemadsen.atlas.beerouter.router.exceptions.DataFileNotFoundException
import com.danemadsen.atlas.beerouter.router.exceptions.RoutingException
import com.danemadsen.atlas.pmtiles.PmtilesReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Bridges the UI to the vendored BeeRouter engine: assembles a
 * [RoutingContext] from the APK's bundled profiles and the device-built
 * `.rd5` segments, runs the search off the main thread, and grows the
 * routing graph when a route needs buckets that were never built — the
 * on-demand flow: a route request is what makes "prepare this region"
 * happen, a handful of 5° buckets at a time.
 */
object RouterGateway {

    /** A user-facing route failure (the engine's exceptions are technical). */
    class RouteException(message: String) : Exception(message)

    /**
     * The engine materializes a found path by mutual recursion
     * (OsmPath.materializeMyElement ↔ materializeOriginElement), one frame
     * pair per way link — a metro-scale route crosses tens of thousands of
     * links and overflows a stock ~1 MB stack. It runs here instead, on a
     * single dedicated thread with a stack deep enough for any real route.
     */
    private val engine_dispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "atlas-routing", ENGINE_STACK_BYTES)
        }.asCoroutineDispatcher()
    }

    /** Internal sentinel: the route needs a bucket that is not on disk. */
    private class MissingBucketException(val bucket: String) : Exception()

    /**
     * Internal sentinel: the search drained a disconnected component. It
     * may be genuine unreachability — or a missing corridor bucket the
     * engine silently treated as border nodes (it only ever *names*
     * waypoint buckets; corridor buckets it just skips), so the caller
     * retries once with a wider corridor before giving up.
     */
    private class IslandFailureException : Exception()

    /**
     * The parsed profile contexts, one per [RouteProfile], reused for the
     * process lifetime: profile compilation (and the classloading behind
     * it) is a large part of a cold first route, and one process must
     * never pay it twice — upstream BRouter's server reuses one context
     * per profile across requests the same way. Reuse is safe because the
     * engine resets its per-route state on every run that COMPLETES
     * (waypoint matching pairs `setWaypoint` with `unsetWaypoint`), and a
     * run that is CANCELLED mid-flight evicts the context below rather
     * than leaving half-set state for the next route. The segments dir
     * never moves and [FileMapSource] checks `exists()` at open time, so
     * buckets built later are still found through the cached source.
     * Every engine pass runs on the single routing thread, so the map
     * needs no locking beyond its own.
     */
    private val context_cache = ConcurrentHashMap<RouteProfile, RoutingContext>()

    /** The cached context for [profile], parsed on first use. */
    private fun contextFor(
        profile: RouteProfile,
        profileContent: String,
        lookupContent: String,
        segmentsDir: File,
    ): RoutingContext = context_cache.computeIfAbsent(profile) {
        RoutingContext(
            profileContent = profileContent,
            lookupContent = lookupContent,
            mapSource = FileMapSource(segmentsDir),
            generateTurns = true,
        )
    }

    /**
     * Internal sentinel: the engine could not match a waypoint to a road.
     * Usually genuine (a long-press on a lake) — but the engine's match
     * range crosses bucket edges while only the waypoint's OWN bucket was
     * ever guaranteed built, so an edge-adjacent waypoint whose roads lie
     * just across the border is fixable with one neighbor build. The
     * caller distinguishes the two by distance to the bucket edge.
     */
    private class WaypointUnmappedException(val point: GeoPoint) : Exception()

    /**
     * Calculates one route from [origin] to [destination]. Throws
     * [RouteException] with a message fit for the drawer on failure;
     * [onPreparing] fires when a missing bucket forces a build first,
     * with the bucket name (null again once the build is done and the
     * retry starts).
     */
    suspend fun route(
        context: Context,
        profile: RouteProfile,
        origin: GeoPoint,
        destination: GeoPoint,
        onPreparing: suspend (bucket: String?) -> Unit = {},
    ): RouteResult {
        val app_context = context.applicationContext
        val profile_content: String
        val lookup_content: String
        val segments_dir: File
        withContext(Dispatchers.IO) {
            // The lookups content MUST be the one the buckets were built
            // with — read the extracted copy, not the asset, so both sides
            // of the fingerprint rule in GraphBuildCoordinator stay honest.
            val profiles_dir = GraphBuildCoordinator.ensureBuildAssets(app_context)
            profile_content = app_context.assets.open("profiles/${profile.assetName}")
                .bufferedReader().use { it.readText() }
            lookup_content = File(profiles_dir, "lookups.dat").readText()
            segments_dir = File(File(app_context.filesDir, "graph"), "segments")
        }

        val manager = managerFor(app_context)
        // A replaced archive must not route over the previous archive's
        // graph: the wipe normally happens inside the :graph service, but
        // the service only runs when something needs building — a route
        // whose corridor the stale state already "covers" would read it
        // as built and silently steer over the old archive's roads (or
        // fail fast on its built-empty records).
        withContext(Dispatchers.IO) { manager.wipeIfArchiveChanged() }
        val origin_bucket = GraphPipeline.bucketNameFor(origin.lon, origin.lat)
        val destination_bucket = GraphPipeline.bucketNameFor(destination.lon, destination.lat)

        // The engine only ever names WAYPOINT buckets as missing (its
        // first-file-access check fires in waypoint matching, never during
        // the search): a missing corridor bucket is silently absorbed as
        // border nodes and the route dies as a bogus "not reachable".
        // So the gateway derives the corridor itself — every 5° bucket the
        // straight line origin -> destination passes through — and grows
        // the graph for all of them before the first engine run.
        val corridor = corridorBuckets(origin, destination)

        // Waypoint buckets first: an origin/destination bucket recorded
        // built-empty (ocean, no routable ways) fails fast and friendly —
        // the engine can never match a waypoint inside such a bucket —
        // before minutes of corridor building.
        var states = withContext(Dispatchers.IO) { manager.bucketStates() }
        for ((bucket, label) in listOf(origin_bucket to "origin", destination_bucket to "destination")) {
            val recorded = states[bucket]
            if (recorded != null && recorded.rd5 == null) {
                throw RouteException("no road near the $label")
            }
        }
        val missing = (listOf(origin_bucket, destination_bucket) + corridor)
            .filter { states[it] == null }
            .distinct()
        if (missing.size > MAX_BUCKET_BUILDS) {
            throw RouteException(
                "this route crosses more than $MAX_BUCKET_BUILDS regions that are not " +
                    "prepared yet — a route that long needs the routing data built first",
            )
        }
        var builds = 0
        for (bucket in missing) {
            currentCoroutineContext().ensureActive()
            builds++
            onPreparing(bucket)
            ensureBucket(app_context, bucket)
            onPreparing(null)
            // An empty waypoint bucket is an honest terminal failure —
            // without this check the engine would re-report the bucket as
            // missing forever (an empty bucket never produces an .rd5).
            if (bucket == origin_bucket || bucket == destination_bucket) {
                val built = withContext(Dispatchers.IO) { manager.bucketState(bucket) }
                if (built?.rd5 == null) {
                    throw RouteException(
                        "no road near the " +
                            if (bucket == origin_bucket) "origin" else "destination",
                    )
                }
            }
        }

        // One fallback: when the prepared corridor still yields an island,
        // the real road path detours off the straight line (around a bay,
        // via a highway). Grow one halo ring around everything built and
        // retry once; if that still fails, the destination genuinely is
        // unreachable on this road network.
        var halo_expanded = false
        var waypoint_neighbors_expanded = false
        val named_missing = HashSet<String>()
        while (true) {
            try {
                val result = enrichTurnNames(
                    app_context,
                    calculate(profile, profile_content, lookup_content, segments_dir, origin, destination),
                )
                // A route that succeeded is the best warmup recipe there
                // is: its exact waypoint pair is known to match and to
                // search, so the next process start replays it (see
                // [warmEngine]) instead of gambling on a bucket center.
                rememberWarmRoute(app_context, profile, origin, destination)
                return result
            } catch (e: WaypointUnmappedException) {
                // The engine matches waypoints against segments in the
                // neighboring buckets too, but only the waypoint's own
                // bucket is guaranteed built: a waypoint near a bucket
                // edge whose match range holds roads only across the
                // border fails as unmapped although one neighbor build
                // would fix it. Retry once with the waypoint buckets'
                // neighbors — but only when the waypoint is actually
                // close to an edge; an interior miss is a genuine
                // no-road and must fail fast, not build 8 buckets.
                currentCoroutineContext().ensureActive()
                if (waypoint_neighbors_expanded || !nearBucketEdge(e.point)) {
                    throw RouteException(
                        if (e.point == origin) "no road near the origin" else "no road near the destination",
                    )
                }
                waypoint_neighbors_expanded = true
                states = withContext(Dispatchers.IO) { manager.bucketStates() }
                val neighbors = haloBuckets(setOf(origin_bucket, destination_bucket))
                    .filter { states[it] == null }
                if (neighbors.isEmpty() || builds + neighbors.size > MAX_BUCKET_BUILDS) {
                    throw RouteException("no road near the origin or the destination")
                }
                builds += neighbors.size
                for (bucket in neighbors) {
                    onPreparing(bucket)
                    ensureBucket(app_context, bucket)
                    onPreparing(null)
                }
            } catch (e: MissingBucketException) {
                // Backstop: the engine named a waypoint bucket our
                // derivation thinks is already satisfied. Once per bucket —
                // a second sighting means the recorded state lies (the .rd5
                // vanished) or the bucket is built-empty, and both need a
                // message, not a spin.
                currentCoroutineContext().ensureActive()
                if (!named_missing.add(e.bucket)) {
                    val state = withContext(Dispatchers.IO) { manager.bucketState(e.bucket) }
                    throw RouteException(
                        if (state?.rd5 != null) {
                            "the routing data for ${e.bucket} is damaged — rebuild the routing data"
                        } else {
                            "no road near the origin or the destination"
                        },
                    )
                }
                if (builds >= MAX_BUCKET_BUILDS) {
                    throw RouteException(
                        "this route crosses more than $MAX_BUCKET_BUILDS regions that are not " +
                            "prepared yet — a route that long needs the routing data built first",
                    )
                }
                builds++
                onPreparing(e.bucket)
                ensureBucket(app_context, e.bucket)
                onPreparing(null)
            } catch (e: IslandFailureException) {
                if (halo_expanded) {
                    throw RouteException("the destination is not reachable on this road network")
                }
                halo_expanded = true
                states = withContext(Dispatchers.IO) { manager.bucketStates() }
                val halo = haloBuckets((corridor + listOf(origin_bucket, destination_bucket)).toSet())
                    .filter { states[it] == null }
                if (halo.isEmpty() || builds + halo.size > MAX_BUCKET_BUILDS) {
                    throw RouteException("the destination is not reachable on this road network")
                }
                builds += halo.size
                for (bucket in halo) {
                    currentCoroutineContext().ensureActive()
                    onPreparing(bucket)
                    ensureBucket(app_context, bucket)
                    onPreparing(null)
                }
            }
        }
    }

    /**
     * Every 5° bucket the straight line [origin] -> [destination] passes
     * through — the engine never names corridor buckets itself (it absorbs
     * them as border nodes), so this derivation is the on-demand growth
     * mechanism for everything between the endpoints. Sampling is far
     * denser than the 5° grid, so no bucket crossing escapes.
     */
    private fun corridorBuckets(origin: GeoPoint, destination: GeoPoint): List<String> {
        // Antimeridian: the short way across the dateline is ±360° away
        // from the naive difference. Unwrap it, or a 0.2° cross-line
        // route sweeps the whole globe (its ~72 buckets then fail the
        // MAX_BUCKET_BUILDS gate) — a dateline archive is supported, so
        // its cross-line routes must actually work.
        val dlon = ((destination.lon - origin.lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val dlat = destination.lat - origin.lat
        val steps = (hypot(dlon, dlat) * CORRIDOR_SAMPLES_PER_DEGREE).toInt().coerceAtLeast(1)
        val buckets = LinkedHashSet<String>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            buckets.add(GraphPipeline.bucketNameFor(origin.lon + dlon * t, origin.lat + dlat * t))
        }
        return buckets.toList()
    }

    /**
     * The one-ring neighborhood of [known] — the island fallback's wider
     * corridor for routes whose real road path detours off the straight
     * line.
     */
    private fun haloBuckets(known: Set<String>): List<String> {
        val halo = LinkedHashSet<String>()
        for (bucket in known) {
            val (lon_min, lat_min) = parseBucketMin(bucket)
            for (d_lon in intArrayOf(-GraphPipeline.BUCKET_DEGREES, 0, GraphPipeline.BUCKET_DEGREES)) {
                for (d_lat in intArrayOf(-GraphPipeline.BUCKET_DEGREES, 0, GraphPipeline.BUCKET_DEGREES)) {
                    // Longitude wraps at the antimeridian, like the bucket
                    // grid's own longitude math: W180's west neighbor is
                    // E175, not a nonexistent W185.
                    val neighbor = GraphPipeline.bucketName(
                        wrapBucketLon(lon_min + d_lon),
                        lat_min + d_lat,
                    )
                    if (neighbor !in known) halo.add(neighbor)
                }
            }
        }
        return halo.toList()
    }

    /** The southwest corner of a bucket, from its name. */
    private fun parseBucketMin(bucket: String): Pair<Int, Int> {
        val lon_token = bucket.substringBefore("_")
        val lat_token = bucket.substringAfter("_")
        val lon = lon_token.drop(1).toInt() * (if (lon_token.first() == 'W') -1 else 1)
        val lat = lat_token.drop(1).toInt() * (if (lat_token.first() == 'S') -1 else 1)
        return lon to lat
    }

    /** Normalizes a raw longitude into [-180, 180) — the antimeridian wrap. */
    private fun wrapLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** The bucket-min form of [wrapLon]: -185 (W185) wraps to 175 (E175). */
    private fun wrapBucketLon(lonMin: Int): Int = ((lonMin + 180) % 360 + 360) % 360 - 180

    /**
     * True when [point] sits within the engine's cross-bucket match range
     * of a bucket edge (BRouter's NodesCache probes roughly ±0.03° into
     * the neighbors): only then can a missing NEIGHBOR bucket be the cause
     * of an unmapped waypoint, and only then is a neighbor build worth
     * trying before declaring "no road".
     */
    private fun nearBucketEdge(point: GeoPoint): Boolean {
        val lon_min = GraphPipeline.bucketLonMinFor(point.lon)
        val lat_min = GraphPipeline.bucketLatMinFor(point.lat)
        val d_lon = minOf(
            point.lon - lon_min,
            lon_min + GraphPipeline.BUCKET_DEGREES - point.lon,
        )
        val d_lat = minOf(
            point.lat - lat_min,
            lat_min + GraphPipeline.BUCKET_DEGREES - point.lat,
        )
        return minOf(d_lon, d_lat) < WAYPOINT_EDGE_MARGIN_DEG
    }

    /**
     * Warms the routing engine in the background so the user's first
     * route does not pay the cold-process cost: profile compilation plus
     * the search loop's classes all load on the first engine pass, which
     * takes minutes on slow hardware while warm routes return in seconds.
     * Parses every runtime profile — the parsed contexts are cached and
     * reused by later routes — and, once at least one bucket is built,
     * runs one tiny throwaway route inside it to load the search classes
     * too.
     *
     * Best-effort by design: every failure is swallowed, and a first-ever
     * session (no buckets built yet) warms the profiles only — its first
     * route after the build still pays the search-loop load once.
     */
    suspend fun warmEngine(context: Context) {
        val app_context = context.applicationContext
        try {
            val profiles_dir = GraphBuildCoordinator.ensureBuildAssets(app_context)
            val lookup_content: String
            val segments_dir: File
            withContext(Dispatchers.IO) {
                lookup_content = File(profiles_dir, "lookups.dat").readText()
                segments_dir = File(File(app_context.filesDir, "graph"), "segments")
            }
            val profile_contents = HashMap<RouteProfile, String>()
            for (profile in RouteProfile.entries) {
                profile_contents[profile] = withContext(Dispatchers.IO) {
                    app_context.assets.open("profiles/${profile.assetName}")
                        .bufferedReader().use { it.readText() }
                }
                // On the routing thread: the parse is engine work, and it
                // must not race a live route on the same context cache.
                withContext(engine_dispatcher) {
                    contextFor(profile, profile_contents.getValue(profile), lookup_content, segments_dir)
                }
            }
            // The warm route replays the last pair that actually routed
            // (see [rememberWarmRoute]). A made-up pair is NOT a
            // substitute: a bucket's center can sit on an isolated or
            // roadless stretch, and the engine's dynamic match range
            // (up to 60 km) then turns the "tiny" warmup into a
            // tens-of-kilometers search that still fails — measured: a
            // bucket-center gamble died in the island pre-check in
            // under a second, loading none of the search machinery. So
            // an install's very first route pays the cold search once
            // and writes the recipe; every process start after that
            // replays a pair known to match and to search.
            val saved = warmPrefs(app_context)
            val saved_profile = saved.getString(KEY_WARM_PROFILE, null)
                ?.let { name -> RouteProfile.entries.firstOrNull { it.name == name } }
            val has_saved_pair = saved.contains(KEY_WARM_ORIGIN_LON) &&
                saved.contains(KEY_WARM_ORIGIN_LAT) &&
                saved.contains(KEY_WARM_DEST_LON) &&
                saved.contains(KEY_WARM_DEST_LAT)
            if (saved_profile == null || !has_saved_pair) {
                return
            }
            val warm_origin = GeoPoint(
                saved.getFloat(KEY_WARM_ORIGIN_LON, 0.0f).toDouble(),
                saved.getFloat(KEY_WARM_ORIGIN_LAT, 0.0f).toDouble(),
            )
            val warm_destination = GeoPoint(
                saved.getFloat(KEY_WARM_DEST_LON, 0.0f).toDouble(),
                saved.getFloat(KEY_WARM_DEST_LAT, 0.0f).toDouble(),
            )
            withContext(engine_dispatcher) {
                val ctx = contextFor(
                    saved_profile,
                    profile_contents.getValue(saved_profile),
                    lookup_content,
                    segments_dir,
                )
                try {
                    RoutingEngine(ctx).doRouting(listOf(
                        waypoint(warm_origin, "from"),
                        waypoint(warm_destination, "to"),
                    ))
                } catch (e: Exception) {
                    // A failure here (routing data wiped or replaced) is
                    // fine: the replay has warmed the classes either way.
                    android.util.Log.i("RouterGateway", "warm route failed: $e")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: a warmup that fails leaves the first real
            // route to pay the cost, exactly as before.
            android.util.Log.i("RouterGateway", "engine warmup aborted: $e")
        }
    }

    /** Prefs holding the warmup recipe: the last pair that routed. */
    private fun warmPrefs(context: Context) =
        context.getSharedPreferences(WARM_PREFS, android.content.Context.MODE_PRIVATE)

    private fun rememberWarmRoute(
        context: Context,
        profile: RouteProfile,
        origin: GeoPoint,
        destination: GeoPoint,
    ) {
        // Float, not double: SharedPreferences carries no doubles, and a
        // ~1 cm replay drift is nothing against the engine's
        // tens-of-meters waypoint catching range.
        warmPrefs(context).edit()
            .putString(KEY_WARM_PROFILE, profile.name)
            .putFloat(KEY_WARM_ORIGIN_LON, origin.lon.toFloat())
            .putFloat(KEY_WARM_ORIGIN_LAT, origin.lat.toFloat())
            .putFloat(KEY_WARM_DEST_LON, destination.lon.toFloat())
            .putFloat(KEY_WARM_DEST_LAT, destination.lat.toFloat())
            .apply()
    }

    /** A fresh manager over the app's graph directories (read-only use here). */
    private fun managerFor(context: Context): GraphBuildManager =
        GraphBuildManager(
            archiveFile = ArchiveStore.archiveFile(context),
            segmentsDir = File(File(context.filesDir, "graph"), "segments"),
            workRoot = File(context.cacheDir, "graph-work"),
        )

    /**
     * A point inside the named 5° bucket — the build service works from a
     * location, and the bucket's center is always interior.
     */
    private fun bucketCenter(bucket: String): GeoPoint {
        val (lon_min, lat_min) = parseBucketMin(bucket)
        return GeoPoint(
            lon_min + BUCKET_CENTER_OFFSET,
            lat_min + BUCKET_CENTER_OFFSET,
        )
    }

    /** One engine run; every failure maps to something the drawer can show. */
    private suspend fun calculate(
        profile: RouteProfile,
        profileContent: String,
        lookupContent: String,
        segmentsDir: File,
        origin: GeoPoint,
        destination: GeoPoint,
    ): RouteResult = withContext(engine_dispatcher) {
        val routing_context = contextFor(profile, profileContent, lookupContent, segmentsDir)
        val track = try {
            RoutingEngine(routing_context).doRouting(listOf(
                waypoint(origin, "from"),
                waypoint(destination, "to"),
            ))
        } catch (e: CancellationException) {
            // No eviction: the engine pairs every per-route mutation with
            // a finally that runs even while the CancellationException
            // propagates (setWaypoint/unsetWaypoint, the nogo-list
            // save/restore, per-run recomputations of direction flags),
            // so a run cut mid-flight leaves the context as clean as a
            // completed one. Evicting here would throw away the parsed
            // profile — the very cost the cache exists to avoid — every
            // time the user replaces a route mid-calculation.
            throw e
        } catch (e: IllegalArgumentException) {
            // The engine signals missing data via require(), not its own
            // exception hierarchy: an absent bucket file, a waypoint off
            // the road network, and the island pre-checks all land here.
            val message = e.message ?: ""
            if (message.contains("datafile") && message.contains("not found")) {
                // The message names e.g. "E115_S30.rd5"; the drawer's label
                // reads better without the suffix.
                throw MissingBucketException(
                    message.substringAfter("datafile ").substringBefore(" not found")
                        .trim().removeSuffix(".rd5")
                )
            }
            if (message.contains("not mapped in existing datafile")) {
                // The caller decides whether this is fixable (a waypoint
                // near a bucket edge whose roads lie across the border) or
                // terminal (a long-press on a lake).
                throw WaypointUnmappedException(
                    if (message.startsWith("from")) origin else destination
                )
            }
            // The island pre-check ("start/target island detected for
            // section $i") reports the same disconnect the
            // RoutingIslandException path does — map it identically so the
            // drawer never shows raw engine text.
            if (message.contains("island detected")) {
                throw IslandFailureException()
            }
            // Anything else from require() is engine-internal (e.g.
            // "Required value was null.") — dependency text the drawer must
            // never show. Surface a generic failure and keep the raw
            // message for logcat.
            android.util.Log.w(
                "RouterGateway",
                "engine IllegalArgumentException: $message",
                e,
            )
            throw RouteException("the routing engine could not process this route")
        } catch (e: RoutingIslandException) {
            throw IslandFailureException()
        } catch (e: RoutingException) {
            // "no track found at pass=$cfi" is the search draining a
            // disconnected component — same family as an island.
            if (e.message?.contains("no track found") == true) {
                throw IslandFailureException()
            }
            if (e.message?.contains("memory limit") == true) {
                // Raw engine text must never reach the drawer; this one is
                // exactly what long multi-bucket corridors risk.
                throw RouteException("the route search ran out of memory — try a shorter route")
            }
            throw RouteException(e.message ?: "no route found")
        } catch (e: IllegalStateException) {
            // requireNotNull(null) { "no track found" } — the engine's null
            // result — is an ISE, not a RoutingException.
            if (e.message?.contains("no track found") == true) {
                throw IslandFailureException()
            }
            if (e.message?.contains("re-tracking") == true) {
                throw RouteException("the route could not be traced on the prepared road network")
            }
            // Same rule as the IllegalArgumentException catch-all above: raw
            // engine text stays in logcat, the drawer gets a generic failure.
            android.util.Log.w(
                "RouterGateway",
                "engine IllegalStateException: ${e.message}",
                e,
            )
            throw RouteException("the routing engine could not process this route")
        } catch (e: DataFileNotFoundException) {
            throw RouteException(
                "the routing data for a region on this route is damaged — rebuild the routing data",
            )
        } catch (e: DataCorruptionException) {
            throw RouteException(
                "the routing data for a region on this route is damaged — rebuild the routing data",
            )
        } ?: throw RouteException("no route found")

        RouteResult(
            profile = profile,
            origin = origin,
            destination = destination,
            distanceMeters = track.distance,
            durationSeconds = track.totalSeconds,
            ascendMeters = track.ascend,
            points = track.nodes.map {
                GeoPoint(it.position.longitudeDegree, it.position.latitudeDegree)
            },
            turns = turnsOf(track),
        )
    }

    /**
     * Fills the turns' street names from the archive's
     * `transportation_name` layer — the engine's `wayTags` can never carry
     * one (see [StreetNameResolver]). A name must never sink the route:
     * any failure here keeps the engine's (nameless) turns as they were.
     */
    private suspend fun enrichTurnNames(context: Context, result: RouteResult): RouteResult {
        val archive = ArchiveStore.archiveFile(context)
        val turns = withContext(Dispatchers.IO) {
            runCatching {
                PmtilesReader.open(archive.absolutePath).use { reader ->
                    StreetNameResolver.resolveNames(reader, result.points, result.turns)
                }
            }.getOrElse { failure ->
                android.util.Log.w(
                    "RouterGateway",
                    "street name resolution failed; turns stay nameless",
                    failure,
                )
                result.turns
            }
        }
        if (turns === result.turns) return result
        return result.copy(turns = turns)
    }

    /**
     * The engine's voice hints, mapped to the UI's turn model. Hints index
     * the same nodes list [RouteResult.points] is built from, so
     * [TurnPoint.pointIndex] aligns with the rendered polyline; the
     * per-leg distance comes from `distanceToNext` (meters of route
     * between this hint and the next). Beeline/off-route engine-internal
     * commands carry no user-facing maneuver and are dropped.
     */
    private fun turnsOf(track: OsmTrack): List<TurnPoint> {
        val hints = track.voiceHints.list
        val turns = ArrayList<TurnPoint>(hints.size)
        for ((index, hint) in hints.withIndex()) {
            val command = when (hint.command) {
                VoiceHint.Command.C -> TurnCommand.STRAIGHT
                VoiceHint.Command.TL -> TurnCommand.TURN_LEFT
                VoiceHint.Command.TSLL -> TurnCommand.TURN_SLIGHT_LEFT
                VoiceHint.Command.TSHL -> TurnCommand.TURN_SHARP_LEFT
                VoiceHint.Command.TR -> TurnCommand.TURN_RIGHT
                VoiceHint.Command.TSLR -> TurnCommand.TURN_SLIGHT_RIGHT
                VoiceHint.Command.TSHR -> TurnCommand.TURN_SHARP_RIGHT
                VoiceHint.Command.KL -> TurnCommand.KEEP_LEFT
                VoiceHint.Command.KR -> TurnCommand.KEEP_RIGHT
                VoiceHint.Command.TLU, VoiceHint.Command.TRU, VoiceHint.Command.TU ->
                    TurnCommand.U_TURN
                VoiceHint.Command.RNDB -> TurnCommand.ROUNDABOUT
                VoiceHint.Command.RNLB -> TurnCommand.ROUNDABOUT_LEFT
                VoiceHint.Command.EL -> TurnCommand.EXIT_LEFT
                VoiceHint.Command.ER -> TurnCommand.EXIT_RIGHT
                VoiceHint.Command.END -> TurnCommand.ARRIVE
                // BL (beeline) and OFFR are engine bookkeeping, and UNSET
                // never survives the hint processor's dedupe.
                VoiceHint.Command.UNSET, VoiceHint.Command.BL, VoiceHint.Command.OFFR -> null
            } ?: continue
            turns.add(
                TurnPoint(
                    command = command,
                    lon = hint.position.longitudeDegree,
                    lat = hint.position.latitudeDegree,
                    pointIndex = hint.indexInTrack,
                    // distanceToNext holds the route length from THIS hint
                    // to the NEXT one, so a turn's approach distance is
                    // the previous hint's distanceToNext.
                    distanceFromPreviousMeters = if (index == 0) 0.0 else {
                        hints[index - 1].distanceToNext
                    },
                    streetName = hint.goodWay?.wayTags?.get("name"),
                )
            )
        }
        return turns
    }

    /**
     * Starts the `:graph` build for [bucket] and suspends until it is
     * durably built (or recorded built-empty). The service itself skips
     * buckets already on disk, so this is cheap for prepared regions; the
     * durable truth is `build-state.json` (via [GraphBuildManager]), not
     * the status file — a status write can be a previous run's terminal
     * state, while the state file only ever names buckets that finished.
     */
    private suspend fun ensureBucket(context: Context, bucket: String) {
        val point = bucketCenter(bucket)
        val sent_at = System.currentTimeMillis()
        val manager = managerFor(context)
        try {
            context.startForegroundService(
                Intent(context, GraphBuildService::class.java)
                    .setAction(GraphBuildService.ACTION_BUILD_FOR_LOCATION)
                    .putExtra(GraphBuildService.EXTRA_LON, point.lon)
                    .putExtra(GraphBuildService.EXTRA_LAT, point.lat)
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) — the
            // user backgrounded the app while this multi-minute loop was
            // still mid-corridor, and Android forbids a NEW foreground
            // service start from the background. viewModelScope is not
            // lifecycle-aware, so this lands here mid-route; fail honestly
            // (the drawer's Retry works from the foreground) instead of
            // letting the raw platform message kill it.
            throw RouteException(
                "Android stopped the routing-data preparation because Atlas was in the " +
                    "background — open the app and try again",
            )
        }
        var saw_fresh_status = false
        // A fresh terminal SUCCESS with our bucket still missing is
        // ambiguous at first: the service may hold a queued request (the
        // latest-wins pending slot) that will build it next, and a no-op
        // run publishes no running=true in between. Only give up once the
        // service stays quiet past this grace window after such a success.
        var quiet_since = 0L
        while (bucket !in withContext(Dispatchers.IO) { manager.builtBuckets() }) {
            currentCoroutineContext().ensureActive()
            val status = GraphBuildCoordinator.readStatusAsync(context)
            if (status != null && status.timestampMs >= sent_at) {
                saw_fresh_status = true
                // Only this request's own terminal/stale states count —
                // before the service writes anything, the file still
                // holds the previous run's story.
                if (status.running) {
                    quiet_since = 0L
                    if (System.currentTimeMillis() - status.timestampMs > BUILD_STALE_MS) {
                        throw RouteException("graph build was interrupted")
                    }
                } else {
                    if (status.error != null) {
                        throw RouteException("graph build failed: ${status.error}")
                    }
                    if (quiet_since == 0L) quiet_since = System.currentTimeMillis()
                }
            }
            if (!saw_fresh_status && System.currentTimeMillis() - sent_at > BUILD_START_TIMEOUT_MS) {
                throw RouteException("graph build did not start")
            }
            if (quiet_since != 0L && System.currentTimeMillis() - quiet_since > BUILD_QUIET_MS) {
                throw RouteException(
                    "the routing data build finished without preparing this region",
                )
            }
            delay(BUILD_POLL_MS)
        }
    }

    private fun waypoint(point: GeoPoint, name: String) =
        OsmNodeNamed(Position.fromDegrees(point.lon, point.lat)).apply { this.name = name }

    private const val BUILD_POLL_MS = 2_000L
    /** Mirrors graph-prep-flow's staleness budget for a dead :graph process. */
    private const val BUILD_STALE_MS = 90_000L
    private const val BUILD_START_TIMEOUT_MS = 30_000L
    /** Queued runs start within seconds of the previous terminal write. */
    private const val BUILD_QUIET_MS = 30_000L
    private const val BUCKET_CENTER_OFFSET = 2.5
    private const val WARM_PREFS = "routing-warmup"
    private const val KEY_WARM_PROFILE = "warm.profile"
    private const val KEY_WARM_ORIGIN_LON = "warm.origin.lon"
    private const val KEY_WARM_ORIGIN_LAT = "warm.origin.lat"
    private const val KEY_WARM_DEST_LON = "warm.dest.lon"
    private const val KEY_WARM_DEST_LAT = "warm.dest.lat"
    private const val MAX_BUCKET_BUILDS = 16
    /** 16 samples per degree: far denser than the 5° bucket grid. */
    private const val CORRIDOR_SAMPLES_PER_DEGREE = 16
    /** Generously above the engine's ~±0.03° cross-edge match range. */
    private const val WAYPOINT_EDGE_MARGIN_DEG = 0.05

    /** The routing thread's stack: deep enough for path chains of ~10⁵ links. */
    private const val ENGINE_STACK_BYTES = 64L * 1024 * 1024
}