package io.github.rufid.usb

import java.io.IOException

/**
 * Pure USB BOT validation helpers (no Android types) for unit tests and transport checks.
 */
internal object BulkOnlyValidation {
    /**
     * CSW dCSWDataResidue is little-endian unsigned 32-bit.
     * Production path: [BulkOnlyTransport] reads CSW via ByteBuffer.int then this mask.
     */
    fun parseCswResidueUnsigned(littleEndianIntBits: Int): Long =
        littleEndianIntBits.toLong() and 0xffff_ffffL

    /**
     * Zero-length control transfers: Android returns transferred length or negative on failure.
     * Non-negative (including 0) is success for wLength=0.
     */
    fun isZeroLengthControlTransferOk(result: Int): Boolean = result >= 0

    fun zeroLengthControlFailureMessage(
        step: String,
        result: Int,
        interfaceId: Int? = null,
        endpointAddress: Int? = null,
    ): String = buildString {
        append("USB control transfer failed: step=$step result=$result")
        if (interfaceId != null) append(" interfaceId=$interfaceId")
        if (endpointAddress != null) {
            append(" endpointAddress=0x${endpointAddress.toString(16).uppercase().padStart(2, '0')}")
        }
    }

    /**
     * USB BOT: status PASSED with non-zero residue is not full success for the CBW data length.
     */
    fun cswPassedWithUnexpectedResidueMessage(
        commandName: String,
        status: Int,
        residue: Long,
        dataLength: Int,
    ): String =
        "CSW incomplete transfer: command=$commandName status=$status " +
            "residue=$residue dataLength=$dataLength"

    /**
     * setInterface policy for Rufid BOT open:
     * - alternateSetting 0 (default BOT iface from discovery): best-effort if setInterface fails
     *   after a successful claimInterface of the same UsbInterface (already claimed default alt).
     * - non-zero alternateSetting: hard failure — BOT alt must be selected explicitly.
     */
    fun setInterfaceFailureIsHard(alternateSetting: Int): Boolean = alternateSetting != 0

    fun setInterfaceHardFailureMessage(alternateSetting: Int, interfaceId: Int): String =
        "Failed to setInterface for BOT alternate setting: " +
            "interfaceId=$interfaceId alternateSetting=$alternateSetting"
}

internal fun requireZeroLengthControlOk(
    result: Int,
    step: String,
    interfaceId: Int? = null,
    endpointAddress: Int? = null,
) {
    if (!BulkOnlyValidation.isZeroLengthControlTransferOk(result)) {
        throw IOException(
            BulkOnlyValidation.zeroLengthControlFailureMessage(
                step = step,
                result = result,
                interfaceId = interfaceId,
                endpointAddress = endpointAddress,
            ),
        )
    }
}
