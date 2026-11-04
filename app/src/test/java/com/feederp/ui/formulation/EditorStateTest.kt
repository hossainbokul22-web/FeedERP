package com.feederp.ui.formulation

import com.feederp.data.formulation.FormulaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {
    private fun line(item: String?, g: String) = LineUi(key = "k${item}$g", itemId = item, gramsText = g)

    @Test fun validLinesKeepsOnlyCompleteAndPositive() {
        val s = EditorState(lines = listOf(line("corn", "600000"), line("soy", "0"), line(null, ""), line("fish", "abc")))
        val v = s.validLines()
        assertEquals(1, v.size)
        assertEquals("corn", v[0].itemId)
        assertEquals(600000.0, v[0].gramsPerTon, 0.0)
    }

    @Test fun totalIsSumOfValidLines() {
        val s = EditorState(lines = listOf(line("a", "600000"), line("b", "400000"), line("c", "")))
        assertEquals(1_000_000.0, s.totalGramsPerTon(), 0.0)
    }

    @Test fun linesProblemFlagsHalfFilled() {
        assertNull(EditorState(lines = listOf(line("a", "100"), line(null, ""))).linesProblem())
        assertNotNull(EditorState(lines = listOf(line(null, "100"))).linesProblem())   // পরিমাণ আছে, আইটেম নেই
        assertNotNull(EditorState(lines = listOf(line("a", ""))).linesProblem())       // আইটেম আছে, পরিমাণ নেই
        assertNotNull(EditorState(lines = listOf(line("a", "-5"))).linesProblem())     // নেগেটিভ
    }

    @Test fun banglaDigitsAreAccepted() {
        val s = EditorState(lines = listOf(line("a", "৫০০০০")))
        assertEquals(50000.0, s.validLines().single().gramsPerTon, 0.0)
    }

    @Test fun targetsParseBlankAsNull() {
        val t = EditorState(minProtein = "18", maxFibre = " ", maxMoisture = "12.5").targets()
        assertEquals(18.0, t.minProteinPct!!, 0.0)
        assertNull(t.maxFibrePct)
        assertEquals(12.5, t.maxMoisturePct!!, 0.0)
        assertNull(t.minFatPct)
    }

    @Test fun onlyDraftIsEditable() {
        assertTrue(EditorState(status = FormulaStatus.DRAFT).editable)
        assertFalse(EditorState(status = FormulaStatus.APPROVED).editable)
        assertFalse(EditorState(status = FormulaStatus.ARCHIVED).editable)
    }

    @Test fun draftInputTrimsSpeciesAndParsesAge() {
        val i = EditorState(code = "B1", name = "ব্রয়লার স্টার্টার", productItemId = "p", species = "  ব্রয়লার ", ageDays = "10", isMedicated = true)
            .toDraftInput()
        assertEquals("ব্রয়লার", i.targetSpecies)
        assertEquals(10, i.targetAgeDays)
        assertTrue(i.isMedicated)
        assertNull(EditorState(species = "  ").toDraftInput().targetSpecies)
        assertNull(EditorState(ageDays = "x").toDraftInput().targetAgeDays)
    }
}
