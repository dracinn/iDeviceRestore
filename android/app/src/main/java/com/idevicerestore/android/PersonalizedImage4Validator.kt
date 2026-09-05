package com.idevicerestore.android

import java.io.File
import java.io.RandomAccessFile

/** Streaming validation for personalized IMG4 files immediately before a state-changing upload. */
object PersonalizedImage4Validator {
    fun validate(file: File, expectedTicket: ByteArray, label: String) {
        require(file.isFile && file.length() > 0L) { "$label personalized IMG4 is missing or empty" }
        require(expectedTicket.isNotEmpty()) { "$label expected ApImg4Ticket is empty" }

        RandomAccessFile(file, "r").use { input ->
            val root = readElement(input, label)
            require(root.tag == TAG_SEQUENCE && root.endOffset == file.length()) { "$label IMG4 root is invalid" }

            input.seek(root.valueOffset)
            val magic = readElement(input, label)
            require(magic.tag == TAG_IA5_STRING && magic.length == 4L) { "$label IMG4 magic is missing" }
            val magicBytes = ByteArray(4)
            input.readFully(magicBytes)
            require(magicBytes.contentEquals(IMG4_MAGIC)) { "$label IMG4 magic does not match" }

            input.seek(magic.endOffset)
            val im4p = readElement(input, label)
            require(im4p.tag == TAG_SEQUENCE) { "$label IMG4 second element is not IM4P" }
            input.seek(im4p.valueOffset)
            val im4pMagic = readElement(input, label)
            require(im4pMagic.tag == TAG_IA5_STRING && im4pMagic.length == 4L) { "$label IM4P magic is missing" }
            val im4pMagicBytes = ByteArray(4)
            input.readFully(im4pMagicBytes)
            require(im4pMagicBytes.contentEquals(IM4P_MAGIC)) { "$label embedded component is not IM4P" }

            input.seek(im4p.endOffset)
            val ticket = readElement(input, label)
            require(ticket.tag == TAG_CONTEXT_0_CONSTRUCTED) { "$label IMG4 ticket element is missing" }
            require(ticket.length == expectedTicket.size.toLong()) { "$label IMG4 ticket length does not match TSS response" }
            val ticketBytes = ByteArray(expectedTicket.size)
            input.readFully(ticketBytes)
            require(ticketBytes.contentEquals(expectedTicket)) { "$label IMG4 ticket bytes do not match TSS response" }
            require(ticket.endOffset == root.endOffset) { "$label IMG4 contains unexpected trailing elements" }
        }
    }

    private data class Element(val tag: Int, val valueOffset: Long, val length: Long, val endOffset: Long)

    private fun readElement(input: RandomAccessFile, label: String): Element {
        require(input.filePointer < input.length()) { "$label ASN.1 element is truncated" }
        val tag = input.readUnsignedByte()
        val first = input.readUnsignedByte()
        val length = if (first and 0x80 == 0) {
            first.toLong()
        } else {
            val count = first and 0x7f
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
        return Element(tag, valueOffset, length, endOffset)
    }

    private val IMG4_MAGIC = "IMG4".toByteArray(Charsets.US_ASCII)
    private val IM4P_MAGIC = "IM4P".toByteArray(Charsets.US_ASCII)
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_CONTEXT_0_CONSTRUCTED = 0xA0
}
