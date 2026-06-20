package io.github.rufid.format

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryVolumeLabelTest {
    @Test
    fun derivesLabelFromUsbProductName() {
        assertEquals("SANDISK 32G", RecoveryVolumeLabel.fromDeviceLabel("USB SanDisk 3.2Gen1"))
    }

    @Test
    fun keepsMeaningfulNonGenericDeviceName() {
        assertEquals("KINGSTON DA", RecoveryVolumeLabel.fromDeviceLabel("Kingston DataTraveler 3.0"))
    }

    @Test
    fun fallsBackForEmptyNames() {
        assertEquals("USB DRIVE", RecoveryVolumeLabel.fromDeviceLabel("   "))
    }
}
