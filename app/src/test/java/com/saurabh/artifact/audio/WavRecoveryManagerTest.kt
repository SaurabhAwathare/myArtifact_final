package com.saurabh.artifact.audio

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecoveryManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val recoveryManager = WavRecoveryManager()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `test repair of file with zeros in header`() {
        // 1. Create a corrupted WAV (44 bytes of zeros + 100 bytes of data)
        val file = tempFolder.newFile("corrupted.wav")
        val data = ByteArray(144) // 44 header + 100 data
        file.writeBytes(data)

        // 2. Run repair
        val result = recoveryManager.recover(file)

        // 3. Verify result type
        assertEquals(WavRecoveryManager.RecoveryResult.REPAIRED, result)

        // 4. Verify Header Content
        val header = file.readBytes().sliceArray(0..43)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF
        val riff = ByteArray(4)
        buffer.get(riff)
        assertEquals("RIFF", String(riff))
        
        // ChunkSize (alignedAudioLen + 36) -> 100 + 36 = 136
        assertEquals(136, buffer.getInt())

        // WAVE
        val wave = ByteArray(4)
        buffer.get(wave)
        assertEquals("WAVE", String(wave))

        // data tag at offset 36
        buffer.position(36)
        val dataTag = ByteArray(4)
        buffer.get(dataTag)
        assertEquals("data", String(dataTag))
        
        // Subchunk2Size at offset 40
        assertEquals(100, buffer.getInt())
    }

    @Test
    fun `test valid header is not modified`() {
        // 1. Create a valid small WAV
        val file = tempFolder.newFile("valid.wav")
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(36)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(44100)
            putInt(88200)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(0)
        }.array()
        file.writeBytes(header)

        // 2. Run repair
        val result = recoveryManager.recover(file)

        // 3. Verify it says FULLY_RECOVERED
        assertEquals(WavRecoveryManager.RecoveryResult.FULLY_RECOVERED, result)
    }

    @Test
    fun `test recovery with checkpoint truncation`() {
        // 1. Create a corrupted WAV with more data than checkpoint
        val file = tempFolder.newFile("checkpoint.wav")
        val data = ByteArray(1000) 
        file.writeBytes(data)

        // 2. Run recovery with checkpoint at 500 bytes
        val result = recoveryManager.recover(file, lastDurableBytes = 500L)

        // 3. Verify result is TRUNCATED
        assertEquals(WavRecoveryManager.RecoveryResult.TRUNCATED, result)
        
        // 4. Verify file length (44 header + 500 data)
        assertEquals(544L, file.length())
    }

    @Test
    fun `test file too small returns CORRUPTED`() {
        val file = tempFolder.newFile("too_small.wav")
        file.writeBytes(ByteArray(10))

        val result = recoveryManager.recover(file)
        assertEquals(WavRecoveryManager.RecoveryResult.CORRUPTED, result)
    }
}
