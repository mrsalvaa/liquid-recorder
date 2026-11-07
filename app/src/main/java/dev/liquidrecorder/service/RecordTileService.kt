// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.liquidrecorder.MainActivity
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.RecStatus
import dev.liquidrecorder.audio.Recorder

/**
 * Ajuste rápido "Grabar". Si no se está grabando, abre la app y empieza (Android no deja usar el
 * micrófono sin que la app se vea); si se está grabando, detiene y guarda.
 */
class RecordTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val s = Recorder.state.value
        tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (s.active) R.string.tile_stop else R.string.tile_record)
        tile.subtitle = when (s.status) {
            RecStatus.RECORDING -> getString(R.string.notif_recording)
            RecStatus.PAUSED -> getString(R.string.notif_paused)
            else -> null
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }

    override fun onClick() {
        if (Recorder.state.value.active) {
            Recorder.stop()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // Android 12 y 13 solo tienen la variante con Intent (la de PendingIntent es de la 14).
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
