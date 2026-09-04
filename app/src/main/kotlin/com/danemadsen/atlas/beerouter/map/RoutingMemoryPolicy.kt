package com.danemadsen.atlas.beerouter.map

public data class RoutingMemoryPolicy public constructor(
    public val graphInitialBudgetBytes: Long,
    public val graphHardLimitBytes: Long,
    public val tileCacheBudgetBytes: Long,
) {
    init {
        require(graphInitialBudgetBytes > 0) { "graphInitialBudgetBytes must be positive" }
        require(graphHardLimitBytes > 0) { "graphHardLimitBytes must be positive" }
        require(tileCacheBudgetBytes > 0) { "tileCacheBudgetBytes must be positive" }
        require(graphInitialBudgetBytes <= graphHardLimitBytes) {
            "graphInitialBudgetBytes must not exceed graphHardLimitBytes"
        }
    }

    public val totalHardLimitBytes: Long = graphHardLimitBytes + tileCacheBudgetBytes

    public companion object {
        /**
         * @throws IllegalArgumentException if the default total hard limit is invalid
         */
        public fun default(): RoutingMemoryPolicy = withTotalHardLimitMegabytes(128)

        /**
         * @throws IllegalArgumentException if [totalHardLimitMegabytes] is not positive
         */
        public fun withTotalHardLimitMegabytes(totalHardLimitMegabytes: Int): RoutingMemoryPolicy {
            require(totalHardLimitMegabytes > 0) { "totalHardLimitMegabytes must be positive" }
            val hardLimitBytes = totalHardLimitMegabytes * MEGABYTE
            return RoutingMemoryPolicy(
                graphInitialBudgetBytes = hardLimitBytes / 8,
                graphHardLimitBytes = hardLimitBytes * 3 / 4,
                tileCacheBudgetBytes = hardLimitBytes / 4,
            )
        }

        private const val MEGABYTE: Long = 1024L * 1024L
    }
}
