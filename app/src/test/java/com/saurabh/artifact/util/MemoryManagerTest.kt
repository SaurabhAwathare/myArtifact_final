package com.saurabh.artifact.util

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class MemoryManagerTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @Test
    fun `register and notify works`() {
        val manager = MemoryManager()
        val mockTrimable = mockk<MemoryTrimable>(relaxed = true)
        
        manager.register(mockTrimable)
        manager.notifyTrim(80)
        
        verify { mockTrimable.trimMemory(80) }
    }

    @Test
    fun `unregister stops notifications`() {
        val manager = MemoryManager()
        val mockTrimable = mockk<MemoryTrimable>(relaxed = true)
        
        manager.register(mockTrimable)
        manager.unregister(mockTrimable)
        manager.notifyTrim(80)
        
        verify(exactly = 0) { mockTrimable.trimMemory(any()) }
    }
}
