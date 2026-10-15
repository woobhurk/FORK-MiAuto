package com.andrerinas.openheadunit.osn

import android.content.Context
import android.content.Intent
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

    private var navigating = false
    private var lastIcon = STRAIGHT

    @Synchronized
    fun update(context: Context, info: UCarCommon.NavigationInfo) {
        val app = context.applicationContext
        if (info.isNavigating != navigating) {
            navigating = info.isNavigating
            send(app, stateIntent(info.isNavigating))
            if (!info.isNavigating) {
                lastIcon = STRAIGHT
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
        send(app, guide)
    }

    @Synchronized
    fun clear(context: Context) {
        if (!navigating) return
        navigating = false
        lastIcon = STRAIGHT
        send(context.applicationContext, stateIntent(false))
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
