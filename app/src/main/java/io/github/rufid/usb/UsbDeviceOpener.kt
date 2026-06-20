package io.github.rufid.usb

import java.io.IOException

object UsbDeviceOpener {
    fun open(device: UsbMassStorageDevice): UsbScsiBlockDevice {
        if (!device.manager.hasPermission(device.device)) {
            throw SecurityException("Missing USB permission for ${device.label}")
        }

        val connection = device.manager.openDevice(device.device)
            ?: throw IOException("Failed to open ${device.label}")

        return UsbScsiBlockDevice(connection, device).also { it.init() }
    }
}

