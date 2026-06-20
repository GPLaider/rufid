package io.github.rufid.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

data class UsbMassStorageDevice(
    val manager: UsbManager,
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
) {
    val label: String
        get() = listOfNotNull(device.manufacturerName, device.productName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { "USB Mass Storage ${device.vendorId}:${device.productId}" }

    companion object {
        private const val SCSI_TRANSPARENT = 6
        private const val BULK_ONLY = 80

        fun discover(manager: UsbManager): List<UsbMassStorageDevice> =
            manager.deviceList.values.flatMap { device ->
                (0 until device.interfaceCount).mapNotNull { index ->
                    val intf = device.getInterface(index)
                    if (!intf.isMassStorage()) return@mapNotNull null

                    val endpoints = intf.bulkEndpoints() ?: return@mapNotNull null
                    UsbMassStorageDevice(manager, device, intf, endpoints.first, endpoints.second)
                }
            }

        private fun UsbInterface.isMassStorage(): Boolean =
            interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                interfaceSubclass == SCSI_TRANSPARENT &&
                interfaceProtocol == BULK_ONLY

        private fun UsbInterface.bulkEndpoints(): Pair<UsbEndpoint, UsbEndpoint>? {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null

            for (index in 0 until endpointCount) {
                val endpoint = getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
            }

            return if (input != null && output != null) Pair(input, output) else null
        }
    }
}
