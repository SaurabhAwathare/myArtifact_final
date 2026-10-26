package com.saurabh.artifact

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
@Config(
    sdk = [33],
    application = Application::class,
)
class IconExportTest {

    @Test
    fun exportPlayStoreIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        
        val size = 512
        val backgroundColor = ContextCompat.getColor(context, R.color.launcher_background)
        assert(backgroundColor == Color.parseColor("#050505")) {
            "Launcher background color must be #050505, but was ${String.format("#%06X", 0xFFFFFF and backgroundColor)}"
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)

        val foreground = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        requireNotNull(foreground) { "Foreground drawable not found" }

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
