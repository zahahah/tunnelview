package com.zahah.tunnelview.appbuilder

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ManifestPatcher {
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_START_TAG = 0x0102
    private const val TYPE_STRING = 0x03
    private const val MANIFEST_HEADER_SIZE = 8

    fun patch(manifestBytes: ByteArray, basePackage: String, newPackage: String, newLabel: String): ByteArray {
        val (strings, flags, chunkSize) = parseStringPool(manifestBytes)
        val updatedStrings = strings.map { current ->
            when {
                current == basePackage -> newPackage
                current.startsWith("$basePackage.") && shouldReplaceQualifiedString(current, basePackage) ->
                    newPackage + current.removePrefix(basePackage)
                else -> current
            }
        }.toMutableList()

        var labelIndex = updatedStrings.indexOf(newLabel)
        if (labelIndex == -1) {
            updatedStrings.add(newLabel)
            labelIndex = updatedStrings.lastIndex
        }
        var packageIndex = updatedStrings.indexOf(newPackage)
        if (packageIndex == -1) {
            updatedStrings.add(newPackage)
            packageIndex = updatedStrings.lastIndex
        }

        val newStringChunk = buildStringPoolChunk(updatedStrings, flags)
        val patched = rebuildManifest(manifestBytes, chunkSize, newStringChunk)
        applyAttributePatches(patched, updatedStrings, packageIndex, labelIndex)
        return patched
    }

    private fun parseStringPool(manifest: ByteArray): Triple<MutableList<String>, Int, Int> {
        val buffer = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        val offset = MANIFEST_HEADER_SIZE
        val type = buffer.getShort(offset).toInt() and 0xFFFF
        require(type == CHUNK_STRING_POOL) { "Unexpected first chunk type: $type" }
        val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
        val chunkSize = buffer.getInt(offset + 4)
        val stringCount = buffer.getInt(offset + 8)
        val styleCount = buffer.getInt(offset + 12)
        val flags = buffer.getInt(offset + 16)
        val stringsStart = buffer.getInt(offset + 20)
        require(styleCount == 0) { "Style string pools are not supported" }
        val utf8 = flags and 0x00000100 != 0
        require(!utf8) { "UTF-8 string pools are not supported" }

        val offsetsStart = offset + headerSize
        val stringsBase = offset + stringsStart
        val result = MutableList(stringCount) { index ->
            val relative = buffer.getInt(offsetsStart + index * 4)
            readUtf16(manifest, stringsBase + relative)
        }
        return Triple(result, flags, chunkSize)
    }

    private fun buildStringPoolChunk(strings: List<String>, flags: Int): ByteArray {
        val headerSize = 28
        val stringCount = strings.size
        val styleCount = 0
        val offsets = IntArray(stringCount)
        val dataStream = ByteArrayOutputStream()

        strings.forEachIndexed { index, value ->
            offsets[index] = dataStream.size()
            writeUtf16(dataStream, value)
        }

        while (dataStream.size() % 4 != 0) {
            dataStream.write(0)
        }

        val stringsStart = headerSize + stringCount * 4 + styleCount * 4
        val chunkSize = stringsStart + dataStream.size()
        val buffer = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(CHUNK_STRING_POOL.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putInt(chunkSize)
        buffer.putInt(stringCount)
        buffer.putInt(styleCount)
        buffer.putInt(flags and 0x00000100.inv())
        buffer.putInt(stringsStart)
        buffer.putInt(0)
        offsets.forEach { buffer.putInt(it) }
        if (buffer.position() < stringsStart) {
            buffer.position(stringsStart)
        }
        buffer.put(dataStream.toByteArray())
        return buffer.array()
    }

    private fun rebuildManifest(original: ByteArray, oldChunkSize: Int, newChunk: ByteArray): ByteArray {
        val remaining = original.size - (MANIFEST_HEADER_SIZE + oldChunkSize)
        val newSize = MANIFEST_HEADER_SIZE + newChunk.size + remaining
        val output = ByteArray(newSize)
        System.arraycopy(original, 0, output, 0, MANIFEST_HEADER_SIZE)
        ByteBuffer.wrap(output, 0, MANIFEST_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4, newSize)
        System.arraycopy(newChunk, 0, output, MANIFEST_HEADER_SIZE, newChunk.size)
        System.arraycopy(
            original,
            MANIFEST_HEADER_SIZE + oldChunkSize,
            output,
            MANIFEST_HEADER_SIZE + newChunk.size,
            remaining
        )
        return output
    }

    private fun applyAttributePatches(
        manifest: ByteArray,
        strings: List<String>,
        packageIndex: Int,
        labelIndex: Int
    ) {
        val stringIndex = strings.withIndex().associate { it.value to it.index }
        val manifestNameIdx = stringIndex["manifest"] ?: error("'manifest' tag not found in string pool")
        val applicationNameIdx = stringIndex["application"] ?: error("'application' tag not found in string pool")
        val packageAttrIdx = stringIndex["package"] ?: error("'package' attr not found in string pool")
        val labelAttrIdx = stringIndex["label"] ?: error("'label' attr not found in string pool")

        val buffer = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        var offset = MANIFEST_HEADER_SIZE
        while (offset < manifest.size) {
            val type = buffer.getShort(offset).toInt() and 0xFFFF
            val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buffer.getInt(offset + 4)
            if (type == CHUNK_START_TAG) {
                val nameIdx = buffer.getInt(offset + 20)
                val attrStart = buffer.getShort(offset + 24).toInt() and 0xFFFF
                val attrSize = buffer.getShort(offset + 26).toInt() and 0xFFFF
                val attrCount = buffer.getShort(offset + 28).toInt() and 0xFFFF
                var attrOffset = offset + headerSize + attrStart
                repeat(attrCount) {
                    val attrNameIdx = buffer.getInt(attrOffset + 4)
                    if (nameIdx == manifestNameIdx && attrNameIdx == packageAttrIdx) {
                        buffer.putInt(attrOffset + 8, packageIndex)
                        buffer.putShort(attrOffset + 12, 8)
                        buffer.put(attrOffset + 14, 0.toByte())
                        buffer.put(attrOffset + 15, TYPE_STRING.toByte())
                        buffer.putInt(attrOffset + 16, packageIndex)
                    } else if (nameIdx == applicationNameIdx && attrNameIdx == labelAttrIdx) {
                        buffer.putInt(attrOffset + 8, labelIndex)
                        buffer.putShort(attrOffset + 12, 8)
                        buffer.put(attrOffset + 14, 0.toByte())
                        buffer.put(attrOffset + 15, TYPE_STRING.toByte())
                        buffer.putInt(attrOffset + 16, labelIndex)
                    }
                    attrOffset += attrSize
                }
            }
            offset += chunkSize
        }
    }

    private fun readUtf16(data: ByteArray, offset: Int): String {
        var pos = offset
        var length = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos].toInt() and 0xFF)
        pos += 2
        if (length and 0x8000 != 0) {
            val high = length and 0x7FFF
            val low = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos].toInt() and 0xFF)
            pos += 2
            length = (high shl 16) or low
        }
        val byteLength = length * 2
        return String(data, pos, byteLength, Charsets.UTF_16LE)
    }

    private fun writeUtf16(stream: ByteArrayOutputStream, value: String) {
        val length = value.length
        require(length < 0x8000) { "UTF-16 string too long" }
        stream.write(length and 0xFF)
        stream.write((length ushr 8) and 0xFF)
        stream.write(value.toByteArray(Charsets.UTF_16LE))
        stream.write(0)
        stream.write(0)
    }

    private fun shouldReplaceQualifiedString(current: String, basePackage: String): Boolean {
        val suffix = current.removePrefix("$basePackage.")
        if (suffix.isEmpty()) return false
        if (suffix.contains('_') || suffix.contains('-')) return true
        if (suffix.none { it.isLowerCase() }) return true
        if (suffix.all { it.isLowerCase() || it.isDigit() || it == '.' }) return true
        return false
    }
}
