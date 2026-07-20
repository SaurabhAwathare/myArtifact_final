package com.saurabh.artifact.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import android.os.SystemClock

/**
 * A DataSource.Factory that intelligently chooses between the standard local/network data source
 * and the EncryptedFileDataSource based on the file path.
 */
@UnstableApi
class SmartDataSourceFactory(
    private val context: Context,
    private val diagnosticLogger: DiagnosticLogger
) : DataSource.Factory {

    private val baseHttpFactory = DefaultHttpDataSource.Factory()
    private val instrumentedHttpFactory = DataSource.Factory {
        DiagnosticDataSource(baseHttpFactory.createDataSource(), "HTTP")
    }
    private val defaultDataSourceFactory = DefaultDataSource.Factory(context, instrumentedHttpFactory)
    private val encryptedDataSourceFactory = EncryptedFileDataSource.Factory(context)

    private inner class DiagnosticDataSource(
        private val delegate: DataSource,
        private val tag: String
    ) : DataSource {
        private var isFirstRead = true

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            diagnosticLogger.info(DiagnosticCategory.NETWORK, "${tag}_OPEN_START", mapOf("uri" to dataSpec.uri.toString()))
            val startTime = SystemClock.elapsedRealtime()
            try {
                val result = delegate.open(dataSpec)
                val elapsed = SystemClock.elapsedRealtime() - startTime
                diagnosticLogger.info(DiagnosticCategory.NETWORK, "${tag}_OPEN_END", mapOf("elapsed" to elapsed))
                return result
            } catch (e: Exception) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                diagnosticLogger.error(DiagnosticCategory.NETWORK, "${tag}_OPEN_FAILED", mapOf("elapsed" to elapsed), e)
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (isFirstRead && length > 0) {
                val startTime = SystemClock.elapsedRealtime()
                val result = delegate.read(buffer, offset, length)
                val elapsed = SystemClock.elapsedRealtime() - startTime
                diagnosticLogger.info(DiagnosticCategory.NETWORK, "${tag}_FIRST_READ", mapOf("elapsed" to elapsed, "result" to result))
                isFirstRead = false
                return result
            }
            return delegate.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun close() = delegate.close()
    }

    override fun createDataSource(): DataSource {
        return object : DataSource {
            private var currentDataSource: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            
            private var totalBytesRead = 0L
            private var networkBytesRead = 0L
            private var cacheBytesRead = 0L

            private val internalTransferListener = object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    totalBytesRead += bytesTransferred
                    if (isNetwork) {
                        networkBytesRead += bytesTransferred
                    } else {
                        cacheBytesRead += bytesTransferred
                    }
                }
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            }

            private fun createCachedDataSource(): DataSource {
                val upstreamFactory = DataSource.Factory {
                    DiagnosticDataSource(defaultDataSourceFactory.createDataSource(), "UPSTREAM")
                }
                
                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(MediaCache.getInstance(context))
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                
                return DiagnosticDataSource(cacheDataSource, "CACHE").also { 
                    it.addTransferListener(internalTransferListener)
                    listeners.forEach { listener -> it.addTransferListener(listener) }
                }
            }

            private fun createEncryptedDataSource(): DataSource {
                return encryptedDataSourceFactory.createDataSource()
                    .also { 
                        listeners.forEach { listener -> it.addTransferListener(listener) }
                    }
            }

            override fun addTransferListener(transferListener: TransferListener) {
                listeners.add(transferListener)
                currentDataSource?.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val selectionStartTime = SystemClock.elapsedRealtime()
                val path = dataSpec.uri.path ?: ""
                
                diagnosticLogger.info(DiagnosticCategory.NETWORK, "SMART_OPEN_START", mapOf("uri" to dataSpec.uri.toString()))
                
                val isEncrypted = path.contains("encrypted_drafts") || 
                                 dataSpec.uri.getQueryParameter("encrypted") == "true" ||
                                 (path.startsWith(this@SmartDataSourceFactory.context.filesDir.absolutePath) && !path.endsWith(".wav"))
                
                currentDataSource = if (isEncrypted) {
                    createEncryptedDataSource()
                } else {
                    createCachedDataSource()
                }
                
                val selectionElapsed = SystemClock.elapsedRealtime() - selectionStartTime
                diagnosticLogger.info(DiagnosticCategory.NETWORK, "SMART_SELECTION", mapOf("elapsed" to selectionElapsed))

                val artifactId = dataSpec.uri.getQueryParameter("artifact_id") ?: dataSpec.uri.lastPathSegment ?: "unknown"
                diagnosticLogger.info(
                    DiagnosticCategory.NETWORK,
                    "CURRENT_DATASOURCE",
                    mapOf(
                        "class" to (currentDataSource?.javaClass?.name ?: "null"),
                        "isDiagnostic" to (currentDataSource is DiagnosticDataSource),
                        "isEncrypted" to isEncrypted,
                        "artifactId" to artifactId,
                        "identity" to System.identityHashCode(currentDataSource)
                    )
                )

                val startTime = SystemClock.elapsedRealtime()
                try {
                    val result = currentDataSource!!.open(dataSpec)
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    
                    val artifactId = dataSpec.uri.getQueryParameter("artifact_id") ?: dataSpec.uri.lastPathSegment ?: "unknown"
                    val range = "bytes=${dataSpec.position}-${if (dataSpec.length != -1L) dataSpec.position + dataSpec.length - 1 else ""}"
                    
                    diagnosticLogger.info(
                        DiagnosticCategory.NETWORK,
                        "SMART_OPEN_END",
                        mapOf(
                            "artifact" to artifactId,
                            "uri" to dataSpec.uri.toString(),
                            "elapsed" to elapsed,
                            "range" to range,
                            "isEncrypted" to isEncrypted
                        )
                    )
                    return result
                } catch (e: Exception) {
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    diagnosticLogger.error(
                        DiagnosticCategory.NETWORK,
                        "DATASOURCE_OPEN_FAILED",
                        mapOf(
                            "uri" to dataSpec.uri.toString(),
                            "elapsed" to elapsed
                        ),
                        e
                    )
                    throw e
                }
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
                    if (totalBytesRead > 0) {
                        diagnosticLogger.info(
                            DiagnosticCategory.NETWORK,
                            "CACHE_EFFECTIVENESS",
                            mapOf(
                                "totalBytes" to totalBytesRead,
                                "cacheBytes" to cacheBytesRead,
                                "networkBytes" to networkBytesRead,
                                "hitRatio" to if (totalBytesRead > 0) cacheBytesRead.toDouble() / totalBytesRead else 0.0
                            )
                        )
                    }
                    currentDataSource?.close()
                } finally {
                    currentDataSource = null
                }
            }
        }
    }
}
