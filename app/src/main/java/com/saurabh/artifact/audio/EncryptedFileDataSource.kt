package com.saurabh.artifact.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.saurabh.artifact.security.SecurityArchitecture
import java.io.File
import java.io.InputStream

/**
 * A Media3 DataSource that reads from an EncryptedFile.
 * This allows playback of encrypted local drafts without decrypting them to temp files first.
 */
@UnstableApi
class EncryptedFileDataSource(
    private val context: Context
) : BaseDataSource(true) {

    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @UnstableApi
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val filePath = dataSpec.uri.path ?: throw java.io.IOException("Missing file path in URI")
        val file = File(filePath)
        
        if (!file.exists()) {
            throw java.io.FileNotFoundException("Encrypted file not found: $filePath")
        }

        transferInitializing(dataSpec)
        
        try {
            inputStream = SecurityArchitecture.openDecryptingStream(context, file)
            
            // Seeking logic: EncryptedFile's InputStream might not support mark/reset,
            // so we skip from the beginning.
            if (dataSpec.position > 0) {
                var skipped = 0L
                while (skipped < dataSpec.position) {
                    val n = inputStream?.skip(dataSpec.position - skipped) ?: 0
                    if (n == 0L) {
                        // skip might return 0 if it's blocked, but for file streams it usually works or fails
                        if (inputStream?.read() == -1) break 
                        skipped++
                    } else {
                        skipped += n
                    }
                }
                if (skipped < dataSpec.position) {
                    throw java.io.IOException("Could not skip to position ${dataSpec.position}")
                }
            }

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                // We don't know the exact plain text length for GCM encrypted files without reading,
                // but we can signal end of input when read() returns -1.
                C.LENGTH_UNSET.toLong()
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            Log.e("EncryptedDataSource", "Failed to open encrypted file", e)
            throw java.io.IOException("Failed to open encrypted file: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        val bytesRead = try {
            inputStream?.read(buffer, offset, bytesToRead) ?: -1
        } catch (e: java.io.IOException) {
            throw e
        }

        if (bytesRead == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining != 0L) {
                throw java.io.EOFException("End of stream reached before reading expected length")
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            inputStream?.close()
        } catch (e: java.io.IOException) {
            Log.e("EncryptedDataSource", "Error closing input stream", e)
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return EncryptedFileDataSource(context)
        }
    }
}
