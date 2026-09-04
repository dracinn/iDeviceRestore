package com.idevicerestore.android

import java.io.File
import java.io.RandomAccessFile

/** Minimal structural validation for raw IM4P restore components without personalization. */
object Image4StructureValidator {
    fun validateRawIm4p(file: File, componentName: String) {
        require(file.isFile) { "$componentName file is missing: ${file.absolutePath}" }
        require(file.length() >= 8L) { "$componentName is too small to be an IM4P container" }

        RandomAccessFile(file, "r").use { input ->
            val tag = input.readUnsignedByte()
            require(tag == 0x30) { "$componentName is not an ASN.1 sequence" }
            val contentLength = readDerLength(input, componentName)
            val headerLength = input.filePointer
            require(headerLength + contentLength == file.length()) {
                "$componentName ASN.1 root length does not match file size"
            }

            val magicTag = input.readUnsignedByte()
            require(magicTag == 0x16) { "$componentName IM4P magic is not an IA5 string" }
            val magicLength = readDerLength(input, componentName)
            require(magicLength == 4L) { "$componentName has invalid IM4P magic length" }
            val magic = ByteArray(4)
            input.readFully(magic)
            require(magic.contentEquals(byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'P'.code.toByte()))) {
                "$componentName component magic is not IM4P"
            }
        }
    }

    private fun readDerLength(input: RandomAccessFile, componentName: String): Long {
        val first = input.readUnsignedByte()
        if (first and 0x80 == 0) return first.toLong()
        val count = first and 0x7f
        require(count in 1..8) { "$componentName has unsupported ASN.1 length width $count" }
        var value = 0L
        repeat(count) {
            val b = input.readUnsignedByte()
            require(value <= (Long.MAX_VALUE ushr 8)) { "$componentName ASN.1 length overflow" }
            value = (value shl 8) or b.toLong()
        }
        return value
    }
}
