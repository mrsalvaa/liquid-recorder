// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.liquidrecorder.audio.Recorder
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.service.RecordingService
import dev.liquidrecorder.util.AppIcons
import dev.liquidrecorder.util.Strings

class RecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.app = this
        Stores.init(this)
        if (AppIcons.migrate(this, Stores.settings.value.appIcon)) Stores.settings.update { it.copy(appIcon = 0) }
        // Grabaciones cortadas (el proceso murió a mitad): se rescatan hasta el último punto.
        val (ok, lost) = Recorder.recover()
        if (ok + lost > 0) RecoveryNotice.value = ok to lost
        RecordingService.ensureChannel(this)
        dev.liquidrecorder.data.Jobs.init(this)
        shortcuts()
    }

    /** Acceso directo "Grabar" al mantener pulsado el ícono (con el id de esta variante). */
    private fun shortcuts() {
        runCatching {
            val sm = getSystemService(ShortcutManager::class.java)
            val record = ShortcutInfo.Builder(this, "record")
                .setShortLabel(getString(R.string.tile_record))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_record))
                .setIntent(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD))
                .build()
            sm.dynamicShortcuts = listOf(record)
        }
    }

    companion object {
        /** Grabaciones rescatadas y perdidas al abrir la app, para avisar una vez. */
        val RecoveryNotice = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)
    }
}
