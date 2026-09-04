package com.idevicerestore.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Local-only Image4 personalization for restore components whose upstream rules are currently
 * understood. This helper performs no USB I/O and refuses components that require IM4R/TBM data.
 */
object RestoreImage4Personalizer {
    data class Result(
        val component: String,
        val file: File?,
        val status: String,
        val deferred: Boolean
    )

    fun personalizeIfSafe(
        raw: IpswComponentExtractor.ExtractedComponent,
        ticket: TssTicketStore.Ticket,
        destinationDirectory: File,
        logger: (String) -> Unit = {}
    ): Result {
        require(raw.identityIndex == ticket.identityIndex) {
            "${raw.name} identity ${raw.identityIndex} does not match TSS identity ${ticket.identityIndex}"
        }
        require(raw.file.isFile) { "Extracted ${raw.name} not found: ${raw.file.absolutePath}" }
        require(ticket.apImg4Ticket.isNotEmpty()) { "ApImg4Ticket is empty" }

        val componentTbm = ticket.componentTbm[raw.name]
        if (componentTbm != null) {
            val metadataBytes = (componentTbm.ucon?.size ?: 0) + (componentTbm.ucer?.size ?: 0)
            logger(
                "Restore Image4: ${raw.name} requires component-specific TBM/IM4R; " +
                    "deferred ($metadataBytes bytes metadata)"
            )
            return Result(raw.name, null, "deferred-tbm-im4r", true)
        }

        val targetTag = when (raw.name) {
            "iBEC" -> null
            "RestoreDeviceTree" -> "rdtr"
            "RestoreSEP" -> "rsep"
            "RestoreKernelCache" -> "rkrn"
            else -> return Result(raw.name, null, "unsupported-component", true)
        }

        check(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) {
            "Could not create restore personalization workspace: ${destinationDirectory.absolutePath}"
        }

        Image4StructureValidator.validateRawIm4p(raw.file, raw.name)
        val source = raw.file.readBytes()
        val im4p = rewriteIm4pTagIfNeeded(source, targetTag)
        val img4 = stitchSimpleImg4(im4p, ticket.apImg4Ticket)
        validateSimpleImg4(img4, ticket.apImg4Ticket)

        val destination = File(destinationDirectory, "identity-${ticket.identityIndex}-${raw.name}.personalized.img4")
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.writeBytes(img4)
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Could not replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Could not finalize personalized ${raw.name}")
        }

        logger(
            "Restore Image4: ${raw.name} personalized raw=${source.size} bytes ticket=${ticket.apImg4Ticket.size} bytes " +
                "output=${destination.length()} bytes tag=${targetTag ?: "unchanged"}"
        )
        return Result(raw.name, destination, "personalized", false)
    }

    private fun rewriteIm4pTagIfNeeded(data: ByteArray, replacement: String?): ByteArray {
        if (replacement == null) return data.copyOf()
        require(replacement.length == 4) { "IM4P replacement tag must be four characters" }

        val root = DerReader(data).readRoot()
        require(root.tag == TAG_SEQUENCE) { "IM4P root is not a sequence" }
        val children = DerReader(data, root.valueOffset, root.endOffset).readAll()
        require(children.size >= 2) { "IM4P structure is incomplete" }
        require(children[0].tag == TAG_IA5_STRING) { "IM4P magic is not an IA5 string" }
        require(data.copyOfRange(children[0].valueOffset, children[0].endOffset).contentEquals(IM4P_MAGIC)) {
            "Component magic is not IM4P"
        }
        val componentTag = children[1]
        require(componentTag.tag == TAG_IA5_STRING) { "IM4P component tag is not an IA5 string" }
        require(componentTag.endOffset - componentTag.valueOffset == 4) { "IM4P component tag is not four bytes" }
        return data.copyOf().also { out ->
            replacement.toByteArray(Charsets.US_ASCII).copyInto(out, componentTag.valueOffset)
        }
    }

    private fun stitchSimpleImg4(im4p: ByteArray, ticket: ByteArray): ByteArray {
        val magic = derElement(TAG_IA5_STRING, IMG4_MAGIC)
        val ticketElement = derElement(TAG_CONTEXT_0_CONSTRUCTED, ticket)
        val body = ByteArrayOutputStream(magic.size + im4p.size + ticketElement.size).apply {
            write(magic)
            write(im4p)
            write(ticketElement)
        }.toByteArray()
        return derElement(TAG_SEQUENCE, body)
    }

    private fun validateSimpleImg4(data: ByteArray, expectedTicket: ByteArray) {
        val root = DerReader(data).readRoot()
        require(root.tag == TAG_SEQUENCE && root.endOffset == data.size) { "IMG4 root is invalid" }
        val children = DerReader(data, root.valueOffset, root.endOffset).readAll()
        require(children.size == 3) { "IMG4 must contain magic, IM4P, and ticket" }
        require(children[0].tag == TAG_IA5_STRING) { "IMG4 magic is missing" }
        require(data.copyOfRange(children[0].valueOffset, children[0].endOffset).contentEquals(IMG4_MAGIC)) {
            "IMG4 magic does not match"
        }
        require(children[1].tag == TAG_SEQUENCE) { "IMG4 second element is not IM4P" }
        require(children[2].tag == TAG_CONTEXT_0_CONSTRUCTED) { "IMG4 ticket element is missing" }
        require(data.copyOfRange(children[2].valueOffset, children[2].endOffset).contentEquals(expectedTicket)) {
            "IMG4 ticket bytes do not match TSS response"
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
            length <= 0xFF -> { out.write(0x81); out.write(length) }
            length <= 0xFFFF -> { out.write(0x82); out.write((length ushr 8) and 0xFF); out.write(length and 0xFF) }
            length <= 0xFFFFFF -> {
                out.write(0x83); out.write((length ushr 16) and 0xFF); out.write((length ushr 8) and 0xFF); out.write(length and 0xFF)
            }
            else -> {
                out.write(0x84); out.write((length ushr 24) and 0xFF); out.write((length ushr 16) and 0xFF)
                out.write((length ushr 8) and 0xFF); out.write(length and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private data class DerElement(val tag: Int, val valueOffset: Int, val endOffset: Int)

    private class DerReader(
        private val data: ByteArray,
        private val start: Int = 0,
        private val limit: Int = data.size
    ) {
        private var offset = start

        fun readRoot(): DerElement {
            val root = readElement()
            require(offset == limit) { "ASN.1 root has trailing bytes" }
            return root
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
            val length = if ((first and 0x80) == 0) first else {
                val count = first and 0x7F
                require(count in 1..4) { "Unsupported ASN.1 length width: $count" }
                require(offset + count <= limit) { "Truncated ASN.1 length" }
                var value = 0L
                repeat(count) { value = (value shl 8) or (data[offset++].toLong() and 0xFF) }
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

    private val IMG4_MAGIC = "IMG4".toByteArray(Charsets.US_ASCII)
    private val IM4P_MAGIC = "IM4P".toByteArray(Charsets.US_ASCII)
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_CONTEXT_0_CONSTRUCTED = 0xA0
}
