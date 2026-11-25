package org.noteflow.app.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String = "lecture",  // lecture|meeting|research
    val status: String = "recording", // recording|paused|ready|interrupted|error
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val audioPath: String? = null,
    val durationMs: Long = 0,
    val errorMessage: String? = null,
    @ColumnInfo(defaultValue="'auto'") val language: String = "auto",
    @ColumnInfo(defaultValue="'local'") val processingMode: String = "local",
    @ColumnInfo(defaultValue="0") val cloudConsent: Boolean = false,
    @ColumnInfo(defaultValue="0") val liveTranslation: Boolean = false,
    @ColumnInfo(defaultValue="'none'") val remoteDeleteState: String = "none",
    @ColumnInfo(defaultValue="'en'") val translationTarget: String = "en"
)

@Entity(tableName="audio_segments", indices=[Index(value=["sessionId","segmentIndex"],unique=true),Index("sessionId")])
data class AudioSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val segmentIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val path: String,
    val status: String = "pending", // pending|processing|done|error
    val originalText: String = "",
    val editedText: String? = null,
    val translationText: String? = null,
    val translationLanguage: String? = null,
    val revision: Int = 0,
    val translatedRevision: Int = -1,
    val errorMessage: String? = null,
    @ColumnInfo(defaultValue="''") val partialText: String = "",
    @ColumnInfo(defaultValue="0") val eventSeq: Int = 0
) {
    fun effectiveText() = editedText ?: originalText
}

@Entity(tableName="summaries", indices=[Index("sessionId")])
data class SummaryEntity(@PrimaryKey val id: String, val sessionId: String, val style: String,
    val text: String, val sourceIdsJson: String, val sourceRevision: Int, val edited: Boolean = false)

@Entity(tableName="flashcards", indices=[Index("sessionId")])
data class FlashcardEntity(@PrimaryKey val id: String, val sessionId: String,
    val question: String, val answer: String, val sourceIdsJson: String, val status: String="draft",
    val dueAt: Long=0L, val intervalDays: Int=0, val ease: Double=2.5)

@Entity(tableName="action_items", indices=[Index("sessionId")])
data class ActionEntity(@PrimaryKey val id: String, val sessionId: String, val text: String,
    val owner: String?=null, val dueDate: String?=null, val decisionState: String="proposal",
    val sourceIdsJson: String="[]", val status: String="draft")

@Entity(tableName="bookmarks", indices=[Index("sessionId")])
data class BookmarkEntity(@PrimaryKey val id: String, val sessionId: String, val segmentId: String?,
    val positionMs: Long, val note: String="")

/** Speaker IDs are relative to an audio segment; they are NOT recognized identities. */
@Entity(tableName="speaker_turns", indices=[Index("sessionId"),Index("segmentId")])
data class SpeakerTurnEntity(@PrimaryKey val id: String, val sessionId: String,
    val segmentId: String, val speaker: String, val startMs: Long, val endMs: Long,
    val text: String)

@Entity(tableName="quiz_questions", indices=[Index("sessionId")])
data class QuizQuestionEntity(@PrimaryKey val id: String, val sessionId: String,
    val question: String, val optionsJson: String, val correctIndex: Int,
    val sourceIdsJson: String, val chosenIndex: Int? = null)

