package io.github.rufid.format

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryVolumeLabelTest {
    @Test
    fun derivesLabelFromUsbProductName() {
        assertEquals("SANDISK", RecoveryVolumeLabel.fromDeviceLabel("USB SanDisk 3.2Gen1"))
    }

    @Test
    fun keepsMeaningfulNonGenericDeviceName() {
        assertEquals("KINGSTON DA", RecoveryVolumeLabel.fromDeviceLabel("Kingston DataTraveler 3.0"))
    }

    @Test
    fun removesUsbGenerationWithoutInventingCapacity() {
        assertEquals("SANDISK", RecoveryVolumeLabel.fromDeviceLabel("USB SanDisk 3.2 Gen 1"))
        assertEquals("SANDISK", RecoveryVolumeLabel.fromDeviceLabel("SanDisk USB 3.0"))
    }

    @Test
    fun preservesDeviceNameOrderWithoutBrandGuessing() {
        assertEquals("JETFLASH TR", RecoveryVolumeLabel.fromDeviceLabel("JetFlash Transcend 3.1"))
    }

    @Test
    fun fallsBackForEmptyNames() {
        assertEquals("USB DRIVE", RecoveryVolumeLabel.fromDeviceLabel("   "))
    }
}
