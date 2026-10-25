package app.luximio.links

/** Produces an opaque native proof for this exact hash; only the backend verifies it. */
fun interface PlayIntegrityTokenProvider {
    suspend fun requestToken(requestHash: String): String
}
