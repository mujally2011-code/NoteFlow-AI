package org.noteflow.app.domain

import org.junit.Assert.*
import org.junit.Test

class StudyToolsTest {
    @Test fun localSearchFindsMixedArabicEnglishWithoutNetwork(){
        val data=listOf("a" to "شرح Database Index", "b" to "حديث عن المواعيد")
        assertEquals("a",lexicalSearch("Database Index",data).first().segmentId)
        assertEquals("b",lexicalSearch("المواعيد",data).first().segmentId)
        assertTrue(lexicalSearch("notthere",data).isEmpty())
    }
    @Test fun quizChecksCorrectIndexAndInvalidInput(){
        assertTrue(quizIsCorrect(1,1,4))
        assertFalse(quizIsCorrect(0,1,4))
        assertFalse(quizIsCorrect(-1,-1,4))
    }
}
