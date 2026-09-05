package com.idevicerestore.android

import android.hardware.usb.UsbDeviceConnection

/**
 * Guarded bridge to Android's framework UsbDeviceConnection.resetDevice() System API.
 *
 * The method exists in Android's runtime framework but is not part of the normal public app SDK.
 * We resolve it before any state-changing restore upload. If hidden-API enforcement blocks access,
 * restore must remain disabled rather than approximating libirecovery's required host USB reset.
 */
object AndroidUsbReset {
    enum class Result {
        SUCCESS,
        INDETERMINATE_FALSE
    }

    data class Capability(
        val available: Boolean,
        val reason: String
    )

    fun capability(connection: UsbDeviceConnection): Capability = runCatching {
        val method = connection.javaClass.getMethod("resetDevice")
        require(method.returnType == Boolean::class.javaPrimitiveType) {
            "resetDevice has unexpected return type ${method.returnType.name}"
        }
        Capability(true, "Android UsbDeviceConnection.resetDevice is available")
    }.getOrElse {
        Capability(false, "Android USB reset unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }

    /**
     * Invokes Android's host-side USB reset.
     *
     * A false framework return is intentionally treated as indeterminate rather than fatal because
     * Apple DFU manifestation can detach the device while resetDevice() is still returning. The
     * caller must decide success from the subsequent USB personality/identity re-enumeration.
     * Reflection/invocation failures remain fatal because no reset attempt can be established.
     */
    fun reset(connection: UsbDeviceConnection): Result {
        val method = runCatching { connection.javaClass.getMethod("resetDevice") }
            .getOrElse { throw IllegalStateException("Android USB reset API is unavailable", it) }
        val result = runCatching { method.invoke(connection) as? Boolean }
            .getOrElse { throw IllegalStateException("Android USB reset invocation failed", it) }
        return if (result == true) Result.SUCCESS else Result.INDETERMINATE_FALSE
    }
}
