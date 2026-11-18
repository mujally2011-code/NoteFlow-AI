package org.noteflow.app.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.noteflow.app.domain.TranscriptEvent
import org.noteflow.app.domain.TranscriptState
import org.noteflow.app.domain.reduceTranscript
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BackendSettings {
    private const val PREF="noteflow_settings"
    private const val ALIAS="noteflow_pair_secret_v1"
    fun mode(context:Context)=context.getSharedPreferences(PREF,0).getString("mode","local") ?: "local"
    fun setMode(context:Context,value:String)=context.getSharedPreferences(PREF,0).edit().putString("mode",value).apply()
    fun language(context:Context)=context.getSharedPreferences(PREF,0).getString("language","ar") ?: "ar"
    fun setLanguage(context:Context,value:String)=context.getSharedPreferences(PREF,0).edit().putString("language",value).apply()
    fun dark(context:Context)=context.getSharedPreferences(PREF,0).getBoolean("dark",false)
    fun setDark(context:Context,v:Boolean)=context.getSharedPreferences(PREF,0).edit().putBoolean("dark",v).apply()
    fun url(context:Context)=context.getSharedPreferences(PREF,0).getString("url","")?.trim()?.trimEnd('/') ?: ""
    fun setUrl(context:Context,v:String){
        require(v.isEmpty() || v.startsWith("https://")) { "Cloud address must use HTTPS" }
        context.getSharedPreferences(PREF,0).edit().putString("url",v.trim().trimEnd('/')).apply()
    }
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun setPairing(context:Context,secret:String){
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val payload=cipher.iv+cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREF,0).edit().putString("credential",Base64.encodeToString(payload,Base64.NO_WRAP)).apply()
    }
    fun pairing(context:Context):String {
        val encoded=context.getSharedPreferences(PREF,0).getString("credential",null)?:return ""
        return try{
            val bytes=Base64.decode(encoded,Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        }catch(_:Exception){""}
    }
    fun configured(context:Context)=url(context).startsWith("https://") && pairing(context).isNotBlank()
}

class BackendGateway(private val context:Context) {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(180,TimeUnit.SECONDS).callTimeout(220,TimeUnit.SECONDS).build()
    private fun url(path:String):String {
        val base=BackendSettings.url(context)
        require(base.startsWith("https://")) { "Configure an HTTPS backend in Settings" }
        return base+path
    }
    private fun requestToken():String {
        val secret=BackendSettings.pairing(context)
        require(secret.isNotEmpty()) { "Set pairing secret in Settings" }
        val body=JSONObject().put("pairing_secret",secret).toString().toRequestBody("application/json".toMediaType())
        val request=Request.Builder().url(url("/v1/auth/token")).post(body).build()
        client.newCall(request).execute().use{r ->
            if(!r.isSuccessful)error("Backend pairing failed: HTTP ${r.code}")
            return JSONObject(r.body!!.string()).getString("access_token")
        }
    }
    private fun builder(path:String)=Request.Builder().url(url(path)).header("Authorization","Bearer ${requestToken()}")
    fun postJson(path:String,json:JSONObject):JSONObject{
        val request=builder(path).post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use{resp->
            if(!resp.isSuccessful)error("Backend HTTP ${resp.code}: ${resp.body?.string()?.take(180)}")
            return JSONObject(resp.body!!.string())
        }
    }
    fun transcribeStream(sessionId:String,index:Int,language:String,file:File,onDelta:(TranscriptState)->Unit):String{
        val media=when(file.extension.lowercase()){
            "m4a"->"audio/mp4";"mp3"->"audio/mpeg";"webm"->"audio/webm";else->"audio/wav"
        }
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("session_id",sessionId).addFormDataPart("segment_index",index.toString())
            .addFormDataPart("language",language)
            .addFormDataPart("audio",file.name,file.asRequestBody(media.toMediaTypeOrNull()))
            .build()
        val request=builder("/v1/transcriptions/stream").post(multipart).build()
        client.newCall(request).execute().use{r->
            if(!r.isSuccessful)error("Transcription HTTP ${r.code}: ${r.body?.string()?.take(180)}")
            val reader=r.body!!.charStream().buffered()
            var type="";var state=TranscriptState()
            var result:String?=null
            reader.forEachLine{line->
                if(line.startsWith("event: "))type=line.removePrefix("event: ")
                if(line.startsWith("data: ")){
                    val data=JSONObject(line.removePrefix("data: "))
                    when(type){
                        "partial"->{state=reduceTranscript(state,TranscriptEvent.Delta(data.getInt("sequence"),data.optString("delta")));onDelta(state)}
                        "final"->{state=reduceTranscript(state,TranscriptEvent.Done(data.getString("text")));result=state.final}
                        "error"->error(data.optString("detail","Transcription failed"))
                    }
                }
            }
            return result ?: error("Transcription stream ended without final result")
        }
    }
    fun translate(segmentId:String,revision:Int,text:String,target:String):String {
        return postJson("/v1/translate",JSONObject().put("segment_id",segmentId).put("revision",revision).put("text",text).put("target",target)).getString("text")
    }
    fun deleteSession(id:String):Boolean{
        val r=builder("/v1/sessions/$id").delete().build()
        client.newCall(r).execute().use{return it.isSuccessful}
    }
}
