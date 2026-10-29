package com.example.livetranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentToolsTest {

    @Test
    fun parsesJsonWrappedInMarkdown() {
        val raw = "```json\n[" +
            "{\"start\":\"00:12\",\"end\":14.5,\"speaker\":\"متحدث 1\",\"en\":\"Hi\",\"ar\":\"مرحبا\"}," +
            "{\"start\":2,\"end\":1,\"speaker\":\"\",\"en\":\"First\",\"ar\":\"أولاً\"}," +
            "{\"start\":5,\"end\":6,\"en\":\"\",\"ar\":\"\"}" +
            "]\n```"
        val segs = SegmentTools.parse(raw)
        assertEquals(2, segs.size)
        // sorted by start; an end before the start becomes start + 3 s
        assertEquals(2.0, segs[0].start, 1e-9)
        assertEquals(5.0, segs[0].end, 1e-9)
        assertEquals(12.0, segs[1].start, 1e-9)
        assertEquals("متحدث 1", segs[1].speaker)
    }

    @Test
    fun srtHasIndexTimesAndNoOverlap() {
        val segs = listOf(
            Segment(1.0, 5.0, "A", "one", "واحد"),
            Segment(4.0, 6.25, "", "two", "اثنان")
        )
        val srt = SegmentTools.toSrt(segs)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:03,950\nA: واحد\none\n"))
        assertTrue(srt.contains("2\n00:00:04,000 --> 00:00:06,250\nاثنان\ntwo\n"))
    }

    @Test
    fun shortRecordingIsNotCut() {
        assertTrue(SegmentTools.chooseCuts(650.0, listOf(100.0, 600.0)).isEmpty())
    }

    @Test
    fun cutsBeforeTheNearestOnset() {
        val cuts = SegmentTools.chooseCuts(1500.0, listOf(300.0, 590.0, 605.0, 1190.0))
        assertEquals(2, cuts.size)
        assertEquals(604.8, cuts[0], 1e-9)
        assertEquals(1189.8, cuts[1], 1e-9)
    }

    @Test
    fun cutsAtBoundaryWithoutOnsets() {
        assertEquals(listOf(600.0, 1200.0), SegmentTools.chooseCuts(1300.0, emptyList()))
    }

    @Test
    fun shiftMovesTimes() {
        val s = SegmentTools.shift(listOf(Segment(1.0, 2.0, "A", "x", "y")), 600.0)
        assertEquals(601.0, s[0].start, 1e-9)
        assertEquals(602.0, s[0].end, 1e-9)
    }
}
