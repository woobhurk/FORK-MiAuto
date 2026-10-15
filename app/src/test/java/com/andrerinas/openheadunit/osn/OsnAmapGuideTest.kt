package com.andrerinas.openheadunit.osn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsnAmapGuideTest {
    @Test fun maneuversUseGaodeIconIds() {
        assertEquals(2, OsnAmapGuide.icon("左转"))
        assertEquals(3, OsnAmapGuide.icon("向右转"))
        assertEquals(8, OsnAmapGuide.icon("掉头"))
        assertEquals(19, OsnAmapGuide.icon("右掉头"))
        assertEquals(66, OsnAmapGuide.icon("靠右行驶"))
        assertEquals(11, OsnAmapGuide.icon("进入环岛"))
        assertEquals(12, OsnAmapGuide.icon("驶出环岛"))
        assertEquals(9, OsnAmapGuide.icon("继续直行"))
        assertEquals(-1, OsnAmapGuide.icon("请走辅路"))
    }

    @Test fun roundaboutExitIsCappedAtTen() {
        assertEquals(3, OsnAmapGuide.roundaboutExit("第3出口"))
        assertEquals(2, OsnAmapGuide.roundaboutExit("第二个出口"))
        assertEquals(10, OsnAmapGuide.roundaboutExit("第十个出口"))
        assertEquals(-1, OsnAmapGuide.roundaboutExit("第11出口"))
        assertEquals(-1, OsnAmapGuide.roundaboutExit("直行"))
    }

    @Test fun distanceHonorsKilometers() {
        assertEquals(1200, OsnAmapGuide.meters("1.2", "公里"))
        assertEquals(800, OsnAmapGuide.meters("800", "米"))
        assertEquals(3500, OsnAmapGuide.meters("3.5km", null))
        assertEquals(0, OsnAmapGuide.meters("即将", "米"))
    }

    @Test fun missingTitleFallsBackToOperationAndRoad() {
        assertEquals("500米", OsnAmapGuide.segmentText("500", null))
        assertEquals("进入主路", OsnAmapGuide.action(null, "中山路", "进入主路"))
        assertEquals("右转 中山路", OsnAmapGuide.action("右转", "中山路", null))
        assertNull(OsnAmapGuide.action(null, "中山路", null))
    }
}
