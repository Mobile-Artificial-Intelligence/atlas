# MapLibre keeps its native + reflection surface; keep the defaults it ships.
-dontwarn org.maplibre.**

# Vendored BeeRouter engine is loaded reflectively nowhere, but its codecs
# use Class-based lookups; keep them whole to be safe until M9 audits this.
-keep class com.danemadsen.atlas.beerouter.** { *; }