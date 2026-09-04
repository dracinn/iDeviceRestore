package com.idevicerestore.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Local-only Image4 stitching for restore components whose upstream rules are fully known.
 *
 * Mirrors current idevicerestore img4_stitch_component behavior for components that do not have
 * a component-TBM entry in the TSS response and are not nonce-slot components. No USB I/O occurs.
 */
object RestoreComponentImage4Personalizer {
    data class Result(
        val component: String,
        val file: File,
        val rawBytes: Long,
        val personalizedBytes: Long,
        val tagRewritten: Boolean,
        val tbmRequired: Boolean
    )

    private val restoreTags = mapOf(
        "RestoreDeviceTree" to "rdtr",
        "RestoreSEP" to "rsep",
        "RestoreKernelCache" to "rkrn"
    )

    fun personalize(
        raw: IpswComponentExtractor.ExtractedComponent,
        ticket: TssTicketStore.Ticket,
        destinationDirectory: File,
        logger: (String) -> Unit = {}
    ): Result {
        require(raw.identityIndex == ticket.identityIndex) {
            "${raw.name} identity ${raw.identityIndex} does not match TSS identity ${ticket.identityIndex}"
        }
        require(raw.name in SUPPORTED_COMPONENTS) { "Unsupported restore component: ${raw.name}" }
        require(raw.file.isFile) { "Raw ${raw.name} file is missing" }
        require(ticket.apImg4Ticket.isNotEmpty()) { "ApImg4Ticket is empty" }

        val tbm = ticket.componentTbm[raw.name]
        if (tbm != null) {
            error("${raw.name} requires IM4R/TBM additional data; guarded local personalizer refuses simplified stitching")
        }

        val component = raw.file.readBytes()
        val root = DerReader(component).readRoot()
        require(root.tag == TAG_SEQUENCE && root.endOffset == component.size) { "${raw.name} is not a complete IM4P ASN.1 sequence" }
        val children = DerReader(component, root.valueOffset, root.endOffset).readAll()
        require(children.size >= 2) { "${raw.name} IM4P structure is incomplete" }
        require(children[0].tag == TAG_IA5_STRING && bytes(component, children[0]).contentEquals(IM4P_MAGIC)) {
            "${raw.name} component magic is not IM4P"
        }
        require(children[1].tag == TAG_IA5_STRING) { "${raw.name} IM4P tag is missing" }
        require(children[1].endOffset - children[1].valueOffset == 4) { "${raw.name} IM4P tag is not four bytes" }

        val mutableComponent = component.copyOf()
        val replacement = restoreTags[raw.name]
        val rewritten = replacement != null
        if (replacement != null) {
            replacement.toByteArray(Charsets.US_ASCII).copyInto(mutableComponent, children[1].valueOffset)
            logger("Restore Image4: ${raw.name} IM4P tag rewritten to $replacement per upstream rules")
        } else {
            logger("Restore Image4: ${raw.name} IM4P tag preserved per upstream rules")
        }

        validateTicket(ticket.apImg4Ticket)
        check(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) {
            "Could not create restore personalization directory"
        }
        val destination = File(destinationDirectory, "identity-${ticket.identityIndex}-${raw.name}.personalized.img4")
        val temporary = File(destination.parentFile, destination.name + ".part")

        val magic = derElement(TAG_IA5_STRING, IMG4_MAGIC)
        val ticketElement = derElement(TAG_CONTEXT_0_CONSTRUCTED, ticket.apImg4Ticket)
        val contentLength = magic.size.toLong() + mutableComponent.size.toLong() + ticketElement.size.toLong()
        require(contentLength <= Int.MAX_VALUE) { "Personalized ${raw.name} is too large" }
        val rootHeader = derHeader(TAG_SEQUENCE, contentLength.toInt())

        temporary.outputStream().buffered().use { output ->
            output.write(rootHeader)
            output.write(magic)
            output.write(mutableComponent)
            output.write(ticketElement)
        }
        validate(temporary, raw.name, ticket.apImg4Ticket, replacement)

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Could not replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Could not finalize ${destination.absolutePath}")
        }
        logger("Restore Image4: ${raw.name} personalized=${destination.length()} bytes TBM=absent structuralValidation=PASSED")
        return Result(raw.name, destination, raw.bytes, destination.length(), rewritten, false)
    }

    fun validate(file: File, componentName: String, expectedTicket: ByteArray, expectedTag: String? = restoreTags[componentName]) {
        val data = file.readBytes()
        val root = DerReader(data).readRoot()
        require(root.tag == TAG_SEQUENCE && root.endOffset == data.size) { "$componentName personalized IMG4 root is invalid" }
        val children = DerReader(data, root.valueOffset, root.endOffset).readAll()
        require(children.size == 3) { "$componentName personalized IMG4 must contain magic, IM4P, and ticket only" }
        require(children[0].tag == TAG_IA5_STRING && bytes(data, children[0]).contentEquals(IMG4_MAGIC)) {
            "$componentName personalized IMG4 magic is invalid"
        }
        val im4p = children[1]
        require(im4p.tag == TAG_SEQUENCE) { "$componentName second IMG4 element is not IM4P" }
        val im4pChildren = DerReader(data, im4p.valueOffset, im4p.endOffset).readAll()
        require(im4pChildren.size >= 2) { "$componentName personalized IM4P structure is incomplete" }
        require(im4pChildren[0].tag == TAG_IA5_STRING && bytes(data, im4pChildren[0]).contentEquals(IM4P_MAGIC)) {
            "$componentName personalized payload is not IM4P"
        }
        if (expectedTag != null) {
            require(String(bytes(data, im4pChildren[1]), Charsets.US_ASCII) == expectedTag) {
                "$componentName personalized IM4P tag does not match $expectedTag"
            }
        }
        val ticket = children[2]
        require(ticket.tag == TAG_CONTEXT_0_CONSTRUCTED) { "$componentName ticket wrapper is not context [0]" }
        require(bytes(data, ticket).contentEquals(expectedTicket)) { "$componentName ticket bytes do not match TSS response" }
    }

    private fun validateTicket(ticket: ByteArray) {
        val root = DerReader(ticket).readRoot()
        require(root.tag == TAG_SEQUENCE && root.endOffset == ticket.size) { "ApImg4Ticket is not a complete ASN.1 sequence" }
    }

    private fun bytes(data: ByteArray, element: DerElement): ByteArray =
        data.copyOfRange(element.valueOffset, element.endOffset)

    private fun derElement(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 8)
        out.write(derHeader(tag, value.size))
        out.write(value)
        return out.toByteArray()
    }

    private fun derHeader(tag: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(6)
        out.write(tag)
        when {
            length < 0x80 -> out.write(length)
            length <= 0xFF -> { out.write(0x81); out.write(length) }
            length <= 0xFFFF -> { out.write(0x82); out.write(length ushr 8); out.write(length) }
            length <= 0xFFFFFF -> { out.write(0x83); out.write(length ushr 16); out.write(length ushr 8); out.write(length) }
            else -> { out.write(0x84); out.write(length ushr 24); out.write(length ushr 16); out.write(length ushr 8); out.write(length) }
        }
        return out.toByteArray()
    }

    private data class DerElement(val tag: Int, val valueOffset: Int, val endOffset: Int)

    private class DerReader(
        private val data: ByteArray,
        start: Int = 0,
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
            require(offset == limit) { "ASN.1 children end at invalid boundary" }
        }
        private fun readElement(): DerElement {
            require(offset < limit) { "Unexpected end of ASN.1 data" }
            val tag = data[offset++].toInt() and 0xFF
            require(offset < limit) { "Missing ASN.1 length" }
            val first = data[offset++].toInt() and 0xFF
            val length = if (first and 0x80 == 0) first else {
                val count = first and 0x7F
                require(count in 1..4 && offset + count <= limit) { "Invalid ASN.1 length" }
                var value = 0L
                repeat(count) { value = (value shl 8) or (data[offset++].toLong() and 0xFF) }
                require(value <= Int.MAX_VALUE) { "ASN.1 element too large" }
                value.toInt()
            }
            val valueOffset = offset
            val end = valueOffset.toLong() + length
            require(end <= limit) { "ASN.1 element exceeds boundary" }
            offset = end.toInt()
            return DerElement(tag, valueOffset, offset)
        }
    }

    private val IMG4_MAGIC = "IMG4".toByteArray(Charsets.US_ASCII)
    private val IM4P_MAGIC = "IM4P".toByteArray(Charsets.US_ASCII)
    private val SUPPORTED_COMPONENTS = setOf("iBEC", "RestoreDeviceTree", "RestoreSEP", "RestoreKernelCache")
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_CONTEXT_0_CONSTRUCTED = 0xA0
}
