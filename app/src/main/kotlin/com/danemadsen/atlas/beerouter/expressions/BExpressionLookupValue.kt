/**
 * A lookup value with optional aliases
 *
 *
 * toString just gives the primary value,
 * equals just compares against primary value
 * matches() also compares aliases
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.expressions

public class BExpressionLookupValue(public val value: String) {
    private val aliases: MutableSet<String> = linkedSetOf()

    override fun toString(): String = value

    /**
     * Add an alias for this lookup value.
     *
     * @param alias the alias to add
     */
    public fun addAlias(alias: String) {
        aliases.add(alias)
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is String -> value == other
            is BExpressionLookupValue -> value == other.value
            else -> false
        }
    }

    override fun hashCode(): Int = value.hashCode()

    /**
     * Check if the given string matches this lookup value or any of its aliases.
     *
     * @param s the string to match
     * @return true if the string matches
     */
    public fun matches(s: String?): Boolean = value == s || s in aliases
}