@Dao
interface NoteFlowDao {
    @Query("SELECT DISTINCT sessionId FROM audio_segments WHERE originalText LIKE '%' || :query || '%' OR editedText LIKE '%' || :query || '%'") fun searchMatchingSessionIds(query:String):Flow<List<String>>
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun observeSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id=:id LIMIT 1") suspend fun get(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE id=:id LIMIT 1") fun observeSession(id: String): Flow<SessionEntity?>
    @Upsert suspend fun upsert(session: SessionEntity)
    @Query("UPDATE sessions SET status=:status, updatedAt=:now, audioPath=COALESCE(:path,audioPath), durationMs=:duration, errorMessage=:error WHERE id=:id")
    suspend fun updateCapture(id: String,status: String,now: Long,path: String?,duration:Long,error:String?)
    @Query("UPDATE sessions SET title=:title,updatedAt=:now WHERE id=:id") suspend fun rename(id:String,title:String,now:Long)
    @Query("UPDATE sessions SET remoteDeleteState=:state WHERE id=:id") suspend fun markRemoteDelete(id:String,state:String)
    @Query("UPDATE sessions SET status='interrupted',errorMessage='Previous recording interrupted. Completed chunks are safe.' WHERE status IN ('recording','paused')")
    suspend fun recoverInterrupted()
    @Query("UPDATE sessions SET translationTarget=:target WHERE id=:id")
    suspend fun updateTranslationTarget(id:String,target:String)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun delete(id:String)

    @Upsert suspend fun upsertSegment(segment:AudioSegmentEntity)
    @Query("SELECT * FROM audio_segments WHERE sessionId=:id ORDER BY segmentIndex") fun observeSegments(id:String):Flow<List<AudioSegmentEntity>>
    @Query("SELECT * FROM audio_segments WHERE sessionId=:id ORDER BY segmentIndex") suspend fun listSegments(id:String):List<AudioSegmentEntity>
    @Query("SELECT * FROM audio_segments WHERE id=:id") suspend fun segment(id:String):AudioSegmentEntity?
    @Query("UPDATE audio_segments SET partialText=:partial,eventSeq=:seq WHERE id=:id AND eventSeq < :seq AND status != 'done'") suspend fun savePartial(id:String,partial:String,seq:Int)
    @Query("UPDATE audio_segments SET status=:status,originalText=:text,partialText='',errorMessage=NULL WHERE id=:id") suspend fun saveTranscript(id:String,status:String,text:String)
    @Query("UPDATE audio_segments SET status=:status,errorMessage=:error WHERE id=:id") suspend fun markSegment(id:String,status:String,error:String?)
    @Query("UPDATE audio_segments SET editedText=:text,revision=revision+1,translationText=NULL,translatedRevision=-1 WHERE id=:id") suspend fun editText(id:String,text:String)
    @Query("UPDATE audio_segments SET translationText=:text,translationLanguage=:lang,translatedRevision=:revision WHERE id=:id AND revision=:revision")
    suspend fun saveTranslation(id:String,text:String,lang:String,revision:Int)
    @Query("DELETE FROM audio_segments WHERE sessionId=:id") suspend fun deleteSegments(id:String)

    @Query("SELECT * FROM summaries WHERE sessionId=:id ORDER BY rowid DESC") fun summaries(id:String):Flow<List<SummaryEntity>>
    @Upsert suspend fun upsertSummary(e:SummaryEntity)
    @Query("DELETE FROM summaries WHERE sessionId=:id") suspend fun deleteSummaries(id:String)
    @Query("SELECT * FROM flashcards WHERE sessionId=:id ORDER BY rowid DESC") fun cards(id:String):Flow<List<FlashcardEntity>>
    @Upsert suspend fun upsertCard(e:FlashcardEntity)
    @Query("DELETE FROM flashcards WHERE sessionId=:id") suspend fun deleteCards(id:String)
    @Query("SELECT * FROM action_items WHERE sessionId=:id ORDER BY rowid DESC") fun actions(id:String):Flow<List<ActionEntity>>
    @Upsert suspend fun upsertAction(e:ActionEntity)
    @Query("DELETE FROM action_items WHERE id=:id") suspend fun deleteAction(id:String)
    @Query("DELETE FROM action_items WHERE sessionId=:id") suspend fun deleteActions(id:String)
    @Query("SELECT * FROM bookmarks WHERE sessionId=:id ORDER BY positionMs") fun bookmarks(id:String):Flow<List<BookmarkEntity>>
    @Upsert suspend fun upsertBookmark(e:BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE sessionId=:id") suspend fun deleteBookmarks(id:String)
    @Query("SELECT * FROM speaker_turns WHERE sessionId=:id ORDER BY startMs,segmentId")
    fun speakers(id:String):Flow<List<SpeakerTurnEntity>>
    @Upsert suspend fun upsertSpeakerTurn(item:SpeakerTurnEntity)
    @Query("DELETE FROM speaker_turns WHERE sessionId=:id") suspend fun deleteSpeakers(id:String)
    @Query("SELECT * FROM quiz_questions WHERE sessionId=:id ORDER BY rowid DESC")
    fun quizzes(id:String):Flow<List<QuizQuestionEntity>>
    @Upsert suspend fun upsertQuiz(item:QuizQuestionEntity)
    @Query("DELETE FROM quiz_questions WHERE sessionId=:id") suspend fun deleteQuizzes(id:String)
}

