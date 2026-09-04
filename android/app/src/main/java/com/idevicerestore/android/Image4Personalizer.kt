package com.idevicerestore.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Pure Image4 personalization for the initial iBSS stage.
 *
 * This mirrors idevicerestore's img4_stitch_component layout for components that do not require
 * an IM4R/TBM payload: SEQUENCE { IA5String("IMG4"), raw IM4P, [0] ApImg4Ticket }.
 * It performs no USB I/O and deliberately refuses malformed/non-IM4P input.
 */
object Image4Personalizer {
    data class PersonalizedIbss(
        val file: File,
        val rawBytes: Long,
        val ticketBytes: Int,
        val personalizedBytes: Long,
        val identityIndex: Int,
        val buildId: String
    ) {
        fun summary(): String =
            "Image4 iBSS: raw=$rawBytes bytes ticket=$ticketBytes bytes personalized=$personalizedBytes bytes " +
                "identity=$identityIndex build=$buildId"
    }

    fun personalizeIbss(
        rawIbss: IpswComponentExtractor.ExtractedComponent,
        ticket: TssTicketStore.Ticket,
        destinationDirectory: File,
        logger: (String) -> Unit = {}
    ): PersonalizedIbss {
        require(rawIbss.name == "iBSS") { "Only iBSS is accepted by this stage" }
        require(rawIbss.identityIndex == ticket.identityIndex) {
            "iBSS identity ${rawIbss.identityIndex} does not match TSS identity ${ticket.identityIndex}"
        }
        require(ticket.apImg4Ticket.isNotEmpty()) { "ApImg4Ticket is empty" }
        require(rawIbss.file.isFile) { "Extracted iBSS not found: ${rawIbss.file.absolutePath}" }
        check(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) {
            "Could not create personalization workspace: ${destinationDirectory.absolutePath}"
        }

        val component = rawIbss.file.readBytes()
        val componentRoot = DerReader(component).readRoot()
        require(componentRoot.tag == TAG_SEQUENCE) { "iBSS is not an ASN.1 sequence" }
        require(componentRoot.endOffset == component.size) { "iBSS has trailing data outside its ASN.1 root" }
        val componentChildren = DerReader(component, componentRoot.valueOffset, componentRoot.endOffset).readAll()
        require(componentChildren.size >= 2) { "iBSS IM4P structure is incomplete" }
        require(componentChildren[0].tag == TAG_IA5_STRING) { "iBSS IM4P magic is not an IA5 string" }
        require(component.copyOfRange(componentChildren[0].valueOffset, componentChildren[0].endOffset)
            .contentEquals(IM4P_MAGIC)) { "iBSS component magic is not IM4P" }

        val ticketRoot = DerReader(ticket.apImg4Ticket).readRoot()
        require(ticketRoot.tag == TAG_SEQUENCE) { "ApImg4Ticket is not an ASN.1 sequence" }
        require(ticketRoot.endOffset == ticket.apImg4Ticket.size) { "ApImg4Ticket has trailing data" }

        logger("Image4 personalization: validated raw iBSS IM4P bytes=${component.size}")
        logger("Image4 personalization: validated ApImg4Ticket bytes=${ticket.apImg4Ticket.size}")

        val magic = derElement(TAG_IA5_STRING, IMG4_MAGIC)
        val ticketElement = derElement(TAG_CONTEXT_0_CONSTRUCTED, ticket.apImg4Ticket)
        val contentLength = magic.size.toLong() + component.size.toLong() + ticketElement.size.toLong()
        require(contentLength <= Int.MAX_VALUE) { "Personalized iBSS is too large" }
        val rootHeader = derHeader(TAG_SEQUENCE, contentLength.toInt())

        val destination = File(destinationDirectory, "identity-${ticket.identityIndex}-iBSS.personalized.img4")
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.outputStream().buffered().use { output ->
            output.write(rootHeader)
            output.write(magic)
            output.write(component)
            output.write(ticketElement)
        }

        validatePersonalizedIbss(temporary, ticket.apImg4Ticket)

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Could not replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Could not finalize personalized iBSS ${destination.absolutePath}")
        }

