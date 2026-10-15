package com.andrerinas.openheadunit.osn

import java.util.regex.Pattern

/**
 * Turns an ICCOA navigation sentence into the Gaode icon and distance fields that
 * Geely's HUD already understands. The maneuver order matches the OSN CarLink bridge:
 * a more specific phrase such as "右掉头" has to win over "掉头".
 */
internal object OsnAmapGuide {
    private val EXIT = Pattern.compile("第([0-9]{1,2}|[一二三四五六七八九十])个?出口")
    private const val CN_DIGITS = "一二三四五六七八九十"

    /** Gaode `NEW_ICON`, or -1 when the sentence does not name a maneuver. */
    fun icon(operation: String?): Int {
        if (operation.isNullOrEmpty()) return -1
        if (operation.contains("掉头")) return if (operation.contains("右")) 19 else 8
        if (operation.contains("环岛")) {
            return if (operation.contains("出") || operation.contains("离")) 12 else 11
        }
        if (operation.contains("左后")) return 6
        if (operation.contains("右后")) return 7
        if (operation.contains("左前")) return 4
        if (operation.contains("右前")) return 5
        if (operation.contains("左转")) return 2
        if (operation.contains("右转")) return 3
        if (operation.contains("靠左")) return 65
        if (operation.contains("靠右")) return 66
        if (operation.contains("服务区")) return 13
        if (operation.contains("收费站")) return 14
        if (operation.contains("到达")) return 15
        if (operation.contains("隧道")) return 16
        if (operation.contains("途经")) return 10
        if (operation.contains("直行") || operation.contains("继续") || operation.contains("沿")) return 9
        return -1
    }

    /** 1..10 when the sentence says "第N出口", otherwise -1. */
    fun roundaboutExit(text: String?): Int {
        if (text.isNullOrEmpty()) return -1
        val match = EXIT.matcher(text)
        if (!match.find()) return -1
        val token = match.group(1) ?: return -1
        val value = if (token[0] in '0'..'9') {
            token.toIntOrNull() ?: return -1
        } else {
            val index = CN_DIGITS.indexOf(token)
            if (index < 0) return -1
            index + 1
        }
        return value.takeIf { it in 1..10 } ?: -1
    }

    fun meters(distance: String?, unit: String?): Int {
        if (distance.isNullOrEmpty()) return 0
        val number = distance.replace(Regex("[^0-9.]"), "")
        val value = number.toDoubleOrNull() ?: return 0
        val scale = unit.orEmpty() + distance
        val meters = if (scale.contains("公里") || scale.contains("km", ignoreCase = true)) value * 1000.0 else value
        return meters.toInt().coerceAtLeast(0)
    }

    fun segmentText(distance: String?, unit: String?): String? {
        if (distance.isNullOrEmpty()) return null
        return distance + (unit ?: "米")
    }

    fun action(operation: String?, where: String?, title: String?): String? {
        if (!title.isNullOrEmpty()) return title
        if (!operation.isNullOrEmpty() && !where.isNullOrEmpty()) return "$operation $where"
        if (!operation.isNullOrEmpty()) return operation
        return null
    }
}
