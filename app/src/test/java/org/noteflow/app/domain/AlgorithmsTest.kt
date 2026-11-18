package org.noteflow.app.domain

import org.junit.Assert.*
import org.junit.Test

class AlgorithmsTest{
    @Test fun dedupeOutOfOrderDeltas(){
        var s=TranscriptState()
        s=reduceTranscript(s,TranscriptEvent.Delta(2,"Hello "))
        s=reduceTranscript(s,TranscriptEvent.Delta(1,"duplicate"))
        s=reduceTranscript(s,TranscriptEvent.Delta(2,"repeat"))
        s=reduceTranscript(s,TranscriptEvent.Delta(3,"world"))
        assertEquals("Hello world",s.partial)
        s=reduceTranscript(s,TranscriptEvent.Done("Hello world"))
        s=reduceTranscript(s,TranscriptEvent.Delta(4,"extra"))
        assertEquals("Hello world",s.final)
        assertEquals("",s.partial)
    }
    @Test fun schedulingIsMonotonic(){
        val a=scheduleNextReview(0,2.5,2,1000L)
        assertTrue(a.dueAt>1000L)
        val b=scheduleNextReview(a.intervalDays,a.ease,1,1000L)
        assertTrue(b.intervalDays>a.intervalDays)
        assertEquals(1,scheduleNextReview(99,2.5,0,1000L).intervalDays)
    }
}
