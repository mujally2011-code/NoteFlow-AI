import org.noteflow.app.domain.*

fun main() {
    var state=TranscriptState()
    state=reduceTranscript(state,TranscriptEvent.Delta(2,"نراجع Database "))
    state=reduceTranscript(state,TranscriptEvent.Delta(2,"Duplicate"))
    state=reduceTranscript(state,TranscriptEvent.Delta(1,"Out of order"))
    state=reduceTranscript(state,TranscriptEvent.Delta(3,"Index"))
    check(state.partial=="نراجع Database Index")
    state=reduceTranscript(state,TranscriptEvent.Done("نراجع Database Index"))
    state=reduceTranscript(state,TranscriptEvent.Delta(4,"should be ignored"))
    check(state.final=="نراجع Database Index")
    val a=scheduleNextReview(0,2.5,2,1000L)
    val b=scheduleNextReview(a.intervalDays,a.ease,1,1000L)
    check(a.intervalDays==3 && b.intervalDays>a.intervalDays)
    check(scheduleNextReview(12,2.5,0,1000L).intervalDays==1)
    val matches=lexicalSearch("Database Index",listOf("a" to "محاضرة Database Index", "b" to "اجتماع المالية"))
    check(matches.size==1 && matches[0].segmentId=="a")
    check(quizIsCorrect(2,2,4) && !quizIsCorrect(2,1,4))
    println("PASS: transcript ordering, review scheduler, offline search, quiz grading (JVM)")
}
