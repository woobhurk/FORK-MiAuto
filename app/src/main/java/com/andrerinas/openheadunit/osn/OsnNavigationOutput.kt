package com.andrerinas.openheadunit.osn

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog
import com.ucar.vehiclesdk.UCarCommon

/**
 * Publishes ICCOA guidance as the Gaode standard broadcast Geely's HUD already listens for.
 * Observed from the OSN CarLink build: state changes use KEY_TYPE 10019 (8 begin, 9 end)
 * and each guidance frame uses KEY_TYPE 10001.
 */
internal object OsnNavigationOutput {
    private const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val KEY_GUIDE = 10001
    private const val KEY_STATE = 10019
    private const val STATE_BEGIN = 8
    private const val STATE_END = 9
    private const val STRAIGHT = 9
    private const val HEARTBEAT_MS = 5_000L
    /** Xiaoba drops the HUD maneuver after this long with no new guidance frame. */
    private const val STALE_FRAME_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = Runnable { onHeartbeat() }

    private var navigating = false
    private var lastIcon = STRAIGHT
    private var appContext: Context? = null
    private var lastGuide: Intent? = null
    private var lastFrameAt = 0L

    @Synchronized
    fun update(context: Context, info: UCarCommon.NavigationInfo) {
        val app = context.applicationContext
        appContext = app
        if (info.isNavigating != navigating) {
            navigating = info.isNavigating
            send(app, stateIntent(info.isNavigating))
            if (!info.isNavigating) {
                lastIcon = STRAIGHT
                lastGuide = null
                handler.removeCallbacks(heartbeat)
                return
            }
        }
        if (!info.isNavigating) return

        var icon = OsnAmapGuide.icon(info.operation)
        if (icon > 0) lastIcon = icon else icon = lastIcon
        var exit = OsnAmapGuide.roundaboutExit(info.operation)
        if (exit <= 0) exit = OsnAmapGuide.roundaboutExit(info.title2)
        if (exit > 0) icon = 12

        val guide = Intent(ACTION)
        guide.putExtra("KEY_TYPE", KEY_GUIDE)
        guide.putExtra("XB_CARLINK", 1)
        guide.putExtra("NEW_ICON", icon)
        if (exit > 0) guide.putExtra("ROUNG_ABOUT_NUM", exit)
        val iconBytes = info.directionIcon
        if (iconBytes != null && iconBytes.isNotEmpty()) guide.putExtra("XB_NAVI_ICON", iconBytes)
        guide.putExtra("SEG_REMAIN_DIS", OsnAmapGuide.meters(info.distance, info.distanceUnit))
        val segment = info.title1?.takeIf { it.isNotEmpty() }
            ?: OsnAmapGuide.segmentText(info.distance, info.distanceUnit)
        if (segment != null) guide.putExtra("SEG_REMAIN_DIS_AUTO", segment)
        info.where?.takeIf { it.isNotEmpty() }?.let { guide.putExtra("NEXT_ROAD_NAME", it) }
        OsnAmapGuide.action(info.operation, info.where, info.title2)?.let { guide.putExtra("ACTION", it) }
        guide.putExtra("TYPE", 0)
        lastGuide = Intent(guide)
        lastFrameAt = System.currentTimeMillis()
        send(app, guide)
        scheduleHeartbeat()
    }

    @Synchronized
    fun clear(context: Context) {
        if (!navigating) return
        navigating = false
        lastIcon = STRAIGHT
        lastGuide = null
        handler.removeCallbacks(heartbeat)
        send(context.applicationContext, stateIntent(false))
    }

    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    /**
     * Mirrors Xiaoba `XbNaviBridge`'s heartbeat: repeat the last guidance frame every five
     * seconds, and end the HUD maneuver when frames have been stale for thirty seconds.
     */
    private fun onHeartbeat() {
        val ctx: Context
        val guide: Intent
        synchronized(this) {
            if (!navigating) return
            val current = lastGuide ?: return
            val app = appContext ?: return
            val now = System.currentTimeMillis()
            if (now - lastFrameAt > STALE_FRAME_MS) {
                navigating = false
                lastGuide = null
                lastIcon = STRAIGHT
                send(app, stateIntent(false))
                return
            }
            ctx = app
            guide = Intent(current)
        }
        send(ctx, guide)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun stateIntent(active: Boolean): Intent =
        Intent(ACTION).apply {
            putExtra("KEY_TYPE", KEY_STATE)
            putExtra("EXTRA_STATE", if (active) STATE_BEGIN else STATE_END)
            putExtra("XB_CARLINK", 1)
        }

    private fun send(context: Context, intent: Intent) {
        try {
            context.sendBroadcast(intent)
        } catch (error: RuntimeException) {
            AppLog.w("OSN HUD broadcast failed: ${error.message}")
        }
    }
}
