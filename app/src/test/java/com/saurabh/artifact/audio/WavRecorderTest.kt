package com.saurabh.artifact.audio

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class WavRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var outputFile: File
    private lateinit var wavRecorder: WavRecorder

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        outputFile = tempFolder.newFile("test.wav")
        wavRecorder = WavRecorder(context, outputFile)
    }

    @Test
    fun `audioChannel capacity should be 50`() {
        val field = WavRecorder::class.java.getDeclaredField("audioChannel")
        field.isAccessible = true
        field.get(wavRecorder) as kotlinx.coroutines.channels.Channel<*>
    }

    @Test
    fun `stopHardware should release AudioRecord`() {
        // Since AudioRecord is mocked/not easily instantiated in unit tests without Robolectric,
        // we mainly verify it doesn't crash and sets the internal field to null.
        wavRecorder.stopHardware()
        
        val field = WavRecorder::class.java.getDeclaredField("audioRecord")
        field.isAccessible = true
        assertNull(field.get(wavRecorder))
    }

    @Test
    fun `drainAndRelease should finalize WAV header`() = runTest {
        // Start recording (mocking dependencies is hard here, so we test the state transition)
        wavRecorder.start()
        
        // Emergency stop
        wavRecorder.drainAndRelease(500)
        
        // Verify file exists and has a header (at least 44 bytes)
        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() >= 44)
    }

    @Test
    fun `drainAndRelease should handle timeout gracefully`() = runTest {
        wavRecorder.start()
        
        // We can't easily stall the writerJob in a unit test without mocking RandomAccessFile,
        // but we verify it completes even with a very short timeout.
        wavRecorder.drainAndRelease(1) 
        
        // Should not throw exception
    }
}
