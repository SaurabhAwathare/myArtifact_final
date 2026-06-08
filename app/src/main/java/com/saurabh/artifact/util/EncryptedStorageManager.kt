package com.saurabh.artifact.util

import android.content.Context
import com.saurabh.artifact.security.SecurityArchitecture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedStorageManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun getEncryptedInputStream(file: File): InputStream {
        return SecurityArchitecture.openDecryptingStream(context, file)
    }
}
