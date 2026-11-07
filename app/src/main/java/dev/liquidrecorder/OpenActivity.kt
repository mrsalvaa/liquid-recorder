// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.ui.glass.GlassTheme
import dev.liquidrecorder.ui.glass.isDarkTheme
import dev.liquidrecorder.util.preferHighestRefreshRate

/**
 * "Abrir con" desde otra app (archivos, mensajería): reproduce el audio, también de videos. Solo
 * lee lo que se le pasó, con el permiso temporal que da la otra app; nada se copia salvo que se
 * elija "Guardar en grabaciones".
 */
class OpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        val uri = uriFrom(intent)
        if (uri == null) {
            finish()
            return
        }
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                RecorderUi(settings.animatedBackground, flatNav = settings.uiStyle == 1, external = uri, onExit = { finish() })
            }
        }
    }

    private fun uriFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }?.takeIf { it.scheme == "content" || it.scheme == "file" }
    }
}