@Database(entities=[SessionEntity::class,AudioSegmentEntity::class,SummaryEntity::class,FlashcardEntity::class,ActionEntity::class,BookmarkEntity::class,SpeakerTurnEntity::class,QuizQuestionEntity::class],version=4,exportSchema=true)
abstract class NoteFlowDatabase:RoomDatabase(){abstract fun notes():NoteFlowDao}

object AppDatabase {
    @Volatile private var instance: NoteFlowDatabase?=null
    fun get(context:Context):NoteFlowDatabase = instance ?: synchronized(this){
        instance ?: Room.databaseBuilder(context.applicationContext,NoteFlowDatabase::class.java,"noteflow.db")
            .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4).build().also{instance=it}
    }
    private val MIGRATION_1_2 = object:androidx.room.migration.Migration(1,2){
        override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS sessions_new (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,audioPath TEXT,durationMs INTEGER NOT NULL,errorMessage TEXT)")
            db.execSQL("INSERT OR IGNORE INTO sessions_new(id,title,kind,status,createdAt,updatedAt,audioPath,durationMs,errorMessage) SELECT id,title,kind,status,0,0,NULL,0,NULL FROM sessions")
            db.execSQL("DROP TABLE sessions")
            db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")
        }
    }
    private val MIGRATION_2_3=object:androidx.room.migration.Migration(2,3){
        override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE sessions ADD COLUMN language TEXT NOT NULL DEFAULT 'auto'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN processingMode TEXT NOT NULL DEFAULT 'local'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN cloudConsent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN liveTranslation INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN remoteDeleteState TEXT NOT NULL DEFAULT 'none'")
            db.execSQL("CREATE TABLE IF NOT EXISTS audio_segments (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,segmentIndex INTEGER NOT NULL,startMs INTEGER NOT NULL,endMs INTEGER NOT NULL,path TEXT NOT NULL,status TEXT NOT NULL,originalText TEXT NOT NULL,editedText TEXT,translationText TEXT,translationLanguage TEXT,revision INTEGER NOT NULL,translatedRevision INTEGER NOT NULL,errorMessage TEXT,partialText TEXT NOT NULL DEFAULT '',eventSeq INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audio_segments_sessionId_segmentIndex ON audio_segments(sessionId,segmentIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_segments_sessionId ON audio_segments(sessionId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS summaries(id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,style TEXT NOT NULL,text TEXT NOT NULL,sourceIdsJson TEXT NOT NULL,sourceRevision INTEGER NOT NULL,edited INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_summaries_sessionId ON summaries(sessionId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS flashcards(id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,question TEXT NOT NULL,answer TEXT NOT NULL,sourceIdsJson TEXT NOT NULL,status TEXT NOT NULL,dueAt INTEGER NOT NULL,intervalDays INTEGER NOT NULL,ease REAL NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_flashcards_sessionId ON flashcards(sessionId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS action_items(id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,text TEXT NOT NULL,owner TEXT,dueDate TEXT,decisionState TEXT NOT NULL,sourceIdsJson TEXT NOT NULL,status TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_action_items_sessionId ON action_items(sessionId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,segmentId TEXT,positionMs INTEGER NOT NULL,note TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_sessionId ON bookmarks(sessionId)")
        }
    }
    private val MIGRATION_3_4=object:androidx.room.migration.Migration(3,4){
        override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE sessions ADD COLUMN translationTarget TEXT NOT NULL DEFAULT 'en'")
            db.execSQL("CREATE TABLE IF NOT EXISTS speaker_turns (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, segmentId TEXT NOT NULL, speaker TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_speaker_turns_sessionId ON speaker_turns(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_speaker_turns_segmentId ON speaker_turns(segmentId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS quiz_questions (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, question TEXT NOT NULL, optionsJson TEXT NOT NULL, correctIndex INTEGER NOT NULL, sourceIdsJson TEXT NOT NULL, chosenIndex INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_quiz_questions_sessionId ON quiz_questions(sessionId)")
        }
    }

}
