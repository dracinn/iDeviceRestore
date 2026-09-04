package com.idevicerestore.android

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Local-only Image4 personalization for restore components whose upstream rules are currently
 * understood. This helper performs no USB I/O and refuses components that require IM4R/TBM data.
 * Large IM4P payloads are streamed to disk so personalization does not depend on Android heap size.
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
        val componentTagOffset = if (targetTag == null) null else findComponentTagOffset(raw.file, raw.name)

        val destination = File(destinationDirectory, "identity-${ticket.identityIndex}-${raw.name}.personalized.img4")
        val temporary = File(destination.parentFile, destination.name + ".part")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not remove stale temporary file ${temporary.absolutePath}")
        }

        try {
            stitchSimpleImg4Streaming(
                rawFile = raw.file,
                replacementTag = targetTag,
                replacementOffset = componentTagOffset,
                ticket = ticket.apImg4Ticket,
                destination = temporary
            )
            validateSimpleImg4File(temporary, ticket.apImg4Ticket)

            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace ${destination.absolutePath}")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not finalize personalized ${raw.name}")
            }
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }

        logger(
            "Restore Image4: ${raw.name} personalized raw=${raw.file.length()} bytes " +
                "ticket=${ticket.apImg4Ticket.size} bytes output=${destination.length()} bytes " +
                "tag=${targetTag ?: "unchanged"} io=streaming"
        )
        return Result(raw.name, destination, "personalized", false)
    }

    private fun findComponentTagOffset(file: File, componentName: String): Long {
        RandomAccessFile(file, "r").use { input ->
            val root = readElementHeader(input, componentName)
            require(root.tag == TAG_SEQUENCE) { "$componentName IM4P root is not a sequence" }
            require(root.endOffset == file.length()) { "$componentName IM4P root length does not match file size" }

            input.seek(root.valueOffset)
            val magic = readElementHeader(input, componentName)
            require(magic.tag == TAG_IA5_STRING && magic.length == 4L) { "$componentName IM4P magic is invalid" }
            val magicBytes = ByteArray(4)
            input.readFully(magicBytes)
            require(magicBytes.contentEquals(IM4P_MAGIC)) { "$componentName component magic is not IM4P" }

            input.seek(magic.endOffset)
            val tag = readElementHeader(input, componentName)
            require(tag.tag == TAG_IA5_STRING && tag.length == 4L) { "$componentName IM4P component tag is invalid" }
            return tag.valueOffset
        }
    }

    private fun stitchSimpleImg4Streaming(
        rawFile: File,
        replacementTag: String?,
        replacementOffset: Long?,
        ticket: ByteArray,
        destination: File
    ) {
        if (replacementTag != null) {
            require(replacementTag.length == 4) { "IM4P replacement tag must be four characters" }
            require(replacementOffset != null && replacementOffset >= 0L) { "Missing IM4P replacement offset" }
            require(replacementOffset + 4L <= rawFile.length()) { "IM4P replacement offset exceeds source file" }
        }

        val magicElement = derElement(TAG_IA5_STRING, IMG4_MAGIC)
        val ticketHeader = derHeader(TAG_CONTEXT_0_CONSTRUCTED, ticket.size.toLong())
        val bodyLength = magicElement.size.toLong() + rawFile.length() + ticketHeader.size.toLong() + ticket.size.toLong()
        val rootHeader = derHeader(TAG_SEQUENCE, bodyLength)

        BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE).use { output ->
            output.write(rootHeader)
            output.write(magicElement)

            BufferedInputStream(FileInputStream(rawFile), BUFFER_SIZE).use { input ->
                if (replacementTag == null) {
                    input.copyTo(output, BUFFER_SIZE)
                } else {
                    val offset = replacementOffset!!
                    copyExactly(input, output, offset)
                    output.write(replacementTag.toByteArray(Charsets.US_ASCII))
                    discardExactly(input, 4L)
                    input.copyTo(output, BUFFER_SIZE)
                }
            }

            output.write(ticketHeader)
            output.write(ticket)
            output.flush()
        }
    }

    private fun validateSimpleImg4File(file: File, expectedTicket: ByteArray) {
        require(file.isFile && file.length() > 0L) { "Personalized IMG4 file is missing or empty" }
        RandomAccessFile(file, "r").use { input ->
            val root = readElementHeader(input, "personalized IMG4")
            require(root.tag == TAG_SEQUENCE && root.endOffset == file.length()) { "IMG4 root is invalid" }

            input.seek(root.valueOffset)
            val magic = readElementHeader(input, "personalized IMG4")
            require(magic.tag == TAG_IA5_STRING && magic.length == 4L) { "IMG4 magic is missing" }
            val magicBytes = ByteArray(4)
            input.readFully(magicBytes)
            require(magicBytes.contentEquals(IMG4_MAGIC)) { "IMG4 magic does not match" }

            input.seek(magic.endOffset)
            val im4p = readElementHeader(input, "personalized IMG4")
            require(im4p.tag == TAG_SEQUENCE) { "IMG4 second element is not IM4P" }

            input.seek(im4p.endOffset)
            val ticket = readElementHeader(input, "personalized IMG4")
            require(ticket.tag == TAG_CONTEXT_0_CONSTRUCTED) { "IMG4 ticket element is missing" }
            require(ticket.length == expectedTicket.size.toLong()) { "IMG4 ticket length does not match TSS response" }
            val ticketBytes = ByteArray(expectedTicket.size)
            input.readFully(ticketBytes)
            require(ticketBytes.contentEquals(expectedTicket)) { "IMG4 ticket bytes do not match TSS response" }
            require(ticket.endOffset == root.endOffset) { "IMG4 contains unexpected trailing elements" }
        }
    }

    private fun copyExactly(input: BufferedInputStream, output: BufferedOutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0L) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw IOException("Unexpected EOF while streaming IM4P payload")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun discardExactly(input: BufferedInputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(16)
        while (remaining > 0L) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw IOException("Unexpected EOF while replacing IM4P component tag")
            remaining -= read.toLong()
        }
    }

    private fun derElement(tag: Int, value: ByteArray): ByteArray {
        val header = derHeader(tag, value.size.toLong())
        return ByteArrayOutputStream(header.size + value.size).apply {
            write(header)
            write(value)
        }.toByteArray()
    }

    private fun derHeader(tag: Int, length: Long): ByteArray {
        require(length >= 0L) { "Negative DER length" }
        val out = ByteArrayOutputStream(10)
        out.write(tag)
        if (length < 0x80L) {
            out.write(length.toInt())
        } else {
            var value = length
            var width = 0
            val bytes = ByteArray(8)
            while (value > 0L) {
                bytes[7 - width] = (value and 0xFFL).toByte()
                value = value ushr 8
                width++
            }
            out.write(0x80 or width)
            out.write(bytes, 8 - width, width)
        }
        return out.toByteArray()
    }

    private data class FileDerElement(
        val tag: Int,
        val valueOffset: Long,
        val length: Long,
        val endOffset: Long
    )

    private fun readElementHeader(input: RandomAccessFile, label: String): FileDerElement {
        val tagPosition = input.filePointer
        require(tagPosition < input.length()) { "$label ASN.1 element is truncated" }
        val tag = input.readUnsignedByte()
        val first = input.readUnsignedByte()
        val length = if ((first and 0x80) == 0) {
            first.toLong()
        } else {
            val count = first and 0x7F
            require(count in 1..8) { "$label has unsupported ASN.1 length width $count" }
            var value = 0L
            repeat(count) {
                val b = input.readUnsignedByte()
                require(value <= (Long.MAX_VALUE ushr 8)) { "$label ASN.1 length overflow" }
                value = (value shl 8) or b.toLong()
            }
            value
        }
        val valueOffset = input.filePointer
        val endOffset = valueOffset + length
        require(endOffset >= valueOffset && endOffset <= input.length()) { "$label ASN.1 element exceeds file boundary" }
        return FileDerElement(tag, valueOffset, length, endOffset)
    }

    private val IMG4_MAGIC = "IMG4".toByteArray(Charsets.US_ASCII)
    private val IM4P_MAGIC = "IM4P".toByteArray(Charsets.US_ASCII)
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_CONTEXT_0_CONSTRUCTED = 0xA0
    private const val BUFFER_SIZE = 64 * 1024
}
