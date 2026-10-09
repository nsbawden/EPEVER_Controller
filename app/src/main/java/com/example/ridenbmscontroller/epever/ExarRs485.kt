package com.example.ridenbmscontroller.epever

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface

/**
 * Best-effort userspace setup for the official EPEVER XR21V1410 cable.
 * Puts the UART GPIO mode into RS-485 half-duplex. Safe to call on other chips.
 */
object ExarRs485 {
    const val VID_EXAR = 0x04E2
    const val PID_XR21V1410 = 0x1410

    fun looksLikeOfficialCable(device: UsbDevice): Boolean {
        return device.vendorId == VID_EXAR
    }

    fun enable(connection: UsbDeviceConnection, device: UsbDevice) {
        if (!looksLikeOfficialCable(device)) return
        val usbInterface = device.getInterface(0)
        if (!connection.claimInterface(usbInterface, true)) return
        runCatching {
            // XR21V1410 UART GPIO mode register 0x1a = RS-485 (value 0x03).
            writeReg(connection, usbInterface, 0x1A, 0x03)
        }
    }

    private fun writeReg(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        register: Int,
        value: Int
    ) {
        val requestType = 0x40 // vendor, host-to-device, device
        connection.controlTransfer(
            requestType,
            0x00,
            value and 0xFFFF,
            (usbInterface.id shl 8) or (register and 0xFF),
            null,
            0,
            200
        )
    }
}
