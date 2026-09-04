package com.idevicerestore.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTPS transport for Apple TSS. This is never invoked by automatic USB probing. */
class TssHttpTransport(
    private val logger: (String) -> Unit = {},
    private val endpoint: String = DEFAULT_ENDPOINT
) {
    data class Response(
        val status: Int,
        val message: String,
        val plist: PlistNode.Dict?,
        val apImg4Ticket: ByteArray?
    ) {
        val success: Boolean get() = status == 0 && apImg4Ticket != null
        fun summary(): String =
            "TSS response: status=$status message=${message.ifBlank { "unknown" }} " +
                "ApImg4Ticket=${apImg4Ticket?.size?.let { "$it bytes" } ?: "absent"}"
    }

    fun send(request: TssRequestBuilder.Result): Response {
        val body = request.xml()
        require(body.isNotEmpty()) { "TSS request body is empty" }
        logger("TSS: POST Apple signing request bytes=${body.size} identity=${request.identityIndex}")

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("User-Agent", "InetURL/1.0")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setFixedLengthStreamingMode(body.size)
        }

        return try {
            connection.outputStream.use { it.write(body) }
            val http = connection.responseCode
            val responseBytes = (if (http in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (http !in 200..299) throw IOException("Apple TSS HTTP $http")

            val text = responseBytes.toString(Charsets.UTF_8)
            val status = parseStatus(text)
            val message = parseMessage(text)
            val xmlStart = text.indexOf("<?xml")
            val plist = if (xmlStart >= 0) {
                TssPlistParser.parse(text.substring(xmlStart).toByteArray(Charsets.UTF_8))
            } else null
            val ticket = plist?.data("ApImg4Ticket")
            val response = Response(status, message, plist, ticket)
            logger(response.summary())
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseStatus(text: String): Int {
        if ("MESSAGE=SUCCESS" in text) return 0
        return Regex("(?:^|&)STATUS=([0-9]+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun parseMessage(text: String): String =
        Regex("(?:^|&)MESSAGE=([^&\\r\\n]*)").find(text)?.groupValues?.get(1).orEmpty()

    companion object {
        const val DEFAULT_ENDPOINT = "https://gs.apple.com/TSS/controller?action=2"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
    }
}
