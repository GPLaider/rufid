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
        connection.claimInterface(device.usbInterface, true)
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
        require(offset == 0) { "Non-zero offset writes are not implemented yet." }
        require(length % blockSize == 0) {
            "Writes must be block-aligned. length=$length blockSize=$blockSize"
        }
        if (length == 0) return
        val blocks = length / blockSize
        val lba = currentLba()
        try {
            transport.command(writeCommand(lba, blocks), buffer, length, dataIn = false)
        } catch (error: ScsiCommandException) {
            throw IOException(error.usbWriteFailureMessage(positionBytes, lba, blocks), error)
        }
        positionBytes += length
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
        connection.releaseInterface(device.usbInterface)
        connection.close()
    }

    private fun waitUntilReady() {
        var lastError: Throwable? = null
        repeat(10) {
            try {
                transport.command(Scsi.testUnitReady(), null, 0, dataIn = false)
                return
            } catch (error: ScsiCommandException) {
                lastError = error
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
}
