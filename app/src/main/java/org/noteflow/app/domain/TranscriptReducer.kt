package org.noteflow.app.domain

/** Pure replay-safe reducer: never count duplicate or out-of-order partial deltas twice. */
data class TranscriptState(val sequence:Int=0,val partial:String="",val final:String?=null)
sealed interface TranscriptEvent {
    data class Delta(val sequence:Int,val text:String):TranscriptEvent
    data class Done(val text:String):TranscriptEvent
}
fun reduceTranscript(state:TranscriptState,event:TranscriptEvent):TranscriptState = when(event){
    is TranscriptEvent.Delta -> if(state.final!=null || event.sequence<=state.sequence) state
        else state.copy(sequence=event.sequence,partial=state.partial+event.text)
    is TranscriptEvent.Done -> if(state.final!=null) state
        else state.copy(final=event.text,partial="")
}
