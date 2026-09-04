package com.idevicerestore.android

import java.util.Base64

sealed class PlistNode {
    data class Dict(val values: LinkedHashMap<String, PlistNode> = linkedMapOf()) : PlistNode() {
        operator fun get(key: String): PlistNode? = values[key]
        fun string(key: String): String? = (values[key] as? StringValue)?.value
        fun integer(key: String): Long? = (values[key] as? IntegerValue)?.value
        fun unsignedInteger(key: String): ULong? = when (val value = values[key]) {
            is UnsignedIntegerValue -> value.value
            is IntegerValue -> value.value.takeIf { it >= 0 }?.toULong()
            else -> null
        }
        fun bool(key: String): Boolean? = (values[key] as? BoolValue)?.value
        fun data(key: String): ByteArray? = (values[key] as? DataValue)?.value
        fun dict(key: String): Dict? = values[key] as? Dict
        fun array(key: String): ArrayValue? = values[key] as? ArrayValue
        fun copy(): Dict = Dict(LinkedHashMap(values.mapValues { (_, value) -> value.deepCopy() }))
    }

    data class ArrayValue(val values: MutableList<PlistNode> = mutableListOf()) : PlistNode()
    data class StringValue(val value: String) : PlistNode()
    data class IntegerValue(val value: Long) : PlistNode()
    data class UnsignedIntegerValue(val value: ULong) : PlistNode()
    data class BoolValue(val value: Boolean) : PlistNode()
    data class DataValue(val value: ByteArray) : PlistNode()

    fun deepCopy(): PlistNode = when (this) {
        is Dict -> copy()
        is ArrayValue -> ArrayValue(values.map { it.deepCopy() }.toMutableList())
        is StringValue -> copy()
        is IntegerValue -> copy()
        is UnsignedIntegerValue -> copy()
        is BoolValue -> copy()
        is DataValue -> DataValue(value.copyOf())
    }

    companion object {
        fun decodeData(text: String): ByteArray =
            if (text.isBlank()) ByteArray(0)
            else Base64.getMimeDecoder().decode(text.filterNot(Char::isWhitespace))
    }
}
