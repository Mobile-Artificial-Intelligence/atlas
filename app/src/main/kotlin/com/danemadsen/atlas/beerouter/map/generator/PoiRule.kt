package com.danemadsen.atlas.beerouter.map.generator

public data class PoiRule(
    public val key: String,
    public val value: String,
    public val additionalTags: Map<String, String>,
) {
    public fun matches(tags: Map<String, String>): Boolean =
        tags[key] == value && additionalTags.all { (k, v) -> tags[k] == v }
}
