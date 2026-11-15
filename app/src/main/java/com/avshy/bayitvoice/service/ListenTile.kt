package com.avshy.bayitvoice.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.avshy.bayitvoice.R

/**
 * Quick Settings tile: on = listening for the wake word, off = paused, microphone free for other apps.
 * Unavailable while the listening service is not running.
 */
class ListenTile : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        if (!WakeWordService.isRunning) return
        ListenGate.setPaused(this, !ListenGate.paused(this).value)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val running = WakeWordService.isRunning
        val listening = running && !ListenGate.paused(this).value
        tile.state = when {
            !running -> Tile.STATE_UNAVAILABLE
            listening -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (listening) R.string.tile_listening else R.string.tile_paused)
        tile.updateTile()
    }

    companion object {
        /** Asks the system to redraw the tile after the listening state changed. */
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, ListenTile::class.java))
        }
    }
}
