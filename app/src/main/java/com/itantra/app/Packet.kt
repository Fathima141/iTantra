package com.itantra.app

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

data class Packet(
    val language: String,
    val priority: Int,
    val sequence: Long,
    val timestamp: Long,
    val message: String,
    val latitude: Double? = null,
    val longitude: Double? = null
) {

    fun encode(): ByteArray {

        val bodyText =
            "IT1|$language|$priority|$sequence|$timestamp|" +
                    "${latitude ?: ""}|${longitude ?: ""}|$message"

        val body = bodyText.toByteArray(StandardCharsets.UTF_8)

        val crc32 = CRC32()
        crc32.update(body)

        return ByteBuffer
            .allocate(4 + body.size + 8)
            .putInt(body.size)
            .put(body)
            .putLong(crc32.value)
            .array()
    }

    companion object {

        fun decode(bytes: ByteArray): Packet? {
            return try {

                val buffer = ByteBuffer.wrap(bytes)

                if (buffer.remaining() < 12) {
                    return null
                }

                val bodyLength = buffer.int

                if (bodyLength < 0 ||
                    bodyLength > buffer.remaining() - 8
                ) {
                    return null
                }

                val body = ByteArray(bodyLength)
                buffer.get(body)

                val receivedCrc = buffer.long

                val crc32 = CRC32()
                crc32.update(body)

                if (crc32.value != receivedCrc) {
                    return null
                }

                val text = String(
                    body,
                    StandardCharsets.UTF_8
                )

                val parts = text.split("|", limit = 8)

                if (parts.size != 8) {
                    return null
                }

                if (parts[0] != "IT1") {
                    return null
                }

                Packet(
                    language = parts[1],
                    priority = parts[2].toInt(),
                    sequence = parts[3].toLong(),
                    timestamp = parts[4].toLong(),
                    latitude = parts[5]
                        .takeIf { it.isNotBlank() }
                        ?.toDoubleOrNull(),
                    longitude = parts[6]
                        .takeIf { it.isNotBlank() }
                        ?.toDoubleOrNull(),
                    message = parts[7]
                )

            } catch (e: Exception) {
                null
            }
        }
    }
}
