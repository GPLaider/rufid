package io.github.rufid.usb

internal object Scsi {
    const val TEST_UNIT_READY = 0x00
    const val REQUEST_SENSE = 0x03
    const val INQUIRY = 0x12
    const val READ_CAPACITY_10 = 0x25
    const val READ_10 = 0x28
    const val WRITE_10 = 0x2A
    const val SYNCHRONIZE_CACHE_10 = 0x35
    const val READ_16 = 0x88
    const val WRITE_16 = 0x8A
    const val SYNCHRONIZE_CACHE_16 = 0x91
    const val SERVICE_ACTION_IN_16 = 0x9E

    private const val READ_CAPACITY_16_SERVICE_ACTION = 0x10

    fun read10(lba: Int, blocks: Int): ByteArray =
        ByteArray(10).also {
            it[0] = READ_10.toByte()
            putInt(it, 2, lba)
            putShort(it, 7, blocks)
        }

    fun write10(lba: Int, blocks: Int): ByteArray =
        ByteArray(10).also {
            it[0] = WRITE_10.toByte()
            putInt(it, 2, lba)
            putShort(it, 7, blocks)
        }

    fun read16(lba: Long, blocks: Int): ByteArray =
        ByteArray(16).also {
            it[0] = READ_16.toByte()
            putLong(it, 2, lba)
            putInt(it, 10, blocks)
        }

    fun write16(lba: Long, blocks: Int): ByteArray =
        ByteArray(16).also {
            it[0] = WRITE_16.toByte()
            putLong(it, 2, lba)
            putInt(it, 10, blocks)
        }

    fun readCapacity10(): ByteArray =
        ByteArray(10).also { it[0] = READ_CAPACITY_10.toByte() }

    fun readCapacity16(allocationLength: Int = 32): ByteArray =
        ByteArray(16).also {
            it[0] = SERVICE_ACTION_IN_16.toByte()
            it[1] = READ_CAPACITY_16_SERVICE_ACTION.toByte()
            putInt(it, 10, allocationLength)
        }

    fun requestSense(allocationLength: Int = 18): ByteArray =
        ByteArray(6).also {
            it[0] = REQUEST_SENSE.toByte()
            it[4] = allocationLength.toByte()
        }

    fun testUnitReady(): ByteArray =
        ByteArray(6).also { it[0] = TEST_UNIT_READY.toByte() }

    fun synchronizeCache10(): ByteArray =
        ByteArray(10).also { it[0] = SYNCHRONIZE_CACHE_10.toByte() }

    fun synchronizeCache16(): ByteArray =
        ByteArray(16).also { it[0] = SYNCHRONIZE_CACHE_16.toByte() }

    fun commandName(commandBlock: ByteArray): String =
        when (commandBlock.firstOrNull()?.toInt()?.and(0xff)) {
            TEST_UNIT_READY -> "TEST UNIT READY"
            REQUEST_SENSE -> "REQUEST SENSE"
            INQUIRY -> "INQUIRY"
            READ_CAPACITY_10 -> "READ CAPACITY(10)"
            READ_10 -> "READ(10)"
            WRITE_10 -> "WRITE(10)"
            SYNCHRONIZE_CACHE_10 -> "SYNCHRONIZE CACHE(10)"
            READ_16 -> "READ(16)"
            WRITE_16 -> "WRITE(16)"
            SYNCHRONIZE_CACHE_16 -> "SYNCHRONIZE CACHE(16)"
            SERVICE_ACTION_IN_16 -> if (commandBlock.getOrNull(1)?.toInt()?.and(0x1f) == READ_CAPACITY_16_SERVICE_ACTION) {
                "READ CAPACITY(16)"
            } else {
                "SERVICE ACTION IN(16)"
            }
            else -> "SCSI command"
        }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun putShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 56).toByte()
        bytes[offset + 1] = (value ushr 48).toByte()
        bytes[offset + 2] = (value ushr 40).toByte()
        bytes[offset + 3] = (value ushr 32).toByte()
        bytes[offset + 4] = (value ushr 24).toByte()
        bytes[offset + 5] = (value ushr 16).toByte()
        bytes[offset + 6] = (value ushr 8).toByte()
        bytes[offset + 7] = value.toByte()
    }
}
