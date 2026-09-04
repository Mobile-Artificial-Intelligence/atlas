package com.danemadsen.atlas.beerouter.map.generator

public object PoiConfigParser {
    private val additionalTagPattern = Regex("""\[([^=\]]+)=([^\]]+)\]""")

    public fun parse(content: String): PoiGenerationConfig {
        val rules = mutableListOf<PoiRule>()
        for ((index, rawLine) in content.lineSequence().withIndex()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) {
                continue
            }
            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.lastIndex) {
                "invalid POI rule on line ${index + 1}: $rawLine"
            }
            val key = line.substring(0, separator).trim()
            val valueAndRest = line.substring(separator + 1).trim()
            require(key.isNotEmpty() && valueAndRest.isNotEmpty()) {
                "invalid POI rule on line ${index + 1}: $rawLine"
            }
            val value = additionalTagPattern.replace(valueAndRest, "").trim()
            require(value.isNotEmpty()) {
                "invalid POI rule on line ${index + 1}: missing primary value: $rawLine"
            }
            require(value != "*") {
                "wildcard POI rules are not supported by lookup-driven rd5 encoding: $rawLine"
            }
            val additionalTags = additionalTagPattern.findAll(valueAndRest).associate {
                it.groupValues[1] to it.groupValues[2]
            }
            rules += PoiRule(key, value, additionalTags)
        }
        return PoiGenerationConfig(rules.distinct())
    }
}
