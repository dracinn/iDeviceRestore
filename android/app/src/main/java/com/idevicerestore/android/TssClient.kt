package com.idevicerestore.android

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.xmlpull.v1.XmlPullParser

/** HTTPS-only Apple TSS client. This does not perform any USB/device state transition. */
class TssClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val logger: (String) -> Unit = {}
) {
    data class Response(
        val status: Int,
        val message: String,
        val plist: PlistValue.Dict,
        val apImg4Ticket: ByteArray
    ) {
        fun summary(): String = "TSS response: status=$status message=$message ApImg4Ticket=${apImg4Ticket.size} bytes"
    }

    fun send(request: TssRequestBuilder.BuildResult): Response {
        require(endpoint.startsWith("https://")) { "TSS endpoint must use HTTPS" }
        val payload = request.xml()
        logger("TSS: POST Apple signing request (${payload.size} XML bytes, components=${request.componentCount})")

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("User-Agent", "InetURL/1.0")
            setFixedLengthStreamingMode(payload.size)
        }

        try {
            connection.outputStream.use { it.write(payload) }
            val httpCode = connection.responseCode
            val body = (if (httpCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            val text = body.toString(StandardCharsets.UTF_8)
            val status = Regex("(?:^|&)STATUS=(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val message = Regex("(?:^|&)MESSAGE=([^&\\r\\n]*)").find(text)?.groupValues?.get(1).orEmpty()
            if (httpCode !in 200..299) throw IOException("Apple TSS HTTP $httpCode status=$status message=$message")
            if (!text.contains("MESSAGE=SUCCESS")) {
                throw IOException("Apple TSS rejected request: status=$status message=${message.ifEmpty { "unknown" }}")
            }

            val xmlOffset = text.indexOf("<?xml")
            if (xmlOffset < 0) throw IOException("Apple TSS response did not contain an XML plist")
            val plistBytes = text.substring(xmlOffset).toByteArray(StandardCharsets.UTF_8)
            val plist = parsePlist(plistBytes)
            val ticket = plist.data("ApImg4Ticket")
                ?: throw IOException("Apple TSS response did not contain ApImg4Ticket data")
            if (ticket.isEmpty()) throw IOException("Apple TSS returned an empty ApImg4Ticket")

            logger("TSS: SUCCESS status=$status ApImg4Ticket=${ticket.size} bytes")
            return Response(status, message.ifEmpty { "SUCCESS" }, plist, ticket.copyOf())
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlist(data: ByteArray): PlistValue.Dict {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(data), Charsets.UTF_8.name())
        }
        while (!(parser.eventType == XmlPullParser.START_TAG && parser.name == "plist")) {
            if (parser.next() == XmlPullParser.END_DOCUMENT) throw IOException("TSS plist root not found")
        }
        if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "dict") {
            throw IOException("TSS plist root value is not a dictionary")
        }
        return XmlPlistCodec.parseValue(parser) as? PlistValue.Dict
            ?: throw IOException("TSS plist root value is not a dictionary")
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://gs.apple.com/TSS/controller?action=2"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
