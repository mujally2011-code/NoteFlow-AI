package org.noteflow.app.domain

/** Stateless fallbacks for strictly offline use; never call external services. */
data class LocalMatch(val segmentId:String,val text:String,val score:Int)

fun lexicalSearch(query:String, segments:List<Pair<String,String>>):List<LocalMatch> {
    val words=query.trim().lowercase().split(Regex("\\s+")).filter{it.length>=2}
    if(words.isEmpty()) return emptyList()
    return segments.mapNotNull{(id,text)->
        val normalized=text.lowercase()
        val score=words.count{it in normalized}
        if(score>0)LocalMatch(id,text,score) else null
    }.sortedWith(compareByDescending<LocalMatch>{it.score}.thenBy{it.segmentId})
}

fun quizIsCorrect(selected:Int,correct:Int,options:Int):Boolean =
    options>=2 && selected in 0 until options && correct in 0 until options && selected==correct
