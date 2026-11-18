package org.noteflow.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.noteflow.app.audio.RecordingService
import org.noteflow.app.data.*
import org.noteflow.app.domain.scheduleNextReview
import org.noteflow.app.export.Exports
import org.noteflow.app.network.BackendGateway
import org.noteflow.app.network.BackendSettings
import org.noteflow.app.network.TranscriptionWorker
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity:ComponentActivity(){
    private val dao by lazy{AppDatabase.get(this).notes()}
    private var player:MediaPlayer?=null
    private var permissionAction:(()->Unit)?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO){if(!RecordingService.running)dao.recoverInterrupted()}
        setContent{NoteFlowScreen()}
    }
    override fun onDestroy(){player?.release();super.onDestroy()}

    private fun command(action:String){startService(Intent(this,RecordingService::class.java).setAction(action))}
    private fun play(segment:AudioSegmentEntity,onError:(String)->Unit){
        try{
            player?.release()
            player=MediaPlayer().apply{setDataSource(segment.path);prepare();start()}
        }catch(e:Exception){onError(e.message?:"Playback failed")}
    }
    private fun remove(session:SessionEntity){
        lifecycleScope.launch(Dispatchers.IO){
            // Deleting app-owned local data is unconditional. Remote deletion is separately reported.
            val folder=File(filesDir,"recordings/${session.id}").canonicalFile
            val root=File(filesDir,"recordings").canonicalFile
            if(folder.parentFile==root)folder.deleteRecursively()
            dao.deleteSegments(session.id);dao.deleteSummaries(session.id);dao.deleteCards(session.id)
            dao.deleteActions(session.id);dao.deleteBookmarks(session.id);dao.delete(session.id)
            if(session.cloudConsent){
                val deleted=if(BackendSettings.configured(applicationContext))
                    try{BackendGateway(applicationContext).deleteSession(session.id)}catch(_:Exception){false}
                    else false
                if(!deleted) getSharedPreferences("noteflow_pending_deletions",0).edit().putBoolean(session.id,true).apply()
            }
        }
    }

    private fun importAudio(uri:Uri,kind:String,language:String,onResult:(String)->Unit){
        lifecycleScope.launch(Dispatchers.IO){
            val id=UUID.randomUUID().toString()
            val path=uri.lastPathSegment.orEmpty().lowercase()
            val extension=listOf("mp3","m4a","wav","webm").firstOrNull{path.endsWith(".$it")} ?: when(contentResolver.getType(uri)){
                "audio/mpeg"->"mp3";"audio/mp4","audio/x-m4a"->"m4a";"audio/wav","audio/x-wav"->"wav";"audio/webm"->"webm";else->""
            }
            var dest:File?=null
            try{
                require(extension.isNotEmpty()){ "Select MP3, M4A, WAV or WebM" }
                val folder=File(filesDir,"recordings/$id").apply{mkdirs()}
                dest=File(folder,"00000.$extension")
                contentResolver.openInputStream(uri)?.use{input->
                    dest!!.outputStream().use{out->
                        val buffer=ByteArray(64*1024);var count: Int;var size=0L
                        while(input.read(buffer).also{count=it}!=-1){size+=count;require(size<300L*1024*1024){"File exceeds 300 MB limit"};out.write(buffer,0,count)}
                    }
                } ?: error("Selected file cannot be read")
                require(dest!!.length()>0){"Empty audio file"}
                // Validate an actual playable audio track, not merely an extension.
                val extractor=MediaExtractor()
                var durationMs=0L
                try{
                    extractor.setDataSource(dest!!.absolutePath)
                    val codecs=MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    require((0 until extractor.trackCount).any{index->
                        val fmt=extractor.getTrackFormat(index)
                        val supported=fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true && codecs.findDecoderForFormat(fmt)!=null
                        if(supported && fmt.containsKey(MediaFormat.KEY_DURATION))durationMs=fmt.getLong(MediaFormat.KEY_DURATION)/1000
                        supported
                    }){"No supported audio decoder on this device"}
                }finally{extractor.release()}
                val now=System.currentTimeMillis()
                dao.upsert(SessionEntity(id=id,title="Imported ${DateFormat.getDateTimeInstance().format(Date(now))}",kind=kind,status="ready",createdAt=now,updatedAt=now,audioPath=dest!!.absolutePath,durationMs=durationMs,language=language,processingMode=BackendSettings.mode(applicationContext)))
                dao.upsertSegment(AudioSegmentEntity("$id:0",id,0,0,durationMs,dest!!.absolutePath))
                // Cloud processing requires opt-in separately, even for imports.
                withContext(Dispatchers.Main){onResult("Imported locally. Enable cloud consent in session to transcribe.")}
            }catch(e:Exception){dest?.parentFile?.deleteRecursively();withContext(Dispatchers.Main){onResult(e.message?:"Import failed")}}
        }
    }

    private fun translate(segment:AudioSegmentEntity,session:SessionEntity,inform:(String)->Unit){
        if(session.processingMode=="local" || !session.cloudConsent){inform("Cloud consent is required for this feature");return}
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val target=if(session.language=="en")"ar" else "en"
                val result=BackendGateway(applicationContext).translate(segment.id,segment.revision,segment.effectiveText(),target)
                dao.saveTranslation(segment.id,result,target,segment.revision)
                withContext(Dispatchers.Main){inform("Translation saved")}
            }catch(e:Exception){withContext(Dispatchers.Main){inform(e.message?:"Translation failed")}}
        }
    }
    private fun generate(kind:String,style:String,session:SessionEntity,inform:(String)->Unit){
        if(session.processingMode=="local" || !session.cloudConsent){inform("Cloud consent required. Open Settings and enable server processing.");return}
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val segments=dao.listSegments(session.id).filter{it.effectiveText().isNotBlank()}
                require(segments.isNotEmpty()){"Transcribe or edit at least one audio segment first"}
                val request=JSONObject().put("kind",kind).put("style",style).put("segments",JSONArray().apply{
                    segments.forEach{put(JSONObject().put("id",it.id).put("text",it.effectiveText()))}
                })
                val items=BackendGateway(applicationContext).postJson("/v1/generate",request).getJSONArray("items")
                val revision=segments.maxOf{it.revision}
                for(i in 0 until items.length()){
                    val item=items.getJSONObject(i)
                    val sources=item.getJSONArray("source_ids").toString()
                    when(kind){
                        "summary"->dao.upsertSummary(SummaryEntity(item.getString("id"),session.id,style,item.getString("text"),sources,revision))
                        "flashcards"->dao.upsertCard(FlashcardEntity(item.getString("id"),session.id,item.getString("question"),item.getString("answer"),sources))
                        "actions"->dao.upsertAction(ActionEntity(item.getString("id"),session.id,item.getString("text"),item.optString("owner").takeIf{it.isNotBlank()},item.optString("due_date").takeIf{it.isNotBlank()},item.optString("decision_state","proposal"),sources))
                    }
                }
                withContext(Dispatchers.Main){inform("Generated ${items.length()} items")}
            }catch(e:Exception){withContext(Dispatchers.Main){inform(e.message?:"Generation failed")}}
        }
    }

    @Composable
    private fun NoteFlowScreen(){
        val sessions by dao.observeSessions().collectAsState(initial=emptyList())
        var page by remember{mutableStateOf("home")}
        var selectedId by remember{mutableStateOf("")}
        var tab by remember{mutableStateOf("audio")}
        var title by remember{mutableStateOf("")}
        var kind by remember{mutableStateOf("lecture")}
        var language by remember{mutableStateOf("auto")}
        var hasConsent by remember{mutableStateOf(false)}
        var cloudConsent by remember{mutableStateOf(false)}
        var liveTranslation by remember{mutableStateOf(false)}
        var dialog by remember{mutableStateOf("")}
        var query by remember{mutableStateOf("")}
        var filter by remember{mutableStateOf("all")}
        var message by remember{mutableStateOf("")}
        var selectedSegment by remember{mutableStateOf("")}
        var editValue by remember{mutableStateOf("")}
        var editCard by remember{mutableStateOf("")}
        var editAnswer by remember{mutableStateOf("")}
        var editOwner by remember{mutableStateOf("")}
        var editDue by remember{mutableStateOf("")}
        var exportFormat by remember{mutableStateOf("txt")}
        var exportId by remember{mutableStateOf("")}
        val selected=sessionOrNull(sessions,selectedId)
        val segmentsFlow=remember(selectedId){dao.observeSegments(selectedId)}
        val segments by segmentsFlow.collectAsState(initial=emptyList())
        val summariesFlow=remember(selectedId){dao.summaries(selectedId)}
        val summaries by summariesFlow.collectAsState(initial=emptyList())
        val cardsFlow=remember(selectedId){dao.cards(selectedId)}
        val cards by cardsFlow.collectAsState(initial=emptyList())
        val actionsFlow=remember(selectedId){dao.actions(selectedId)}
        val actions by actionsFlow.collectAsState(initial=emptyList())
        val bookmarksFlow=remember(selectedId){dao.bookmarks(selectedId)}
        val bookmarks by bookmarksFlow.collectAsState(initial=emptyList())
        val matchingFlow=remember(query){dao.searchMatchingSessionIds(query)}
        val matchingIds by matchingFlow.collectAsState(initial=emptyList())
        val active=sessions.firstOrNull{it.status=="recording" || it.status=="paused"}
        val activeSegmentsFlow=remember(active?.id){dao.observeSegments(active?.id?:"")}
        val activeSegments by activeSegmentsFlow.collectAsState(initial=emptyList())
        val uiArabic=BackendSettings.language(applicationContext)=="ar"
        var refreshLocale by remember{mutableIntStateOf(0)}
        val arabic=if(refreshLocale>=0)uiArabic else uiArabic
        fun tr(ar:String,en:String)=if(arabic)ar else en
        var dark by remember{mutableStateOf(BackendSettings.dark(applicationContext))}
        var mode by remember{mutableStateOf(BackendSettings.mode(applicationContext))}
        var server by remember{mutableStateOf(BackendSettings.url(applicationContext))}
        var secret by remember{mutableStateOf("")}
        var chooseSegments by remember{mutableStateOf(setOf<String>())}
        var clock by remember{mutableLongStateOf(System.currentTimeMillis())}
        LaunchedEffect(active?.id){while(active!=null){clock=System.currentTimeMillis();delay(1000)}}
        val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
            if(granted)permissionAction?.invoke() else message=tr("يلزم السماح باستخدام الميكروفون","Microphone permission is required")
            permissionAction=null
        }
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
            if(uri!=null)importAudio(uri,kind,language){message=it}
        }
        val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
            if(uri!=null){val session=sessions.firstOrNull{it.id==exportId};if(session!=null)lifecycleScope.launch(Dispatchers.IO){
                try{
                    val all=dao.listSegments(session.id)
                    val use=if(chooseSegments.isEmpty())all else all.filter{it.id in chooseSegments}
                    val cs=cardsIfSelected(session.id);val ss=summariesIfSelected(session.id);val ac=actionsIfSelected(session.id)
                    contentResolver.openOutputStream(uri)?.use{Exports.write(exportFormat,it,session,use,cs,ss,ac)} ?: error("Export destination unavailable")
                    withContext(Dispatchers.Main){message=tr("تم تصدير الملف","File exported")}
                }catch(e:Exception){withContext(Dispatchers.Main){message=e.message?:"Export error"}}
            }}
        }
        CompositionLocalProvider(LocalLayoutDirection provides if(arabic)LayoutDirection.Rtl else LayoutDirection.Ltr){
            MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme(
                primary=androidx.compose.ui.graphics.Color(0xFF246B78),
                secondary=androidx.compose.ui.graphics.Color(0xFF53767A)
            )){
                Scaffold(topBar={Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    Text(if(page=="home")"NoteFlow AI" else if(page=="settings")tr("الإعدادات","Settings") else selected?.title?:"NoteFlow AI",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    TextButton(onClick={if(page=="home")page="settings" else page="home"}){Text(if(page=="home")"⚙" else tr("الرئيسية","Home"))}
                }}){inner->
                    LazyColumn(Modifier.fillMaxSize().padding(inner).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=80.dp)){
                        when(page){
                            "home"->{
                                item{Text(tr("حوّل المحاضرات والاجتماعات إلى معرفة قابلة للمراجعة","Turn recordings into useful notes"),style=MaterialTheme.typography.bodyMedium)}
                                if(active!=null) {
                                    item{ActivePanel(active,activeSegments,clock,::command,{lifecycleScope.launch{
                                        dao.upsertBookmark(BookmarkEntity(UUID.randomUUID().toString(),active.id,activeSegments.lastOrNull()?.id,
                                            active.durationMs+(if(active.status=="recording")(clock-active.updatedAt).coerceAtLeast(0L) else 0L),""))
                                        message=tr("تم وضع علامة مرجعية","Bookmark saved")
                                    }},arabic)}
                                }else{
                                    item{OutlinedTextField(title,onValueChange={title=it},label={Text(tr("عنوان الجلسة","Session title"))},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                                    item{Row(modifier=Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                        listOf("lecture" to tr("محاضرة","Lecture"),"meeting" to tr("اجتماع","Meeting"),"research" to tr("بحث / تدريب","Research")).forEach{(v,label)->FilterChip(selected=kind==v,onClick={kind=v},label={Text(label)})}
                                    }}
                                    item{Row(modifier=Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                        listOf("auto" to tr("تلقائي","Auto"),"ar" to "عربي","en" to "English").forEach{(v,label)->FilterChip(selected=language==v,onClick={language=v},label={Text(label)})}
                                    }}
                                    item{Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=hasConsent,onCheckedChange={hasConsent=it});Text(tr("أؤكد إبلاغ المشاركين والحصول على الموافقة اللازمة","I confirm appropriate recording consent"),style=MaterialTheme.typography.bodySmall)}}
                                    item{Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=cloudConsent,onCheckedChange={cloudConsent=it});Text(tr("أسمح بإرسال الصوت والنص إلى الخادم لهذه الجلسة","Allow cloud processing for this session"),style=MaterialTheme.typography.bodySmall)}}
                                    item{Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=liveTranslation,onCheckedChange={liveTranslation=it});Text(tr("ترجمة المقاطع فور تفريغها","Translate finalized chunks automatically"),style=MaterialTheme.typography.bodySmall)}}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        Button(enabled=hasConsent,modifier=Modifier.weight(1f),onClick={
                                            val start: () -> Unit = {
                                                lifecycleScope.launch{
                                                    val id=UUID.randomUUID().toString()
                                                    dao.upsert(SessionEntity(id=id,title=title.ifBlank{tr("تسجيل جديد","New recording")},kind=kind,status="recording",language=language,
                                                        processingMode=mode,cloudConsent=cloudConsent && mode!="local",liveTranslation=liveTranslation))
                                                    try{ContextCompat.startForegroundService(this@MainActivity,Intent(this@MainActivity,RecordingService::class.java).setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_SESSION,id))}
                                                    catch(e:Exception){dao.updateCapture(id,"error",System.currentTimeMillis(),null,0,e.message)}
                                                }
                                            }
                                            if(ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)start()
                                            else{permissionAction=start;permission.launch(Manifest.permission.RECORD_AUDIO)}
                                        }){Text(tr("● بدء تسجيل","● Start recording"))}
                                        OutlinedButton(modifier=Modifier.weight(1f),onClick={picker.launch(arrayOf("audio/*","application/octet-stream"))}){Text(tr("استيراد ملف","Import audio"))}
                                    }}
                                }
                                item{Text(tr("المكتبة المحلية","Local library"),style=MaterialTheme.typography.titleMedium)}
                                item{OutlinedTextField(query,onValueChange={query=it},label={Text(tr("بحث في العناوين والنصوص","Search titles and transcripts"))},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                                item{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    listOf("all" to tr("الكل","All"),"lecture" to tr("محاضرات","Lectures"),"meeting" to tr("اجتماعات","Meetings"),"research" to tr("بحث","Research"),"recent" to tr("آخر 7 أيام","Last 7 days")).forEach{(value,label)->FilterChip(selected=filter==value,onClick={filter=value},label={Text(label)})}
                                }}
                                val filtered=sessions.filter{(filter=="all"||it.kind==filter||(filter=="recent" && System.currentTimeMillis()-it.createdAt<7*86_400_000L)) && (query.isBlank() || it.title.contains(query,true) || it.id in matchingIds)}
                                if(filtered.isEmpty())item{EmptyState(tr("لا توجد جلسات مطابقة","No matching sessions"))}
                                items(filtered,key={it.id}){s->Card(onClick={selectedId=s.id;tab=if(query.isNotBlank() && s.id in matchingIds)"transcript" else "audio";chooseSegments=emptySet();page="detail"},modifier=Modifier.fillMaxWidth()){
                                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                                        Text(s.title,style=MaterialTheme.typography.titleMedium)
                                        Text("${s.kind} • ${s.status} • ${DateFormat.getDateInstance().format(Date(s.createdAt))}",style=MaterialTheme.typography.bodySmall)
                                        Text("${s.durationMs/1000}s • ${if(s.cloudConsent)tr("سحابي بموافقة","Cloud opt-in") else tr("محلي","Local")}",style=MaterialTheme.typography.labelSmall)
                                    }
                                }}
                            }
                            "settings"->{
                                item{Text(tr("إعدادات الخصوصية والمعالجة","Processing & privacy"),style=MaterialTheme.typography.titleMedium)}
                                item{Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                                    listOf("local" to tr("محلي فقط — لا إرسال لأي بيانات","Local only — no upload"),"cloud" to tr("سحابي عند الطلب (بموافقة كل جلسة)","Cloud when approved per session"),"auto" to tr("تلقائي — محلي عند توفر المحرك، وإلا بعد موافقة صريحة","Auto — local when available; cloud only with consent")).forEach{(value,label)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(selected=mode==value,onClick={mode=value});Text(label,style=MaterialTheme.typography.bodySmall)}}
                                }}
                                item{Text(tr("تُحفظ ملفات التسجيل محلياً. لم يُضمّن محرك تفريغ محلي في هذا البناء.","Local audio is stored on device. This build does not bundle an offline ASR model."),style=MaterialTheme.typography.bodySmall)}
                                item{OutlinedTextField(server,onValueChange={server=it},label={Text("HTTPS gateway URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                                item{OutlinedTextField(secret,onValueChange={secret=it},label={Text(tr("سر الاقتران (يُخزّن مشفراً)","Pairing secret (encrypted at rest)"))},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                                item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    FilterChip(selected=arabic,onClick={BackendSettings.setLanguage(applicationContext,"ar");refreshLocale++},label={Text("العربية")})
                                    FilterChip(selected=!arabic,onClick={BackendSettings.setLanguage(applicationContext,"en");refreshLocale++},label={Text("English")})
                                }}
                                item{Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=dark,onCheckedChange={dark=it;BackendSettings.setDark(applicationContext,it)});Spacer(Modifier.width(12.dp));Text(tr("الوضع الداكن","Dark mode"))}}
                                item{OutlinedButton(onClick={lifecycleScope.launch(Dispatchers.IO){
                                    val pref=getSharedPreferences("noteflow_pending_deletions",0)
                                    val pending=pref.all.keys
                                    var success=0
                                    if(BackendSettings.configured(applicationContext)){
                                        pending.forEach{id->
                                            try{if(BackendGateway(applicationContext).deleteSession(id)){
                                                pref.edit().remove(id).apply();success++
                                            }}catch(_:Exception){}
                                        }
                                    }
                                    withContext(Dispatchers.Main){message="Remote deletion requests completed: $success / ${pending.size}"}
                                }}){Text(tr("إعادة محاولة الحذف السحابي المعلق","Retry pending cloud deletion"))}}
                                item{Button(onClick={try{
                                    BackendSettings.setUrl(applicationContext,server)
                                    if(secret.isNotBlank()){BackendSettings.setPairing(applicationContext,secret);secret=""}
                                    BackendSettings.setMode(applicationContext,mode)
                                    message=tr("تم حفظ الإعدادات","Settings saved")
                                }catch(e:Exception){message=e.message?:"Invalid configuration"}}){Text(tr("حفظ الإعدادات","Save settings"))}}
                            }
                            "detail"->if(selected!=null){
                                item{Row(modifier=Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                                    listOf("audio" to tr("الصوت","Audio"),"transcript" to tr("النص","Text"),"summary" to tr("ملخص","Summary"),"cards" to tr("بطاقات","Cards"),"tasks" to tr("مهام","Tasks"),"notes" to tr("علامات","Notes")).forEach{(v,label)->FilterChip(selected=tab==v,onClick={tab=v},label={Text(label)})}
                                }}
                                item{Text("${selected.kind} • ${selected.status} • ${selected.durationMs/1000}s",style=MaterialTheme.typography.labelMedium)}
                                if(!selected.cloudConsent)item{
                                    OutlinedButton(onClick={dialog="enableCloud"}){Text(tr("تفعيل التفريغ السحابي لهذه الجلسة بعد موافقتي","Enable cloud transcription with my consent"))}
                                }

                                when(tab){
                                    "audio"->{
                                        item{Text(tr("المقاطع المحفوظة","Saved audio chunks"),style=MaterialTheme.typography.titleMedium)}
                                        items(segments,key={it.id}){s->Surface(tonalElevation=1.dp,shape=RoundedCornerShape(12.dp)){
                                            Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                                                Column(Modifier.weight(1f)){
                                                    Text("${s.segmentIndex+1}. ${(s.endMs-s.startMs)/1000}s • ${s.status}")
                                                    Text(s.path.substringAfterLast('/'),style=MaterialTheme.typography.bodySmall)
                                                }
                                                TextButton(onClick={play(s){message=it}}){Text("▶")}
                                                TextButton(onClick={if(selected.cloudConsent && selected.processingMode!="local"){TranscriptionWorker.queue(applicationContext,s.id);message=tr("تمت إضافة المهمة للطابور","Queued")}else message=tr("يلزم تفعيل المعالجة السحابية لهذه الجلسة","Enable cloud consent for this session first")}){Text("ASR")}
                                            }
                                        }}
                                        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                            Button(onClick={exportFormat="txt";exportId=selected.id;exporter.launch("${selected.title}.txt")}){Text("TXT")}
                                            OutlinedButton(onClick={exportFormat="json";exportId=selected.id;exporter.launch("${selected.title}.json")}){Text("JSON")}
                                            OutlinedButton(onClick={exportFormat="pdf";exportId=selected.id;exporter.launch("${selected.title}.pdf")}){Text("PDF")}
                                        }}
                                        item{OutlinedButton(onClick={editValue=selected.title;dialog="rename"}){Text(tr("إعادة تسمية الجلسة","Rename session"))}}
                                        item{OutlinedButton(onClick={dialog="delete"}){Text(tr("حذف الجلسة وملفاتها","Delete session and its files"))}}
                                    }
                                    "transcript"->{
                                        item{Text(tr("الأصل محفوظ دائماً والتعديل منفصل","Original transcript stays unchanged; edits are separate"),style=MaterialTheme.typography.bodySmall)}
                                        if(segments.isEmpty())item{EmptyState(tr("لا توجد مقاطع بعد","No audio chunks yet"))}
                                        items(segments,key={it.id}){s->Card(Modifier.fillMaxWidth()){
                                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                                                Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){
                                                    Text("#${s.segmentIndex+1} • ${s.startMs/1000}s",style=MaterialTheme.typography.labelMedium)
                                                    Text(s.status,style=MaterialTheme.typography.labelSmall)
                                                }
                                                if(s.partialText.isNotBlank())Text(s.partialText+" …",color=MaterialTheme.colorScheme.secondary)
                                                Text(s.effectiveText().ifBlank{tr("لم يتم التفريغ بعد","Not transcribed yet")})
                                                if(s.editedText!=null)Text(tr("الأصل: ","Original: ")+s.originalText,style=MaterialTheme.typography.bodySmall)
                                                s.translationText?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
                                                if(s.translationText!=null && s.translatedRevision!=s.revision)Text(tr("الترجمة قديمة","Translation outdated"),color=MaterialTheme.colorScheme.error)
                                                s.errorMessage?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                                                Row(horizontalArrangement=Arrangement.spacedBy(3.dp)){
                                                    TextButton(onClick={selectedSegment=s.id;editValue=s.effectiveText();dialog="editTranscript"}){Text(tr("تصحيح","Edit"))}
                                                    TextButton(onClick={translate(s,selected){message=it}}){Text(tr("ترجمة","Translate"))}
                                                    TextButton(onClick={play(s){message=it}}){Text("▶")}
                                                    Checkbox(checked=chooseSegments.contains(s.id),onCheckedChange={chooseSegments=if(it)chooseSegments+s.id else chooseSegments-s.id})
                                                }
                                            }
                                        }}
                                    }
                                    "summary"->{
                                        item{Text(tr("ملخصات ذات مصادر قابلة للرجوع","Source-linked summaries"),style=MaterialTheme.typography.titleMedium)}
                                        item{Row(modifier=Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                            listOf("brief" to tr("قصير","Brief"),"study" to tr("دراسي","Study"),"detailed" to tr("مفصل","Detailed"),"meeting" to tr("محضر","Minutes")).forEach{(style,label)->OutlinedButton(onClick={generate("summary",style,selected){message=it}}){Text(label)}}
                                        }}
                                        items(summaries,key={it.id}){s->Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                            Text(s.style.uppercase(),style=MaterialTheme.typography.labelMedium)
                                            Text(s.text)
                                            TextButton(onClick={selectedSegment=s.id;editValue=s.text;dialog="editSummary"}){Text(tr("تحرير الملخص","Edit summary"))}
                                            if(segments.any{it.revision>s.sourceRevision}) Text(tr("قد يحتاج إلى تحديث بعد تعديل النص","May be stale after transcript edit"),color=MaterialTheme.colorScheme.error)
                                            TextButton(onClick={openSource(s.sourceIdsJson,segments,::play){message=it}}){Text(tr("فتح المصدر الصوتي","Open source audio"))}
                                        }}}
                                    }
                                    "cards"->{
                                        item{Row(verticalAlignment=Alignment.CenterVertically){Button(onClick={generate("flashcards","study",selected){message=it}}){Text(tr("إنشاء بطاقات","Generate flashcards"))};Spacer(Modifier.width(6.dp));OutlinedButton(onClick={exportFormat="csv";exportId=selected.id;exporter.launch("cards.csv")}){Text("CSV")}}}
                                        items(cards,key={it.id}){c->Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                            Text(c.question,fontWeight=FontWeight.SemiBold)
                                            var flipped by remember(c.id){mutableStateOf(false)}
                                            OutlinedButton(onClick={flipped=!flipped}){Text(if(flipped)c.answer else tr("عرض الإجابة","Show answer"))}
                                            Text(c.status+if(c.dueAt>0)" • ${DateFormat.getDateInstance().format(Date(c.dueAt))}" else "",style=MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                                TextButton(onClick={selectedSegment=c.id;editValue=c.question;editAnswer=c.answer;dialog="editCard"}){Text(tr("تعديل","Edit"))}
                                                TextButton(onClick={lifecycleScope.launch{dao.upsertCard(c.copy(status="accepted"))}}){Text(tr("اعتماد","Accept"))}
                                                TextButton(onClick={openSource(c.sourceIdsJson,segments,::play){message=it}}){Text(tr("المصدر","Source"))}
                                            }
                                            if(c.status=="accepted")Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                                listOf(0 to tr("صعبة","Again"),1 to tr("جيدة","Good"),2 to tr("سهلة","Easy")).forEach{(rating,label)->
                                                    OutlinedButton(onClick={lifecycleScope.launch{
                                                        val n=scheduleNextReview(c.intervalDays,c.ease,rating,System.currentTimeMillis())
                                                        dao.upsertCard(c.copy(dueAt=n.dueAt,intervalDays=n.intervalDays,ease=n.ease))
                                                    }}){Text(label)}
                                                }
                                            }
                                        }}}
                                    }
                                    "tasks"->{
                                        item{Button(onClick={generate("actions","meeting",selected){message=it}}){Text(tr("استخراج مهام الاجتماع","Extract meeting tasks"))}}
                                        items(actions,key={it.id}){task->Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                                            Text(task.text,fontWeight=FontWeight.SemiBold)
                                            Text("${task.decisionState} • ${task.status} • ${task.owner?:tr("بدون مسؤول","No owner")} • ${task.dueDate?:tr("بدون موعد","No date")}",style=MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement=Arrangement.spacedBy(3.dp)){
                                                TextButton(onClick={selectedSegment=task.id;editValue=task.text;editOwner=task.owner.orEmpty();editDue=task.dueDate.orEmpty();dialog="editTask"}){Text(tr("تعديل","Edit"))}
                                                TextButton(onClick={lifecycleScope.launch{dao.upsertAction(task.copy(status="accepted"))}}){Text(tr("اعتماد","Accept"))}
                                                TextButton(onClick={openSource(task.sourceIdsJson,segments,::play){message=it}}){Text(tr("المصدر","Source"))}
                                                TextButton(onClick={lifecycleScope.launch{dao.deleteAction(task.id)}}){Text(tr("حذف","Delete"))}
                                            }
                                        }}}
                                    }
                                    "notes"->{
                                        item{Button(onClick={selectedSegment="";editValue="";dialog="newBookmark"}){Text(tr("إضافة علامة وملاحظة","Add bookmark & note"))}}
                                        items(bookmarks,key={it.id}){b->Card{Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                                            Column(Modifier.weight(1f)){Text("${b.positionMs/1000}s");Text(b.note)}
                                            TextButton(onClick={segments.firstOrNull{it.id==b.segmentId}?.let{play(it){message=it}}}){Text("▶")}
                                        }}}
                                    }
                                }
                            }
                        }
                    }
                }
                if(dialog.isNotEmpty())AlertDialog(onDismissRequest={dialog=""},title={Text(when(dialog){"delete"->tr("حذف نهائي؟","Delete session?");"editTranscript"->tr("تصحيح النص","Correct transcript");"editCard"->tr("تعديل البطاقة","Edit card");"editTask"->tr("تحرير المهمة","Edit task");"enableCloud"->tr("تأكيد إرسال التسجيل للخادم؟","Allow server to process this recording?");"editSummary"->tr("تحرير الملخص","Edit summary");"rename"->tr("إعادة تسمية الجلسة","Rename session");else->tr("إضافة ملاحظة","Add note")})},
                    text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        if(dialog!="delete" && dialog!="enableCloud")OutlinedTextField(editValue,onValueChange={editValue=it},label={Text(tr("النص","Text"))})
                        if(dialog=="editCard")OutlinedTextField(editAnswer,onValueChange={editAnswer=it},label={Text(tr("الإجابة","Answer"))})
                        if(dialog=="editTask"){
                            OutlinedTextField(editOwner,onValueChange={editOwner=it},label={Text(tr("المسؤول (اختياري)","Owner (optional)"))})
                            OutlinedTextField(editDue,onValueChange={editDue=it},label={Text(tr("الموعد (اختياري)","Due date (optional)"))})
                        }
                    }},confirmButton={TextButton(onClick={
                        val sid=selectedId;val target=selectedSegment;val payload=editValue
                        when(dialog){
                            "delete"->selected?.let{remove(it);page="home"}
                            "rename"->lifecycleScope.launch{dao.rename(sid,payload.ifBlank{"Untitled"},System.currentTimeMillis())}
                            "enableCloud"->selected?.let{s->lifecycleScope.launch{
                                dao.upsert(s.copy(cloudConsent=true,processingMode="cloud"))
                                dao.listSegments(s.id).filter{it.status!="done"}.forEach{TranscriptionWorker.queue(applicationContext,it.id)}
                            }}
                            "editSummary"->summaries.firstOrNull{it.id==target}?.let{summary->lifecycleScope.launch{dao.upsertSummary(summary.copy(text=payload,edited=true))}}
                            "editTranscript"->lifecycleScope.launch{dao.editText(target,payload)}
                            "editCard"->cards.firstOrNull{it.id==target}?.let{c->lifecycleScope.launch{dao.upsertCard(c.copy(question=payload,answer=editAnswer))}}
                            "editTask"->actions.firstOrNull{it.id==target}?.let{a->lifecycleScope.launch{dao.upsertAction(a.copy(text=payload,owner=editOwner.takeIf{it.isNotBlank()},dueDate=editDue.takeIf{it.isNotBlank()}))}}
                            "newBookmark"->lifecycleScope.launch{val ref=segments.lastOrNull();dao.upsertBookmark(BookmarkEntity(UUID.randomUUID().toString(),sid,ref?.id,ref?.startMs?:0,payload))}
                        };dialog=""
                    }){Text(tr("حفظ","Save"))}},dismissButton={TextButton(onClick={dialog=""}){Text(tr("إلغاء","Cancel"))}})
                if(message.isNotBlank())AlertDialog(onDismissRequest={message=""},title={Text("NoteFlow AI")},text={Text(message)},confirmButton={TextButton(onClick={message=""}){Text(tr("حسناً","OK"))}})
            }
        }
    }

    private fun sessionOrNull(sessions:List<SessionEntity>,id:String):SessionEntity?=sessions.firstOrNull{it.id==id}
    private suspend fun cardsIfSelected(id:String):List<FlashcardEntity> = dao.cards(id).first()
    private suspend fun summariesIfSelected(id:String):List<SummaryEntity> = dao.summaries(id).first()
    private suspend fun actionsIfSelected(id:String):List<ActionEntity> = dao.actions(id).first()
    private fun openSource(json:String,segments:List<AudioSegmentEntity>,play:(AudioSegmentEntity,(String)->Unit)->Unit,onMessage:(String)->Unit){
        val ids=try{JSONArray(json).let{a->(0 until a.length()).map{a.getString(it)}}}catch(_:Exception){emptyList()}
        val segment=segments.firstOrNull{it.id in ids}
        if(segment!=null && File(segment.path).isFile)play(segment,onMessage)
        else onMessage("Source transcript: "+ids.joinToString())
    }
}

