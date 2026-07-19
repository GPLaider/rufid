package io.github.rufid.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class BulkOnlyTransport(
    private val connection: UsbDeviceConnection,
    private val device: UsbMassStorageDevice,
    private val timeoutMs: Int = 30_000,
) {
    private val tag = AtomicInteger(1)

    /**
     * BOT open/reset sequence for flaky Android USB hosts (Samsung etc.):
     * mass-storage class reset + CLEAR_FEATURE(ENDPOINT_HALT) on both bulk pipes.
     * Zero-length controlTransfer results: only negative values are failures.
     */
    fun resetRecovery() {
        val empty = ByteArray(0)
        val resetResult = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
            BulkOnlyConstants.MASS_STORAGE_RESET,
            0,
            device.usbInterface.id,
            empty,
            0,
            timeoutMs,
        )
        requireZeroLengthControlOk(
            result = resetResult,
            step = "MASS_STORAGE_RESET",
            interfaceId = device.usbInterface.id,
        )
        clearHalt(device.inEndpoint.address)
        clearHalt(device.outEndpoint.address)
    }

    fun command(
        commandBlock: ByteArray,
        data: ByteArray?,
        dataLength: Int,
        dataIn: Boolean,
    ) {
        require(dataLength == 0 || data != null)
        require(data == null || dataLength <= data.size)

        val commandTag = tag.getAndIncrement()
        sendCbw(commandTag, commandBlock, dataLength, dataIn)

        if (dataLength > 0 && data != null) {
            val endpoint = if (dataIn) device.inEndpoint else device.outEndpoint
            val transferred = transferData(endpoint, data, dataLength)
            if (transferred != dataLength) {
                runCatching { resetRecovery() }
                throw IOException("Bulk transfer failed: expected=$dataLength actual=$transferred")
            }
        }

        val csw = readCsw(commandTag)
        val commandName = Scsi.commandName(commandBlock)
        if (csw.status != BulkOnlyConstants.CSW_STATUS_PASSED) {
            if (csw.status == BulkOnlyConstants.CSW_STATUS_PHASE_ERROR) {
                runCatching { resetRecovery() }
            }
            val sense = if ((commandBlock.firstOrNull()?.toInt()?.and(0xff) ?: -1) == Scsi.REQUEST_SENSE) {
                null
            } else {
                requestSenseOrNull()
            }
            throw ScsiCommandException(commandName, csw.status, csw.residue, sense)
        }
        if (csw.residue != 0L) {
            runCatching { resetRecovery() }
            throw IOException(
                BulkOnlyValidation.cswPassedWithUnexpectedResidueMessage(
                    commandName = commandName,
                    status = csw.status,
                    residue = csw.residue,
                    dataLength = dataLength,
                ),
            )
        }
    }

    private fun sendCbw(commandTag: Int, commandBlock: ByteArray, dataLength: Int, dataIn: Boolean) {
        require(commandBlock.size <= 16)

        val buffer = ByteBuffer.allocate(BulkOnlyConstants.CBW_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(BulkOnlyConstants.CBW_SIGNATURE)
        buffer.putInt(commandTag)
        buffer.putInt(dataLength)
        buffer.put(if (dataIn) BulkOnlyConstants.FLAG_IN.toByte() else BulkOnlyConstants.FLAG_OUT.toByte())
        buffer.put(0) // LUN 0
        buffer.put(commandBlock.size.toByte())
        buffer.put(commandBlock)
        while (buffer.position() < BulkOnlyConstants.CBW_SIZE) buffer.put(0)

        val bytes = buffer.array()
        val transferred = connection.bulkTransfer(device.outEndpoint, bytes, bytes.size, timeoutMs)
        if (transferred != bytes.size) {
            runCatching { resetRecovery() }
            throw IOException("CBW transfer failed: expected=${bytes.size} actual=$transferred")
        }
    }

    private fun readCsw(expectedTag: Int): CswResult {
        val bytes = ByteArray(BulkOnlyConstants.CSW_SIZE)
        var transferred = connection.bulkTransfer(device.inEndpoint, bytes, bytes.size, timeoutMs)
        if (transferred != bytes.size) {
            // Same command completion recovery only — clear IN halt and re-receive CSW once.
            // Does not re-issue the original SCSI WRITE/READ.
            clearHalt(device.inEndpoint.address)
            try {
                Thread.sleep(20L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            transferred = connection.bulkTransfer(device.inEndpoint, bytes, bytes.size, timeoutMs)
        }
        if (transferred != bytes.size) {
            runCatching { resetRecovery() }
            throw IOException("CSW transfer failed: expected=${bytes.size} actual=$transferred")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val signature = buffer.int
        val tag = buffer.int
        val residueBits = buffer.int
        val residue = BulkOnlyValidation.parseCswResidueUnsigned(residueBits)
        val status = buffer.get().toInt() and 0xff

        if (signature != BulkOnlyConstants.CSW_SIGNATURE) {
            runCatching { resetRecovery() }
            throw IOException("Invalid CSW signature")
        }
        if (tag != expectedTag) {
            runCatching { resetRecovery() }
            throw IOException("Mismatched CSW tag")
        }

        return CswResult(residue, status)
    }

    private fun transferData(endpoint: UsbEndpoint, data: ByteArray, dataLength: Int): Int {
        // Align OUT chunks to wMaxPacketSize; many Android hosts stall on odd bulk OUT sizes.
        val packet = max(endpoint.maxPacketSize, 64)
        val preferredChunk = if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
            max(packet, 4 * 1024)
        } else {
            16 * 1024
        }
        var total = 0
        while (total < dataLength) {
            var chunk = min(preferredChunk, dataLength - total)
            val remainingAfter = dataLength - total - chunk
            if (endpoint.direction == UsbConstants.USB_DIR_OUT && remainingAfter > 0 && chunk % packet != 0) {
                chunk -= chunk % packet
                if (chunk == 0) chunk = min(packet, dataLength - total)
            }
            val transferred = connection.bulkTransfer(endpoint, data, total, chunk, timeoutMs)
            if (transferred <= 0) return if (total == 0) transferred else total
            total += transferred
        }
        return total
    }

    private fun requestSenseOrNull(): ScsiSense? {
        val senseBytes = ByteArray(18)
        val commandTag = tag.getAndIncrement()
        return try {
            sendCbw(commandTag, Scsi.requestSense(senseBytes.size), senseBytes.size, dataIn = true)
            val transferred = transferData(device.inEndpoint, senseBytes, senseBytes.size)
            val csw = readCsw(commandTag)
            if (transferred <= 0 ||
                csw.status != BulkOnlyConstants.CSW_STATUS_PASSED ||
                csw.residue != 0L
            ) {
                null
            } else {
                ScsiSense.parse(senseBytes.copyOf(transferred))
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun clearHalt(endpointAddress: Int) {
        val empty = ByteArray(0)
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT,
            USB_REQ_CLEAR_FEATURE,
            USB_FEATURE_ENDPOINT_HALT,
            endpointAddress,
            empty,
            0,
            timeoutMs,
        )
        requireZeroLengthControlOk(
            result = result,
            step = "CLEAR_FEATURE(ENDPOINT_HALT)",
            endpointAddress = endpointAddress,
        )
    }

    private data class CswResult(
        val residue: Long,
        val status: Int,
    )
}

private const val USB_RECIP_INTERFACE = 0x01
private const val USB_RECIP_ENDPOINT = 0x02
private const val USB_REQ_CLEAR_FEATURE = 0x01
private const val USB_FEATURE_ENDPOINT_HALT = 0x00
