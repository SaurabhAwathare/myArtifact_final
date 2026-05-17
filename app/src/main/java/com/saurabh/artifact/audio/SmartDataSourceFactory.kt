package com.saurabh.artifact.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

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

    override fun createDataSource(): DataSource {
        return object : DataSource {
            private var currentDataSource: DataSource? = null
            private val defaultDataSource = defaultDataSourceFactory.createDataSource()

            override fun addTransferListener(transferListener: TransferListener) {
                defaultDataSource.addTransferListener(transferListener)
                // Note: EncryptedFileDataSource also inherits addTransferListener but we 
                // handle delegation here.
            }

            override fun open(dataSpec: DataSpec): Long {
                val path = dataSpec.uri.path ?: ""
                currentDataSource = if (path.contains("encrypted_drafts")) {
                    encryptedDataSourceFactory.createDataSource()
                } else {
                    defaultDataSource
                }
                return currentDataSource!!.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return currentDataSource?.read(buffer, offset, length) ?: -1
            }

            override fun getUri(): Uri? {
                return currentDataSource?.getUri()
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
