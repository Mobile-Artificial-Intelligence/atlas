// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables
package com.danemadsen.atlas.beerouter.expressions


public class BExpressionContextNode : BExpressionContext {
    override val buildInVariableNames: Array<String?>
        get() = BUILD_IN_VARIABLES

    /**
     * Get the initial cost variable.
     */
    public val initialcost: Float
        get() = getBuildInVariable(0)


    /**
     * Create an Expression-Context for node context.
     *
     * @param meta the expression metadata
     */
    public constructor(meta: BExpressionMetaData) : super("node", meta)

    /**
     * Create an Expression-Context for node context.
     *
     * @param hashSize size of hashmap for result caching
     * @param meta the expression metadata
     */
    public constructor(hashSize: Int, meta: BExpressionMetaData) : super("node", hashSize, meta)

    public companion object {
        private val BUILD_IN_VARIABLES: Array<String?> = arrayOf("initialcost")
    }
}
