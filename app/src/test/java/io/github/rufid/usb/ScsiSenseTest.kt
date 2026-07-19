package io.github.rufid.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScsiSenseTest {
    @Test
    fun unsupportedSynchronizeCacheIsNonFatalFlushCase() {
        val invalidCommand = ScsiCommandException(
            commandName = "SYNCHRONIZE CACHE(10)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x05, 0x20, 0x00),
        )
        val invalidField = ScsiCommandException(
            commandName = "SYNCHRONIZE CACHE(16)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x05, 0x24, 0x00),
        )
        val invalidParameterList = ScsiCommandException(
            commandName = "SYNCHRONIZE CACHE(10)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x05, 0x26, 0x00),
        )

        assertTrue(invalidCommand.isUnsupportedSynchronizeCache())
        assertTrue(invalidField.isUnsupportedSynchronizeCache())
        assertTrue(invalidParameterList.isUnsupportedSynchronizeCache())
    }

    @Test
    fun writeErrorsRemainFatal() {
        val writeFailure = ScsiCommandException(
            commandName = "WRITE(10)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x05, 0x20, 0x00),
        )
        val writeInvalidParameterList = ScsiCommandException(
            commandName = "WRITE(10)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x05, 0x26, 0x00),
        )
        val mediumError = ScsiCommandException(
            commandName = "SYNCHRONIZE CACHE(10)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x03, 0x11, 0x00),
        )

        assertFalse(writeFailure.isUnsupportedSynchronizeCache())
        assertFalse(writeInvalidParameterList.isUnsupportedSynchronizeCache())
        assertFalse(mediumError.isUnsupportedSynchronizeCache())
    }

    @Test
    fun diagnosticMessagesIdentifyWriteOrFinalFlushPath() {
        val writeFailure = ScsiCommandException(
            commandName = "WRITE(10)",
            status = 1,
            residue = 512,
            sense = ScsiSense(0x70, 0x03, 0x11, 0x00),
        )
        val flushFailure = ScsiCommandException(
            commandName = "SYNCHRONIZE CACHE(10)",
            status = 1,
            residue = 0L,
            sense = ScsiSense(0x70, 0x04, 0x29, 0x00),
        )

        val writeMessage = writeFailure.usbWriteFailureMessage(byteOffset = 1_048_576L, lba = 2048L, blocks = 128)
        val flushMessage = flushFailure.usbFlushFailureMessage(byteOffset = 7_864_320_000L)

        assertTrue(writeMessage.contains("USB data write failed"))
        assertTrue(writeMessage.contains("byte offset 1048576"))
        assertTrue(writeMessage.contains("LBA 2048"))
        assertTrue(writeMessage.contains("WRITE(10) failed"))
        assertTrue(flushMessage.contains("USB final cache sync failed"))
        assertTrue(flushMessage.contains("SYNCHRONIZE CACHE(10) failed"))
    }

    @Test
    fun unknownCompletionMessagePreservesScsiSenseAndForbidsSilentRetryHint() {
        val writeFailure = ScsiCommandException(
            commandName = "WRITE(10)",
            status = 1,
            residue = 512,
            sense = ScsiSense(0x70, 0x03, 0x11, 0x00),
        )
        val unknown = formatUsbWriteUnknownCompletionMessage(
            byteOffset = 1_048_576L,
            lba = 2048L,
            blocks = 128,
            cause = writeFailure,
        )
        assertTrue(unknown.contains("byte offset 1048576"))
        assertTrue(unknown.contains("LBA 2048"))
        assertTrue(unknown.contains("blocks 128"))
        assertTrue(unknown.contains("Completion is unknown; verify media before retrying."))
        assertTrue(unknown.contains("WRITE(10) failed"))
        // Preserve SCSI sense detail from the original command exception.
        assertTrue(unknown.contains(writeFailure.message!!))
    }

    @Test
    fun unknownCompletionMessageForNonScsiCauseStillCarriesPosition() {
        val cause = IOException("CSW transfer failed: expected=13 actual=-1")
        val unknown = formatUsbWriteUnknownCompletionMessage(
            byteOffset = 4096L,
            lba = 8L,
            blocks = 8,
            cause = cause,
        )
        assertTrue(unknown.contains("byte offset 4096"))
        assertTrue(unknown.contains("LBA 8"))
        assertTrue(unknown.contains("blocks 8"))
        assertTrue(unknown.contains("CSW transfer failed: expected=13 actual=-1"))
        assertTrue(unknown.contains("Completion is unknown; verify media before retrying."))
    }
}
