package com.ektp.reader.nfc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class EktpReader {

    sealed class ReadResult {
        data class Success(val photo: Bitmap) : ReadResult()
        data class Error(val message: String) : ReadResult()
        object Reading : ReadResult()
    }

    /**
     * Reads the photo from the e-KTP card via IsoDep.
     * Must be run from a background thread (e.g. Dispatchers.IO).
     */
    suspend fun readPhoto(isoDep: IsoDep): ReadResult = withContext(Dispatchers.IO) {
        try {
            // Set 5 seconds timeout
            isoDep.timeout = 5000
            if (!isoDep.isConnected) {
                isoDep.connect()
            }

            // 1. SELECT MF (Master File)
            val selectMfResponse = isoDep.transceive(ApduUtils.SELECT_MF)
            if (!ApduUtils.isSuccessResponse(selectMfResponse)) {
                return@withContext ReadResult.Error("Gagal SELECT Master File (MF)")
            }

            // 2. SELECT EF (Elementary File) Photo
            val selectEfResponse = isoDep.transceive(ApduUtils.SELECT_EF_PHOTO)
            if (!ApduUtils.isSuccessResponse(selectEfResponse)) {
                return@withContext ReadResult.Error("Gagal SELECT EF Photo")
            }

            // 3. READ BINARY first 8 bytes
            // Command to read first 8 bytes from offset 0
            val readSizeCommand = ApduUtils.buildReadBinaryCommand(0, 8)
            val sizeResponse = isoDep.transceive(readSizeCommand)
            if (!ApduUtils.isSuccessResponse(sizeResponse)) {
                return@withContext ReadResult.Error("Gagal membaca ukuran file foto")
            }

            // Photo size is stored in the first 2 bytes (big-endian)
            val photoSize = ((sizeResponse[0].toInt() and 0xFF) shl 8) or (sizeResponse[1].toInt() and 0xFF)
            if (photoSize <= 0) {
                return@withContext ReadResult.Error("Ukuran foto tidak valid: $photoSize byte")
            }

            // Create array to hold the full photo bytes
            val photoBytes = ByteArray(photoSize)

            // The sizeResponse contains:
            // - 8 bytes of data (first 2 bytes = size, next 6 bytes = photo data)
            // - 2 bytes of SW1 SW2
            // We copy the 6 bytes of photo data to photoBytes starting at index 0.
            val initialDataLength = sizeResponse.size - 4 // sizeResponse.length - 2 (SW) - 2 (size prefix) = 6
            if (initialDataLength > 0) {
                System.arraycopy(sizeResponse, 2, photoBytes, 0, initialDataLength)
            }

            // Loop to read the remaining photo bytes starting from offset 8
            var offset = 8
            while (offset < photoSize) {
                val nextOffset = offset + 112
                val lengthToRead = if (nextOffset > photoSize) {
                    // Last chunk: read remaining photo bytes + 2 bytes size offset difference?
                    // According to original logic: (photoSize - offset) + 2
                    (photoSize - offset) + 2
                } else {
                    112
                }

                val command = ApduUtils.buildReadBinaryCommand(offset, lengthToRead)
                val response = isoDep.transceive(command)

                if (!ApduUtils.isSuccessResponse(response)) {
                    return@withContext ReadResult.Error("Gagal membaca data foto pada offset $offset")
                }

                // Copy received data (excluding SW1 and SW2) to photoBytes
                // Target index in photoBytes is offset - 2 (because photoBytes doesn't contain the 2-byte size prefix)
                val targetIndex = offset - 2
                val bytesToCopy = response.size - 2
                System.arraycopy(response, 0, photoBytes, targetIndex, bytesToCopy)

                offset = nextOffset
            }

            // Decode photo bytes to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
            if (bitmap != null) {
                ReadResult.Success(bitmap)
            } else {
                ReadResult.Error("Gagal men-decode foto e-KTP")
            }

        } catch (e: IOException) {
            ReadResult.Error("Koneksi NFC terputus: ${e.localizedMessage}")
        } catch (e: Exception) {
            ReadResult.Error("Error: ${e.localizedMessage}")
        } finally {
            try {
                if (isoDep.isConnected) {
                    isoDep.close()
                }
            } catch (e: IOException) {
                // Ignore close error
            }
        }
    }
}
