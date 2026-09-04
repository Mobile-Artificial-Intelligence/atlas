package com.danemadsen.atlas.beerouter.map.generator

public data class PoiGenerationConfig(
    public val rules: List<PoiRule>,
) {
    public val isEmpty: Boolean
        get() = rules.isEmpty()

    public fun matchingRules(tags: Map<String, String>): List<PoiRule> = rules.filter { it.matches(tags) }

    public companion object {
        public val EMPTY: PoiGenerationConfig = PoiGenerationConfig(emptyList())
    }
}
