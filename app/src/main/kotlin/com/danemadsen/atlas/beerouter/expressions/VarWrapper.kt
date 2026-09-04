package com.danemadsen.atlas.beerouter.expressions

public class VarWrapper(
    public var vars: FloatArray? = null,
    public var hash: Int = 0
) {

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VarWrapper) return false
        if (hash != other.hash) return false
        return vars.contentEquals(other.vars)
    }

    override fun toString(): String = "VarWrapper(vars=${vars?.contentToString()}, hash=$hash)"
}