@Composable
private fun ActivePanel(session:SessionEntity,segments:List<AudioSegmentEntity>,clock:Long,send:(String)->Unit,mark:()->Unit,arabic:Boolean){
    val seconds=(session.durationMs+(if(session.status=="recording")(clock-session.updatedAt).coerceAtLeast(0L) else 0L))/1000L
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(arabic)"● جارٍ تسجيل ${session.title}" else "● Recording ${session.title}",style=MaterialTheme.typography.titleMedium)
            Text("${seconds/60} : ${(seconds%60).toString().padStart(2,'0')} | ${session.status}",style=MaterialTheme.typography.headlineMedium)
            Text(if(session.cloudConsent)"☁ ${if(arabic)"معالجة سحابية بموافقتك" else "Cloud with consent"}" else "⌂ ${if(arabic)"تسجيل محلي فقط" else "Local recording only"}")
            segments.takeLast(3).forEach{seg->Text(if(seg.originalText.isNotBlank())seg.effectiveText() else seg.partialText.ifBlank{"#${seg.segmentIndex+1} · ${seg.status}"},style=MaterialTheme.typography.bodySmall)}
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                OutlinedButton(onClick={send(if(session.status=="paused")RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE)}){Text(if(session.status=="paused")"▶" else "Ⅱ")}
                Button(onClick={send(RecordingService.ACTION_STOP)}){Text(if(arabic)"إنهاء وحفظ" else "Stop & save")}
                OutlinedButton(onClick=mark){Text("☆")}
            }
        }
    }
}

@Composable
private fun EmptyState(text:String){Surface(tonalElevation=1.dp,shape=RoundedCornerShape(12.dp)){Text(text,modifier=Modifier.fillMaxWidth().padding(20.dp),style=MaterialTheme.typography.bodyMedium)}}
