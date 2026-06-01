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
            
            // The default data source is wrapped in a CacheDataSource to enable local caching
            // of remote artifacts.
            private val cachedDataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(defaultDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            private val encryptedDataSource = encryptedDataSourceFactory.createDataSource()

            override fun addTransferListener(transferListener: TransferListener) {
                cachedDataSource.addTransferListener(transferListener)
                encryptedDataSource.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val path = dataSpec.uri.path ?: ""
                currentDataSource = if (path.contains("encrypted_drafts")) {
                    encryptedDataSource
                } else {
                    cachedDataSource
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
                currentDataSource?.close()
                currentDataSource = null
            }
        }
    }
}
