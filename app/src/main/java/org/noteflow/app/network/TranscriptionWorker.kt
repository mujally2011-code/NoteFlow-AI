package org.noteflow.app.network

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.noteflow.app.data.AppDatabase
import java.io.File

class TranscriptionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        val segmentId=inputData.getString("segment")?:return@withContext Result.failure()
        val dao=AppDatabase.get(applicationContext).notes()
        val segment=dao.segment(segmentId)?:return@withContext Result.success()
        val session=dao.get(segment.sessionId)?:return@withContext Result.success()
        if(session.processingMode=="local" || !session.cloudConsent) return@withContext Result.success()
        if(segment.status=="done")return@withContext Result.success()
        if(!BackendSettings.configured(applicationContext)){
            dao.markSegment(segmentId,"error","Configure HTTPS gateway and pairing secret")
            return@withContext Result.failure()
        }
        try{
            dao.markSegment(segmentId,"processing",null)
            val gateway=BackendGateway(applicationContext)
            val text=gateway.transcribeStream(session.id,segment.segmentIndex,session.language,File(segment.path)){state->
                // Safe from work callback on its blocking worker thread; Room writes use a new coroutine.
                kotlinx.coroutines.runBlocking {dao.savePartial(segmentId,state.partial,state.sequence)}
            }
            dao.saveTranscript(segmentId,"done",text)
            if(session.liveTranslation){
                val target=if(session.language=="en")"ar" else "en"
                val latest=dao.segment(segmentId) ?: segment
                val translated=gateway.translate(segmentId,latest.revision,latest.effectiveText(),target)
                dao.saveTranslation(segmentId,translated,target,latest.revision)
            }
            Result.success()
        }catch(e:Exception){
            dao.markSegment(segmentId,"error",e.message?.take(180))
            if(runAttemptCount<4)Result.retry() else Result.failure()
        }
    }
    companion object{
        fun queue(context:Context,segmentId:String){
            val req=OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(workDataOf("segment" to segmentId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,java.time.Duration.ofSeconds(20)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("asr:$segmentId",ExistingWorkPolicy.KEEP,req)
        }
    }
}