        val result = PersonalizedIbss(
            file = destination,
            rawBytes = component.size.toLong(),
            ticketBytes = ticket.apImg4Ticket.size,
            personalizedBytes = destination.length(),
            identityIndex = ticket.identityIndex,
            buildId = ticket.buildId
        )
        logger(result.summary())
        return result
    }

    fun validatePersonalizedIbss(file: File, expectedTicket: ByteArray? = null) {
        require(file.isFile) { "Personalized iBSS not found: ${file.absolutePath}" }
        val data = file.readBytes()
        val root = DerReader(data).readRoot()
        require(root.tag == TAG_SEQUENCE && root.endOffset == data.size) { "Personalized iBSS has invalid IMG4 root" }
        val children = DerReader(data, root.valueOffset, root.endOffset).readAll()
        require(children.size == 3) { "Personalized iBSS must contain exactly IMG4 magic, IM4P, and ticket" }

        val magic = children[0]
        require(magic.tag == TAG_IA5_STRING && data.copyOfRange(magic.valueOffset, magic.endOffset).contentEquals(IMG4_MAGIC)) {
            "Personalized iBSS is missing IMG4 magic"
        }

        val im4p = children[1]
        require(im4p.tag == TAG_SEQUENCE) { "Personalized iBSS second element is not IM4P" }
        val im4pChildren = DerReader(data, im4p.valueOffset, im4p.endOffset).readAll()
        require(im4pChildren.isNotEmpty() && im4pChildren[0].tag == TAG_IA5_STRING) { "Personalized iBSS IM4P magic is missing" }
        require(data.copyOfRange(im4pChildren[0].valueOffset, im4pChildren[0].endOffset).contentEquals(IM4P_MAGIC)) {
            "Personalized iBSS component is not IM4P"
        }

        val ticket = children[2]
        require(ticket.tag == TAG_CONTEXT_0_CONSTRUCTED) { "Personalized iBSS ticket is not context-specific [0]" }
        if (expectedTicket != null) {
            require(data.copyOfRange(ticket.valueOffset, ticket.endOffset).contentEquals(expectedTicket)) {
                "Personalized iBSS ticket bytes do not match the TSS response"
            }
        }
    }

    private fun derElement(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 8)
        out.write(derHeader(tag, value.size))
        out.write(value)
        return out.toByteArray()
    }

    private fun derHeader(tag: Int, length: Int): ByteArray {
        require(length >= 0)
        val out = ByteArrayOutputStream(6)
        out.write(tag)
        when {
            length < 0x80 -> out.write(length)
            length <= 0xFF -> {
                out.write(0x81)
                out.write(length)
            }
            length <= 0xFFFF -> {
                out.write(0x82)
                out.write((length ushr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            length <= 0xFFFFFF -> {
                out.write(0x83)
                out.write((length ushr 16) and 0xFF)
                out.write((length ushr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(0x84)
                out.write((length ushr 24) and 0xFF)
                out.write((length ushr 16) and 0xFF)
                out.write((length ushr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private data class DerElement(
        val tag: Int,
        val valueOffset: Int,
        val endOffset: Int
    )

    private class DerReader(
        private val data: ByteArray,
        private val start: Int = 0,
        private val limit: Int = data.size
    ) {
        private var offset = start

        fun readRoot(): DerElement {
            val element = readElement()
            require(offset == limit) { "ASN.1 root has trailing bytes" }
            return element
        }

        fun readAll(): List<DerElement> = buildList {
            while (offset < limit) add(readElement())
            require(offset == limit) { "ASN.1 child parsing ended at an invalid boundary" }
        }

        private fun readElement(): DerElement {
            require(offset < limit) { "Unexpected end of ASN.1 data" }
            val tag = data[offset++].toInt() and 0xFF
            require(offset < limit) { "Missing ASN.1 length" }
            val first = data[offset++].toInt() and 0xFF
            val length = if ((first and 0x80) == 0) {
                first
            } else {
                val count = first and 0x7F
                require(count in 1..4) { "Unsupported ASN.1 length width: $count" }
                require(offset + count <= limit) { "Truncated ASN.1 length" }
                var value = 0L
                repeat(count) {
                    value = (value shl 8) or (data[offset++].toLong() and 0xFF)
                }
                require(value <= Int.MAX_VALUE) { "ASN.1 element is too large" }
                value.toInt()
            }
            val valueOffset = offset
            val end = valueOffset.toLong() + length.toLong()
            require(end <= limit.toLong()) { "ASN.1 element exceeds containing boundary" }
            offset = end.toInt()
            return DerElement(tag, valueOffset, offset)
        }
    }

    private val IMG4_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'G'.code.toByte(), '4'.code.toByte())
    private val IM4P_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'P'.code.toByte())
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_CONTEXT_0_CONSTRUCTED = 0xA0
}
