package org.noteflow.app.offline

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.noteflow.app.data.AppDatabase
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.time.Duration

/** Uses an explicitly installed Vosk model and never calls Android INTERNET APIs. */
class OfflineTranscriptionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        val segmentId=inputData.getString("segment")?:return@withContext Result.failure()
        val dao=AppDatabase.get(applicationContext).notes()
        val segment=dao.segment(segmentId)?:return@withContext Result.success()
        if(segment.status=="done")return@withContext Result.success()
        if(!ModelInstaller.installed(applicationContext)){
            dao.markSegment(segmentId,"error","Install an offline Vosk speech model in Settings")
            return@withContext Result.failure()
        }
        try{
            dao.markSegment(segmentId,"processing",null)
            val text=StringBuilder()
            Model(ModelInstaller.path(applicationContext).absolutePath).use { model ->
                Recognizer(model,16000.0f).use { recognizer ->
                    PcmDecoder().decode(File(segment.path)){pcm->
                        if(pcm.isNotEmpty() && recognizer.acceptWaveForm(pcm,pcm.size)){
                            val part=JSONObject(recognizer.result).optString("text").trim()
                            if(part.isNotBlank())text.append(part).append(' ')
                        }
                    }
                    text.append(JSONObject(recognizer.finalResult).optString("text"))
                }
            }
            val output=text.toString().trim()
            if(output.isBlank())error("No recognizable speech in this segment")
            dao.saveTranscript(segmentId,"done",output)
            Result.success()
        }catch(e:Exception){
            dao.markSegment(segmentId,"error","Offline ASR: ${e.message?.take(150)}")
            if(runAttemptCount<2) Result.retry() else Result.failure()
        }
    }
    companion object{
        fun queue(context:Context,segmentId:String){
            val request=OneTimeWorkRequestBuilder<OfflineTranscriptionWorker>()
                .setInputData(workDataOf("segment" to segmentId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,Duration.ofSeconds(15)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("offline-asr:$segmentId",ExistingWorkPolicy.KEEP,request)
        }
    }
}
