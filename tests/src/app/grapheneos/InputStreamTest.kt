package app.grapheneos

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStreamTest {
    @Test
    fun acceptsEmptyAndShortResponses() {
        for (size in listOf(0, 1, 63)) {
            val bytes = ByteArray(size) { it.toByte() }
            assertArrayEquals(bytes, bytes.inputStream().readBoundedBytes(64))
        }
    }

    @Test
    fun acceptsResponseAtLimit() {
        val bytes = ByteArray(1024 * 1024) { it.toByte() }
        assertArrayEquals(bytes, bytes.inputStream().readBoundedBytes(bytes.size))
    }

    @Test
    fun rejectsResponseOneByteOverLimit() {
        val bytes = ByteArray(1024 * 1024 + 1)
        assertThrows(IOException::class.java) {
            bytes.inputStream().readBoundedBytes(bytes.size - 1)
        }
    }

    @Test
    fun stopsReadingAnUnendingResponse() {
        var bytesRead = 0
        val input = object : InputStream() {
            override fun read(): Int {
                bytesRead++
                return 0
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                bytesRead += length
                return length
            }
        }
        assertThrows(IOException::class.java) { input.readBoundedBytes(100_000) }
        assertEquals(100_001, bytesRead)
    }

    @Test
    fun handlesShortReads() {
        val bytes = ByteArray(64) { it.toByte() }
        val input = object : InputStream() {
            private val source = bytes.inputStream()

            override fun read(): Int = source.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return source.read(buffer, offset, minOf(length, 3))
            }
        }
        assertArrayEquals(bytes, input.readBoundedBytes(64))
    }

    @Test
    fun boundsDecompressedBytes() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(4096)) }
        }.toByteArray()
        assertTrue(compressed.size < 1024)
        GZIPInputStream(compressed.inputStream()).use { input ->
            assertThrows(IOException::class.java) { input.readBoundedBytes(1024) }
        }
    }

    @Test
    fun preservesReadFailures() {
        val failure = IOException("read failed")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
        }
        val thrown = assertThrows(IOException::class.java) { input.readBoundedBytes(64) }
        assertSame(failure, thrown)
    }

    @Test
    fun leavesClosingToCaller() {
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(2)) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        input.use {
            assertThrows(IOException::class.java) { it.readBoundedBytes(1) }
            assertFalse(closed)
        }
        assertTrue(closed)
    }

    @Test
    fun handlesZeroLimit() {
        assertArrayEquals(byteArrayOf(), byteArrayOf().inputStream().readBoundedBytes(0))
        assertThrows(IOException::class.java) {
            byteArrayOf(0).inputStream().readBoundedBytes(0)
        }
    }

    @Test
    fun rejectsInvalidLimits() {
        for (limit in listOf(-1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                byteArrayOf().inputStream().readBoundedBytes(limit)
            }
        }
    }
}
