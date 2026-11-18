package com.ahcrazy.video2

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.ui.Video2Root
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        handleIncomingFile(intent)
        setContent { Video2Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    /** Files opened from a file manager land directly in the Library (iOS document-types parity). */
    private fun handleIncomingFile(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "content" && uri.scheme != "file") return
        Thread {
            runCatching {
                val app = application as Video2Application
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "مستورد"
                val dest = File(app.library.videosDir, "${System.currentTimeMillis()}-${name}")
                contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) } ?: return@Thread
                val kind = MediaKind.infer(dest.name, contentResolver.getType(uri))
                app.library.add(SavedVideo(title = name, sourceUrl = uri.toString(), localPath = dest.absolutePath,
                    kind = kind, fileSize = dest.length(), extractionMethod = "opened-file"))
            }
        }.start()
        intent.data = null
    }
}
