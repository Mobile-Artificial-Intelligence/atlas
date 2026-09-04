package com.danemadsen.atlas.beerouter.codec


public interface TagValueValidator {
    /**
     * @param tagValueSet the way description to check
     * @return 0 = nothing, 1=no matching, 2=normal
     */
    public fun accessType(tagValueSet: ByteArray?): Int

    public fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray?

    public fun isLookupIdxUsed(idx: Int): Boolean

    public fun setDecodeForbidden(decodeForbidden: Boolean)

    public fun checkStartWay(ab: ByteArray?): Boolean
}
