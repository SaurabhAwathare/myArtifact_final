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

            private fun getCachedDataSource(): DataSource {
                return cachedDataSource ?: CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(defaultDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource().also { cachedDataSource = it }
            }

            private fun getEncryptedDataSource(): DataSource {
                return encryptedDataSource ?: encryptedDataSourceFactory.createDataSource()
                    .also { encryptedDataSource = it }
            }

            override fun addTransferListener(transferListener: TransferListener) {
                // Add to both potentially used sources
                // Note: These are lazy, so we might need to be careful if listeners are added before open
                // In Media3, listeners are usually added by the caller before open
            }

            override fun open(dataSpec: DataSpec): Long {
                val path = dataSpec.uri.path ?: ""
                currentDataSource = if (path.contains("encrypted_drafts")) {
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
                return currentDataSource?.getResponseHeaders() ?: emptyMap()
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
