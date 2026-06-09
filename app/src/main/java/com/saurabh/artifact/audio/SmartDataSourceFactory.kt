package com.saurabh.artifact.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource

/**
 * A DataSource.Factory that intelligently chooses between the standard local/network data source
 * and the EncryptedFileDataSource based on the file path.
 */
@UnstableApi
class SmartDataSourceFactory(
    private val context: Context
) : DataSource.Factory {

    private val defaultDataSourceFactory = DefaultDataSource.Factory(context)
    private val encryptedDataSourceFactory = EncryptedFileDataSource.Factory(context)
    private val cache = MediaCache.getInstance(context)

    override fun createDataSource(): DataSource {
        return object : DataSource {
            private var currentDataSource: DataSource? = null
            
            private var cachedDataSource: DataSource? = null
            private var encryptedDataSource: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()

            private fun getCachedDataSource(): DataSource {
                return cachedDataSource ?: CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(defaultDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource().also { 
                        cachedDataSource = it 
                        listeners.forEach { listener -> it.addTransferListener(listener) }
                    }
            }

            private fun getEncryptedDataSource(): DataSource {
                return encryptedDataSource ?: encryptedDataSourceFactory.createDataSource()
                    .also { 
                        encryptedDataSource = it 
                        listeners.forEach { listener -> it.addTransferListener(listener) }
                    }
            }

            override fun addTransferListener(transferListener: TransferListener) {
                listeners.add(transferListener)
                cachedDataSource?.addTransferListener(transferListener)
                encryptedDataSource?.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val path = dataSpec.uri.path ?: ""
                
                // Detection logic: 
                // 1. Explicit marker in path (legacy)
                // 2. Query parameter in URI (cleaner for future-proofing)
                // 3. Local file check: If it's in our internal storage and not a raw WAV, it's encrypted.
                
                val isEncrypted = path.contains("encrypted_drafts") || 
                                 dataSpec.uri.getQueryParameter("encrypted") == "true" ||
                                 (path.startsWith(this@SmartDataSourceFactory.context.filesDir.absolutePath) && !path.endsWith(".wav"))
                
                currentDataSource = if (isEncrypted) {
                    getEncryptedDataSource()
                } else {
                    getCachedDataSource()
                }
                return currentDataSource!!.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return currentDataSource?.read(buffer, offset, length) ?: -1
            }

            override fun getUri(): Uri? {
                return currentDataSource?.uri
            }

            override fun getResponseHeaders(): Map<String, List<String>> {
                return currentDataSource?.responseHeaders ?: emptyMap()
            }

            override fun close() {
                try {
                    cachedDataSource?.close()
                    encryptedDataSource?.close()
                } finally {
                    cachedDataSource = null
                    encryptedDataSource = null
                    currentDataSource = null
                }
            }
        }
    }
}
