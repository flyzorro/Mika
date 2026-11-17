package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchGeometryTest {
    private val regions = WatchGeometry.recognizeRegions(2412, 1080)

    @Test fun theLeftMiddleDockDoesNotCoverTheTable() {
        // 看屏期间的固定位置:左边、屏高约一半。
        assertFalse(WatchGeometry.needsHide(WatchRect(10, 428, 157, 575), regions))
    }

    @Test fun coversTheHandAlongTheBottom() {
        assertTrue(WatchGeometry.needsHide(WatchRect(1800, 950, 2100, 1080), regions))
        assertTrue(WatchGeometry.needsHide(WatchRect(0, 810, 300, 1000), regions))
    }

    @Test fun coversTheWallCounterAtTheTopLeft() {
        assertTrue(WatchGeometry.needsHide(WatchRect(20, 30, 200, 140), regions))
    }

    @Test fun coversTheAvatarAndQueBadgeAtTheBottomLeft() {
        assertTrue(WatchGeometry.needsHide(WatchRect(40, 700, 320, 940), regions))
    }

    @Test fun nothingDrawnNeedsNoHiding() {
        assertFalse(WatchGeometry.needsHide(null, regions))
    }
}
