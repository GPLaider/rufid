package io.github.rufid.usb

import java.io.IOException

data class ScsiSense(
    val responseCode: Int,
    val senseKey: Int,
    val additionalSenseCode: Int,
    val additionalSenseQualifier: Int,
) {
    val senseKeyName: String
        get() = when (senseKey) {
            0x00 -> "No Sense"
            0x01 -> "Recovered Error"
            0x02 -> "Not Ready"
            0x03 -> "Medium Error"
            0x04 -> "Hardware Error"
            0x05 -> "Illegal Request"
            0x06 -> "Unit Attention"
            0x07 -> "Data Protect"
            0x08 -> "Blank Check"
            0x0b -> "Aborted Command"
            0x0e -> "Miscompare"
            else -> "Sense Key 0x${senseKey.toHex2()}"
        }

    val additionalDescription: String
        get() = when (additionalSenseCode to additionalSenseQualifier) {
            0x04 to 0x01 -> "device is becoming ready"
            0x04 to 0x02 -> "device needs initialization"
            0x20 to 0x00 -> "invalid command"
            0x21 to 0x00 -> "logical block address out of range"
            0x24 to 0x00 -> "invalid field in command"
            0x27 to 0x00 -> "write protected"
            0x28 to 0x00 -> "media changed"
            0x29 to 0x00 -> "device reset"
            0x3a to 0x00 -> "medium not present"
            else -> "ASC/ASCQ 0x${additionalSenseCode.toHex2()}/0x${additionalSenseQualifier.toHex2()}"
        }

    companion object {
        fun parse(bytes: ByteArray): ScsiSense {
            val responseCode = bytes.getOrNull(0)?.toInt()?.and(0x7f) ?: 0
            val senseKey = bytes.getOrNull(2)?.toInt()?.and(0x0f) ?: 0
            val asc = bytes.getOrNull(12)?.toInt()?.and(0xff) ?: 0
            val ascq = bytes.getOrNull(13)?.toInt()?.and(0xff) ?: 0
            return ScsiSense(responseCode, senseKey, asc, ascq)
        }
    }
}

class ScsiCommandException(
    commandName: String,
    status: Int,
    residue: Int,
    val sense: ScsiSense?,
) : IOException(
    buildString {
        append("$commandName failed: status=$status residue=$residue")
        if (sense != null) {
            append(", ${sense.senseKeyName}, ${sense.additionalDescription}")
        }
    },
)

private fun Int.toHex2(): String = toString(16).uppercase().padStart(2, '0')
