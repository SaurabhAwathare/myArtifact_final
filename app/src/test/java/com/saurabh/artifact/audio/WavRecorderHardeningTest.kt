package com.saurabh.artifact.audio

import android.content.Context
import android.media.AudioRecord
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.Continuation

@RunWith(RobolectricTestRunner::class)
class WavRecorderHardeningTest {

    private lateinit var context: Context
    private lateinit var outputFile: File
    private lateinit var wavRecorder: WavRecorder
    private val mockAudioRecord = mockk<AudioRecord>(relaxed = true)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        outputFile = File.createTempFile("test_hardening", ".wav")
        wavRecorder = WavRecorder(context, outputFile)
    }

    @Test
    fun `capture loop reports ERROR_DEAD_OBJECT and terminates`() = runTest {
        // 1. Inject mock AudioRecord via reflection
        val arField = WavRecorder::class.java.getDeclaredField("audioRecord")
        arField.isAccessible = true
        arField.set(wavRecorder, mockAudioRecord)

        // 2. Set recording state to started
        val isRecField = WavRecorder::class.java.getDeclaredField("isRecording")
        isRecField.isAccessible = true
        isRecField.set(wavRecorder, true)

        // 3. Mock read to return ERROR_DEAD_OBJECT
        every { mockAudioRecord.read(any<ByteArray>(), any(), any()) } returns AudioRecord.ERROR_DEAD_OBJECT

        var reportedError: Int? = null
        wavRecorder.onHardwareError = { reportedError = it }

        // 4. Trigger private captureAudioLoop
        val captureMethod = WavRecorder::class.java.getDeclaredMethod(
            "captureAudioLoop",
            Int::class.javaPrimitiveType,
            Continuation::class.java
        )
        captureMethod.isAccessible = true
        captureMethod.invoke(wavRecorder, 1024, Continuation<Unit>(coroutineContext) { })
        assertEquals(AudioRecord.ERROR_DEAD_OBJECT, reportedError)
    }

    @Test
    fun `start reports ERROR_BAD_VALUE when initialization fails`() {
        mockkStatic(AudioRecord::class)
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns AudioRecord.ERROR_BAD_VALUE

        var reportedError: Int? = null
        wavRecorder.onHardwareError = { reportedError = it }

        wavRecorder.start()
        
        assertEquals(AudioRecord.ERROR_BAD_VALUE, reportedError)
        unmockkStatic(AudioRecord::class)
    }
}
