package com.saurabh.artifact.util

import java.util.concurrent.LinkedBlockingQueue

/**
 * A thread-safe pool of ByteArray buffers to reduce GC pressure during high-frequency audio capture.
 */
class BufferPool(private val bufferSize: Int, maxPoolSize: Int = 200) {
    private val pool = LinkedBlockingQueue<ByteArray>(maxPoolSize)

    /**
     * Acquires a buffer from the pool, or creates a new one if the pool is empty.
     */
    fun acquire(): ByteArray {
        return pool.poll() ?: ByteArray(bufferSize)
    }

    /**
     * Returns a buffer to the pool.
     */
    fun release(buffer: ByteArray) {
        if (buffer.size == bufferSize) {
            pool.offer(buffer)
        }
    }
}
