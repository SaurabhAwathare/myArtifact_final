package com.saurabh.artifact

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IconExportTest {

    @Test
    fun exportPlayStoreIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw Background
        // We know from inspection it's @color/obsidian_950 (#0F0F0F)
        val backgroundColor = ContextCompat.getColor(context, R.color.obsidian_950)
        canvas.drawColor(backgroundColor)

        // 2. Draw Foreground
        val foreground = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        requireNotNull(foreground) { "Foreground drawable not found" }

        // Adaptive icon foregrounds are designed for a 108x108 viewport
        // where the "safe zone" is the center. 
        // For a Play Store icon, we want the full 512x512 to match the adaptive icon's layers.
        foreground.setBounds(0, 0, size, size)
        foreground.draw(canvas)

        // 3. Save to PNG
        val outputDir = File("../docs")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val outputFile = File(outputDir, "artifact_play_store_icon_512.png")
        
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        println("Icon exported to: ${outputFile.absolutePath}")
        assert(outputFile.exists())
    }
}
