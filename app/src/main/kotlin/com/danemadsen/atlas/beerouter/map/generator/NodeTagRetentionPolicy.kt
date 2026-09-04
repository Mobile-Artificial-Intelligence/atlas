package com.danemadsen.atlas.beerouter.map.generator

import java.io.File

internal data class NodeTagRetentionPolicy(
    val routingRelevantKeys: Set<String>,
    val poiConfig: PoiGenerationConfig,
) {
    internal fun retainedTags(tags: Map<String, String>): Map<String, String> {
        if (poiConfig.isEmpty) {
            return tags
        }
        val retained = linkedMapOf<String, String>()
        for ((key, value) in tags) {
            if (key in routingRelevantKeys) {
                retained[key] = value
            }
        }
        for (rule in poiConfig.matchingRules(tags)) {
            retained[rule.key] = rule.value
            for ((tagKey, tagValue) in rule.additionalTags) {
                val actualValue = tags[tagKey]
                if (actualValue != null) {
                    retained[tagKey] = actualValue
                }
            }
        }
        return retained
    }

    internal companion object {
        internal val DEFAULT: NodeTagRetentionPolicy = NodeTagRetentionPolicy(emptySet(), PoiGenerationConfig.EMPTY)

        internal fun fromProfiles(
            lookupFile: File,
            profileFiles: List<File>,
            poiConfig: PoiGenerationConfig,
        ): NodeTagRetentionPolicy {
            val nodeKeys = parseNodeLookupKeys(lookupFile.readText())
            val routingRelevantKeys = linkedSetOf<String>()
            val tokenPattern = Regex("[A-Za-z][A-Za-z0-9:_-]*")
            for (profileFile in profileFiles.distinct()) {
                if (!profileFile.isFile) {
                    continue
                }
                val tokens = tokenPattern.findAll(profileFile.readText()).map { it.value }.toSet()
                for (key in nodeKeys) {
                    if (key in tokens) {
                        routingRelevantKeys += key
                    }
                }
            }
            return NodeTagRetentionPolicy(routingRelevantKeys, poiConfig)
        }

        private fun parseNodeLookupKeys(content: String): Set<String> {
            var inNodeContext = false
            val keys = linkedSetOf<String>()
            for (rawLine in content.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("---context:")) {
                    inNodeContext = line == "---context:node"
                    continue
                }
                if (!inNodeContext || line.isEmpty() || line.startsWith("#") || line.startsWith("---")) {
                    continue
                }
                val keyPart = line.substringBefore(' ').trim()
                val separator = keyPart.indexOf(';')
                if (separator > 0) {
                    keys += keyPart.substring(0, separator)
                }
            }
            return keys
        }
    }
}
