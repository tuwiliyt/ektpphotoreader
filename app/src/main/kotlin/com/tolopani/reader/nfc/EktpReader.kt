package com.tolopani.reader.nfc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class EktpReader {

    sealed class ReadResult {
        data class Success(
            val photo: Bitmap,
            val signature: Bitmap? = null,
            val signatureStatus: String? = null
        ) : ReadResult()
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
            val photoBitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
            if (photoBitmap == null) {
                return@withContext ReadResult.Error("Gagal men-decode foto e-KTP")
            }

            // 4. Try to read Signature (Optional)
            var signatureBitmap: Bitmap? = null
            var signatureStatus: String? = "Tidak ditemukan"
            
            try {
                android.util.Log.d("EktpReader", "Attempting to select Signature EF (6F F3)")
                var selectSigResponse = isoDep.transceive(ApduUtils.SELECT_EF_SIGNATURE)
                var sw = if (selectSigResponse.size >= 2) {
                    String.format("%02X%02X", selectSigResponse[selectSigResponse.size - 2], selectSigResponse[selectSigResponse.size - 1])
                } else "Unknown"
                
                // If 6F F3 fails with 6A 82 (File Not Found), try 6F 04 as fallback
                if (sw == "6A82") {
                    android.util.Log.d("EktpReader", "6F F3 not found, trying 6F 04")
                    val SELECT_EF_SIG_ALT = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x6F.toByte(), 0x04.toByte())
                    selectSigResponse = isoDep.transceive(SELECT_EF_SIG_ALT)
                    sw = if (selectSigResponse.size >= 2) {
                        String.format("%02X%02X", selectSigResponse[selectSigResponse.size - 2], selectSigResponse[selectSigResponse.size - 1])
                    } else "Unknown"
                }

                if (ApduUtils.isSuccessResponse(selectSigResponse)) {
                    val sigSizeCommand = ApduUtils.buildReadBinaryCommand(0, 8)
                    val sigSizeResponse = isoDep.transceive(sigSizeCommand)
                    
                    val sizeSw = if (sigSizeResponse.size >= 2) {
                        String.format("%02X%02X", sigSizeResponse[sigSizeResponse.size - 2], sigSizeResponse[sigSizeResponse.size - 1])
                    } else "Unknown"

                    if (ApduUtils.isSuccessResponse(sigSizeResponse)) {
                        val sigSize = ((sigSizeResponse[0].toInt() and 0xFF) shl 8) or (sigSizeResponse[1].toInt() and 0xFF)
                        if (sigSize > 0) {
                            val sigBytes = ByteArray(sigSize)
                            // The first 2 bytes are the size prefix, data starts at index 2
                            val dataInResponse = sigSizeResponse.size - 4 // dataLength - 2 (SW) - 2 (size prefix)
                            
                            if (dataInResponse > 0) {
                                System.arraycopy(sigSizeResponse, 2, sigBytes, 0, Math.min(dataInResponse, sigSize))
                            }

                            var sigOffset = 8
                            while (sigOffset < sigSize) {
                                val nextOffset = sigOffset + 112
                                val len = if (nextOffset > sigSize) (sigSize - sigOffset) + 2 else 112
                                val cmd = ApduUtils.buildReadBinaryCommand(sigOffset, len)
                                val resp = isoDep.transceive(cmd)
                                if (!ApduUtils.isSuccessResponse(resp)) break
                                
                                val targetIndex = sigOffset - 2
                                val bytesToCopy = resp.size - 2
                                System.arraycopy(resp, 0, sigBytes, targetIndex, Math.min(bytesToCopy, sigSize - targetIndex))
                                
                                sigOffset = nextOffset
                            }
                            signatureBitmap = BitmapFactory.decodeByteArray(sigBytes, 0, sigBytes.size)
                            signatureStatus = if (signatureBitmap != null) "Berhasil" else "Format tidak didukung"
                        } else {
                            signatureStatus = "Kosong"
                        }
                    } else {
                        signatureStatus = if (sizeSw == "6982") "Terkunci (Enkripsi)" else "Akses Ditolak ($sizeSw)"
                    }
                } else {
                    signatureStatus = when (sw) {
                        "6982" -> "Terkunci (Enkripsi)"
                        "6A82" -> "Tidak tersedia di kartu ini"
                        else -> "Akses Ditolak ($sw)"
                    }
                }
            } catch (e: Exception) {
                signatureStatus = "Error: ${e.message}"
            }

            ReadResult.Success(photoBitmap, signatureBitmap, signatureStatus)

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
