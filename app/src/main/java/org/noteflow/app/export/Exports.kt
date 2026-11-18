package org.noteflow.app.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import org.noteflow.app.data.*
import java.io.OutputStream

/** Export real user-selected content; no sample fixtures are inserted into the application. */
object Exports {
    fun write(format:String,stream:OutputStream,session:SessionEntity,segments:List<AudioSegmentEntity>,cards:List<FlashcardEntity>,summaries:List<SummaryEntity>,actions:List<ActionEntity>){
        val lines=buildList {
            add(session.title)
            add("Session type: ${session.kind} | Language: ${session.language}")
            segments.forEach { s->
                add("[${s.startMs/1000}s–${s.endMs/1000}s] ${s.effectiveText()}")
                s.translationText?.takeIf{it.isNotBlank()}?.let{add("Translation: $it")}
            }
            summaries.forEach{add("Summary (${it.style}): ${it.text}")}
            actions.forEach{add("Task (${it.status}, ${it.decisionState}): ${it.text} | ${it.owner ?: ""} | ${it.dueDate ?: ""}")}
        }
        when(format){
            "txt" -> stream.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            "csv" -> {
                fun quote(s:String)="\""+s.replace("\"","\"\"")+"\""
                val table=buildList {
                    add("question,answer,status,source_ids")
                    cards.forEach{add(listOf(it.question,it.answer,it.status,it.sourceIdsJson).joinToString(",",transform=::quote))}
                }
                stream.write(("\uFEFF"+table.joinToString("\r\n")).toByteArray(Charsets.UTF_8))
            }
            "json" -> {
                val root=JSONObject().put("session",JSONObject().put("id",session.id).put("title",session.title).put("kind",session.kind))
                root.put("segments",JSONArray().apply { segments.forEach{put(JSONObject().put("id",it.id).put("startMs",it.startMs).put("endMs",it.endMs).put("original",it.originalText).put("edited",it.editedText).put("translation",it.translationText))} })
                root.put("summaries",JSONArray().apply{summaries.forEach{put(JSONObject().put("text",it.text).put("source_ids",JSONArray(it.sourceIdsJson)))} })
                root.put("cards",JSONArray().apply{cards.forEach{put(JSONObject().put("question",it.question).put("answer",it.answer).put("source_ids",JSONArray(it.sourceIdsJson)))} })
                root.put("actions",JSONArray().apply{actions.forEach{put(JSONObject().put("text",it.text).put("owner",it.owner).put("dueDate",it.dueDate).put("source_ids",JSONArray(it.sourceIdsJson)))} })
                stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
            }
            "pdf"->writePdf(stream,lines)
            else -> error("Unknown export: $format")
        }
        stream.flush()
    }

    /** Android StaticLayout performs bidi shaping before text is drawn onto the PDF canvas. */
    private fun writePdf(stream:OutputStream,lines:List<String>){
        val document=PdfDocument()
        val width=595;val height=842;val padding=44
        var pageNum=0
        var page:PdfDocument.Page?=null
        var y=padding
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(31,42,64);textSize=13f}
        try {
            for(text in lines){
                val content=text.ifBlank { " " }
                val direction=if(content.any{it in '\u0600'..'\u06ff'})TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
                val layout=StaticLayout.Builder.obtain(content,0,content.length,paint,width-2*padding)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(direction).setIncludePad(false).build()
                if(page==null || y+layout.height+28 > height-padding){
                    page?.let{document.finishPage(it)}
                    pageNum++
                    page=document.startPage(PdfDocument.PageInfo.Builder(width,height,pageNum).create())
                    y=padding
                }
                val canvas=page!!.canvas
                canvas.save();canvas.translate(padding.toFloat(),y.toFloat());layout.draw(canvas);canvas.restore()
                y+=layout.height+18
            }
            page?.let{document.finishPage(it)}
            if(pageNum==0){val empty=document.startPage(PdfDocument.PageInfo.Builder(width,height,1).create());document.finishPage(empty)}
            document.writeTo(stream)
        }finally{document.close()}
    }
}
