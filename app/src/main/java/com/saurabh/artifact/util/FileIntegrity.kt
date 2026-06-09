package com.saurabh.artifact.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object FileIntegrity {

    /**
     * Calculates the SHA-256 checksum of a file.
     */
    fun calculateChecksum(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) return ""
        
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { inputStream ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Hashes a string using SHA-256.
     */
    fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
