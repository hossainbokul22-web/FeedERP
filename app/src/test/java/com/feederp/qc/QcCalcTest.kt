package com.feederp.qc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QcCalcTest {
    @Test fun passedIsInclusiveOfLimits() {
        assertTrue(QcCalc.passed(12.0, null, 12.0))
        assertFalse(QcCalc.passed(12.0001, null, 12.0))
        assertTrue(QcCalc.passed(18.0, 18.0, null))
        assertFalse(QcCalc.passed(17.99, 18.0, null))
        assertTrue(QcCalc.passed(15.0, 10.0, 20.0))
        assertFalse(QcCalc.passed(25.0, 10.0, 20.0))
        assertFalse(QcCalc.passed(5.0, 10.0, 20.0))
    }

    @Test fun nanAndInfinityNeverPass() {
        assertFalse(QcCalc.passed(Double.NaN, 0.0, 10.0))
        assertFalse(QcCalc.passed(Double.POSITIVE_INFINITY, null, 10.0))
        assertFalse(QcCalc.passed(Double.NEGATIVE_INFINITY, 0.0, null))
    }

    @Test fun inputValidation() {
        assertNotNull(QcCalc.inputError(null, 1.0, 2.0))             // মান নেই
        assertNotNull(QcCalc.inputError(Double.NaN, 1.0, 2.0))
        assertNotNull(QcCalc.inputError(-1.0, 1.0, 2.0))             // ঋণাত্মক
        assertNotNull(QcCalc.inputError(5.0, null, null))            // কোনো সীমা নেই
        assertNotNull(QcCalc.inputError(5.0, 20.0, 10.0))            // min > max
        assertNotNull(QcCalc.inputError(5.0, Double.NaN, 10.0))
        assertNotNull(QcCalc.inputError(5.0, null, Double.POSITIVE_INFINITY))
        assertNull(QcCalc.inputError(5.0, 10.0, 10.0))
        assertNull(QcCalc.inputError(0.0, null, 12.0))
        assertNull(QcCalc.inputError(5.0, 5.0, null))
    }

    @Test fun latestResultPerParameterWins() {
        val ps = listOf(
            TestPoint("MOISTURE", passed = false, createdAt = 100),
            TestPoint("MOISTURE", passed = true, createdAt = 200),     // পুনঃটেস্টে পাস
            TestPoint("AFLATOXIN", passed = true, createdAt = 150),
        )
        val latest = QcCalc.latestPerParameter(ps)
        assertEquals(2, latest.size)
        assertTrue(latest.first { it.parameter == "MOISTURE" }.passed)
        assertEquals(QcRecommendation.ALL_PASS, QcCalc.recommendation(ps))
    }

    @Test fun laterFailureOverridesEarlierPass() {
        val ps = listOf(
            TestPoint("MOISTURE", passed = true, createdAt = 100),
            TestPoint("MOISTURE", passed = false, createdAt = 300),
        )
        assertEquals(listOf("MOISTURE"), QcCalc.failedParameters(ps))
        assertEquals(QcRecommendation.SOME_FAILED, QcCalc.recommendation(ps))
    }

    @Test fun noTestsAndAnyFailure() {
        assertEquals(QcRecommendation.NO_TESTS, QcCalc.recommendation(emptyList()))
        val ps = listOf(
            TestPoint("MOISTURE", true, 1), TestPoint("AFLATOXIN", false, 2), TestPoint("PROTEIN", true, 3),
        )
        assertEquals(listOf("AFLATOXIN"), QcCalc.failedParameters(ps))
        assertEquals(QcRecommendation.SOME_FAILED, QcCalc.recommendation(ps))
    }

    @Test fun passingNeedsAllPassOrOverrideWithReason() {
        // সব পাস: কারও অনুমতি/কারণ লাগে না
        assertNull(QcCalc.decisionError("PASSED", QcRecommendation.ALL_PASS, canOverride = false, note = null))
        // ফেল থাকলে সাধারণ ইউজার পারবে না
        assertNotNull(QcCalc.decisionError("PASSED", QcRecommendation.SOME_FAILED, canOverride = false, note = "ঠিক আছে"))
        // টেস্টহীন লটও সাধারণ ইউজার পাস করতে পারবে না
        assertNotNull(QcCalc.decisionError("PASSED", QcRecommendation.NO_TESTS, canOverride = false, note = "x"))
        // ম্যানেজার কারণ ছাড়া পারবে না, কারণসহ পারবে
        assertNotNull(QcCalc.decisionError("PASSED", QcRecommendation.SOME_FAILED, canOverride = true, note = "  "))
        assertNull(QcCalc.decisionError("PASSED", QcRecommendation.SOME_FAILED, canOverride = true, note = "পুনঃটেস্ট ঠিক"))
    }

    @Test fun holdAndRejectNeedReasonOthersRejected() {
        assertNotNull(QcCalc.decisionError("HOLD", QcRecommendation.ALL_PASS, false, null))
        assertNull(QcCalc.decisionError("HOLD", QcRecommendation.NO_TESTS, false, "ল্যাবে পাঠানো হয়েছে"))
        assertNotNull(QcCalc.decisionError("REJECTED", QcRecommendation.SOME_FAILED, true, ""))
        assertNull(QcCalc.decisionError("REJECTED", QcRecommendation.SOME_FAILED, false, "অ্যাফ্লাটক্সিন বেশি"))
        assertNotNull(QcCalc.decisionError("PENDING", QcRecommendation.ALL_PASS, true, "x"))
        assertNotNull(QcCalc.decisionError("bogus", QcRecommendation.ALL_PASS, true, "x"))
    }

    @Test fun parameterNamesFallBackToKey() {
        assertEquals("আর্দ্রতা", QcParameters.nameBn("MOISTURE"))
        assertEquals("CUSTOM_X", QcParameters.nameBn("CUSTOM_X"))
        assertEquals("ppb", QcParameters.unitOf("AFLATOXIN"))
        assertNull(QcParameters.unitOf("CUSTOM_X"))
    }
}
