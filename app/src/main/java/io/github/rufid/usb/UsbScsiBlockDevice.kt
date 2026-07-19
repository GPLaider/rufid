package io.github.rufid.usb

import android.hardware.usb.UsbDeviceConnection
import io.github.rufid.core.SeekableBlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbScsiBlockDevice(
    private val connection: UsbDeviceConnection,
    private val device: UsbMassStorageDevice,
) : SeekableBlockDevice {
    private val transport = BulkOnlyTransport(connection, device)
    private var positionBytes = 0L
    private var lastBlockAddress = 0L
    private var use16ByteCommands = false

    override var blockSize: Int = 512
        private set

    override var sizeBytes: Long = 0
        private set

    fun init() {
        try {
            if (!connection.claimInterface(device.usbInterface, true)) {
                throw IOException(
                    "Failed to claim USB interface for ${device.label} " +
                        "(interfaceId=${device.usbInterface.id})",
                )
            }
            // Explicit BOT alternate-setting select (docs/USB_BOT_COMPATIBILITY.md).
            // Return value is not discarded: hard-fail for non-zero alt; best-effort for alt 0
            // after successful claim of the same interface (default BOT setting already claimed).
            val alternateSetting = device.usbInterface.alternateSetting
            val setInterfaceOk = connection.setInterface(device.usbInterface)
            if (!setInterfaceOk && BulkOnlyValidation.setInterfaceFailureIsHard(alternateSetting)) {
                throw IOException(
                    BulkOnlyValidation.setInterfaceHardFailureMessage(
                        alternateSetting = alternateSetting,
                        interfaceId = device.usbInterface.id,
                    ),
                )
            }
            transport.resetRecovery()
            waitUntilReady()

            val capacity = ByteArray(8)
            transport.command(Scsi.readCapacity10(), capacity, capacity.size, dataIn = true)
            val parsed = ByteBuffer.wrap(capacity).order(ByteOrder.BIG_ENDIAN)
            val lastBlockAddress10 = parsed.int.toLong() and 0xffff_ffffL
            val blockSize10 = parsed.int

            if (lastBlockAddress10 == 0xffff_ffffL) {
                readCapacity16()
            } else {
                lastBlockAddress = lastBlockAddress10
                blockSize = blockSize10
                use16ByteCommands = false
                sizeBytes = (lastBlockAddress + 1L) * blockSize.toLong()
            }
        } catch (error: Exception) {
            runCatching { connection.releaseInterface(device.usbInterface) }
            runCatching { connection.close() }
            throw error
        }
    }

    override fun seek(byteOffset: Long) {
        require(byteOffset >= 0)
        require(byteOffset % blockSize == 0L) {
            "USB SCSI writes must be block-aligned. offset=$byteOffset blockSize=$blockSize"
        }
        positionBytes = byteOffset
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset == 0) { "Non-zero offset reads are not implemented yet." }
        require(length % blockSize == 0)
        if (length == 0) return 0
        val blocks = length / blockSize
        val lba = currentLba()
        transport.command(readCommand(lba, blocks), buffer, length, dataIn = true)
        positionBytes += length
        return length
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        require(length % blockSize == 0) {
            "Writes must be block-aligned. length=$length blockSize=$blockSize"
        }
        if (length == 0) return
        // Phone USB hosts often fail large single WRITE + CSW cycles.
        // Chunk only; never re-issue the same LBA WRITE after an ambiguous CSW failure.
        val maxBytes = maxWriteBlocks * blockSize
        var remaining = length
        var sourceOffset = offset
        while (remaining > 0) {
            val chunk = minOf(maxBytes, remaining)
            writeChunk(buffer, sourceOffset, chunk)
            positionBytes += chunk
            sourceOffset += chunk
            remaining -= chunk
        }
    }

    private fun writeChunk(buffer: ByteArray, sourceOffset: Int, length: Int) {
        val packet = if (sourceOffset == 0 && length == buffer.size) {
            buffer
        } else {
            buffer.copyOfRange(sourceOffset, sourceOffset + length)
        }
        val blocks = length / blockSize
        val lba = currentLba()
        try {
            transport.command(writeCommand(lba, blocks), packet, length, dataIn = false)
        } catch (error: Exception) {
            // DATA OUT may have completed while CSW was lost. Do not re-issue WRITE.
            throw IOException(
                formatUsbWriteUnknownCompletionMessage(positionBytes, lba, blocks, error),
                error,
            )
        }
    }

    override fun flush() {
        val command = if (use16ByteCommands) Scsi.synchronizeCache16() else Scsi.synchronizeCache10()
        try {
            transport.command(command, null, 0, dataIn = false)
        } catch (error: ScsiCommandException) {
            if (error.isUnsupportedSynchronizeCache()) return
            throw IOException(error.usbFlushFailureMessage(positionBytes), error)
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(device.usbInterface) }
        runCatching { connection.close() }
    }

    private fun waitUntilReady() {
        var lastError: Throwable? = null
        repeat(12) {
            try {
                transport.command(Scsi.testUnitReady(), null, 0, dataIn = false)
                return
            } catch (error: ScsiCommandException) {
                lastError = error
                Thread.sleep(250L)
            } catch (error: IOException) {
                lastError = error
                transport.resetRecovery()
                Thread.sleep(250L)
            }
        }
        throw IOException("USB device is not ready", lastError)
    }

    private fun readCapacity16() {
        val capacity = ByteArray(32)
        transport.command(Scsi.readCapacity16(capacity.size), capacity, capacity.size, dataIn = true)
        val parsed = ByteBuffer.wrap(capacity).order(ByteOrder.BIG_ENDIAN)
        val lastBlockAddress16 = parsed.long
        if (lastBlockAddress16 < 0) throw IOException("Device is too large for signed 64-bit capacity accounting")
        lastBlockAddress = lastBlockAddress16
        blockSize = parsed.int
        use16ByteCommands = true
        sizeBytes = (lastBlockAddress + 1L) * blockSize.toLong()
    }

    private fun currentLba(): Long {
        val lba = positionBytes / blockSize
        if (lba > lastBlockAddress) throw IOException("LBA exceeds device capacity")
        return lba
    }

    private fun readCommand(lba: Long, blocks: Int): ByteArray =
        if (use16ByteCommands || lba > 0xffff_ffffL || blocks > 0xffff) {
            Scsi.read16(lba, blocks)
        } else {
            Scsi.read10(lba.toInt(), blocks)
        }

    private fun writeCommand(lba: Long, blocks: Int): ByteArray =
        if (use16ByteCommands || lba > 0xffff_ffffL || blocks > 0xffff) {
            Scsi.write16(lba, blocks)
        } else {
            Scsi.write10(lba.toInt(), blocks)
        }

    private companion object {
        // 4 KiB / 512 B = 8 blocks. Short BOT stages + packet-aligned OUT.
        const val maxWriteBlocks = 8
    }
}
