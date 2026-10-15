package com.andrerinas.openheadunit.osn

import android.content.Context
import com.ucar.vehiclesdk.UCarCommon

/** OSN vehicle outputs: cluster/HUD music via the media session, steering keys, and Gaode HUD guidance. */
internal object OsnCarLink {
    fun start(context: Context) = OsnMusicSession.start(context)

    fun onMusic(context: Context, info: UCarCommon.MusicInfo) {
        OsnMusicSession.update(context, info)
    }

    fun onNavigation(context: Context, info: UCarCommon.NavigationInfo) {
        OsnNavigationOutput.update(context, info)
    }

    fun onDisconnected(context: Context) {
        OsnNavigationOutput.clear(context)
        OsnMusicSession.clear()
    }

    fun onHardwareKey(keyCode: Int, isDown: Boolean) {
        if (isDown) OsnMusicSession.onMediaKey(keyCode)
    }
}
