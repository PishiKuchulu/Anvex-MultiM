package com.anvex.agents.core.ports

/** Read access to snapshot content by digest (implemented by the CAS). Returned bytes are hash-verified by the implementation. */
interface BlobSource {
    fun get(digest: ContentDigest): ByteArray?
}
