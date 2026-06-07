package com.tolopani.reader.nfc

object ApduUtils {
    // SELECT Master File (MF)
    val SELECT_MF = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x7F.toByte(), 0x0A.toByte())

    // SELECT Elementary File (EF) Photo
    val SELECT_EF_PHOTO = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x6F.toByte(), 0xF2.toByte())

    // SELECT Elementary File (EF) Signature
    val SELECT_EF_SIGNATURE = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x6F.toByte(), 0xF3.toByte())

    /**
     * Converts a hex string to a byte array.
     */
    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Converts a byte array to a hex string.
     */
    fun byteArrayToHexString(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    /**
     * Validates status word (SW1 SW2) from the card response.
     * 90 00 or 91 00 means Success.
     */
    fun isSuccessResponse(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        return (sw1 == 0x90.toByte() || sw1 == 0x91.toByte()) && sw2 == 0x00.toByte()
    }

    /**
     * Builds a READ BINARY APDU command.
     * Command format: 00 B0 [P1] [P2] [Le]
     * P1 is offset high byte, P2 is offset low byte, Le is length to read.
     */
    fun buildReadBinaryCommand(offset: Int, length: Int): ByteArray {
        val p1 = ((offset shr 8) and 0xFF).toByte()
        val p2 = (offset and 0xFF).toByte()
        val le = (length and 0xFF).toByte()
        return byteArrayOf(0x00.toByte(), 0xB0.toByte(), p1, p2, le)
    }
}
