package org.noteflow.app.audio

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.noteflow.app.data.AppDatabase
import org.noteflow.app.data.AudioSegmentEntity
import org.noteflow.app.network.TranscriptionWorker
import org.noteflow.app.offline.ModelInstaller
import org.noteflow.app.offline.OfflineTranscriptionWorker
import java.io.File

/** Record 15-second independently finalized M4A chunks, protecting completed chunks on crash. */
class RecordingService:Service(){
    companion object{
        const val ACTION_START="org.noteflow.START";const val ACTION_PAUSE="org.noteflow.PAUSE"
        const val ACTION_RESUME="org.noteflow.RESUME";const val ACTION_STOP="org.noteflow.STOP"
        const val EXTRA_SESSION="session_id"
        private const val CHANNEL="noteflow_recording";private const val ID=31
        private const val SEGMENT_MS=15000L
        @Volatile var running=false
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val handler=Handler(Looper.getMainLooper())
    private var recorder:MediaRecorder?=null
    private var id:String?=null
    private var output:File?=null
    private var index=0
    private var elapsed=0L
    private var segmentStart=0L
    private var paused=false
    private val rotate=object:Runnable {override fun run(){
        if(recorder!=null && !paused){closeChunk(true);startChunk();handler.postDelayed(this,SEGMENT_MS)}
    }}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"NoteFlow AI recording",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_START->if(id==null){
                id=intent.getStringExtra(EXTRA_SESSION)
                if(id.isNullOrEmpty()){stopSelf();return START_NOT_STICKY}
                val stopIntent=PendingIntent.getService(this,32,Intent(this,RecordingService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notice=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("NoteFlow AI · Recording").setContentText("Tap Stop to save the recording")
                    .addAction(android.R.drawable.ic_media_pause,"Stop",stopIntent).setOngoing(true).build()
                if(Build.VERSION.SDK_INT>=29) startForeground(ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(ID,notice)
                try{startChunk();running=true;handler.postDelayed(rotate,SEGMENT_MS)}catch(e:Exception){error(e.message ?: "Recording failed")}
            }
            ACTION_PAUSE->if(!paused && recorder!=null){
                handler.removeCallbacks(rotate);closeChunk(false);paused=true;update("paused")
            }
            ACTION_RESUME->if(paused){paused=false;startChunk();handler.postDelayed(rotate,SEGMENT_MS);update("recording")}
            ACTION_STOP->finish()
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun startChunk(){
        val session=id?:return
        val dir=File(filesDir,"recordings/$session").apply{mkdirs()}
        val current=File(dir,"${index.toString().padStart(5,'0')}.m4a")
        val rec=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this) else MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioSamplingRate(44100)
        rec.setAudioEncodingBitRate(96000)
        rec.setOutputFile(current.absolutePath)
        rec.prepare();rec.start();recorder=rec;output=current;segmentStart=System.currentTimeMillis()
        update("recording")
    }
    private fun closeChunk(rotating:Boolean){
        val rec=recorder?:return
        val session=id?:return
        val now=System.currentTimeMillis()
        val duration=(now-segmentStart).coerceAtLeast(0L)
        val file=output
        var saved=false
        try{rec.stop();saved=file!=null && file.length()>1024}catch(_:RuntimeException){file?.delete()}
        finally{rec.reset();rec.release();recorder=null}
        if(saved && file!=null){
            val thisIndex=index++
            val start=elapsed
            elapsed+=duration
            val item=AudioSegmentEntity("$session:$thisIndex",session,thisIndex,start,elapsed,file.absolutePath)
            scope.launch{
                val db=AppDatabase.get(applicationContext).notes()
                db.upsertSegment(item)
                db.updateCapture(session,if(rotating)"recording" else "paused",System.currentTimeMillis(),file.absolutePath,elapsed,null)
                val config=db.get(session)
                if(config != null && config.processingMode != "cloud" && ModelInstaller.installed(applicationContext)) {
                    OfflineTranscriptionWorker.queue(applicationContext,item.id)
                } else if(config != null && config.cloudConsent && config.processingMode != "local") {
                    TranscriptionWorker.queue(applicationContext,item.id)
                }
            }
        }
    }
    private fun update(status:String){id?.let{session->scope.launch{AppDatabase.get(applicationContext).notes().updateCapture(session,status,System.currentTimeMillis(),null,elapsed,null)}}}
    private fun error(message:String){id?.let{session->scope.launch{AppDatabase.get(applicationContext).notes().updateCapture(session,"error",System.currentTimeMillis(),null,elapsed,message)}};handler.removeCallbacks(rotate);stopSelf()}
    private fun finish(){
        running=false
        handler.removeCallbacks(rotate)
        closeChunk(false)
        val session=id;id=null
        if(session!=null)scope.launch{AppDatabase.get(applicationContext).notes().updateCapture(session,"ready",System.currentTimeMillis(),null,elapsed,null)}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){running=false;handler.removeCallbacks(rotate);if(recorder!=null){closeChunk(false);id?.let{session->scope.launch{AppDatabase.get(applicationContext).notes().updateCapture(session,"interrupted",System.currentTimeMillis(),null,elapsed,"Recording interrupted")}}};super.onDestroy()}
}
