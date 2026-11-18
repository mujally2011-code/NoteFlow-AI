package org.noteflow.app.domain

/** Provider interfaces. Implementations deliberately omitted until consent, backend and security are configured. */
data class TranscriptLine(val id: String, val text: String, val startMs: Long?, val endMs: Long?)
interface TranscriptionProvider { suspend fun transcribe(audioPath: String, languageHints: List<String>): List<TranscriptLine> }
interface TranslationProvider { suspend fun translate(text: String, targetLanguage: String): String }
interface SummaryProvider { suspend fun summarize(lines: List<TranscriptLine>, style: String): String }
interface FlashcardProvider { suspend fun generate(lines: List<TranscriptLine>): List<Pair<String,String>> }
interface ActionsProvider { suspend fun extract(lines: List<TranscriptLine>): List<String> }
