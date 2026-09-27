package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotPartTest {

    @Test
    fun `parses the three parts and rejects anything else`() {
        assertEquals(emptySet<SnapshotPart>(), SnapshotPart.parse(emptyList()))
        assertEquals(
            setOf(SnapshotPart.MINUTES, SnapshotPart.MOTION_WINDOWS, SnapshotPart.HEALTH_CONNECT_RECORDS),
            SnapshotPart.parse(listOf("healthConnectRecords", "minutes", "motionWindows", "minutes"))
        )
        assertNull(SnapshotPart.parse(listOf("minutes", "sources")))
    }
}
