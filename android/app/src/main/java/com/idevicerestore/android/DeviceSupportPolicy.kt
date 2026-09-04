package com.idevicerestore.android

/** Central product-support gate for devices that iDeviceRestore must not operate on. */
object DeviceSupportPolicy {
    fun blockReason(device: FirmwareCatalog.Device): String? {
        val assessment = DeviceSupportMatrix.assess(device)
        return if (assessment.status == DeviceSupportMatrix.SupportStatus.BLOCKED) {
            "${assessment.summary} (${device.name}, ${device.identifier})"
        } else {
            null
        }
    }

    fun requireSupported(device: FirmwareCatalog.Device) {
        blockReason(device)?.let { reason -> throw UnsupportedOperationException(reason) }
    }

    fun requireSupportedIdentifier(identifier: String, devices: List<FirmwareCatalog.Device>) {
        val assessment = DeviceSupportMatrix.assessIdentifier(identifier, devices)
        if (assessment.status == DeviceSupportMatrix.SupportStatus.BLOCKED) {
            throw UnsupportedOperationException("${assessment.summary} ($identifier)")
        }
    }
}
