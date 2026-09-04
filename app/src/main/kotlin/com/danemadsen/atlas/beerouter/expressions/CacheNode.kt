package com.danemadsen.atlas.beerouter.expressions

public class CacheNode(
    public var ab: ByteArray? = null,
    public var vars: FloatArray? = null,
    public var hash: Int = 0
) {

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val n = other as? CacheNode ?: return false
        if (hash != n.hash) return false

        val currentAb = ab ?: return true // hack: null = crc match only
        val otherAb = n.ab ?: return false
        if (currentAb === otherAb) return true
        return currentAb.contentEquals(otherAb)
    }
}
