package io.droyagent.app.nativeui.standardvoice

/** Only a content fingerprint enters the voice journal; the ordinary draft remains in its existing store. */
internal fun standardVoiceDraftToken(text: String, attachments: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    listOf(text, attachments).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
