package org.noteflow.app.domain

/** Offline spaced repetition, input rating 0=again,1=good,2=easy. */
data class ReviewPlan(val dueAt:Long,val intervalDays:Int,val ease:Double)
fun scheduleNextReview(oldInterval:Int,oldEase:Double,rating:Int,now:Long):ReviewPlan{
    require(rating in 0..2)
    val ease=(oldEase+when(rating){0->-0.25;1->0.0;else->0.15}).coerceIn(1.3,3.2)
    val days=when(rating){0->1;1->if(oldInterval<1)1 else (oldInterval*ease).toInt().coerceAtLeast(oldInterval+1);else->if(oldInterval<1)3 else (oldInterval*(ease+0.5)).toInt().coerceAtLeast(oldInterval+1)}
    return ReviewPlan(now+days*86_400_000L,days,ease)
}
