package io.github.rufid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class RawImageWriterTest {
    @Test
    fun writesFinalPartialBlockWithZeroPadding() {
        val device = MemoryBlockDevice(sizeBytes = 1024)
        val writer = RawImageWriter(device, bufferSize = 512)

        writer.write(ByteArrayInputStream(byteArrayOf(1, 2, 3)), imageSize = 3) {}

        assertArrayEquals(byteArrayOf(1, 2, 3), device.bytes.copyOfRange(0, 3))
        assertEquals(0, device.bytes[3].toInt())
        assertTrue(device.flushed)
    }

    @Test
    fun rejectsWritesThatWouldExceedDeviceCapacity() {
        val device = MemoryBlockDevice(sizeBytes = 512)
        val writer = RawImageWriter(device, bufferSize = 512)

        assertThrows(IOException::class.java) {
            writer.write(ByteArrayInputStream(ByteArray(513)), imageSize = 513) {}
        }
    }

    @Test
    fun rejectsShortInputWhenExpectedSizeIsKnown() {
        val device = MemoryBlockDevice(sizeBytes = 2048)
        val writer = RawImageWriter(device, bufferSize = 512)

        assertThrows(IOException::class.java) {
            writer.write(ByteArrayInputStream(ByteArray(10)), imageSize = 11) {}
        }
    }

    private class MemoryBlockDevice(
        override val blockSize: Int = 512,
        override val sizeBytes: Long,
    ) : SeekableBlockDevice {
        val bytes = ByteArray(sizeBytes.toInt())
        var flushed = false
        private var position = 0

        override fun seek(byteOffset: Long) {
            position = byteOffset.toInt()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            bytes.copyInto(buffer, offset, position, position + length)
            position += length
            return length
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            buffer.copyInto(bytes, position, offset, offset + length)
            position += length
        }

        override fun flush() {
            flushed = true
        }

        override fun close() = Unit
    }
}
