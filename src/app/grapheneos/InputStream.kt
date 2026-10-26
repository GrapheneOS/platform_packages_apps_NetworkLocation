package app.grapheneos

import java.io.IOException
import java.io.InputStream

@Throws(IOException::class)
internal fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
    require(maxBytes in 0 until Int.MAX_VALUE)
    // Read one extra byte to distinguish an exact fit from an oversized response.
    // Bound the stream itself: Content-Length can be absent or describe compressed data.
    val bytes = readNBytes(maxBytes + 1)
    if (bytes.size > maxBytes) {
        throw IOException("response exceeds $maxBytes bytes")
    }
    return bytes
}
