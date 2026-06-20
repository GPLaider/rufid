package io.github.rufid.usb

internal object BulkOnlyConstants {
    const val CBW_SIGNATURE = 0x43425355
    const val CSW_SIGNATURE = 0x53425355
    const val CBW_SIZE = 31
    const val CSW_SIZE = 13
    const val FLAG_IN = 0x80
    const val FLAG_OUT = 0x00
    const val CSW_STATUS_PASSED = 0x00
    const val CSW_STATUS_FAILED = 0x01
    const val CSW_STATUS_PHASE_ERROR = 0x02
    const val MASS_STORAGE_RESET = 0xff
}
