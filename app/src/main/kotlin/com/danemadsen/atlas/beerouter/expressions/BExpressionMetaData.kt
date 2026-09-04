package com.danemadsen.atlas.beerouter.expressions

public class BExpressionMetaData {
    public var lookupVersion: Short = -1
        internal set
    public var lookupMinorVersion: Short = -1
        internal set
    public var minAppVersion: Short = -1
        internal set

    private val listeners: MutableMap<String?, BExpressionContext> = HashMap()

    /**
     * Register a listener for the given context.
     *
     * @param context the context name
     * @param ctx the expression context
     */
    public fun registerListener(context: String?, ctx: BExpressionContext) {
        listeners[context] = ctx
    }

    /**
     * Read metadata from the given content string.
     *
     * @param content the metadata content
     * @throws NumberFormatException if version numbers are malformed
     * @throws IllegalArgumentException if metadata parsing fails
     */
    public fun readMetaData(content: String) {
        var ctx: BExpressionContext? = null

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith(CONTEXT_TAG) -> {
                    ctx = listeners[line.substring(CONTEXT_TAG.length)]
                }

                line.startsWith(VERSION_TAG) -> {
                    lookupVersion = line.substring(VERSION_TAG.length).toShort()
                }

                line.startsWith(MINOR_VERSION_TAG) -> {
                    lookupMinorVersion = line.substring(MINOR_VERSION_TAG.length).toShort()
                }

                line.startsWith(MIN_APP_VERSION_TAG) -> {
                    minAppVersion = line.substring(MIN_APP_VERSION_TAG.length).toShort()
                }

                line.startsWith(VARLENGTH_TAG) -> Unit
                else -> ctx?.parseMetaLine(line)
            }
        }

        for (c in listeners.values) {
            c.finishMetaParsing()
        }
    }

    public companion object {
        private const val CONTEXT_TAG = "---context:"
        private const val VERSION_TAG = "---lookupversion:"
        private const val MINOR_VERSION_TAG = "---minorversion:"
        private const val VARLENGTH_TAG = "---readvarlength"
        private const val MIN_APP_VERSION_TAG = "---minappversion:"
    }
}
