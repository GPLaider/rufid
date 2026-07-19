package io.github.rufid.partition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rufid.core.SeekableBlockDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class UefiNtfsHelperIntegrationTest {
    @Test
    fun writesAndReadsBackPinnedUefiNtfsHelperImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = context.assets.open("payloads/uefi/uefi-ntfs.img").use { it.readBytes() }
        val modifiedHelper = helper.clone().also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        }
        val output = context.filesDir.resolve("uefi-ntfs-qa.img")
        output.delete()

        val device = FileBlockDevice(output, DEVICE_SIZE)
        try {
            val layout = UefiNtfsRuntimeWriter(device, payloadSource = { helper }).write()
            val readBack = ByteArray(helper.size)
            device.seek(layout.helperStartSector * layout.sectorSize)
            assertEquals(helper.size, device.read(readBack, 0, readBack.size))
            assertArrayEquals(helper, readBack)
        } finally {
            device.close()
            output.delete()
        }

        assertTrue(
            UefiNtfsSecureBootVerifier().matchesPinnedSignedPayload(helper, UefiArchitecture.X64),
        )
        assertFalse(
            UefiNtfsSecureBootVerifier().matchesPinnedSignedPayload(modifiedHelper, UefiArchitecture.X64),
        )
    }

    @Test
    fun writesAndReadsBackGptAtProductionImageSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = context.assets.open("payloads/uefi/uefi-ntfs.img").use { it.readBytes() }
        val output = context.filesDir.resolve("uefi-ntfs-gpt-large-qa.img")
        output.delete()

        val device = FileBlockDevice(output, PRODUCTION_IMAGE_SIZE)
        try {
            val layout = UefiNtfsRuntimeWriter(
                blockDevice = device,
                mode = UefiNtfsPartitionTableMode.Gpt,
                payloadSource = { helper },
            ).write()
            assertEquals(UefiNtfsPartitionTableMode.Gpt, layout.mode)
            assertEquals(PRODUCTION_IMAGE_SIZE / 512L - 34L, layout.gptLastUsableLba)
        } finally {
            device.close()
            output.delete()
        }
    }

    private class FileBlockDevice(file: java.io.File, override val sizeBytes: Long) : SeekableBlockDevice {
        override val blockSize: Int = 512
        private val file = RandomAccessFile(file, "rw").also { it.setLength(sizeBytes) }

        override fun seek(byteOffset: Long) = file.seek(byteOffset)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)

        override fun write(buffer: ByteArray, offset: Int, length: Int) = file.write(buffer, offset, length)

        override fun flush() = file.fd.sync()

        override fun close() = file.close()
    }

    private companion object {
        const val DEVICE_SIZE = 64L * 1024L * 1024L
        const val PRODUCTION_IMAGE_SIZE = 12L * 1024L * 1024L * 1024L
    }
}
