package com.danemadsen.atlas.beerouter.map.generator

import kotlin.math.absoluteValue

internal data class GeneratedLookupResult(
    val lookupContent: String,
    val lookupVersion: Short,
    val lookupMinorVersion: Short,
    val addedEntries: Set<PoiRule>,
)

internal object GeneratedLookups {
    internal fun generate(baseContent: String, config: PoiGenerationConfig): GeneratedLookupResult {
        if (config.isEmpty) {
            val version = baseContent.substringAfter("---lookupversion:").lineSequence().first().trim().toShort()
            val minorVersion = baseContent.substringAfter("---minorversion:").lineSequence().first().trim().toShort()
            return GeneratedLookupResult(baseContent, version, minorVersion, emptySet())
        }

        val lines = baseContent.lines().toMutableList()
        val nodeContextStart = lines.indexOfFirst { it.trim() == "---context:node" }
        require(nodeContextStart >= 0) { "lookup metadata does not contain ---context:node" }
        val nodeContextEnd = lines.subList(nodeContextStart + 1, lines.size)
            .indexOfFirst { it.trim().startsWith("---context:") }
            .let { relativeIndex -> if (relativeIndex < 0) lines.size else nodeContextStart + 1 + relativeIndex }

        val existingValuesByKey = linkedMapOf<String, MutableSet<String>>()
        val firstLineByKey = linkedMapOf<String, Int>()
        for (lineIndex in (nodeContextStart + 1) until nodeContextEnd) {
            val parsed = parseLookupLine(lines[lineIndex]) ?: continue
            firstLineByKey.putIfAbsent(parsed.key, lineIndex)
            existingValuesByKey.getOrPut(parsed.key) { linkedSetOf() }.addAll(parsed.values)
        }

        val missingByKey = linkedMapOf<String, MutableSet<String>>()
        val addedEntries = linkedSetOf<PoiRule>()
        for (rule in config.rules.sortedWith(compareBy(PoiRule::key, PoiRule::value))) {
            val ruleEntries = buildList {
                add(PoiRule(rule.key, rule.value, emptyMap()))
                for ((tagKey, tagValue) in rule.additionalTags) {
                    add(PoiRule(tagKey, tagValue, emptyMap()))
                }
            }
            for (entry in ruleEntries) {
                val existingValues = existingValuesByKey[entry.key]
                if (existingValues?.contains(entry.value) == true) {
                    continue
                }
                missingByKey.getOrPut(entry.key) { linkedSetOf() }.add(entry.value)
                addedEntries += entry
            }
        }

        if (missingByKey.isEmpty()) {
            val version = baseContent.substringAfter("---lookupversion:").lineSequence().first().trim().toShort()
            val minorVersion = baseContent.substringAfter("---minorversion:").lineSequence().first().trim().toShort()
            return GeneratedLookupResult(baseContent, version, minorVersion, emptySet())
        }

        for ((key, missingValues) in missingByKey) {
            val lineIndex = firstLineByKey[key]
            if (lineIndex != null) {
                val parsed = requireNotNull(parseLookupLine(lines[lineIndex]))
                val mergedValues = (parsed.values + missingValues).distinct()
                lines[lineIndex] = "${parsed.key};${parsed.histogram} ${mergedValues.joinToString(" ")}"
            } else {
                for (value in missingValues) {
                    lines.add(nodeContextEnd, "$key;0000000000 $value")
                }
            }
        }

        val versionLineIndex = lines.indexOfFirst { it.trim().startsWith("---lookupversion:") }
        require(versionLineIndex >= 0) { "lookup metadata does not contain ---lookupversion" }
        val minorLineIndex = lines.indexOfFirst { it.trim().startsWith("---minorversion:") }
        require(minorLineIndex >= 0) { "lookup metadata does not contain ---minorversion" }

        val lookupVersion = lines[versionLineIndex].trim().substringAfter(':').toShort()
        val baseMinorVersion = lines[minorLineIndex].trim().substringAfter(':').toInt()
        val schemaFingerprint = addedEntries
            .joinToString("|") { "${it.key}=${it.value}" }
            .hashCode()
            .absoluteValue
        val minorVersion = ((baseMinorVersion + 1 + (schemaFingerprint % 30_000)) % Short.MAX_VALUE.toInt())
            .toShort()
        lines[minorLineIndex] = "---minorversion:$minorVersion"

        return GeneratedLookupResult(
            lookupContent = lines.joinToString("\n"),
            lookupVersion = lookupVersion,
            lookupMinorVersion = minorVersion,
            addedEntries = addedEntries,
        )
    }

    private data class ParsedLookupLine(
        val key: String,
        val histogram: String,
        val values: List<String>,
    )

    private fun parseLookupLine(line: String): ParsedLookupLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---")) {
            return null
        }
        val keyAndRest = trimmed.split(' ', limit = 2)
        if (keyAndRest.size < 2) {
            return null
        }
        val keyPart = keyAndRest[0]
        val separator = keyPart.indexOf(';')
        if (separator <= 0 || separator == keyPart.lastIndex) {
            return null
        }
        return ParsedLookupLine(
            key = keyPart.substring(0, separator),
            histogram = keyPart.substring(separator + 1),
            values = keyAndRest[1].trim().split(Regex("\\s+")).filter(String::isNotBlank),
        )
    }
}
